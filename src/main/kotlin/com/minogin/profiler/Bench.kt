package com.minogin.profiler

import java.util.concurrent.CyclicBarrier
import kotlin.math.max

const val STAGE_RUN = 1
const val STAGE_MEASURE = 2
const val STAGE_STOP = 3
const val STAGE_HOOK = 4

/** Root calls between clock checks: nanoTime must not end up inside the hot loop. */
private const val CLOCK_CHUNK = 256

/**
 * A worker thread. Inactive threads exist and take part in the barriers but do no work —
 * that is the artificial starvation mode (3 busy out of 16).
 */
class Worker(
    val id: Int,
    val active: Boolean,
    private val labeled: Boolean,
    private val w: Workload,
    private val barrier: CyclicBarrier,
) : Thread("bench-$id") {

    @Volatile var stage: Int = 0
    @Volatile var deadline: Long = 0

    /** Root calls only. Everything else is reconstructed from the graph. */
    val rootCalls = LongArray(OP_COUNT)

    /** Batch measurement: self duration of an operation, and inclusive for the roots. */
    val measuredSelf = DoubleArray(OP_COUNT)
    val measuredInclusive = DoubleArray(OP_COUNT)

    /** Hook analysis: the hook timed alone, and leaf operations timed with and without it. */
    var hookDirect: Double = Double.NaN
        private set
    val opPlain = DoubleArray(OP_COUNT)
    val opHooked = DoubleArray(OP_COUNT)


    private var state: Long = -0x61c8864680b583ebL * (id + 1)
    private var idx: Int = id * 257

    /** This thread's slot, kept so the aggregate can read its counters before the thread exits. */
    lateinit var slot: OpSlot
        private set

    fun resetCounters() {
        rootCalls.fill(0L)
        if (::slot.isInitialized) slot.resetCounts()
    }

    override fun run() {
        // Register up front, idle threads included. Lazily registering on first entry would hide
        // the starved threads from the sampler entirely, which is backwards for occupancy work,
        // and would let slots appear in the middle of a run.
        slot = Profiler.slot()
        try {
            while (true) {
                barrier.await()
                val s = stage
                if (active) {
                    when (s) {
                        STAGE_RUN -> runUntilDeadline()
                        STAGE_MEASURE -> measureBatches()
                        STAGE_HOOK -> measureHookCost()
                    }
                }
                barrier.await()
                if (s == STAGE_STOP) return
            }
        } finally {
            Profiler.release()
        }
    }

    private fun runUntilDeadline() {
        var s = state
        var i = idx
        val sch = w.schedule
        val mask = sch.size - 1
        val counts = rootCalls
        val d = deadline
        // One perfectly predicted branch per root call. It costs the same on both sides of the
        // labelled/unlabelled comparison, so it cancels exactly and cannot bias the result.
        val lbl = labeled
        while (true) {
            var k = 0
            while (k < CLOCK_CHUNK) {
                val op = sch[i and mask]
                i++
                s = if (lbl) w.execLabeled(op, s) else w.exec(op, s)
                counts[op]++
                k++
            }
            if (System.nanoTime() >= d) break
        }
        state = s
        idx = i
        Sink.consume(s)
    }

    /**
     * The second truth: what an operation actually costs — on all worker threads at once, that
     * is, under the same conditions the run happened in. nanoTime sits around the batch, not
     * around the call, so its cost smears out.
     *
     * The measurement goes through the same [timeBurnOnce] that fitted the iteration counts.
     * That is deliberate: a loop of its own here diverged by up to 15% on short operations,
     * because the JIT compiles two loops that mean the same thing differently depending on
     * where they come from, and at twenty nanoseconds that difference in code shape shows.
     * With a shared primitive the two truths differ by conditions, not by code.
     *
     * Trials are interleaved — every operation once, then round again — rather than finishing one
     * operation before starting the next. Consecutive trials would give each operation its own
     * ~28 ms window, and anything that drifts across the measurement would land on whichever
     * operation occupied that window. On a hybrid CPU it does drift: with some cores free the
     * scheduler moves threads between performance and efficiency cores, and consecutive trials
     * turned that into an 11% per-operation spread at 8 threads while 4 and 16 sat under 2%.
     * Interleaved, drift hits every operation equally and cancels in the shares.
     */
    private fun measureBatches() {
        val samples = Array(OP_COUNT) { DoubleArray(TRIALS) }
        for (t in 0 until TRIALS) {
            for (id in 0 until OP_COUNT) samples[id][t] = timeBurnOnce(w.iters[id])
        }
        for (id in 0 until OP_COUNT) {
            samples[id].sort()
            measuredSelf[id] = samples[id][TRIALS / 2]
        }

        var s = state
        val incl = inclusiveNanos(subtreeCounts())
        val inclSamples = Array(OP_COUNT) { DoubleArray(TRIALS) }
        for (t in 0 until TRIALS) {
            for (id in 0 until OP_COUNT) {
                if (ROOT_WEIGHTS[id] == 0) continue
                val reps = repsFor(incl[id])
                val t0 = System.nanoTime()
                var r = 0
                while (r < reps) {
                    s = w.exec(id, s)
                    r++
                }
                inclSamples[id][t] = (System.nanoTime() - t0).toDouble() / reps
            }
        }
        for (id in 0 until OP_COUNT) {
            inclSamples[id].sort()
            measuredInclusive[id] = inclSamples[id][TRIALS / 2]
        }

        state = s
        Sink.consume(s)
    }


    /**
     * Two independent readings of the same quantity, so they can be checked against each other.
     *
     * [hookDirect] times the hook and nothing else — signal is 100% of the measurement, no large
     * numbers to subtract. [opPlain] and [opHooked] time a leaf operation with and without the
     * hook, in the same method and the same shape of loop, so their difference is the hook too.
     *
     * The earlier attempt compared exec against execLabeled, which are different methods with
     * different inlining trees, and reported a negative hook cost. Here the two loops differ by
     * the hook and nothing else, and every duration from 20 ns to 2 us is covered — so the range
     * over which the differential stays usable is measured rather than assumed.
     */
    private fun measureHookCost() {
        val id = 0
        hookDirect = medianOf(HOOK_TRIALS) { timeHookOnly(HOOK_REPS) }

        var s = state
        val plain = Array(OP_COUNT) { DoubleArray(HOOK_TRIALS) }
        val hooked = Array(OP_COUNT) { DoubleArray(HOOK_TRIALS) }
        for (t in 0 until HOOK_TRIALS) {
            for (op in 0 until OP_COUNT) {
                if (OPS[op].children.isNotEmpty()) continue
                val n = w.iters[op]
                val reps = repsFor(OPS[op].selfNanos)

                var t0 = System.nanoTime()
                var r = 0
                while (r < reps) {
                    s = burn(s, n)
                    r++
                }
                plain[op][t] = (System.nanoTime() - t0).toDouble() / reps

                t0 = System.nanoTime()
                r = 0
                while (r < reps) {
                    s = op(op) { burn(s, n) }
                    r++
                }
                hooked[op][t] = (System.nanoTime() - t0).toDouble() / reps
            }
        }
        for (op in 0 until OP_COUNT) {
            if (OPS[op].children.isNotEmpty()) continue
            plain[op].sort()
            hooked[op].sort()
            opPlain[op] = plain[op][HOOK_TRIALS / 2]
            opHooked[op] = hooked[op][HOOK_TRIALS / 2]
        }
        state = s
        Sink.consume(s + id)
    }

    /** The hook alone, around an empty body. The opaque writes cannot be optimised away. */
    private fun timeHookOnly(reps: Int): Double {
        val t0 = System.nanoTime()
        var r = 0
        while (r < reps) {
            val slot = Profiler.slot()
            val prev = slot.getOpaque()
            slot.setOpaque(0)
            slot.count(0)
            slot.setOpaque(prev)
            r++
        }
        return (System.nanoTime() - t0).toDouble() / reps
    }

    private inline fun medianOf(trials: Int, block: () -> Double): Double {
        val a = DoubleArray(trials) { block() }
        a.sort()
        return a[trials / 2]
    }

    private fun repsFor(expectedNanos: Double): Int =
        max(500.0, 4_000_000.0 / max(1.0, expectedNanos)).toInt()

    private companion object {
        const val TRIALS = 7
        const val HOOK_TRIALS = 15
        const val HOOK_REPS = 500_000
    }
}

/** The bench: a thread pool and the barrier the main thread drives them through stages with. */
class Bench(val threads: Int, val activeThreads: Int, val labeled: Boolean, val workload: Workload) {
    private val barrier = CyclicBarrier(threads + 1)
    val workers = List(threads) { Worker(it, it < activeThreads, labeled, workload, barrier) }

    fun start() = workers.forEach { it.isDaemon = true; it.start() }

    private fun stage(s: Int) {
        workers.forEach { it.stage = s }
        barrier.await()
        barrier.await()
    }

    /**
     * Releases the workers and returns immediately, so the caller can do something while they run
     * — measuring what clock the machine is actually giving the run, for instance. Pair with
     * [runAwait].
     */
    fun runStart(durationNanos: Long): Long {
        val d = System.nanoTime() + durationNanos
        workers.forEach { it.deadline = d }
        workers.forEach { it.stage = STAGE_RUN }
        barrier.await()
        return System.nanoTime()
    }

    /** Waits for the run to finish. Returns its actual duration in nanoseconds. */
    fun runAwait(startedAt: Long): Long {
        barrier.await()
        return System.nanoTime() - startedAt
    }

    /** Returns the actual duration of the run in nanoseconds. */
    fun run(durationNanos: Long): Long = runAwait(runStart(durationNanos))

    fun measure() = stage(STAGE_MEASURE)

    fun measureHook() = stage(STAGE_HOOK)

    fun stop() {
        stage(STAGE_STOP)
        workers.forEach { it.join() }
    }

    fun totalRootCalls(): Long = workers.sumOf { w -> w.rootCalls.sum() }

    fun resetCounters() = workers.forEach { it.resetCounters() }
}

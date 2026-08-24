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
 * A gap between two consecutive clock checks longer than this is taken to be the thread having
 * been off the CPU rather than slow.
 *
 * One chunk of 256 root calls costs of the order of 100 us, and the machine's clock swings by 2x
 * inside a run, so an unstalled chunk can take anywhere from 50 to 200 us. Half a millisecond is
 * clear of that and far below Windows' 15.6 ms quantum, so what is counted here is descheduling
 * and not jitter.
 *
 * The consequence is that this is a *lower* bound on off-CPU time: a preemption shorter than the
 * threshold is invisible, and one that happens to land inside a chunk boundary is charged in full.
 * That is the right direction for its purpose, which is to confirm an OS number rather than
 * replace it.
 */
private const val STALL_GAP_NANOS = 500_000L

/**
 * The one thing in this bench that genuinely blocks.
 *
 * Everything else spins on arithmetic and never waits for anything, which is what made the whole
 * workload useless for checking a measurement of stalling. Here a worker takes a real lock, holds
 * it for a configured time, and every other worker that wants it in the meantime is parked by the
 * operating system — off the CPU, and still inside the operation as far as the sampler is
 * concerned. That gap is precisely what phase 3.5 exists to measure.
 *
 * The contention rate is a parameter rather than an accident. With `threads` workers each taking
 * the lock every [intervalNanos] and holding it for [holdNanos], the lock's utilisation is
 * `threads × hold / interval`, so the configuration says how hard they will collide — under 1 and
 * the queue stays short, over 1 and it runs away.
 *
 * What the waiting *costs* is not computed from that, though. It is timed by the thread doing the
 * waiting, exactly, for the same reason the preemption detector exists: a queue's behaviour under a
 * real scheduler is not something to predict from a formula and then believe.
 */
class ContendedLock(val holdNanos: Long, val intervalNanos: Long) {
    val lock = java.util.concurrent.locks.ReentrantLock()

    /** Iterations of the busy loop that fill the critical section. Set at calibration. */
    @Volatile
    var holdIters: Int = 0

    fun utilisation(threads: Int): Double = threads * holdNanos.toDouble() / intervalNanos
}

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
    private val contended: ContendedLock? = null,
    private val lockOpId: Int = -1,
) : Thread("bench-$id") {

    @Volatile var stage: Int = 0
    @Volatile var deadline: Long = 0

    /** Root calls only. Everything else is reconstructed from the graph. */
    val rootCalls = LongArray(OP_COUNT)

    /** Batch measurement: self duration of an operation, and inclusive for the roots. */
    val measuredSelf = DoubleArray(OP_COUNT)
    val measuredInclusive = DoubleArray(OP_COUNT)

    /**
     * The worker's own account of how much of the run it spent off the CPU, and the wall time that
     * account covers.
     *
     * The second reading of the duty cycle, and it owes nothing to the operating system's own
     * numbers — this is the thread noticing that the clock jumped while it was not looking. The
     * bench never blocks, so anything here is the scheduler taking the core away, which on a
     * hybrid machine under load it does far more than the bench's design assumed.
     *
     * It costs nothing: the run loop already reads nanoTime once per chunk to check its deadline.
     */
    var stallNanos: Long = 0; private set
    var stalls: Long = 0; private set
    var maxStallNanos: Long = 0; private set
    var runWallNanos: Long = 0; private set

    /**
     * The worker's own account of the contended lock: how long it waited, how long it held, and
     * how often. Timed by the thread that did the waiting, so this is the truth the duty cycle and
     * the long-instance detector are checked against.
     */
    var lockWaitNanos: Long = 0; private set
    var lockHeldNanos: Long = 0; private set
    var lockAcquisitions: Long = 0; private set
    var maxLockWaitNanos: Long = 0; private set

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
        stallNanos = 0
        stalls = 0
        maxStallNanos = 0
        runWallNanos = 0
        lockWaitNanos = 0
        lockHeldNanos = 0
        lockAcquisitions = 0
        maxLockWaitNanos = 0
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
        // The deadline check doubles as the stall detector: the gap between two of these readings
        // is a chunk of work, unless the thread lost the CPU in between, and then it is that too.
        val started = System.nanoTime()
        var last = started
        var shortest = Long.MAX_VALUE
        var stalled = 0L
        var events = 0L
        var worst = 0L
        // The lock is taken on a wall-clock cadence rather than every n-th call, so the contention
        // rate is what the configuration says and not a function of how fast this machine happens
        // to be running today. Staggered by thread id so all eight do not arrive together on the
        // first attempt, which would make the first interval unrepresentative of the rest.
        var nextLock = if (contended == null) Long.MAX_VALUE
        else started + contended.intervalNanos * (id + 1) / 8
        while (true) {
            var k = 0
            while (k < CLOCK_CHUNK) {
                val op = sch[i and mask]
                i++
                s = if (lbl) w.execLabeled(op, s) else w.exec(op, s)
                counts[op]++
                k++
            }
            var now = System.nanoTime()
            val gap = now - last
            if (gap < shortest) shortest = gap
            if (gap > STALL_GAP_NANOS) {
                stalled += gap
                events++
                if (gap > worst) worst = gap
            }
            if (now >= nextLock) {
                s = takeLock(s)
                now = System.nanoTime()
                nextLock = now + contended!!.intervalNanos
                // The lock does not count towards the stall detector: it is the one wait in this
                // bench that is *supposed* to happen, and mixing it into the preemption figure
                // would destroy the very comparison it exists to make possible. The clock reading
                // restarts from here for the same reason.
            }
            last = now
            if (now >= d) break
        }
        // Each counted gap includes the chunk of work that ran inside it. Subtracting the fastest
        // chunk the thread managed all run takes the work back out and leaves the stall itself.
        stallNanos = stalled - events * (if (shortest == Long.MAX_VALUE) 0 else shortest)
        stalls = events
        maxStallNanos = worst
        runWallNanos = last - started
        state = s
        idx = i
        Sink.consume(s)
    }

    /**
     * One trip through the contended lock, labelled.
     *
     * The label goes *outside* the acquisition, deliberately. A thread parked waiting for the lock
     * is still inside `lockedUpdate` as far as its slot is concerned, so the sampler counts it —
     * which is the whole point. That occupancy is not CPU, and the duty cycle should see exactly
     * this much of it; the wait also lasts milliseconds, so the long-instance detector should see
     * these executions and no others.
     *
     * Putting the label inside the lock instead would hide the wait completely and the operation
     * would look like an ordinary well-behaved 2 ms of work. That is worth knowing in its own
     * right: where the label sits decides whether waiting is visible at all.
     */
    private fun takeLock(state: Long): Long = op(lockOpId) {
        val c = contended!!
        val t0 = System.nanoTime()
        c.lock.lock()
        val acquired = System.nanoTime()
        val waited = acquired - t0
        lockWaitNanos += waited
        lockAcquisitions++
        if (waited > maxLockWaitNanos) maxLockWaitNanos = waited
        try {
            burn(state, c.holdIters)
        } finally {
            c.lock.unlock()
            lockHeldNanos += System.nanoTime() - acquired
        }
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

/**
 * What the working threads saw of their own scheduling during a run. Summed over active workers:
 * [wallNanos] is thread-time, so it is roughly the run duration times the number of them.
 *
 * [offCpuNanos] is preemption only — time the scheduler took the core away with nothing to wait
 * for. Time spent waiting for the contended lock is [lockWaitNanos] and is deliberately kept
 * apart: one is the machine misbehaving and the other is the workload behaving exactly as
 * configured, and the whole point of the exercise is to see whether the instrument can tell them
 * apart.
 */
class Stalls(
    val wallNanos: Long,
    val offCpuNanos: Long,
    val events: Long,
    val worstNanos: Long,
    val lockWaitNanos: Long = 0,
    val lockHeldNanos: Long = 0,
    val lockAcquisitions: Long = 0,
    val maxLockWaitNanos: Long = 0,
) {
    /** Everything that kept a thread off the CPU, from either cause. */
    val totalOffCpuNanos: Long get() = offCpuNanos + lockWaitNanos
}

/** The bench: a thread pool and the barrier the main thread drives them through stages with. */
class Bench(
    val threads: Int,
    val activeThreads: Int,
    val labeled: Boolean,
    val workload: Workload,
    val contended: ContendedLock? = null,
    lockOpId: Int = -1,
) {
    private val barrier = CyclicBarrier(threads + 1)
    val workers = List(threads) {
        Worker(it, it < activeThreads, labeled, workload, barrier, contended, lockOpId)
    }

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

    /** The workers' own account of the last run: wall time, time off the CPU, and how often. */
    fun stalls(): Stalls = workers.filter { it.active }.let { active ->
        Stalls(
            wallNanos = active.sumOf { it.runWallNanos },
            offCpuNanos = active.sumOf { it.stallNanos },
            events = active.sumOf { it.stalls },
            worstNanos = active.maxOfOrNull { it.maxStallNanos } ?: 0L,
            lockWaitNanos = active.sumOf { it.lockWaitNanos },
            lockHeldNanos = active.sumOf { it.lockHeldNanos },
            lockAcquisitions = active.sumOf { it.lockAcquisitions },
            maxLockWaitNanos = active.maxOfOrNull { it.maxLockWaitNanos } ?: 0L,
        )
    }

    fun resetCounters() = workers.forEach { it.resetCounters() }
}

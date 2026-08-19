package com.minogin.profiler

import java.util.concurrent.CyclicBarrier
import kotlin.math.max

const val STAGE_RUN = 1
const val STAGE_MEASURE = 2
const val STAGE_STOP = 3

/** Root calls between clock checks: nanoTime must not end up inside the hot loop. */
private const val CLOCK_CHUNK = 256

/**
 * A worker thread. Inactive threads exist and take part in the barriers but do no work —
 * that is the artificial starvation mode (3 busy out of 16).
 */
class Worker(
    val id: Int,
    val active: Boolean,
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

    private var state: Long = -0x61c8864680b583ebL * (id + 1)
    private var idx: Int = id * 257

    fun resetCounters() = rootCalls.fill(0L)

    override fun run() {
        while (true) {
            barrier.await()
            val s = stage
            if (active) {
                when (s) {
                    STAGE_RUN -> runUntilDeadline()
                    STAGE_MEASURE -> measureBatches()
                }
            }
            barrier.await()
            if (s == STAGE_STOP) return
        }
    }

    private fun runUntilDeadline() {
        var s = state
        var i = idx
        val sch = w.schedule
        val mask = sch.size - 1
        val counts = rootCalls
        val d = deadline
        while (true) {
            var k = 0
            while (k < CLOCK_CHUNK) {
                val op = sch[i and mask]
                i++
                s = w.exec(op, s)
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
     * The measurement goes through the same [measureBurn] that fitted the iteration counts.
     * That is deliberate: a loop of its own here diverged by up to 15% on short operations,
     * because the JIT compiles two loops that mean the same thing differently depending on
     * where they come from, and at twenty nanoseconds that difference in code shape shows.
     * With a shared primitive the two truths differ by conditions, not by code.
     */
    private fun measureBatches() {
        for (id in 0 until OP_COUNT) {
            measuredSelf[id] = measureBurn(w.iters[id], TRIALS)
        }
        var s = state
        val incl = inclusiveNanos(subtreeCounts())
        for (id in 0 until OP_COUNT) {
            if (ROOT_WEIGHTS[id] == 0) continue
            val reps = repsFor(incl[id])
            measuredInclusive[id] = medianOf(TRIALS) {
                val t0 = System.nanoTime()
                var r = 0
                while (r < reps) {
                    s = w.exec(id, s)
                    r++
                }
                (System.nanoTime() - t0).toDouble() / reps
            }
        }
        state = s
        Sink.consume(s)
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
    }
}

/** The bench: a thread pool and the barrier the main thread drives them through stages with. */
class Bench(val threads: Int, val activeThreads: Int, val workload: Workload) {
    private val barrier = CyclicBarrier(threads + 1)
    val workers = List(threads) { Worker(it, it < activeThreads, workload, barrier) }

    fun start() = workers.forEach { it.isDaemon = true; it.start() }

    private fun stage(s: Int) {
        workers.forEach { it.stage = s }
        barrier.await()
        barrier.await()
    }

    /** Returns the actual duration of the run in nanoseconds. */
    fun run(durationNanos: Long): Long {
        val d = System.nanoTime() + durationNanos
        workers.forEach { it.deadline = d }
        val t0 = System.nanoTime()
        stage(STAGE_RUN)
        return System.nanoTime() - t0
    }

    fun measure() = stage(STAGE_MEASURE)

    fun stop() {
        stage(STAGE_STOP)
        workers.forEach { it.join() }
    }

    fun totalRootCalls(): Long = workers.sumOf { w -> w.rootCalls.sum() }

    fun resetCounters() = workers.forEach { it.resetCounters() }
}

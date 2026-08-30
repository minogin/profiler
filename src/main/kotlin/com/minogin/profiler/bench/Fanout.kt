package com.minogin.profiler.bench

import com.minogin.profiler.CoarseContext
import com.minogin.profiler.OpSlot
import com.minogin.profiler.Profiler
import com.minogin.profiler.captureCoarse
import com.minogin.profiler.withCoarse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The bench's fork and join, and the first thing in it that hands work between threads.
 *
 * Until this existed every worker ran its own schedule and nothing was ever passed anywhere, so a
 * coarse context could not cross a thread even in principle and phase 5 had nothing to test. Here a
 * worker opens a `request`, splits its chunks across a pool of helpers, waits, and closes it — which
 * is the shape Lucene's search has and the shape that produced the defect this phase exists to fix.
 *
 * **A separate pool, not the workers themselves.** The workers keep their role as request drivers,
 * so the barrier and the stage protocol are untouched and every earlier mode still runs exactly as
 * it did. The price is that the process holds `threads + helpers` threads, which is why the
 * configuration this is meant to be read at is few drivers and many helpers.
 *
 * **Propagation is a switch, and both settings are kept.** With [propagate] off this is exactly what
 * 5a measured: the driver's context stays behind and the request's own span sees almost none of the
 * work. With it on, each chunk carries the context it was forked under. Keeping both means the
 * phase 5 checks are an A/B inside one binary rather than a claim about a build that no longer
 * exists — and the off arm is byte-identical to what 5a ran, because the mount is branched around
 * rather than passed a null.
 */
class Fanout(
    val helpers: Int,
    private val w: Workload,
    private val labeled: Boolean,
    barrier: CyclicBarrier,
    /** Whether a dispatched chunk carries the coarse execution its driver was inside. */
    val propagate: Boolean = true,
) {
    /**
     * One request's worth of fan-out, and the bench's own stopwatch on it.
     *
     * [occupancyNanos] is the sum of how long each helper *held* a chunk of this request, wall
     * clock. Divided by the request's span it is the measured parallelism — and it is deliberately
     * occupancy and not CPU, because the number it is checked against is occupancy too: the
     * profiler's `inclusiveHits / instanceTicks` counts a thread inside a context whether or not it
     * was running. Comparing occupancy against CPU would measure the scheduler, not the propagation.
     */
    class Request(chunks: Int) {
        @JvmField
        val latch = CountDownLatch(chunks)

        @JvmField
        val occupancyNanos = AtomicLong()
    }

    /** One unit of dispatched work: a window of the schedule, and which request it belongs to. */
    private class Chunk(
        @JvmField val start: Int,
        @JvmField val calls: Int,
        @JvmField val req: Request,
        /**
         * The coarse execution this chunk was forked under, or null when propagation is off.
         *
         * Captured on the **driver** at submit time and not on the helper at run time, which is the
         * one way to get this wrong that still compiles: by the time a helper picks the chunk up,
         * the driver's context is not reachable from it and its own is empty.
         */
        @JvmField val ctx: CoarseContext?,
        /**
         * Whether the driver counts this chunk down and waits for it.
         *
         * False for a chunk deliberately left un-joined, so it outlives the span it was forked
         * under. See [submitEscaping].
         */
        @JvmField val joined: Boolean,
    )

    private val queue = LinkedBlockingQueue<Chunk>()

    /**
     * Chunks submitted and not yet finished. The helpers' exit condition, and the reason a run
     * cannot end with a driver still parked on a latch: a helper leaves only once the deadline has
     * passed *and* nothing is outstanding.
     */
    private val pending = AtomicLong()

    private val threads = List(helpers) { Helper(it, barrier) }

    /** Which stage the bench is driving. Written by the main thread between the two barrier trips. */
    @Volatile
    var stage: Int = 0

    /** Set before each run, exactly as the workers' deadline is. */
    @Volatile
    var deadline: Long = 0

    fun start() = threads.forEach { it.isDaemon = true; it.start() }

    fun join() = threads.forEach { it.join() }

    /** Root calls executed on the helpers, which in fan-out mode is nearly all of them. */
    fun rootCalls(): List<LongArray> = threads.map { it.rootCalls }

    /** Self durations each helper measured, so its own calls can be priced by its own clock. */
    fun measuredSelf(): List<DoubleArray> = threads.map { it.measuredSelf }

    /** The helpers' slots, so their call counters can be read before the threads exit. */
    fun slots(): List<OpSlot> = threads.mapNotNull { it.slotOrNull }

    fun resetCounters() = threads.forEach { it.rootCalls.fill(0L) }

    /** Hands one window of the schedule to whichever helper takes it first. */
    fun submit(start: Int, calls: Int, req: Request) {
        pending.incrementAndGet()
        queue.put(Chunk(start, calls, req, if (propagate) captureCoarse() else null, joined = true))
    }

    /**
     * Dispatches a chunk under the caller's context that **nobody will wait for**.
     *
     * Staged on purpose, and it is the only way to see the stale-context detector work. Fire and
     * forget is a perfectly ordinary thing for a program to do, and it is exactly what the detector
     * exists to catch: the driver closes its request and moves on while this chunk is still running
     * under a context that has ended.
     *
     * It is still counted and it still finishes — the helpers drain the pipeline before the run ends
     * — so root calls are conserved and the escape does not quietly change the workload into a
     * different one.
     */
    fun submitEscaping(start: Int, calls: Int, req: Request) {
        pending.incrementAndGet()
        queue.put(Chunk(start, calls, req, if (propagate) captureCoarse() else null, joined = false))
    }

    /** Blocks the driver until every chunk of [req] has been run. This is the join. */
    fun await(req: Request) = req.latch.await()

    private inner class Helper(val id: Int, private val barrier: CyclicBarrier) :
        Thread("bench-helper-$id") {

        /** Root calls per operation, exactly as a worker keeps them. Summed with the workers'. */
        @JvmField
        val rootCalls = LongArray(OP_COUNT)

        /**
         * This helper's own reading of what each operation costs — truth B, measured on the thread
         * that ran the calls.
         *
         * Helpers take part in the measure stage for the same reason the workers do it per thread
         * rather than once: threads do not run at the same speed on a hybrid chip, so a thread's own
         * calls have to be priced by that thread's own clock. Without this, fan-out would move the
         * work onto threads whose durations nobody measured, and the two-truths check would be
         * comparing counts from one set of threads against durations from another.
         */
        @JvmField
        val measuredSelf = DoubleArray(OP_COUNT)

        var slotOrNull: OpSlot? = null
            private set

        private var state: Long = -0x61c8864680b583ebL * (id + 101)

        override fun run() {
            // Up front, as the workers do: a slot appearing in the middle of a run would show up in
            // the sampler's walk as a thread that did not exist a tick ago.
            slotOrNull = Profiler.slot()
            try {
                while (true) {
                    barrier.await()
                    val s = stage
                    when (s) {
                        STAGE_RUN -> drain()
                        STAGE_MEASURE -> measure()
                    }
                    barrier.await()
                    if (s == STAGE_STOP) return
                }
            } finally {
                Profiler.release()
            }
        }

        /**
         * Takes chunks until the run is over.
         *
         * The exit condition is the deadline **and** an empty pipeline, never the deadline alone: a
         * driver submits its last request just before the deadline passes, and a helper that left on
         * the clock would leave it parked on a latch nobody will ever count down.
         */
        private fun drain() {
            while (true) {
                val c = queue.poll(POLL_MICROS, TimeUnit.MICROSECONDS)
                if (c == null) {
                    if (System.nanoTime() >= deadline && pending.get() == 0L) return
                    continue
                }
                val t0 = System.nanoTime()
                // Branched rather than always mounting, so that with propagation off the helper runs
                // precisely the code 5a measured and the A/B differs by the mount and nothing else.
                if (propagate) withCoarse(c.ctx) { runChunk(c) } else runChunk(c)
                // Timed around the work and nothing else, so this is how long the helper was inside
                // the driver's request — which is what the driver's span has to be divided by.
                c.req.occupancyNanos.addAndGet(System.nanoTime() - t0)
                pending.decrementAndGet()
                if (c.joined) c.req.latch.countDown()
            }
        }

        private fun runChunk(c: Chunk) {
            var s = state
            val sch = w.schedule
            val mask = sch.size - 1
            var i = c.start
            var k = 0
            while (k < c.calls) {
                val op = sch[i and mask]
                i++
                s = if (labeled) w.execLabeled(op, s) else w.exec(op, s)
                rootCalls[op]++
                k++
            }
            state = s
            Sink.consume(s)
        }

        /** Self durations only. The inclusive half stays on the workers, which is where it is read. */
        private fun measure() {
            val samples = Array(OP_COUNT) { DoubleArray(MEASURE_TRIALS) }
            for (t in 0 until MEASURE_TRIALS) {
                for (id in 0 until OP_COUNT) samples[id][t] = timeBurnOnce(w.iters[id])
            }
            for (id in 0 until OP_COUNT) {
                samples[id].sort()
                measuredSelf[id] = samples[id][MEASURE_TRIALS / 2]
            }
        }
    }

    private companion object {
        /**
         * How long a helper waits on an empty queue before re-checking whether the run is over.
         *
         * Short enough that the end of a run is not padded by it, long enough that an idle helper is
         * not spinning on a core the sampler and the drivers want. It is a poll and not a park
         * because the exit condition is a clock reading, which no signal carries.
         */
        const val POLL_MICROS = 200L

        /** As [Worker]'s TRIALS. The median of an odd number of readings, taken on this thread. */
        const val MEASURE_TRIALS = 7
    }
}

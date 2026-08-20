package com.minogin.profiler

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

/** Slot value meaning "this thread is not inside any instrumented operation right now". */
const val NO_OP = -1

/**
 * One thread's slot: the id of the operation that thread is currently inside.
 *
 * The padding is not decoration. A slot is a 12-byte header plus a 4-byte field, so four of them
 * would share a 64-byte cache line, and every worker's write would invalidate its neighbours'
 * copies on other cores — tens of nanoseconds against operations that last twenty. Since all
 * slots have identical layout, the distance between two slots' `current` fields is exactly the
 * object size whatever order HotSpot chooses internally, so making the object large enough is
 * sufficient. 15 longs takes it past 128 bytes, which also defeats adjacent-line prefetching.
 * The cost is under 2 KB for the whole registry.
 */
@Suppress("unused")
class OpSlot {
    @Volatile
    @JvmField
    var current: Int = NO_OP

    @JvmField var p1: Long = 0
    @JvmField var p2: Long = 0
    @JvmField var p3: Long = 0
    @JvmField var p4: Long = 0
    @JvmField var p5: Long = 0
    @JvmField var p6: Long = 0
    @JvmField var p7: Long = 0
    @JvmField var p8: Long = 0
    @JvmField var p9: Long = 0
    @JvmField var p10: Long = 0
    @JvmField var p11: Long = 0
    @JvmField var p12: Long = 0
    @JvmField var p13: Long = 0
    @JvmField var p14: Long = 0
    @JvmField var p15: Long = 0
}

/**
 * The slot registry. A thread gets its slot from a ThreadLocal and is added to the walk list on
 * first access. Threads are expected to register themselves up front, so the list is stable by
 * the time the sampler starts and no thread appears mid-run.
 */
object Profiler {
    private val allSlots = CopyOnWriteArrayList<OpSlot>()

    private val local: ThreadLocal<OpSlot> = ThreadLocal.withInitial {
        val s = OpSlot()
        allSlots.add(s)
        s
    }

    /** The calling thread's slot, registering it on first call. */
    fun slot(): OpSlot = local.get()

    /**
     * Drops the calling thread's slot. A thread that has finished must not stay in the walk list:
     * its slot reads empty forever, inflating the sampler's denominator and — worse for occupancy
     * work — counting a dead thread as an idle one. A registry that only ever grows is also a
     * plain leak in anything long-lived with a thread pool that recycles.
     */
    fun release() {
        allSlots.remove(local.get())
        local.remove()
    }

    /** Every live registered slot. Read by the sampler. */
    fun slots(): List<OpSlot> = allSlots
}

/**
 * Marks the calling thread as being inside operation [id] for the duration of [body].
 *
 * The previous value is restored rather than cleared — clearing would break nesting, since the
 * caller is still inside its own operation when a nested one returns.
 */
inline fun <T> op(id: Int, body: () -> T): T {
    val slot = Profiler.slot()
    val prev = slot.current
    slot.current = id
    try {
        return body()
    } finally {
        slot.current = prev
    }
}

/**
 * How the sampler waits for its next tick.
 *
 * Measured at a 1 ms request, 8 workers on 16 cores: PARK achieved 1.62 ms with 2,534 resyncs out
 * of 6,177 ticks, SLEEP 1.81 ms, SPIN 1.001 ms with a single resync. With all 16 cores loaded
 * PARK degraded to 13.5 ms — the sampler simply could not get scheduled.
 *
 * SPIN is the default and the others are kept as the evidence for that choice, worth re-running
 * on different hardware. PARK also bunches: after falling behind it fires several ticks in quick
 * succession, and bunched samples are correlated samples, which more samples will not fix.
 */
enum class WaitStrategy {
    /** LockSupport.parkNanos — free, but only as punctual as the scheduler is willing to be. */
    PARK,

    /** Thread.sleep(1) in a loop. Tested on the theory that a short sleep makes HotSpot ask
     *  Windows for a finer timer; it made no difference. */
    SLEEP,

    /** Busy-spin on nanoTime — microsecond accuracy, at the price of a whole core. */
    SPIN,
}

/**
 * The sampling thread. Wakes on a fixed step, reads every slot, increments the counter for
 * whatever id it found. Reads other threads' slots without any synchronisation — deliberately;
 * whether that smears the picture is a phase 3 question, answered by whether the numbers agree.
 *
 * Counters are plain longs: this thread is their only writer.
 */
class Sampler(private val stepNanos: Long, private val wait: WaitStrategy) : Thread("sampler") {

    /** Hits per operation; the last entry counts slots that held no operation. */
    val counters = LongArray(OP_COUNT + 1)

    var ticks: Long = 0; private set
    var lagged: Long = 0; private set
    var minStep: Long = Long.MAX_VALUE; private set
    var maxStep: Long = 0; private set
    var span: Long = 0; private set

    @Volatile private var running = true

    init {
        isDaemon = true
        priority = MAX_PRIORITY
    }

    override fun run() {
        val slots = Profiler.slots()
        var next = System.nanoTime() + stepNanos
        var prev = 0L
        var first = 0L

        while (running) {
            waitUntil(next)
            if (!running) break
            val now = System.nanoTime()

            for (s in slots) {
                val c = s.current
                counters[if (c < 0) OP_COUNT else c]++
            }
            ticks++

            if (prev == 0L) {
                first = now
            } else {
                val d = now - prev
                if (d < minStep) minStep = d
                if (d > maxStep) maxStep = d
            }
            prev = now
            span = now - first

            next += stepNanos
            // Fell behind by more than a whole step: resync rather than fire a burst of catch-up
            // ticks, which would bunch samples and distort the shares.
            if (next <= now) {
                next = now + stepNanos
                lagged++
            }
        }
    }

    private fun waitUntil(deadline: Long) {
        when (wait) {
            WaitStrategy.PARK -> {
                var left = deadline - System.nanoTime()
                // parkNanos may return early; loop until the deadline has actually passed.
                while (left > 0 && running) {
                    LockSupport.parkNanos(left)
                    left = deadline - System.nanoTime()
                }
            }

            WaitStrategy.SLEEP -> {
                while (running && deadline - System.nanoTime() > 0) {
                    try {
                        sleep(1)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }

            WaitStrategy.SPIN -> {
                while (running && deadline - System.nanoTime() > 0) onSpinWait()
            }
        }
    }

    fun shutdown() {
        running = false
        LockSupport.unpark(this)
        join()
    }

    /** Total samples taken: one per slot per tick. */
    fun totalSamples(): Long = counters.sum()
}

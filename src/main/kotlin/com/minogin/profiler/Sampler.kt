package com.minogin.profiler

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
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
    /**
     * Accessed opaquely, not volatile. A volatile store on x86 is not a store — it needs a
     * StoreLoad barrier, a lock-prefixed instruction costing tens of cycles, and the hook does two
     * of them per operation call. Measured, that came to about 20 ns per call and 16% of the
     * bench's throughput, against a design that assumed single-digit nanoseconds.
     *
     * Opaque is the right strength. It forbids the JIT from eliminating or reordering the access —
     * a plain field would let dead-store elimination drop the entry write entirely once the body
     * inlines, silently blinding the profiler — while emitting no fence at all, so it compiles to
     * an ordinary MOV. That is the identical instruction a relaxed store in C or Rust produces.
     *
     * The ordering we give up is the ordering we already gave up on the read side: the sampler may
     * see a value a few nanoseconds stale. There was never a reason for the write side to be
     * stronger than the read side.
     */
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

    /**
     * Calls per operation on this thread. Plain longs with a single writer — the owning thread —
     * so no fence and no contention.
     *
     * Counting is nearly free here because the expensive part of the hook is finding the
     * per-thread data, and by this point we already hold it. And the count is not a luxury: a
     * share on its own cannot tell "200M calls at 8 ns" from "1000 calls at 1.6 ms", and those
     * two want opposite fixes.
     *
     * Padded at both ends. The array is a separate object, so without padding two threads'
     * arrays could straddle a cache line at their boundary, and these are written on every single
     * operation call — exactly the traffic false sharing punishes hardest.
     */
    @JvmField
    val counts = LongArray(OP_COUNT + 2 * COUNT_PAD)

    fun count(id: Int) {
        counts[id + COUNT_PAD]++
    }

    fun countOf(id: Int): Long = counts[id + COUNT_PAD]

    fun resetCounts() = counts.fill(0L)

    companion object {
        /** 16 longs — 128 bytes — of padding at each end of the counter array. */
        const val COUNT_PAD = 16

        /**
         * Static final so the JIT can constant-fold it and intrinsify the accessor down to a bare
         * memory instruction. A non-final handle would leave a real method call in the hot path.
         */
        @JvmStatic
        val CURRENT: VarHandle =
            MethodHandles.lookup().findVarHandle(OpSlot::class.java, "current", Int::class.javaPrimitiveType)
    }
}

/** Marks this thread as inside operation [id]. No fence — see [OpSlot.current]. */
fun OpSlot.setOpaque(id: Int) = OpSlot.CURRENT.setOpaque(this, id)

/** Reads the slot as the sampler does: no fence, possibly a few nanoseconds stale. */
fun OpSlot.getOpaque(): Int = OpSlot.CURRENT.getOpaque(this) as Int

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
    val prev = slot.getOpaque()
    slot.setOpaque(id)
    // After the label, deliberately. Before it, the increment would be billed to the caller and
    // would add to the attribution bias; after it, the time lands on the operation it belongs to.
    // That inflates busy operations by calls x counterCost — the one distortion we can subtract
    // exactly, since the counter measures precisely the quantity the correction needs.
    slot.count(id)
    try {
        return body()
    } finally {
        slot.setOpaque(prev)
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
class Sampler(
    private val stepNanos: Long,
    private val wait: WaitStrategy,
    private val jitterFraction: Double = 0.25,
) : Thread("sampler") {

    // Ticking on an exact beat can lock onto a workload that has a rhythm of its own near the
    // same period — the wagon-wheel effect, where more samples do not help because every sample
    // catches the same phase. Scattering the interval makes that impossible. The jitter is
    // symmetric, so the mean interval is still the requested step. Seeded, so runs repeat.
    private val rnd = java.util.Random(20260820L)

    private fun nextInterval(): Long =
        if (jitterFraction <= 0.0) stepNanos
        else stepNanos + ((rnd.nextDouble() * 2 - 1) * jitterFraction * stepNanos).toLong()

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
        var next = System.nanoTime() + nextInterval()
        var prev = 0L
        var first = 0L

        while (running) {
            waitUntil(next)
            if (!running) break
            val now = System.nanoTime()

            for (s in slots) {
                val c = s.getOpaque()
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

            next += nextInterval()
            // Fell behind by more than a whole step: resync rather than fire a burst of catch-up
            // ticks, which would bunch samples and distort the shares.
            if (next <= now) {
                next = now + nextInterval()
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

package com.minogin.profiler

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.sqrt

/** Slot value meaning "this thread is not inside any instrumented operation right now". */
const val NO_OP = -1

/**
 * Ceiling on how many distinct operations may be registered.
 *
 * Fixed rather than growable on purpose. The hot path indexes a counter array by operation id, and
 * a growable array would need either an indirection through a volatile reference or a copy that
 * races with the writers. A fixed ceiling costs 2 KB of counters per thread and nothing on the hot
 * path, and 256 hand-placed labels is far past what anyone will write.
 */
const val MAX_OPERATIONS = 256

/** Where the sampler counts slots that held no operation. */
const val NO_OP_INDEX = MAX_OPERATIONS

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
    val counts = LongArray(MAX_OPERATIONS + 2 * COUNT_PAD)

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

    private val names = arrayOfNulls<String>(MAX_OPERATIONS)
    private val ids = ConcurrentHashMap<String, Int>()
    private val nextId = AtomicInteger(0)

    /** Calls from threads that have already exited, folded in so a report does not lose them. */
    private val retiredCounts = LongArray(MAX_OPERATIONS)

    @Volatile
    private var sampler: Sampler? = null
    private var startedAt: Long = 0

    /**
     * Id for an operation name, assigned on first call and stable thereafter. Idempotent, so it is
     * safe to call from a static initialiser, a lazy holder, or once per call site.
     *
     * Do this at startup, not on the hot path — it takes a map lookup, which is many times the cost
     * of the hook it feeds.
     */
    fun register(name: String): Int = ids.computeIfAbsent(name) {
        val id = nextId.getAndIncrement()
        check(id < MAX_OPERATIONS) { "more than $MAX_OPERATIONS distinct operations registered" }
        names[id] = name
        id
    }

    fun nameOf(id: Int): String = names[id] ?: "op#$id"

    fun registeredCount(): Int = nextId.get()

    /** The calling thread's slot, registering it on first call. */
    fun slot(): OpSlot = local.get()

    /**
     * Drops the calling thread's slot. A thread that has finished must not stay in the walk list:
     * its slot reads empty forever, inflating the sampler's denominator and — worse for occupancy
     * work — counting a dead thread as an idle one. A registry that only ever grows is also a
     * plain leak in anything long-lived with a thread pool that recycles.
     *
     * Its call counts are folded into the retired totals first, or a pool that recycles threads
     * would silently lose everything the retired ones did.
     */
    fun release() {
        val s = local.get()
        synchronized(retiredCounts) {
            for (id in 0 until MAX_OPERATIONS) retiredCounts[id] += s.countOf(id)
        }
        allSlots.remove(s)
        local.remove()
    }

    /** Every live registered slot. Read by the sampler. */
    fun slots(): List<OpSlot> = allSlots

    /** Total calls of an operation: live threads plus those that have already exited. */
    fun callsOf(id: Int): Long =
        synchronized(retiredCounts) { retiredCounts[id] } + allSlots.sumOf { it.countOf(id) }

    /** Starts sampling. One sampler at a time. */
    fun start(stepMillis: Double = 1.0, wait: WaitStrategy = WaitStrategy.SPIN, jitter: Double = 0.25) {
        check(sampler == null) { "already sampling" }
        startedAt = System.nanoTime()
        sampler = Sampler((stepMillis * 1_000_000).toLong(), wait, jitter).also { it.start() }
    }

    /** Stops sampling and returns what was collected. */
    fun stop(): Report {
        val s = checkNotNull(sampler) { "not sampling" }
        s.shutdown()
        sampler = null
        val duration = System.nanoTime() - startedAt
        val stats = (0 until registeredCount()).map { id ->
            OperationStat(id, nameOf(id), s.counters[id], callsOf(id))
        }
        return Report(stats, s.counters[NO_OP_INDEX], s.ticks, s.span, duration, s.maxSlots)
    }
}

/** One operation's line in a [Report]. */
class OperationStat(val id: Int, val name: String, val hits: Long, val calls: Long)

/**
 * What a sampling session collected.
 *
 * Shares are over *labelled* samples. Samples that caught a thread outside any operation are
 * reported separately as [idleHits] rather than folded into the denominator — mixing them in would
 * make every share depend on how much uninstrumented code happened to be running.
 */
class Report(
    val operations: List<OperationStat>,
    val idleHits: Long,
    val ticks: Long,
    val samplingSpanNanos: Long,
    val durationNanos: Long,
    val threads: Int,
) {
    val labelledHits: Long get() = operations.sumOf { it.hits }

    fun shareOf(op: OperationStat): Double =
        if (labelledHits == 0L) 0.0 else op.hits.toDouble() / labelledHits

    /** Error chance alone would produce at this hit count — anything smaller is not measurable. */
    fun noiseFloorOf(op: OperationStat): Double =
        if (op.hits > 0) 1.0 / sqrt(op.hits.toDouble()) else Double.NaN

    fun render(): String = buildString {
        val achieved = if (ticks > 1) samplingSpanNanos.toDouble() / (ticks - 1) / 1e6 else Double.NaN
        appendLine("=".repeat(84))
        appendLine(
            String.format(
                Locale.ROOT, "%,d labelled samples over %.1f s, %,d ticks at %.3f ms, %d threads",
                labelledHits, durationNanos / 1e9, ticks, achieved, threads
            )
        )
        appendLine(
            String.format(
                Locale.ROOT, "outside any operation: %,d samples (%.1f%% of all)",
                idleHits, idleHits * 100.0 / (labelledHits + idleHits).coerceAtLeast(1)
            )
        )
        appendLine("=".repeat(84))
        appendLine(String.format(Locale.ROOT, "%-32s %10s %14s %9s %8s", "operation", "share", "calls", "hits", "noise"))
        appendLine("-".repeat(84))
        for (op in operations.sortedByDescending { it.hits }) {
            appendLine(
                String.format(
                    Locale.ROOT, "%-32s %9.3f%% %,14d %9d %7.2f%%",
                    op.name, shareOf(op) * 100, op.calls, op.hits, noiseFloorOf(op) * 100
                )
            )
        }
        appendLine("-".repeat(84))
        appendLine("share is of labelled samples; noise is 1/sqrt(hits), the error chance alone gives")
    }
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
    val counters = LongArray(MAX_OPERATIONS + 1)

    var ticks: Long = 0; private set
    var lagged: Long = 0; private set
    var minStep: Long = Long.MAX_VALUE; private set
    var maxStep: Long = 0; private set
    var span: Long = 0; private set

    /** Most slots seen at any one tick. Read after the fact, when the threads may already be gone. */
    var maxSlots: Int = 0; private set

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
                counters[if (c < 0) NO_OP_INDEX else c]++
            }
            if (slots.size > maxSlots) maxSlots = slots.size
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

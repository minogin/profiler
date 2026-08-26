package com.minogin.profiler

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.min
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
 * How many threads the long-instance detector can track at once.
 *
 * Only a ceiling on the sampler's parallel arrays — 16 KB for the lot — and indexes are recycled
 * when threads die, so this is a limit on *simultaneous* registered threads and not on how many a
 * process may create. A thread past the ceiling is still sampled and still counted; it is only
 * invisible to the detector, and the report says so rather than quietly dropping it.
 */
const val MAX_SLOTS = 1024

/** How deeply hand-placed `enter` labels may nest on one thread before the stack gives up. */
const val MAX_SPAN_DEPTH = 64

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
class OpSlot(
    /**
     * This slot's position in the sampler's own parallel arrays, or -1 if the registry was full
     * when the thread arrived and the detector cannot track it.
     *
     * The sampler needs somewhere to remember what it saw here last tick, and that somewhere must
     * not be this object: a write from the sampling thread would invalidate the owner's cache line
     * on every tick, which is exactly the false sharing all the padding below exists to prevent.
     * So the slot carries an index and the sampler keeps its own arrays.
     *
     * Assigned at construction and never changed. Released when the thread dies, and handed to the
     * next thread that arrives — the sampler notices, because the new thread's call counter will
     * not match what the old one left behind.
     */
    @JvmField val index: Int,
) {
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

    /**
     * The owning thread, as an id rather than a reference — a reference would keep a dead thread
     * and everything it held reachable for as long as the slot lived.
     *
     * Written once at construction, which happens on the owning thread inside the ThreadLocal
     * supplier, so this costs nothing on the hot path and needs no publication guarantee beyond
     * the one final-field-like initialisation already gives the sampler.
     */
    @JvmField
    val threadId: Long = Thread.currentThread().threadId()

    /**
     * The owning thread, weakly, so the sampler can ask what state it is in.
     *
     * Weak and not strong. [threadId] exists because a strong reference would keep a dead thread
     * and everything it held reachable for as long as the slot lived, and that reasoning has not
     * changed — `release()` is called by hand, so a thread that dies without releasing would pin
     * itself forever, which is exactly the leak the virtual-thread hazard already describes. A weak
     * reference costs the sampler one indirection per slot per tick and cannot leak at all. The
     * sampler has a core; the process does not have spare heap.
     *
     * Written once at construction on the owning thread, so nothing here reaches the hot path.
     */
    @JvmField
    val thread: java.lang.ref.WeakReference<Thread> =
        java.lang.ref.WeakReference(Thread.currentThread())

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

    /** For a label placed around [n] units of work rather than one. See `op(id, times)`. */
    fun count(id: Int, n: Int) {
        counts[id + COUNT_PAD] += n
    }

    /**
     * The stack of operations this thread has entered without leaving, for the non-lexical form.
     *
     * Only `enter`/`exit` touch it — `op(id) { }` keeps its predecessor in a local and restores it
     * in a `finally`, which the compiler writes and nothing can leak past. So depth here is exactly
     * the number of labels placed by hand and not yet closed, which is what makes it a balance
     * check rather than a general call-depth counter.
     *
     * A plain array with a single writer, and read from the sampling thread only when the session
     * ends. Sixty-four is far past any sane nesting of hand-placed labels; past that the label is
     * still set correctly and only the restoring is given up, which is recorded rather than thrown.
     */
    @JvmField
    val stack = IntArray(MAX_SPAN_DEPTH)

    @JvmField
    var depth: Int = 0

    /** Hand-placed labels that overflowed the stack, so exit could not restore what came before. */
    @JvmField
    var overflows: Long = 0

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

        /**
         * For the sampler's read of somebody else's call counter. The owner keeps writing a plain
         * `counts[id]++` — the hot path is not touched — while the reader goes through this, so
         * the JIT cannot hoist the read out of the sampling loop and hand back a value from a
         * minute ago. Coherence does the rest; a few nanoseconds stale is the same bargain the
         * slot itself already makes.
         */
        @JvmStatic
        val COUNTS: VarHandle = MethodHandles.arrayElementVarHandle(LongArray::class.java)
    }
}

/** Marks this thread as inside operation [id]. No fence — see [OpSlot.current]. */
fun OpSlot.setOpaque(id: Int) = OpSlot.CURRENT.setOpaque(this, id)

/** Reads the slot as the sampler does: no fence, possibly a few nanoseconds stale. */
fun OpSlot.getOpaque(): Int = OpSlot.CURRENT.getOpaque(this) as Int

/** How many times this thread has entered [id], read from another thread. See [OpSlot.COUNTS]. */
fun OpSlot.countOpaque(id: Int): Long = OpSlot.COUNTS.getOpaque(counts, id + OpSlot.COUNT_PAD) as Long

/**
 * The slot registry. A thread gets its slot from a ThreadLocal and is added to the walk list on
 * first access. Threads are expected to register themselves up front, so the list is stable by
 * the time the sampler starts and no thread appears mid-run.
 */
object Profiler {
    private val allSlots = CopyOnWriteArrayList<OpSlot>()

    /**
     * Slot indexes in use, and the ones given back by threads that have died.
     *
     * Recycled rather than ever-growing, because a pool that creates and destroys threads for the
     * life of a process would otherwise walk off the end of the sampler's arrays. Exhausting the
     * ceiling is not fatal: the slot still works and is still sampled, it merely cannot be tracked
     * by the long-instance detector, and the report says how many such slots there were.
     */
    private val freeIndexes = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val nextIndex = AtomicInteger(0)

    private fun takeIndex(): Int =
        freeIndexes.poll() ?: nextIndex.getAndIncrement().let { if (it < MAX_SLOTS) it else -1 }

    private val local: ThreadLocal<OpSlot> = ThreadLocal.withInitial {
        val s = OpSlot(takeIndex())
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
     * Enters operation [id] until a matching [exit], for a boundary that is not a block.
     *
     * **`op(id) { }` is the form to reach for first.** It is inline, its `finally` is written by the
     * compiler, and it cannot leak. This exists because almost nothing in third-party code is ours
     * to wrap in a block: the boundary Calcite offers is two callbacks, one before the rule fires
     * and one after, and the trial had to write its own fifteen lines against [slot] to use it.
     * The two forms nest in either order.
     *
     * What it costs, and it is not the hook: **there is no `finally` here, so a body that throws
     * leaves the label set** and every later sample on this thread is billed to it. No error, no
     * warning, a plausible wrong number — the contaminating direction. Calcite's "after"
     * notification is not inside a `finally`, so this is not a hypothetical. See [expectBalanced].
     */
    fun enter(id: Int) {
        val s = local.get()
        if (s.depth < MAX_SPAN_DEPTH) s.stack[s.depth++] = s.getOpaque() else s.overflows++
        s.setOpaque(id)
        s.count(id)
    }

    /** Leaves the innermost hand-placed operation, restoring what the thread was inside before. */
    fun exit() {
        val s = local.get()
        s.setOpaque(if (s.depth > 0) s.stack[--s.depth] else NO_OP)
    }

    /** How many hand-placed labels this thread has entered and not left. */
    fun depth(): Int = local.get().depth

    /**
     * Asserts that this thread has closed every label it opened, and reports whether it had.
     *
     * The check the trial performed after every one of its 484 iterations rather than assuming the
     * library's users would think of it. Call it wherever the caller knows the thread should be
     * quiescent — between requests, between iterations, at the end of a task — and a leak surfaces
     * there instead of quietly contaminating everything that follows.
     *
     * Resets the stack, so one leak is one report rather than every subsequent check failing too.
     */
    fun expectBalanced(): Boolean {
        val s = local.get()
        if (s.depth == 0) return true
        imbalances.incrementAndGet()
        s.depth = 0
        s.setOpaque(NO_OP)
        return false
    }

    /** Labels left open at a point the caller said should be quiescent. See [expectBalanced]. */
    private val imbalances = AtomicInteger(0)

    /** Threads still inside a hand-placed label right now, which at the end of a session is a leak. */
    fun openSpans(): Int = allSlots.count { it.depth > 0 }

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
        // Removed from the walk list first, so the sampler cannot be reading this slot at the
        // moment its index is handed to somebody else.
        if (s.index >= 0) freeIndexes.add(s.index)
        local.remove()
    }

    /** Slots that arrived after the ceiling and are therefore invisible to the detector. */
    fun untrackedSlots(): Int = (nextIndex.get() - MAX_SLOTS).coerceAtLeast(0)

    /** Every live registered slot. Read by the sampler. */
    fun slots(): List<OpSlot> = allSlots

    /** Total calls of an operation: live threads plus those that have already exited. */
    fun callsOf(id: Int): Long =
        synchronized(retiredCounts) { retiredCounts[id] } + allSlots.sumOf { it.countOf(id) }

    /**
     * Starts sampling. One sampler at a time.
     *
     * [strict] stops the session if a label turns out to be on something below the floor — see the
     * severity ladder in plan.md. Switch it off to profile code you do not own and cannot resize.
     */
    fun start(
        stepMillis: Double = 1.0,
        wait: WaitStrategy = WaitStrategy.SPIN,
        jitter: Double = 0.25,
        strict: Boolean = true,
        sampleState: Boolean = true,
    ) {
        check(sampler == null) { "already sampling" }
        startedAt = System.nanoTime()
        sampler = Sampler((stepMillis * 1_000_000).toLong(), wait, jitter, strict = strict, sampleState = sampleState)
            .also { it.start() }
    }

    /** Stops sampling and returns what was collected. */
    fun stop(): Report {
        val s = checkNotNull(sampler) { "not sampling" }
        s.shutdown()
        sampler = null
        val duration = System.nanoTime() - startedAt
        val stats = (0 until registeredCount()).map { id ->
            OperationStat(
                id, nameOf(id), s.counters[id], s.sessionCalls(id), s.stuckHits[id], s.stuckInstances[id],
                s.waitingHits[id], s.stuckWaitingHits[id], s.activeTicks[id],
            )
        }
        // A label still open when the session ends is a leak by definition: nothing can close it
        // now. Counted here rather than left to the user to notice, because the symptom — one
        // operation quietly accumulating everybody else's samples — looks exactly like a finding.
        return Report(
            stats, s.counters[NO_OP_INDEX], s.ticks, s.span, duration, s.maxSlots, s.duty(), s.failure,
            imbalances = imbalances.get(), openAtEnd = openSpans(), stateSampled = s.sampleState,
            idleWaitingHits = s.idleWaitingHits,
        )
    }
}

/**
 * Whether one operation's long executions are evidence of anything, given what the machine was
 * doing to every operation at the same time — see [Report.machineFloor].
 *
 * All three conditions matter and the third was learned the moment this first ran: on a quiet
 * machine the floor falls to 1%, so a single long execution out of two thousand samples clears
 * three times it and means nothing whatever. A ratio against a small number is not evidence. The
 * floor on the share and the minimum count are what keep the answer honest when the machine is
 * behaving perfectly.
 *
 * One function so that the library's report and the bench's own check cannot drift apart, which
 * they did within an hour of the bench check being written by hand.
 */
/** See [Report.impliedUpperNanosOf]. Shared so the run-time check and the report cannot disagree. */
fun impliedUpperNanos(hits: Long, calls: Long, stepNanos: Double): Double =
    if (calls == 0L) Double.NaN else (hits + 2 * sqrt(hits.toDouble()) + 3) * stepNanos / calls

/** Whether a label is on something too small for the instrument to describe. See [Report.tooSmall]. */
fun isTooSmall(hits: Long, calls: Long, stepNanos: Double): Boolean =
    calls > 0 && impliedUpperNanos(hits, calls, stepNanos) * Report.FLOOR_BIAS_ALLOWANCE < Report.FLOOR_NANOS

/**
 * What to tell someone whose label is below the floor. Both reasons, because the second is the one
 * they cannot check for themselves.
 */
fun tooSmallMessage(name: String, calls: Long, upperNanos: Double): String = String.format(
    Locale.ROOT,
    "%s: %,d calls at under %s each, below the %.0f ns floor.%n" +
            "    The hook is a large fraction of an operation that size, the sampler reads it low by " +
            "5-9%%,%n" +
            "    and C2 can move work across the boundaries of adjacent short labels without leaving a " +
            "trace in the numbers.%n" +
            "    Label the enclosing loop instead and divide by the iteration count.",
    name, calls, duration(upperNanos), Report.FLOOR_NANOS
)

fun isSuspect(hits: Long, stuckHits: Long, stuckInstances: Long, machineFloor: Double): Boolean =
    hits > 0 && stuckInstances >= Report.SUSPECT_MIN_INSTANCES &&
            stuckHits.toDouble() / hits > maxOf(machineFloor * Report.SUSPECT_OVER_BASELINE, Report.SUSPECT_MIN_SHARE)

/**
 * A duration in whatever unit keeps it readable. The fine tier spans four orders of magnitude, and
 * a column of nanoseconds makes 1.5 ms and 9 µs look like the same kind of number, which is the
 * one distinction this column exists to draw.
 */
fun duration(nanos: Double): String = when {
    nanos.isNaN() -> "-"
    nanos < 1_000 -> String.format(Locale.ROOT, "%.1f ns", nanos)
    nanos < 1_000_000 -> String.format(Locale.ROOT, "%.1f us", nanos / 1e3)
    else -> String.format(Locale.ROOT, "%.2f ms", nanos / 1e6)
}

/**
 * A total of thread-time, in whatever unit keeps it readable.
 *
 * Separate from [duration] because the two live at opposite ends of the scale: a per-call duration
 * runs to nanoseconds and an occupancy total to seconds, and one formatter covering both would
 * print 40 seconds as "40080.00 ms".
 */
fun threadTime(nanos: Double): String = when {
    nanos.isNaN() -> "-"
    nanos < 1_000_000_000.0 -> String.format(Locale.ROOT, "%.1f ms", nanos / 1e6)
    else -> String.format(Locale.ROOT, "%.2f s", nanos / 1e9)
}

/** One operation's line in a [Report]. */
class OperationStat(
    val id: Int,
    val name: String,
    val hits: Long,
    val calls: Long,
    /** Hits that caught an execution already running a tick earlier. */
    val stuckHits: Long,
    /** How many distinct executions those hits represent. */
    val stuckInstances: Long,
    /** Hits where the owning thread was not runnable. Zero when state sampling is off. */
    val waitingHits: Long = 0,
    /** Hits that were both long and waiting: a long execution caught with its thread parked. */
    val stuckWaitingHits: Long = 0,
    /** Ticks at which at least one thread was inside this operation. */
    val activeTicks: Long = 0,
) {
    /** The fraction of this operation's occupancy spent inside executions longer than a tick. */
    val stuckShare: Double get() = if (hits == 0L) 0.0 else stuckHits.toDouble() / hits

    /** The fraction of this operation's occupancy where its thread was parked, blocked or waiting. */
    val waitingShare: Double get() = if (hits == 0L) 0.0 else waitingHits.toDouble() / hits

    /**
     * Threads inside this operation at once, averaged over the ticks where it was running at all.
     *
     * The divisor that turns occupancy into elapsed time, and a diagnosis in its own right: the
     * same hundred thread-seconds of waiting is a convoy at fifteen threads and mild persistent
     * contention at 1.7, and the two want opposite fixes.
     */
    val concurrency: Double get() = if (activeTicks == 0L) Double.NaN else hits.toDouble() / activeTicks
}

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
    val duty: DutyReport,
    /**
     * Why the session stopped early, or null. Non-null means the numbers below describe a run the
     * profiler could not have measured correctly, and they are shown only so the reader can see
     * what led to the verdict.
     */
    val failure: String? = null,
    /** Times a thread was found holding a label open where the caller said it should not be. */
    val imbalances: Int = 0,
    /** Whether each hit also recorded its thread's state. Without it the verdicts fall back. */
    val stateSampled: Boolean = false,
    /** Of [idleHits], those where the thread was not runnable. See Sampler.idleWaitingHits. */
    val idleWaitingHits: Long = 0,
    /** Threads still inside a hand-placed label when the session ended. */
    val openAtEnd: Int = 0,
) {
    /** False when a fatal finding stopped the session. See the severity ladder in plan.md. */
    val ok: Boolean get() = failure == null

    val labelledHits: Long get() = operations.sumOf { it.hits }

    fun shareOf(op: OperationStat): Double =
        if (labelledHits == 0L) 0.0 else op.hits.toDouble() / labelledHits

    /** Error chance alone would produce at this hit count — anything smaller is not measurable. */
    fun noiseFloorOf(op: OperationStat): Double =
        if (op.hits > 0) 1.0 / sqrt(op.hits.toDouble()) else Double.NaN

    /** What one sample is worth in thread-time: the step the sampler actually achieved. */
    val stepNanos: Double get() = if (ticks > 1) samplingSpanNanos.toDouble() / (ticks - 1) else Double.NaN

    /**
     * This operation's occupancy as thread-time rather than as a fraction — and it is the column to
     * compare between two runs.
     *
     * [shareOf] is a share of *labelled* samples, which is the right denominator (see the note on
     * [Report]) and a treacherous number to compare across runs: it re-scales every row at once
     * whenever the set of labels changes.
     *
     * Measured on the Lucene trial, two runs of each placement, where the only difference is that
     * one labels the factory that builds a clause's scorer and the other does not:
     *
     * ```
     *                 share                    occupancy
     *   phrase   58.4% 57.2%  ->  42.9% 43.6%   38.2 s 41.5 s  ->  41.0 s 40.1 s
     *   prefix   30.9% 32.3%  ->  48.6% 47.7%   20.2 s 23.5 s  ->  46.5 s 44.0 s
     * ```
     *
     * By share, the phrase clause appears to have become **fourteen points cheaper** — a change
     * with a story attached, and the story is false. Its occupancy does not separate by placement at
     * all: 38–41 seconds in every run, which is this machine's run-to-run spread. The clause that
     * actually changed doubled, and the coverage gained by the fix (65–73 s → 92–96 s) is that one
     * clause and nothing else.
     *
     * A misplaced label is invisible in the share column and obvious in this one.
     */
    fun occupancyNanosOf(op: OperationStat): Double = op.hits * stepNanos

    /**
     * How much *wall clock* had at least one thread inside this operation — as against
     * [occupancyNanosOf], which sums across threads.
     *
     * The distinction is the whole point and it only matters when threads wait. Eight threads
     * computing for five seconds is forty thread-seconds of occupancy and the sum is real: on one
     * thread it would genuinely take forty seconds. A hundred threads parked one second on one lock
     * is also a hundred thread-seconds, and there the sum is fiction — the clock advanced one
     * second and removing the lock would save one second, not a hundred. Elapsed is the number that
     * says so, and it costs one counter per operation.
     *
     * It is not latency: it is the union of every execution's interval, so it says the operation had
     * *somebody* inside it for this long and nothing about any single execution. And it is not a
     * counterfactual — see the warning at the foot of the report.
     */
    fun elapsedNanosOf(op: OperationStat): Double = op.activeTicks * stepNanos

    /** Thread-time spent inside any label. */
    val labelledNanos: Double get() = labelledHits * stepNanos

    /**
     * Thread-time the sampler observed at all — every slot read on every tick, labelled or not.
     *
     * The honest denominator for "how much of this run do my labels account for", and the number
     * that makes a coverage figure actionable: *"labels cover 94 s of 181"* is a sentence you can do
     * something about in a way that *"52%"* is not.
     */
    val observedNanos: Double get() = (labelledHits + idleHits) * stepNanos

    /** Occupancy that was not CPU, in samples. The whole run's supply of stalling. */
    val offCpuSamples: Double
        get() = if (duty.available) (1 - duty.duty) * (labelledHits + idleHits) else Double.NaN

    /**
     * How much of an operation's long-running time was certainly spent *running*, as a fraction.
     *
     * The question this answers is the one the long-execution signal cannot answer on its own, and
     * it decides what the reader should do about it. An execution that outlived a tick was either
     * waiting for something or working for a millisecond, and those want opposite responses: the
     * first means the share is occupancy and not CPU, the second means the share is honest and the
     * operation merely belongs in the coarse tier.
     *
     * The answer is arithmetic on two numbers already in the report. The whole run had only
     * [offCpuSamples] of stalling in it, from every cause together, so an operation whose long
     * executions occupy more than that must have been *running* for the difference — whatever the
     * rest of the run was doing, and without attributing a single sample to anybody.
     *
     * A lower bound and deliberately a loose one: it charges the operation with every stall in the
     * run, including stalls that happened somewhere else entirely. When it still comes out high, it
     * is certain.
     */
    fun runningFloorOf(op: OperationStat): Double {
        val offCpu = offCpuSamples
        if (offCpu.isNaN() || op.stuckHits == 0L) return Double.NaN
        return 1 - min(1.0, offCpu / op.stuckHits)
    }

    /**
     * The most an execution can plausibly have cost, from the sampling alone.
     *
     * The hit count is a count of rare events, so its upper confidence bound is
     * `hits + 2√hits + 3` — and that is `3` when nothing was ever sampled, which is the rule of
     * three and the reason an operation the sampler never caught is still measurable. Seeing it
     * zero times in *n* samples means its whole occupancy is under three ticks however large *n*
     * was, so its cost per call is under `3 × tick / calls`. Forty million calls at a 1 ms tick
     * puts that under 0.075 ns, a fraction of one cycle.
     *
     * This is what makes "too small" provable rather than suspected: if even the upper bound is
     * below the floor, no amount of missing evidence can rescue the label.
     */
    fun impliedUpperNanosOf(op: OperationStat): Double = impliedUpperNanos(op.hits, op.calls, stepNanos)

    /**
     * Operations labelled on something too small for the instrument to describe.
     *
     * The bound is inflated by [FLOOR_BIAS_ALLOWANCE] first: the sampler reads short operations
     * 5-9% low below 45 ns — measured against the configured truth — and that bias points the wrong way
     * here, since it would make an innocent operation look smaller than it is.
     */
    fun tooSmall(): List<OperationStat> = operations
        .filter { isTooSmall(it.hits, it.calls, stepNanos) }
        .sortedByDescending { it.calls }

    /**
     * What one execution appears to have cost, from the sampling alone: `hits × step / calls`.
     *
     * The smell test the person who wrote the code can apply and the tool cannot. An operation
     * known to be 20 ns showing 500 ns here is stalling on something, and no amount of share
     * arithmetic would have said so. It is occupancy per call, not CPU per call, and it inherits
     * the same bound as every other number here.
     */
    fun impliedNanosOf(op: OperationStat): Double =
        if (op.calls == 0L) Double.NaN else op.hits * stepNanos / op.calls

    /**
     * The run-wide rate of executions caught spanning a tick. Reported, but *not* what an operation
     * is judged against — see [machineFloor].
     */
    val stuckBaseline: Double
        get() = if (labelledHits == 0L) 0.0 else operations.sumOf { it.stuckHits }.toDouble() / labelledHits

    /**
     * How much of an operation's long-running time the *machine* can account for, and therefore the
     * floor an operation has to clear before it has anything to answer for.
     *
     * **The lesson this phase kept relearning: a baseline must not contain the effect it is used to
     * detect.** Three attempts, each broken by a workload the previous one had not met.
     *
     * 1. *The run-wide rate of long executions.* Assumes long executions are the exception. Against
     *    Calcite's planner that rate is 53.52%, because most of those labels genuinely are coarse,
     *    so three times it was unreachable and nothing could ever be named.
     * 2. *The non-CPU fraction from the duty cycle.* Correct for preemption and GC, which are what
     *    the machine does to everything — but a thread blocked on a lock is also off the CPU, so a
     *    blocking operation raises this floor and hides itself behind it. Measured: an operation
     *    with 88.61% of its occupancy in executions over a tick, against a floor of 35.43% that it
     *    had itself created. Not named.
     * 3. *The lower of that and what a typical operation experienced* — [typicalStuckShare]. The
     *    machine can only be blamed for what both estimates agree on, and the median across
     *    operations cannot be moved by the one operation that blocks.
     *
     * Being conservative about blaming the machine means being liberal about naming operations,
     * which is why [SUSPECT_MIN_SHARE] and [SUSPECT_MIN_INSTANCES] exist: they are what stops a
     * near-zero floor turning noise into an accusation.
     */
    val machineFloor: Double
        get() {
            val fromDuty = if (duty.available) 1 - duty.duty else Double.MAX_VALUE
            val typical = typicalStuckShare()
            val fromOperations = if (typical.isNaN()) Double.MAX_VALUE else typical
            val floor = minOf(fromDuty, fromOperations)
            return if (floor == Double.MAX_VALUE) stuckBaseline else floor
        }

    /**
     * What a *typical* operation's rate of long executions was — the median over operations with
     * enough hits to have a meaningful rate.
     *
     * The second estimate of the same quantity as the duty cycle, and it fails where the duty cycle
     * fails, in the opposite direction. Preemption and GC are charged to whatever was executing, in
     * proportion to its occupancy, so they raise *every* operation's rate together and the median
     * sees them. Blocking is concentrated in the operation doing the blocking, so the median does
     * not see it — which is exactly what a floor must not see.
     *
     * The median rather than the mean, because the mean is the run-wide rate that Calcite already
     * disproved: one operation holding most of the occupancy drags it up until nothing can clear it.
     */
    fun typicalStuckShare(): Double {
        val rates = operations.filter { it.hits >= MEDIAN_MIN_HITS }.map { it.stuckShare }.sorted()
        if (rates.isEmpty()) return Double.NaN
        return if (rates.size % 2 == 1) rates[rates.size / 2]
        else (rates[rates.size / 2 - 1] + rates[rates.size / 2]) / 2
    }

    /**
     * Operations whose executions span a tick far more often than the run as a whole — the ones
     * that are not the size their label claims.
     *
     * Both conditions are provisional and marked as such: this project sets tolerances from
     * measurement, and the measurements that would settle these are the contended bench operation
     * and the Calcite trial, neither of which has been run against this yet. The second condition
     * exists so that a single GC pause, which can freeze one operation across several ticks,
     * cannot on its own accuse it.
     */
    fun suspect(): List<OperationStat> = operations
        .filter { isSuspect(it.hits, it.stuckHits, it.stuckInstances, machineFloor) }
        .sortedByDescending { it.stuckShare }

    /**
     * What to do about an operation whose executions outlive a tick — which is not one answer, and
     * the trial is why.
     *
     * Calcite's rule labels are all flagged by this signal, and their shares were *correct*: they
     * agreed with an independent stack profiler to about a percentage point and produced the one
     * finding this project has to its name. Stopping a run over them would have destroyed it. So a
     * long execution is never fatal. What differs is the advice, and [runningFloorOf] decides
     * which advice applies.
     */
    fun verdictFor(op: OperationStat): String {
        // Sampled state answers this directly and per operation, so it is asked first. The
        // aggregate below is what this had to make do with before phase 6, and it survives only as
        // the fallback for a session that turned state sampling off.
        if (stateSampled && op.stuckHits > 0) {
            val waiting = op.stuckWaitingHits.toDouble() / op.stuckHits
            return when {
                // "Runnable", not "on a core" — the distinction is the whole known limit of this
                // signal. A thread the scheduler preempted is still runnable, so this rules out
                // waiting on another thread and does not rule out waiting for a core. The duty
                // cycle above bounds that second kind, and only in aggregate.
                waiting <= WAITING_NONE -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread runnable — it is not waiting on anything, " +
                            "so the share is honest and the operation wants a coarse label for its per-execution " +
                            "statistics (runnable is not the same as scheduled: see the duty cycle above)",
                    (1 - waiting) * 100
                )

                // The claim the old aggregate test could never make. It is now a measurement of
                // this operation rather than an alibi drawn from the whole run's budget.
                waiting >= WAITING_MOSTLY -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread parked or blocked — it is *waiting*, " +
                            "not working. Read this share as occupancy: it does not add up across threads the " +
                            "way CPU does, and %s of wall clock had anyone inside it at all",
                    waiting * 100, threadTime(elapsedNanosOf(op))
                )

                else -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread parked — part working, part waiting, " +
                            "and the split is measured rather than bounded",
                    waiting * 100
                )
            }
        }

        val running = runningFloorOf(op)
        return when {
            running.isNaN() ->
                "long, and with no duty cycle there is no way to say whether it was working or waiting"

            running >= RUNNING_CERTAIN -> String.format(
                Locale.ROOT,
                "at least %.0f%% of that was on CPU, so it is working and not waiting: the share is honest, and " +
                        "the operation wants a coarse label for its per-execution statistics",
                running * 100
            )

            // Not "it is waiting" — that is a claim this test cannot make without sampled state.
            // The whole run's off-CPU time is a single budget and it is charged in full against
            // every operation separately, so a small operation will always come out ambiguous
            // however innocent it is. What the reader can act on is the size of that budget: at 4%
            // nothing here can be mostly waiting, at 35% something is.
            else -> String.format(
                Locale.ROOT,
                "cannot say which — state sampling is off. The run's whole off-CPU time is %.1f%% of occupancy, " +
                        "and that is enough to account for all of it. Read this share as occupancy rather than " +
                        "as time on a core",
                (1 - duty.duty) * 100
            )
        }
    }

    fun render(): String = buildString {
        val achieved = if (ticks > 1) samplingSpanNanos.toDouble() / (ticks - 1) / 1e6 else Double.NaN
        if (failure != null) {
            appendLine("!".repeat(WIDTH))
            appendLine("PROFILING STOPPED — this label could not have produced a correct number:")
            appendLine("  $failure")
            appendLine("Everything below is what led to that verdict, not a result. Pass strict=false to")
            appendLine("profile anyway, which is what to do when the code is not yours to change.")
            appendLine("!".repeat(WIDTH))
        }
        appendLine("=".repeat(WIDTH))
        appendLine(
            String.format(
                Locale.ROOT, "%,d labelled samples over %.1f s, %,d ticks at %.3f ms, %d threads",
                labelledHits, durationNanos / 1e9, ticks, achieved, threads
            )
        )
        // Coverage in units, not only as a ratio. A percentage is a comparison against a total the
        // reader has to take on trust; the total itself is the thing that makes the gap actionable,
        // and it is the first place a label in the wrong place shows up as a number.
        appendLine(
            String.format(
                Locale.ROOT,
                "labels cover %s of the %s of thread-time observed (%.1f%%); %s was outside every label, in %,d samples",
                threadTime(labelledNanos), threadTime(observedNanos),
                labelledHits * 100.0 / (labelledHits + idleHits).coerceAtLeast(1),
                threadTime(observedNanos - labelledNanos), idleHits
            )
        )
        // What the unlabelled time *was* is the reader's first question and the one they have no
        // other instrument for. "Most of the run is outside every label" means two opposite things —
        // work nobody labelled, or threads doing nothing — and only this line separates them.
        if (stateSampled && idleHits > 0) appendLine(
            String.format(
                Locale.ROOT,
                "  of that unlabelled time, %s was a thread not runnable (%.1f%%) and %s was a thread " +
                        "runnable with no label on it",
                threadTime(idleWaitingHits * stepNanos), idleWaitingHits * 100.0 / idleHits,
                threadTime((idleHits - idleWaitingHits) * stepNanos)
            )
        )
        // The bound belongs beside the sampling rate, not in a footnote: both say how much the
        // numbers below are worth, one against chance and one against stalling.
        for (l in duty.lines(labelledHits.toDouble() / (labelledHits + idleHits).coerceAtLeast(1))) appendLine(l)
        appendLine("=".repeat(WIDTH))
        appendLine(
            String.format(
                Locale.ROOT, "%-26s %8s %10s %8s %9s %7s %13s %8s %6s %10s %7s",
                "operation", "share", "occupancy", "waiting", "elapsed", "threads",
                "calls", "hits", "noise", "impl/call", "over 1t"
            )
        )
        appendLine("-".repeat(WIDTH))
        // Operations the sampler never caught are folded away rather than printed as a screen of
        // zeroes — Calcite's report carried twenty-five rules at 0.000%. Folded, not dropped: the
        // count says how many there were, and a *called* operation with no samples is a real
        // finding, since it means the label is on something too small to see.
        val (seen, unseen) = operations.partition { it.hits > 0 }
        for (op in seen.sortedByDescending { it.hits }) {
            appendLine(
                String.format(
                    Locale.ROOT, "%-26s %7.3f%% %10s %7.1f%% %9s %7.2f %,13d %8d %5.2f%% %10s %6.2f%%",
                    op.name, shareOf(op) * 100, threadTime(occupancyNanosOf(op)),
                    op.waitingShare * 100, threadTime(elapsedNanosOf(op)), op.concurrency,
                    op.calls, op.hits, noiseFloorOf(op) * 100,
                    duration(impliedNanosOf(op)), op.stuckShare * 100
                )
            )
        }
        appendLine("-".repeat(WIDTH))
        if (unseen.isNotEmpty()) {
            val called = unseen.filter { it.calls > 0 }
            appendLine(
                "${unseen.size} operations were never sampled and are folded away" +
                        (if (called.isEmpty()) " (none of them ran at all)"
                        else "; ${called.size} of them did run: " +
                                called.sortedByDescending { it.calls }.take(4).joinToString { it.name } +
                                (if (called.size > 4) ", …" else ""))
            )
        }
        appendLine("share is of labelled samples and is occupancy, not CPU — the duty cycle above bounds the gap")
        appendLine("occupancy is hits x step as thread-time: absolute, so unlike share it does not move when a")
        appendLine("  label is added, moved or removed — which makes it the column to compare between two runs")
        appendLine("waiting is the share of those samples whose thread was parked, blocked or waiting; a thread")
        appendLine("  the scheduler merely preempted still reads runnable, so this is waiting on another thread")
        appendLine("elapsed is wall clock with at least one thread inside, and threads is occupancy / elapsed —")
        appendLine("  occupancy sums across threads and elapsed does not, so 100 s of waiting is a convoy to be")
        appendLine("  broken up at 15 threads and steady contention to be designed out at 1.7. Not latency: it")
        appendLine("  is every execution's interval unioned, so it says nothing about any single one of them")
        appendLine("noise is 1/sqrt(hits), the error chance alone gives")
        appendLine(
            String.format(
                Locale.ROOT,
                "implied/call is hits x step / calls; 'over 1 tick' is occupancy inside executions that outlived a tick",
            )
        )
        appendLine(
            String.format(
                Locale.ROOT,
                "  run-wide %.2f%%, of which the machine itself accounts for up to %.2f%% (preemption and GC pauses)",
                stuckBaseline * 100, machineFloor * 100
            )
        )
        for (op in suspect()) {
            appendLine(
                String.format(
                    Locale.ROOT,
                    "  ! %s: %,d executions lasted over a tick (%.1f%% of its occupancy against a %.1f%% machine floor, %s per call)",
                    op.name, op.stuckInstances, op.stuckShare * 100, machineFloor * 100,
                    duration(impliedNanosOf(op))
                )
            )
            appendLine("    " + verdictFor(op))
        }
        // The other end of the same question. Fatal under strict, so under strict this list is at
        // most one long and the session has already stopped; without it, every offender is named.
        for (op in tooSmall()) {
            appendLine("  ! " + tooSmallMessage(op.name, op.calls, impliedUpperNanosOf(op)))
        }
        if (Profiler.untrackedSlots() > 0) {
            appendLine("    (${Profiler.untrackedSlots()} threads arrived past the $MAX_SLOTS-slot ceiling and are not checked)")
        }
        // A leaked label does not look like an error. It looks like a finding: one operation
        // quietly accumulating everybody else's samples, with a plausible number beside it.
        if (imbalances > 0 || openAtEnd > 0) {
            appendLine("-".repeat(WIDTH))
            if (imbalances > 0) appendLine(
                "! $imbalances labels were still open at a point the caller said should be quiescent — " +
                        "everything after each leak was billed to the leaked operation"
            )
            if (openAtEnd > 0) appendLine(
                "! $openAtEnd threads were still inside a hand-placed label when sampling stopped, which " +
                        "nothing can close now"
            )
            appendLine("  a label placed with enter/exit has no finally: a body that throws leaves it set")
        }
        appendLine("-".repeat(WIDTH))
        // Said in the output rather than in a document, because the one time it mattered it was
        // worth a factor of 275 and nothing on the screen hinted at it.
        appendLine("A share is where time went. It is not what removing the operation would save:")
        appendLine("in the one trial run against real code, an operation holding 46% of the time was worth")
        appendLine("275x when removed, because it was creating work for everything else as well as doing")
        appendLine("its own. The two numbers are different questions and the gap can be orders of magnitude.")
    }

    companion object {
        /**
         * How far above the run-wide rate an operation has to sit before it is named, how many
         * long executions it needs behind that rate, and the floor below which the rate is not
         * worth mentioning at all.
         *
         * All three are provisional. The measurements that would settle them — a bench operation
         * contending on a real lock at a known rate, and the Calcite trial, which has a rule of
         * ~1.5 ms per firing and one of ~9 µs per firing in the same run — have not been made yet.
         * Written here rather than buried so that the day they are measured, this is the one place
         * that changes.
         */
        const val SUSPECT_OVER_BASELINE = 3.0
        const val SUSPECT_MIN_INSTANCES = 20L
        const val SUSPECT_MIN_SHARE = 0.02

        /**
         * Below this, a label is not describing the operation it names.
         *
         * Measured rather than chosen: each leaf's sampled share against the configured truth, over
         * a hundredfold range of durations. From 70 ns upward every leaf lands within ±2.4% and
         * most of them inside their own noise floor; at 45 ns and below they read 4.5–9.1% low
         * against noise floors of 1.4–1.7%, so three to six times noise and all in the same
         * direction. The break sits between 45 and 70 ns.
         *
         * The hook's own cost is *not* what sets this. At 20 ns the hook is 8.5% of the operation
         * and is correctable in principle, since the call count measures exactly the quantity the
         * correction needs. What is not correctable is the bias, and beneath it the hazard that
         * C2 will shuffle work across the boundaries of adjacent short labels altogether.
         */
        const val FLOOR_NANOS = 50.0

        /** How wide the report's rules and column layout are. */
        const val WIDTH = 126

        /** The short-operation bias, so the check cannot accuse an operation of the sampler's own error. */
        const val FLOOR_BIAS_ALLOWANCE = 1.2

        /** Hits below which an operation's rate of long executions is too noisy to enter the median. */
        const val MEDIAN_MIN_HITS = 100L

        /**
         * How much of an operation's long-running time must be *certainly* on CPU before the report
         * says it is working rather than waiting.
         *
         * A judgement, and a cautious one, because the bound behind it is already conservative — it
         * charges the operation with every stall in the whole run. Calcite's slowest rule clears it
         * at 91%; the bench's lock operation, which does nothing but wait, comes nowhere near.
         */
        const val RUNNING_CERTAIN = 0.5

        /**
         * Below this share of waiting among an operation's long samples, it was working.
         *
         * Not zero, because one sample in a thousand catching a GC pause or a page fault is not
         * the operation waiting on anything, and a verdict that hedged over it would hedge over
         * every operation on a real machine.
         */
        const val WAITING_NONE = 0.05

        /** Above this share, calling it waiting rather than working is the honest description. */
        const val WAITING_MOSTLY = 0.5
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
 * The same, for a block that performs [times] units of the operation rather than one.
 *
 * Below about 50 nanoseconds an operation should not carry a label of its own: the hook is a
 * visible fraction of it, the sampler reads it low, and C2 can move work across the boundaries of
 * adjacent short labels without leaving a trace. The remedy is to label the loop instead — and then
 * the report speaks in loop-executions, which is not the unit anybody thinks in. This says how many
 * units the block contains, so the counts and the implied duration per call come back in the
 * caller's terms.
 *
 * `op(probe, times = keys.size) { for (k in keys) table.find(k) }` reports the probe, not the loop.
 */
inline fun <T> op(id: Int, times: Int, body: () -> T): T {
    val slot = Profiler.slot()
    val prev = slot.getOpaque()
    slot.setOpaque(id)
    slot.count(id, times)
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
    dutyWindowNanos: Long = DutyCycle.DEFAULT_WINDOW_NANOS,
    /**
     * Whether a label below the floor stops the session. See the severity ladder in plan.md: a
     * label on something too small is a property of the code and not of the run, so there is no
     * rerun in which it becomes valid, and continuing for twenty minutes to hand back a number that
     * was never going to be right is the worse of the two failures.
     *
     * Off for code you do not own and cannot resize.
     */
    private val strict: Boolean = true,
    /**
     * Whether each hit also records whether the owning thread was runnable.
     *
     * A switch and not a constant, for the same reason `--labels` and `--sampler` are separate:
     * the cost of this has to be measurable against its own absence, and a thing that is always on
     * can only be priced by argument.
     */
    val sampleState: Boolean = true,
) : Thread("sampler") {

    /**
     * Why the session stopped early, or null if it did not. Checked once a second, so a mistake
     * that could never have produced a valid answer is caught in seconds rather than at the end.
     */
    @Volatile
    var failure: String? = null
        private set

    private var nextFloorCheck = Long.MIN_VALUE

    /**
     * The duty cycle rides on this thread's tick loop, but at its own far lower rate — it costs one
     * native call per thread and it is measuring something that moves in scheduler ticks. Carried
     * here rather than on a thread of its own because this thread already walks the slot list, and
     * because the two must cover exactly the same span if the bound is to apply to these shares.
     */
    private val duty = DutyCycle(dutyWindowNanos)

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

    /**
     * Of those hits, the ones that caught an execution which had already been running at the
     * previous tick — see [stuckHits] and the arrays below.
     */
    val stuckHits = LongArray(MAX_OPERATIONS)

    /** How many distinct executions lasted across a tick: one per unbroken run of stuck samples. */
    val stuckInstances = LongArray(MAX_OPERATIONS)

    /**
     * Of those hits, the ones where the owning thread was **not** runnable — parked, blocked, or
     * waiting. Zero everywhere when [sampleState] is off.
     *
     * `RUNNABLE` is not the same as *on a core*: it also covers a thread the scheduler has
     * preempted — 14–18% of wall time on this machine even on a bench that never blocks — and a
     * thread sitting in a blocking socket read, which the JVM cannot see into. So this counts
     * waiting that some *other thread* caused, which is the kind that does not add up across
     * threads. Separating on-a-core from preempted needs the per-thread duty cycle.
     */
    val waitingHits = LongArray(MAX_OPERATIONS)

    /**
     * Hits that were both stuck and waiting — a long execution caught with its thread parked.
     *
     * The pairing is the point. An operation that waits *somewhere* is not the same as one whose
     * *long* executions are where the waiting happens, and it is the second that decides whether a
     * flagged operation is honest-but-coarse or is occupancy masquerading as CPU. Before this the
     * verdict had to charge the whole run's off-CPU budget against every operation separately and
     * could only ever say "cannot say which".
     */
    val stuckWaitingHits = LongArray(MAX_OPERATIONS)

    /**
     * Of the samples that caught a thread inside no label at all, the ones where it was not
     * runnable.
     *
     * The unlabelled column is the one place a reader has no other instrument for. A large
     * unlabelled share reads as "the labels miss most of the run", which is alarming, and this says
     * whether that time was work nobody labelled or a thread doing nothing at all — two findings
     * with nothing in common.
     */
    var idleWaitingHits: Long = 0; private set

    /**
     * Ticks at which at least one thread was inside the operation — its wall-clock footprint,
     * counted once however many threads were in it.
     *
     * This is the divisor that turns summed occupancy back into elapsed time. Occupancy alone
     * cannot distinguish a hundred threads waiting one second from one thread waiting a hundred
     * seconds; both are a hundred thread-seconds and their real costs are a hundredfold apart.
     * `activeTicks × step` is the first, `hits / activeTicks` is the mean concurrency that
     * separates them, and on a contended lock that is the difference between *break up the convoy*
     * and *design the contention out*. See profiler.md, "Turning occupancy back into wall time".
     */
    val activeTicks = LongArray(MAX_OPERATIONS)

    /**
     * The tick at which each operation was last seen, so [activeTicks] counts ticks and not slots.
     * A stamp rather than a flag array cleared each tick: one write when an operation first appears
     * in a tick, and nothing at all for the operations that did not.
     */
    private val seenAt = LongArray(MAX_OPERATIONS) { -1L }

    /**
     * What the last tick found in each slot, kept on this side of the fence.
     *
     * The test is two words against two words. If a slot holds the same operation as last tick
     * *and* that thread's entry counter for it has not moved, then nobody entered the operation in
     * between, so this is not a new execution — it is the same one, still running, and it has now
     * lasted at least a whole tick. For an operation whose label claims tens of nanoseconds that is
     * four orders of magnitude out.
     *
     * A counter that went *backwards* means the index was recycled to a new thread, which is
     * treated as a fresh execution — the one case where the answer would otherwise be nonsense.
     */
    private val prevOp = IntArray(MAX_SLOTS) { NO_OP }
    private val prevCount = LongArray(MAX_SLOTS)
    private val prevStuck = BooleanArray(MAX_SLOTS)

    var ticks: Long = 0; private set
    var lagged: Long = 0; private set
    var minStep: Long = Long.MAX_VALUE; private set
    var maxStep: Long = 0; private set
    var span: Long = 0; private set

    /** Most slots seen at any one tick. Read after the fact, when the threads may already be gone. */
    var maxSlots: Int = 0; private set

    /**
     * Time spent in the slot walk itself, and how many slot visits that covers, so the walk can be
     * priced per slot with state sampling on and off.
     *
     * Two `nanoTime` calls per tick on a thread that has a core — about 40 ns against a millisecond,
     * and it is the only way this feature's cost can be a number rather than an argument.
     */
    var walkNanos: Long = 0; private set
    var walkVisits: Long = 0; private set

    @Volatile private var running = true

    /**
     * Call counts as they stood when this session began.
     *
     * Every number derived from calls — the implied duration, the floor check — divides *this
     * session's* hits by them, so they have to be this session's calls. The registry's totals are
     * for the life of the process and include calls made by threads that died before sampling
     * started, whose counts are folded into the retired totals and stay there.
     *
     * Found by the floor check accusing a 20 ns operation of being under 7.9 ns — an upper bound
     * below the truth, which is arithmetically impossible and so could only be a mismatch of what
     * the numerator and the denominator were counting. The bench's warm-up is a separate set of
     * worker threads that exit before the measured run, and it was inflating every call count by
     * about a tenth.
     */
    private val callsAtStart = LongArray(MAX_OPERATIONS)

    /** Calls made since this session began. See [callsAtStart]. */
    fun sessionCalls(id: Int): Long = (Profiler.callsOf(id) - callsAtStart[id]).coerceAtLeast(0)

    init {
        isDaemon = true
        priority = MAX_PRIORITY
    }

    override fun run() {
        val slots = Profiler.slots()
        // Before the first tick, so that no call is counted whose sample could not have been taken.
        for (id in 0 until MAX_OPERATIONS) callsAtStart[id] = Profiler.callsOf(id)
        var next = System.nanoTime() + nextInterval()
        var prev = 0L
        var first = 0L

        while (running) {
            waitUntil(next)
            if (!running) break
            val now = System.nanoTime()

            val walkStart = System.nanoTime()
            for (s in slots) {
                val c = s.getOpaque()
                counters[if (c < 0) NO_OP_INDEX else c]++

                // Carried down to the long-execution test below, so that a hit which is both long
                // and waiting is counted as such. Knowing an operation waits somewhere is not the
                // same as knowing its *long* executions are where the waiting is.
                // Asked for every slot, labelled or not. What a thread does *outside* every label is
                // the larger half of some runs — 85.5% of thread-time on the Netty trial — and
                // "waiting or working" is exactly as worth knowing there. A field read on the Thread
                // object: no handshake, no safepoint. The weak reference is null only if the thread
                // died and the slot outlived it, in which case there is nothing to ask.
                var waiting = false
                if (sampleState) {
                    val t = s.thread.get()
                    if (t != null && t.state != State.RUNNABLE) waiting = true
                }
                if (c >= 0) {
                    // Once per operation per tick, not once per slot: this counts the operation's
                    // wall-clock footprint, which is the same whether one thread was inside it or
                    // sixteen.
                    if (seenAt[c] != ticks) {
                        seenAt[c] = ticks
                        activeTicks[c]++
                    }
                    if (waiting) waitingHits[c]++
                } else if (waiting) {
                    idleWaitingHits++
                }

                val idx = s.index
                if (idx < 0) continue           // past the ceiling: sampled, but not tracked
                if (c < 0) {
                    prevOp[idx] = NO_OP
                    prevStuck[idx] = false
                    continue
                }
                // The second word. One extra cache line per slot per tick, on a thread that has a
                // core to itself, and nothing at all on the hot path — the counter is already
                // maintained by the hook for the calls column.
                val n = s.countOpaque(c)
                if (prevOp[idx] == c && prevCount[idx] == n) {
                    stuckHits[c]++
                    if (waiting) stuckWaitingHits[c]++
                    // Only the first tick of a run counts as an instance, so a single execution
                    // spanning ten ticks is one long execution and not ten.
                    if (!prevStuck[idx]) {
                        stuckInstances[c]++
                        prevStuck[idx] = true
                    }
                } else {
                    prevStuck[idx] = false
                }
                prevOp[idx] = c
                prevCount[idx] = n
            }
            walkNanos += System.nanoTime() - walkStart
            walkVisits += slots.size
            if (slots.size > maxSlots) maxSlots = slots.size
            ticks++
            // Self-throttling: these return immediately on all but one tick in a thousand.
            duty.tick(now, slots)
            floorCheck(now)

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
        // The tail of the run belongs to the measurement as much as the middle does. Taken here
        // rather than in shutdown() so that it happens on this thread, before the slots go.
        duty.finish(System.nanoTime(), slots)
    }

    /**
     * Is any label on something too small to be described? Evaluated once a second.
     *
     * Nothing has to be assumed about how much evidence has accumulated: the bound is a *upper*
     * one, so early in the run, with few samples, it is simply too loose to accuse anybody. It
     * tightens as the run goes on and fires the moment the answer is certain, which for an
     * operation called millions of times is within the first second or two.
     */
    private fun floorCheck(now: Long) {
        if (!strict || failure != null || now < nextFloorCheck) return
        nextFloorCheck = now + FLOOR_CHECK_NANOS
        if (ticks < 2) return
        val step = span.toDouble() / (ticks - 1)
        for (id in 0 until Profiler.registeredCount()) {
            val calls = sessionCalls(id)
            if (!isTooSmall(counters[id], calls, step)) continue
            failure = tooSmallMessage(Profiler.nameOf(id), calls, impliedUpperNanos(counters[id], calls, step))
            // Stop sampling rather than the application: this is somebody else's process.
            running = false
            return
        }
    }

    /** How much of the occupancy this session sampled was CPU. See [DutyCycle]. */
    fun duty(): DutyReport = duty.report()

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

    private companion object {
        /** Once a second. The check walks the slot registry per operation, so not per tick. */
        const val FLOOR_CHECK_NANOS = 1_000_000_000L
    }
}

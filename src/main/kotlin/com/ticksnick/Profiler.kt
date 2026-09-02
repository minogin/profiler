package com.ticksnick

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

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
 * How many threads the sampler can watch at once.
 *
 * A ceiling on the sampler's parallel arrays — 16 KB for the lot — and indexes are recycled when
 * threads die, so this limits *simultaneous* registered threads and not how many a process may
 * create. A pool of 32 that churns for a week never approaches it.
 *
 * **A thread past the ceiling is not sampled at all**, and the report says how many there were.
 * That is a change: it used to be sampled and merely invisible to the long-execution detector,
 * which meant the walk had no upper bound and a workload that made threads in bulk could stall the
 * sampler outright. A bounded walk with a stated blind spot beats an unbounded one that corrupts
 * every number in the report — see the `D₂` row in profiler.md.
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
    internal var overflows: Long = 0

    /**
     * The coarse execution this thread is currently inside, or null.
     *
     * Where the two tiers meet. The sampler reads this and [current] in the same visit, so every
     * sample carries the pair *(which physical operation, under which logical one)* — which is the
     * cross-tabulation, and neither tier can produce it alone.
     *
     * Accessed opaquely for the identical reason [current] is: no fence on either side, and the
     * publication safety comes from [CoarseContext]'s final fields rather than from ordering here.
     * A stack is not needed beside it — the context chains to its parent, so restoring on exit is
     * one field write.
     */
    @JvmField
    var context: CoarseContext? = null

    /**
     * This thread's span statistics, one entry per coarse type, or null if it has entered none.
     *
     * Lazily allocated so that a program using only the fine tier pays nothing for this — not the
     * array, not the histograms. Written by the owning thread alone, read when the report is taken.
     */
    @JvmField
    internal var coarseAgg: Array<CoarseAgg?>? = null

    /**
     * Records one completed coarse execution of [type] lasting [nanos].
     *
     * Not inline, deliberately: it is called from [coarse], which is, and an inline function that
     * drags a histogram update into every call site would bloat exactly the code the JIT needs to
     * keep small. The work here is a handful of plain writes with a single writer.
     */
    @PublishedApi
    internal fun recordSpan(type: Int, nanos: Long) {
        if (type < 0 || type >= MAX_COARSE_TYPES) return
        val aggs = coarseAgg ?: arrayOfNulls<CoarseAgg>(MAX_COARSE_TYPES).also { coarseAgg = it }
        val a = aggs[type] ?: CoarseAgg(type).also { aggs[type] = it }
        a.record(nanos)
    }

    /** How deeply this thread is nested in coarse executions. Zero when it is inside none. */
    internal fun coarseDepth(): Int = context?.let { it.depth + 1 } ?: 0

    fun countOf(id: Int): Long = counts[id + COUNT_PAD]

    internal fun resetCounts() = counts.fill(0L)

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

        /** The coarse half of the pair. Static final for the same constant-folding reason as [CURRENT]. */
        @JvmStatic
        val CONTEXT: VarHandle =
            MethodHandles.lookup().findVarHandle(OpSlot::class.java, "context", CoarseContext::class.java)
    }
}

/** Marks this thread as inside operation [id]. No fence — see [OpSlot.current]. */
fun OpSlot.setOpaque(id: Int) = OpSlot.CURRENT.setOpaque(this, id)

/** Reads the slot as the sampler does: no fence, possibly a few nanoseconds stale. */
fun OpSlot.getOpaque(): Int = OpSlot.CURRENT.getOpaque(this) as Int

/** How many times this thread has entered [id], read from another thread. See [OpSlot.COUNTS]. */
fun OpSlot.countOpaque(id: Int): Long = OpSlot.COUNTS.getOpaque(counts, id + OpSlot.COUNT_PAD) as Long

/** The coarse execution this thread is inside, or null. No fence — see [OpSlot.context]. */
fun OpSlot.contextOpaque(): CoarseContext? = OpSlot.CONTEXT.getOpaque(this) as CoarseContext?

/** Publishes [ctx] as this thread's current coarse execution. See [OpSlot.context]. */
fun OpSlot.setContextOpaque(ctx: CoarseContext?) = OpSlot.CONTEXT.setOpaque(this, ctx)

/**
 * The slot registry. A thread gets its slot from a ThreadLocal and is added to the walk list on
 * first access. Threads are expected to register themselves up front, so the list is stable by
 * the time the sampler starts and no thread appears mid-run.
 */
object Profiler {
    /**
     * The walk list, as a fixed array indexed by slot index rather than a growable list.
     *
     * **This is the fix for the one outright defect the design doc carried** — `D₂` in
     * [profiler.md]. It used to be a `CopyOnWriteArrayList`, which copies the whole array on every
     * add and every remove: a process that creates threads in bulk paid O(n) per thread and O(n²)
     * over the run, and the sampler then walked a list whose length was the number of live threads,
     * every millisecond, with no ceiling on it. Virtual threads make both of those unbounded — a
     * million short-lived ones is a million array copies and a tick that cannot finish — and
     * "threads, virtual threads, and coroutines" is requirement 2, not an edge case. It was deferred
     * only because no trial here creates them, which is a gap in the trials rather than evidence
     * about the defect.
     *
     * An `AtomicReferenceArray` gives the same safe publication the copy-on-write list did — each
     * element is a volatile read and write — while registration and release become a single store.
     * The walk is then bounded by [MAX_SLOTS] rather than by how many threads the process has made,
     * and in practice by [ceiling], which is how far the indexes have ever reached.
     *
     * **What it costs, and it is a real cost:** a thread that arrives past the ceiling has no index
     * and so is no longer in the walk at all. It used to be sampled and merely invisible to the
     * long-execution detector. Now it is invisible to both, and the report says how many such
     * threads there were — see [Report.untrackedSlots]. That is a bounded, stated degradation in
     * place of an unbounded walk, and an unbounded walk corrupts every number rather than one.
     */
    private val slotByIndex = java.util.concurrent.atomic.AtomicReferenceArray<OpSlot?>(MAX_SLOTS)

    /**
     * One past the highest slot index ever handed out, so the walk skips the tail it has never
     * used. Only ever rises, and it is bounded by [MAX_SLOTS]: on a bench with eight workers the
     * sampler reads nine entries per tick, not 1024.
     */
    private val ceiling = AtomicInteger(0)

    /** How far the walk has to go. See [ceiling]. */
    internal fun slotCeiling(): Int = ceiling.get()

    /** The slot at [i], or null if that index is free right now. See [slotByIndex]. */
    internal fun slotAt(i: Int): OpSlot? = slotByIndex.get(i)

    /** Runs [action] over every live slot. Allocation-free, so the sampler may call it per tick. */
    internal inline fun forEachSlot(action: (OpSlot) -> Unit) {
        for (i in 0 until slotCeiling()) action(slotAt(i) ?: continue)
    }

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
        val idx = takeIndex()
        val s = OpSlot(idx)
        if (idx >= 0) {
            slotByIndex.set(idx, s)
            // Raised after the slot is published, never before, so the sampler cannot read a null
            // inside the range it has been told to walk.
            ceiling.getAndUpdate { if (idx < it) it else idx + 1 }
        }
        s
    }

    private val names = arrayOfNulls<String>(MAX_OPERATIONS)
    private val ids = ConcurrentHashMap<String, Int>()
    private val nextId = AtomicInteger(0)

    /** Calls from threads that have already exited, folded in so a report does not lose them. */
    private val retiredCounts = LongArray(MAX_OPERATIONS)

    /**
     * How many threads that have already exited had called each operation at least once.
     *
     * Counted at the moment a slot is folded away, because that is the last point at which the
     * thread's own per-operation counters still exist: [retiredCounts] sums them and the identity
     * of who contributed is gone one instruction later. One increment per exiting thread per
     * operation it touched, on the thread's way out and never on the hot path.
     */
    private val retiredThreads = IntArray(MAX_OPERATIONS)

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
    fun registerFine(name: String): FineOp = FineOp(registerFineId(name))

    /** The raw id, for the internals that index arrays by it. See [registerFine]. */
    internal fun registerFineId(name: String): Int = ids.computeIfAbsent(name) {
        val id = nextId.getAndIncrement()
        check(id < MAX_OPERATIONS) { "more than $MAX_OPERATIONS distinct operations registered" }
        names[id] = name
        id
    }

    internal fun nameOf(id: Int): String = if (id in 0 until MAX_OPERATIONS) names[id] ?: "op#$id" else "op#$id"

    /** What [op] was registered as. */
    fun nameOf(op: FineOp): String = nameOf(op.id)

    /** What [op] was registered as. */
    fun nameOf(op: CoarseOp): String = coarseNameOf(op.id)

    /**
     * How many operations have been registered, never more than there is room for.
     *
     * Clamped because [register] increments before it checks, so the 257th registration leaves the
     * counter at 257 on its way out through an exception. That exception is an
     * `IllegalStateException` with a clear message, so catching it is a reasonable thing for a
     * caller to do — and it used to mean the *next* `stop()` walked `0 until 257` across arrays of
     * 256 and died with an index error a long way from the cause.
     */
    internal fun registeredCount(): Int = min(nextId.get(), MAX_OPERATIONS)

    private val coarseNames = arrayOfNulls<String>(MAX_COARSE_TYPES)
    private val coarseIds = ConcurrentHashMap<String, Int>()
    private val nextCoarseId = AtomicInteger(0)

    /**
     * Id for a coarse operation, in an id space of its own.
     *
     * Separate from [register] rather than sharing one space, because the same name can legitimately
     * carry both labels: the fine one measures the operation's *self* time, the coarse one its
     * duration and everything nested under it, and comparing the two is a cross-check worth having.
     */
    fun registerCoarse(name: String): CoarseOp = CoarseOp(registerCoarseId(name))

    /** The raw id, for the internals that index arrays by it. See [registerCoarse]. */
    internal fun registerCoarseId(name: String): Int = coarseIds.computeIfAbsent(name) {
        val id = nextCoarseId.getAndIncrement()
        check(id < MAX_COARSE_TYPES) { "more than $MAX_COARSE_TYPES distinct coarse operations registered" }
        coarseNames[id] = name
        id
    }

    internal fun coarseNameOf(type: Int): String =
        if (type in 0 until MAX_COARSE_TYPES) coarseNames[type] ?: "coarse#$type" else "coarse#$type"

    /** How many coarse types have been registered. Clamped for the reason [registeredCount] is. */
    internal fun coarseCount(): Int = min(nextCoarseId.get(), MAX_COARSE_TYPES)

    /**
     * Span statistics from threads that have already exited, so a pool that recycles does not lose
     * the executions its retired threads measured.
     */
    private val retiredCoarse = arrayOfNulls<CoarseAgg>(MAX_COARSE_TYPES)

    /**
     * Threads that ran an execution of each coarse type and have since exited.
     *
     * The coarse half of [threadsOf], and session-scoped for free: [resetCoarse] clears it at the
     * start of every session, where the fine side has to snapshot and subtract because its counters
     * are cumulative.
     */
    private val retiredCoarseThreads = IntArray(MAX_COARSE_TYPES)

    /** Folds one departing thread's span statistics into [retiredCoarse]. */
    private fun retire(aggs: Array<CoarseAgg?>?) {
        if (aggs == null) return
        synchronized(retiredCoarse) {
            for (t in 0 until MAX_COARSE_TYPES) {
                val a = aggs[t] ?: continue
                if (a.count > 0) retiredCoarseThreads[t]++
                val into = retiredCoarse[t] ?: CoarseAgg(t).also { retiredCoarse[t] = it }
                into.mergeFrom(a)
            }
        }
    }

    /**
     * Every completed coarse execution this session knows about, live threads and retired ones.
     *
     * Taken once, when the report is. Allocates and walks every slot, which is why it is not
     * something the sampler does per tick.
     */
    internal fun coarseTotals(): Array<CoarseAgg?> {
        val out = arrayOfNulls<CoarseAgg>(MAX_COARSE_TYPES)
        synchronized(retiredCoarse) {
            for (t in 0 until MAX_COARSE_TYPES) {
                val a = retiredCoarse[t] ?: continue
                out[t] = CoarseAgg(t).also { it.mergeFrom(a) }
            }
        }
        forEachSlot { s ->
            val aggs = s.coarseAgg ?: return@forEachSlot
            for (t in 0 until MAX_COARSE_TYPES) {
                val a = aggs[t] ?: continue
                val into = out[t] ?: CoarseAgg(t).also { out[t] = it }
                into.mergeFrom(a)
            }
        }
        return out
    }

    /**
     * Clears every thread's span statistics, so a report describes one session.
     *
     * The fine counters solve this by snapshotting at the start and subtracting — see
     * `Sampler.callsAtStart` — which a minimum and a histogram cannot be made to do. Resetting is
     * the honest alternative, and it costs the same thing everywhere else here costs: an execution
     * that began before [start] and ends inside the session is counted in full. Two instructions
     * wide, in the direction of reporting a span that is real but started slightly early.
     *
     * **Spans recorded with no session running are discarded when the next session starts.** Called
     * from the sampling thread immediately before its first tick, beside the snapshot of the call
     * counters and for the identical reason: *no execution is counted whose samples could not have
     * been taken.* Getting this wrong is not hypothetical — the bench's warm-up runs on its own
     * worker threads before the measured run, and with the reset in the wrong place every coarse
     * count came out 11.06% high, which is precisely the warm-up.
     */
    internal fun resetCoarse() {
        synchronized(retiredCoarse) {
            for (t in 0 until MAX_COARSE_TYPES) {
                retiredCoarse[t] = null
                retiredCoarseThreads[t] = 0
            }
        }
        forEachSlot { s -> s.coarseAgg?.forEach { it?.reset() } }
    }

    /**
     * Distinct threads that ran an execution of coarse type [t]: live ones plus those that exited.
     *
     * The `Pool` column for a coarse row, and exact for the same reason the fine one is - it counts
     * threads that *ran* the operation rather than threads a sample happened to catch inside it.
     */
    internal fun coarseThreadsOf(t: Int): Int =
        synchronized(retiredCoarse) { retiredCoarseThreads[t] }.let { retired ->
            var live = 0
            forEachSlot { s ->
                val a = s.coarseAgg?.get(t)
                if (a != null && a.count > 0) live++
            }
            retired + live
        }

    /** Threads inside a coarse execution right now, which at the end of a session is a leak. */
    internal fun openContexts(): Int {
        var n = 0
        forEachSlot { if (it.context != null) n++ }
        return n
    }

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
    fun enter(op: FineOp) {
        val s = local.get()
        if (s.depth < MAX_SPAN_DEPTH) s.stack[s.depth++] = s.getOpaque() else s.overflows++
        s.setOpaque(op.id)
        s.count(op.id)
    }

    /**
     * Leaves [op], restoring what the thread was inside before it.
     *
     * **It takes what you are closing, and that is not symmetry for its own sake.** The no-argument
     * form it replaced could not tell a correct unwind from a crossed one — `enter(a); enter(b);
     * exit(); exit()` closes `b` then `a` whether or not that is what the code meant. Naming the
     * operation lets the library check, and a mismatch is the same category of fault as a leak: a
     * label ends up covering work that was never inside it, and the number that comes out is
     * plausible rather than obviously wrong.
     *
     * A mismatch is reported and counted, never thrown — throwing would turn a measurement problem
     * into a crash in somebody else's process — and it still unwinds, so one mistake does not
     * cascade into every label after it.
     */
    fun exit(op: FineOp) {
        val s = local.get()
        val current = s.getOpaque()
        if (current != op.id) mismatch(nameOf(op), if (current < 0) "no operation" else nameOf(current))
        s.setOpaque(if (s.depth > 0) s.stack[--s.depth] else NO_OP)
    }

    /**
     * Enters an execution of coarse operation [op] until a matching [exit].
     *
     * For the boundary that is two callbacks rather than a block, which is most of what third-party
     * code offers. It carries the same hazard the fine form does and for the same reason: **there is
     * no `finally` here**, so a body that throws leaves the context open and everything the thread
     * does afterwards is billed to it. See [expectBalanced].
     */
    fun enter(op: CoarseOp) {
        val s = local.get()
        s.setContextOpaque(CoarseContext(op.id, s.contextOpaque(), System.nanoTime()))
    }

    /**
     * Closes the innermost coarse execution on this thread and records its span.
     *
     * Checked against [op], for the reason the fine form gives. Closing when nothing is open is
     * reported rather than thrown: it means the caller closed something it never opened, which is a
     * measurement problem and not a reason to stop somebody else's program.
     */
    fun exit(op: CoarseOp) {
        val s = local.get()
        val ctx = s.contextOpaque()
        if (ctx == null) {
            mismatch(nameOf(op), "no coarse execution")
            return
        }
        if (ctx.type != op.id) mismatch(nameOf(op), coarseNameOf(ctx.type))
        s.setContextOpaque(ctx.parent)
        ctx.markClosed()
        s.recordSpan(ctx.type, System.nanoTime() - ctx.startNanos)
    }

    /** One closing call that did not match what was open. Counted with the leaks, and fatal under strict. */
    private fun mismatch(closed: String, open: String) {
        imbalances.incrementAndGet()
        sampler?.let { if (it.strict) it.fail(mismatchMessage(closed, open)) }
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
        // Read before anything is reset, and defensively: a slot can hold NO_OP with a depth above
        // zero if the leak was an `exit` without its `enter` rather than the other way round.
        val ctx = s.contextOpaque()
        val id = s.getOpaque()
        val leaked = when {
            // The coarse half is named first when both leaked, because a coarse context left open
            // collects a whole request's worth of samples rather than one label's.
            ctx != null -> coarseNameOf(ctx.type)
            s.depth > 0 && id >= 0 -> nameOf(id)
            s.depth > 0 -> "an unnamed label"
            else -> return true
        }
        imbalances.incrementAndGet()
        s.setContextOpaque(null)
        s.depth = 0
        s.setOpaque(NO_OP)
        // Fatal under strict, and it is the only thing that is. Resetting the slot stops the leak
        // spreading, but it cannot give back the samples already billed to the wrong operation —
        // so continuing means reporting a share that was manufactured by a bug.
        sampler?.let { if (it.strict) it.fail(leakMessage(leaked)) }
        return false
    }

    /**
     * Labels left open at a point the caller said should be quiescent. See [expectBalanced].
     *
     * For the life of the process, because [expectBalanced] is a check a caller may make with no
     * session running at all. What a [Report] carries is this minus [imbalancesAtStart], for the
     * same reason `Sampler.callsAtStart` exists: every number in a report is about one session.
     */
    private val imbalances = AtomicInteger(0)

    /**
     * [imbalances] as it stood when the current session began.
     *
     * Without it a second session in the same process reports the first one's leaks as well as its
     * own, and prints *"N labels were still open at a point the caller said should be quiescent"*
     * about a session in which nothing leaked — which is exactly the failure the message warns
     * about, arriving through the warning itself. Live in the Netty A/B, which starts and stops a
     * session once per arm per round in one JVM.
     */
    private var imbalancesAtStart = 0

    /** [untrackedSlots] as it stood when the current session began. See [Report.untrackedSlots]. */
    private var untrackedAtStart = 0

    /** [reclaimedSlots] as it stood when the current session began. */
    private var reclaimedAtStart = 0

    /** Threads still inside a hand-placed label right now, which at the end of a session is a leak. */
    internal fun openSpans(): Int {
        var n = 0
        forEachSlot { if (it.depth > 0) n++ }
        return n
    }

    /**
     * Drops the calling thread's slot. A thread that has finished must not stay in the walk list:
     * its slot reads empty forever, inflating the sampler's denominator and — worse for occupancy
     * work — counting a dead thread as an idle one. A registry that only ever grows is also a
     * plain leak in anything long-lived with a thread pool that recycles.
     *
     * Its call counts are folded into the retired totals, or a pool that recycles threads would
     * silently lose everything the retired ones did — but *after* the slot leaves the walk list,
     * not before. [callsOf] sums the retired totals and the live slots, so a reader landing between
     * the two lines sees this thread's calls in both halves and counts them twice. That inflates
     * calls, deflates the implied duration and the bound built on it, and can push a perfectly good
     * label under the floor and print an accusation — the exact failure mode this project has
     * already met once, where a rate's numerator and denominator counted different windows.
     *
     * Both orders race; only this one races in a safe direction. Losing the counts for the length
     * of one read makes an operation look *longer* than it is, which cannot manufacture an
     * accusation, and the window is two instructions wide either way.
     */
    fun release() {
        val s = local.get()
        if (s.index >= 0) slotByIndex.set(s.index, null)
        synchronized(retiredCounts) {
            for (id in 0 until MAX_OPERATIONS) {
                val n = s.countOf(id)
                if (n > 0) {
                    retiredCounts[id] += n
                    retiredThreads[id]++
                }
            }
        }
        retire(s.coarseAgg)
        // Removed from the walk list first, so the sampler cannot be reading this slot at the
        // moment its index is handed to somebody else.
        if (s.index >= 0) freeIndexes.add(s.index)
        local.remove()
    }

    /** Threads that died without calling [release] and had to be reclaimed. See [reclaimDeadSlots]. */
    private val reclaimed = AtomicInteger(0)

    internal fun reclaimedSlots(): Int = reclaimed.get()

    /**
     * Reclaims the slots of threads that exited without calling [release].
     *
     * `release()` is the precise way and stays the documented one, but it is the kind of thing a
     * user forgets exactly once and then cannot see: the slot stays in the walk reading `NO_OP`
     * forever, so a dead thread is counted as an *idle* one and quietly inflates the denominator
     * every share is taken over. That is the silent-misattribution failure the accuracy principle
     * says to spend effort on, arriving through an API contract rather than through a label.
     *
     * The slot already holds its thread weakly, so a cleared reference means the thread is gone and
     * has been collected — no handshake, no thread scan, and it cannot resurrect. Reclaiming is then
     * the same three steps as [release] in the same order: leave the walk first, fold the counts
     * second, hand the index back last, so a concurrent [callsOf] undercounts for two instructions
     * rather than double-counting.
     *
     * Called once a second from the sampler, never per tick — this walks the whole ceiling and
     * touches a weak reference per slot, which is not something to spend on a 1 ms budget.
     *
     * It is a safety net and not a substitute: reclamation waits on a garbage collection, so a
     * process that never collects never reclaims. The report says how many it caught, because a
     * user who is silently rescued learns nothing.
     */
    internal fun reclaimDeadSlots() {
        for (i in 0 until slotCeiling()) {
            val s = slotByIndex.get(i) ?: continue
            if (s.thread.get() != null) continue
            if (!slotByIndex.compareAndSet(i, s, null)) continue
            synchronized(retiredCounts) {
                for (id in 0 until MAX_OPERATIONS) {
                    val n = s.countOf(id)
                    if (n > 0) {
                        retiredCounts[id] += n
                        retiredThreads[id]++
                    }
                }
            }
            retire(s.coarseAgg)
            freeIndexes.add(i)
            reclaimed.incrementAndGet()
        }
    }

    /** Slots that arrived after the ceiling and are therefore invisible to the detector. */
    internal fun untrackedSlots(): Int = (nextIndex.get() - MAX_SLOTS).coerceAtLeast(0)

    /** Every live registered slot. Read by the sampler. */
    /**
     * The live slots, as a snapshot. Allocates, so it is for callers outside the tick loop — the
     * sampler and the duty walk use [forEachSlot], which does not.
     */
    fun slots(): List<OpSlot> = buildList { forEachSlot { add(it) } }

    /**
     * Distinct threads that have called an operation: live ones plus those that have exited.
     *
     * The denominator of the `Concurrency / Threads` column, and the reason it is a count of
     * *callers* rather than of samples: a thread that entered the operation once and was never
     * caught by the sampler still ran it, and an operation that only ever runs on one thread is a
     * fact about the code rather than about the run. Free, because the per-operation call counters
     * it reads are written by the hook anyway - nothing here is on the hot path.
     */
    internal fun threadsOf(id: Int): Int =
        synchronized(retiredCounts) { retiredThreads[id] }.let { retired ->
            var live = 0
            forEachSlot { if (it.countOf(id) > 0) live++ }
            retired + live
        }

    /** Total calls of an operation: live threads plus those that have already exited. */
    internal fun callsOf(id: Int): Long =
        synchronized(retiredCounts) { retiredCounts[id] }.let { retired ->
            var live = 0L
            forEachSlot { live += it.countOf(id) }
            retired + live
        }

    /**
     * Starts sampling. One sampler at a time.
     *
     * [strict] stops the session if a label leaks — see the severity ladder in plan.md. Switch it
     * off to profile code you do not own and cannot fix.
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
        imbalancesAtStart = imbalances.get()
        untrackedAtStart = untrackedSlots()
        reclaimedAtStart = reclaimedSlots()
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
                s.waitingHits[id], s.stuckWaitingHits[id], s.activeTicks[id], s.sessionThreads(id),
                s.peakInside[id],
            )
        }
        // A label still open when the session ends is a leak by definition: nothing can close it
        // now. Counted here rather than left to the user to notice, because the symptom — one
        // operation quietly accumulating everybody else's samples — looks exactly like a finding.
        // Taken before the open-context count, so a context still open at the end is visible in
        // both: its span is missing from the totals precisely because nothing closed it.
        val totals = coarseTotals()
        val coarse = (0 until coarseCount()).map { t ->
            val a = totals[t]
            CoarseStat(
                t, coarseNameOf(t),
                count = a?.count ?: 0L,
                spanSumNanos = a?.sumNanos ?: 0L,
                spanMinNanos = if (a == null || a.count == 0L) 0L else a.minNanos,
                spanMaxNanos = a?.maxNanos ?: 0L,
                hist = a?.hist ?: LongArray(SpanHistogram.BUCKETS),
                hits = s.coarseHits[t],
                selfHits = s.coarseSelfHits[t],
                selfRunningHits = s.coarseSelfRunningHits[t],
                selfActiveTicks = s.coarseSelfActiveTicks[t],
                selfPeak = s.coarseSelfPeak[t],
                threads = coarseThreadsOf(t),
                runningHits = s.coarseRunningHits[t],
                inclusiveHits = s.coarseInclusiveHits[t],
                runningInclusiveHits = s.coarseRunningInclusiveHits[t],
                instanceTicks = s.coarseInstanceTicks[t],
                activeTicks = s.coarseActiveTicks[t],
                fine = LongArray(MAX_OPERATIONS + 1) { s.pairHits[t * (MAX_OPERATIONS + 1) + it] },
                staleHits = s.coarseStaleHits[t],
            )
        }
        return Report(
            stats, s.counters[NO_OP_INDEX], s.ticks, s.span, duration, s.maxSlots, s.duty(), s.failure,
            imbalances = imbalances.get() - imbalancesAtStart, openAtEnd = openSpans(), stateSampled = s.sampleState,
            untrackedSlots = (untrackedSlots() - untrackedAtStart).coerceAtLeast(0),
            reclaimedSlots = (reclaimedSlots() - reclaimedAtStart).coerceAtLeast(0),
            idleWaitingHits = s.idleWaitingHits,
            coarse = coarse,
            openContextsAtEnd = openContexts(),
            labelledOutsideCoarse = s.labelledOutsideCoarse,
            staleContextHits = s.staleContextHits,
            coarseSampleHits = s.coarseSampleHits,
        )
    }
}

/**
 * Marks the calling thread as being inside operation [id] for the duration of [body].
 *
 * The previous value is restored rather than cleared — clearing would break nesting, since the
 * caller is still inside its own operation when a nested one returns.
 */
inline fun <T> op(op: FineOp, body: () -> T): T {
    val slot = Profiler.slot()
    val prev = slot.getOpaque()
    slot.setOpaque(op.id)
    // After the label, deliberately. Before it, the increment would be billed to the caller and
    // would add to the attribution bias; after it, the time lands on the operation it belongs to.
    // That inflates busy operations by calls x counterCost — the one distortion we can subtract
    // exactly, since the counter measures precisely the quantity the correction needs.
    slot.count(op.id)
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
inline fun <T> op(op: FineOp, times: Int, body: () -> T): T {
    val slot = Profiler.slot()
    val prev = slot.getOpaque()
    slot.setOpaque(op.id)
    slot.count(op.id, times)
    try {
        return body()
    } finally {
        slot.setOpaque(prev)
    }
}


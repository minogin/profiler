package com.minogin.profiler

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
        // Read before the reset, and defensively: a slot can hold NO_OP with a depth above zero if
        // the leak was an `exit` without its `enter` rather than the other way round.
        val id = s.getOpaque()
        val open = if (id >= 0) nameOf(id) else "an unnamed label"
        s.depth = 0
        s.setOpaque(NO_OP)
        // Fatal under strict, and it is the only thing that is. Resetting the slot stops the leak
        // spreading, but it cannot give back the samples already billed to the wrong operation —
        // so continuing means reporting a share that was manufactured by a bug.
        sampler?.let { if (it.strict) it.fail(leakMessage(open)) }
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
            imbalances = imbalances.get() - imbalancesAtStart, openAtEnd = openSpans(), stateSampled = s.sampleState,
            idleWaitingHits = s.idleWaitingHits,
        )
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


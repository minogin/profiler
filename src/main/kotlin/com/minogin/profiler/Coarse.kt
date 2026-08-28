package com.minogin.profiler

/**
 * Ceiling on distinct coarse types. Same reasoning as [MAX_OPERATIONS] and a much smaller number:
 * coarse labels go on logical operations — serving a query, expanding a frontier — and fifty is
 * already a large application's worth. The sampler indexes flat arrays by this.
 */
const val MAX_COARSE_TYPES = 64

/**
 * How far up the parent chain the sampler walks when crediting inclusive occupancy.
 *
 * A bound and not a limit on nesting: contexts nest as deeply as the caller likes, and past this
 * depth only the *inclusive* credit for the outermost ancestors is given up. Sixteen is far past
 * any sane nesting of logical operations, and the walk happens once per occupied slot per tick.
 */
const val MAX_COARSE_DEPTH = 16

/**
 * One execution of a coarse operation.
 *
 * Allocated on entry, published into the calling thread's slot, and read from the sampling thread
 * with no lock and no fence. That is safe for exactly one reason: every identity field is `val`, so
 * the end of the constructor carries a freeze, and a thread that sees the reference is guaranteed to
 * see the fields. It is the same bargain the fine slot makes — a read may be a few nanoseconds
 * stale, and never torn.
 *
 * **Never recycled.** A pool would break that freeze and, worse, would let the sampler credit a
 * sample to a context that had since become a different execution. Silent misattribution is the
 * failure mode this whole design keeps having to guard against, and an allocation here costs a
 * fraction of the ~40 ns the tier already spends.
 */
class CoarseContext @PublishedApi internal constructor(
    /** Which coarse operation this is an execution of. */
    @JvmField val type: Int,
    /** The execution this one is nested inside, or null. The stack, as a chain of few long-lived objects. */
    @JvmField val parent: CoarseContext?,
    /** `System.nanoTime()` at entry. Half of the only exactly-measured quantity in the report. */
    @JvmField val startNanos: Long,
) {
    /** How many ancestors this context has. Kept so the sampler's walk is bounded without counting. */
    @JvmField
    val depth: Int = if (parent == null) 0 else parent.depth + 1

    /**
     * The tick at which the sampler last counted this instance, so an instance occupied by three
     * threads at one tick counts once and not three times.
     *
     * Written and read **only by the sampling thread**, so it needs no ordering of any kind. It is
     * the one field the sampler touches, and touching it dirties a cache line the owning thread
     * will want again at exit — one miss per instance per tick, against an object that already cost
     * an allocation. See [Sampler] and `plan.md`, phase 5.
     */
    @JvmField
    internal var tickStamp: Long = -1L
}

/**
 * A log-bucket histogram of span durations: fixed memory, any percentile to a known precision.
 *
 * Eight sub-buckets per octave, over a range of 1 ns to about 2.4 hours. 320 buckets, 2.5 KB per
 * type per thread — and allocated lazily, so a thread pays only for the types it actually enters.
 *
 * Percentiles are reported at the **top** of the bucket that contains them, so a printed p99 is
 * never below the truth. Erring upward is the right direction for a latency figure: it can say an
 * operation is slower than it is, never faster.
 *
 * **The price of that choice is [PRECISION], and it is 12.5% rather than the 6.25% a half-width
 * would suggest.** The widest bucket in an octave runs from `8x` to `9x`, so a value sitting at the
 * bottom of one is reported an eighth high. Reporting the midpoint would halve that and give up the
 * never-below guarantee, which is the wrong trade for a latency number. Measured on the bench: a
 * true p50 of 851.9 us landed at the bottom of the [851968, 917503] bucket and was reported as
 * 917.5 us, +7.70% — quantisation behaving exactly as specified, and it briefly looked like a defect.
 *
 * Forty lines rather than a dependency, which is the whole of the argument for writing it.
 */
internal object SpanHistogram {
    /** Sub-buckets per octave, as a power of two. */
    const val SUB_BITS = 3
    const val SUB = 1 shl SUB_BITS
    const val OCTAVES = 40
    const val BUCKETS = (OCTAVES + 1) * SUB

    /**
     * The most a reported percentile can exceed the true one, as a fraction — never below it.
     *
     * `9/8 - 1`, from the widest bucket in an octave. See the class note.
     */
    const val PRECISION = 1.0 / SUB

    /** Which bucket a duration falls in. Linear below [SUB], logarithmic above it. */
    fun bucketOf(v: Long): Int {
        if (v < SUB) return if (v < 0) 0 else v.toInt()
        val hb = 63 - java.lang.Long.numberOfLeadingZeros(v)
        val shift = hb - SUB_BITS
        if (shift >= OCTAVES) return BUCKETS - 1
        val sub = ((v ushr shift) and (SUB - 1).toLong()).toInt()
        return ((shift + 1) shl SUB_BITS) + sub
    }

    /** The smallest duration that lands in bucket [i]. */
    fun lowerBound(i: Int): Long =
        if (i < SUB) i.toLong() else (SUB.toLong() + (i and (SUB - 1))) shl ((i shr SUB_BITS) - 1)

    /** The largest duration that lands in bucket [i]. */
    fun upperBound(i: Int): Long = if (i >= BUCKETS - 1) Long.MAX_VALUE else lowerBound(i + 1) - 1

    /**
     * The [p]-th percentile of [counts], reported at the top of its bucket. `p` in 0..1.
     *
     * Rank is `ceil(p x n)` and at least 1, so p50 of a single value is that value and p100 is the
     * largest — the convention that makes a percentile of a tiny sample say something sensible
     * rather than nothing.
     */
    fun percentile(counts: LongArray, total: Long, p: Double): Double {
        if (total <= 0L) return Double.NaN
        val rank = maxOf(1L, Math.ceil(p * total).toLong())
        var seen = 0L
        for (i in counts.indices) {
            seen += counts[i]
            if (seen >= rank) return upperBound(i).toDouble()
        }
        return upperBound(counts.size - 1).toDouble()
    }
}

/**
 * One thread's account of one coarse type: what it measured itself, as against what the sampler saw.
 *
 * Per thread, so the recording is a handful of plain writes with a single writer — no fence and no
 * contention on a path that runs thousands of times a second. Folded together when the report is
 * taken, and folded into the retired totals when the thread goes, exactly as the fine counters are.
 */
internal class CoarseAgg(@JvmField val type: Int) {
    @JvmField var count: Long = 0
    @JvmField var sumNanos: Long = 0
    @JvmField var minNanos: Long = Long.MAX_VALUE
    @JvmField var maxNanos: Long = 0
    @JvmField val hist = LongArray(SpanHistogram.BUCKETS)

    fun record(nanos: Long) {
        count++
        sumNanos += nanos
        if (nanos < minNanos) minNanos = nanos
        if (nanos > maxNanos) maxNanos = nanos
        hist[SpanHistogram.bucketOf(nanos)]++
    }

    fun mergeFrom(o: CoarseAgg) {
        count += o.count
        sumNanos += o.sumNanos
        if (o.minNanos < minNanos) minNanos = o.minNanos
        if (o.maxNanos > maxNanos) maxNanos = o.maxNanos
        for (i in hist.indices) hist[i] += o.hist[i]
    }

    fun reset() {
        count = 0
        sumNanos = 0
        minNanos = Long.MAX_VALUE
        maxNanos = 0
        hist.fill(0L)
    }
}

/**
 * Marks the calling thread as inside an execution of coarse operation [type] for the duration of
 * [body], and measures how long that took.
 *
 * This is the form to reach for. Its `finally` is written by the compiler and cannot leak, and the
 * span it records is exactly the interval the caller wrapped. [enterCoarse] exists for the boundary
 * that is not a block.
 *
 * **What it costs is not the fine tier's ~2 ns.** An allocation, two `nanoTime` calls and a handful
 * of writes come to tens of nanoseconds, which is why the tier boundary exists at all: an operation
 * under about a microsecond cannot afford this and belongs in the fine tier. See
 * `profiler.md`, "Where the boundary is".
 */
inline fun <T> coarse(type: Int, body: () -> T): T {
    val slot = Profiler.slot()
    val parent = slot.contextOpaque()
    val ctx = CoarseContext(type, parent, System.nanoTime())
    slot.setContextOpaque(ctx)
    try {
        return body()
    } finally {
        // Restored before the span is recorded, so the recording itself is never billed to the
        // execution it is closing.
        slot.setContextOpaque(parent)
        slot.recordSpan(type, System.nanoTime() - ctx.startNanos)
    }
}

/**
 * Enters an execution of coarse operation [type] until a matching [exitCoarse].
 *
 * For the boundary that is two callbacks rather than a block — which is most of what third-party
 * code offers. It carries the same hazard [Profiler.enter] does and for the same reason: **there is
 * no `finally` here**, so a body that throws leaves the context open, and everything the thread does
 * afterwards is billed to it until something closes it. See [Profiler.expectBalanced].
 */
fun enterCoarse(type: Int) {
    val s = Profiler.slot()
    s.setContextOpaque(CoarseContext(type, s.contextOpaque(), System.nanoTime()))
}

/**
 * Closes the innermost coarse execution on this thread and records its span.
 *
 * An unmatched call is a no-op rather than an error: it means the caller closed something it never
 * opened, which [Profiler.expectBalanced] is the place to notice, and throwing here would turn a
 * measurement problem into an application crash in somebody else's process.
 */
fun exitCoarse() {
    val s = Profiler.slot()
    val ctx = s.contextOpaque() ?: return
    s.setContextOpaque(ctx.parent)
    s.recordSpan(ctx.type, System.nanoTime() - ctx.startNanos)
}

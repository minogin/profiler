package com.minogin.profiler.bench

import com.minogin.profiler.*
import java.util.Locale
import kotlin.math.abs

/**
 * How far the profiler's percentiles may sit from the workers' own, **put through the same
 * histogram**, as a fraction.
 *
 * The comparison is deliberately like-for-like. Comparing a quantised percentile against an exact
 * one measures the quantiser, which is specified — the raw figure is printed beside it for a reader
 * to see, and it is not what the check is about. What the check is about is whether the profiler
 * recorded *the same intervals the workers timed*, and for that the two sides have to be treated
 * identically. Then they should agree exactly, and one bucket of slack covers a span whose two
 * measurements straddle a boundary.
 */
private const val SPAN_TOLERANCE = SpanHistogram.PRECISION

/**
 * How far a coarse operation's sampled breakdown may sit from the truth, in percentage points.
 *
 * Three times the fine tier's budget, and the reason is arithmetic rather than laxity. Both suffer
 * the same attribution bias — a child's hook prologue runs while the parent's label is still set, so
 * the parent absorbs it — but the two are divided by different things. A run-wide share divides by
 * everything, so twenty nanoseconds is a fraction of a point. **This share divides by one coarse
 * operation**, so the same twenty nanoseconds against `rankBatch`'s 1140 ns is one and a half.
 *
 * Set from measurement, as everything here is: worst observed 1.91 pp, at 8 threads, on the self
 * entry of every one of the three types — which is exactly where the bias must land if that is what
 * it is. Correcting it with call counts was considered and dropped (ideas.md item 11); widening a
 * budget with a stated cause is the honest alternative to correcting with an estimate.
 */
private const val BREAKDOWN_TOLERANCE_PP = 3.0

/**
 * The coarse tier against the truth — and unlike everything else here, most of that truth is
 * **directly measured** rather than reconstructed.
 *
 * A share had to be computed from configuration because nothing can time a 20 ns operation without
 * destroying it. A span is a different kind of quantity: two clock readings around something that
 * lasts microseconds, so the bench can take exactly the measurement the profiler takes and the check
 * becomes an identity rather than an estimate. What is still sampled — the breakdown, the busy time —
 * is checked against the call graph, which is the truth the fine tier already uses.
 *
 * Four things are asserted, and they fail for different reasons:
 *
 * 1. **Execution counts are exact.** The profiler counts every completed execution and so does the
 *    graph. A mismatch is a lost or double-counted span, not a measurement error.
 * 2. **Percentiles match the workers' own stopwatches**, to the histogram's stated precision.
 * 3. **The breakdown matches `subtree x selfNanos`** — the cross-tabulation, which is the one thing
 *    neither tier produces alone.
 * 4. **Parallelism is exactly 1.0.** Contexts do not cross threads until phase 5, so every occupied
 *    execution is occupied by the one thread that created it. A known answer, which is the point:
 *    anything else means the instance stamping is broken, and we find that out here rather than in
 *    phase 5, where the true answer is no longer known.
 */
internal fun checkCoarse(bench: Bench, o: Outcome, sampler: Sampler, stepMillis: Double): Boolean {
    // The snapshot taken when the sampler stopped, not a fresh read: the batch measurement that
    // runs afterwards calls execLabeled tens of thousands of times and records a span for every one.
    val totals = o.coarseTotals
    val subtree = subtreeCounts()
    val stepNanos = stepMillis * 1e6
    var ok = true

    println("\n--- Phase 4: the coarse tier against the truth ---")

    // --- 1. counts, exact on both sides ---------------------------------------------
    val calls = o.totalCalls
    println(String.format(Locale.ROOT, "  %-14s %12s %12s %9s", "coarse op", "executions", "truth", "diff"))
    for (id in COARSE_OPS) {
        val got = totals[coarseTypeOf[id]]?.count ?: 0L
        val want = calls[id]
        val diff = got - want
        // The deadline cuts a thread off mid-pass, so one execution per thread may be counted by the
        // graph and not by the profiler. That is the whole of the allowance; anything wider is a bug.
        val slack = bench.activeThreads.toLong()
        val bad = abs(diff) > slack
        if (bad) ok = false
        println(
            String.format(
                Locale.ROOT, "  %-14s %,12d %,12d %+9d%s",
                OPS[id].name, got, want, diff, if (bad) "   MISMATCH" else ""
            )
        )
    }

    // --- 2. spans against the workers' own stopwatches ------------------------------
    var dropped = 0L
    var n = 0
    for (w in bench.workers) {
        n += w.requestCount
        dropped += w.requestsDropped
    }
    if (dropped > 0) {
        println("  ! $dropped request spans overflowed the worker buffers, so the comparison below is")
        println("    against the first part of the run rather than all of it")
        ok = false
    }
    val reqAgg = totals[requestType]
    if (n == 0 || reqAgg == null || reqAgg.count == 0L) {
        println("  ! no request spans were recorded — the coarse labels did not run")
        return false
    }
    val sorted = LongArray(n)
    var at = 0
    for (w in bench.workers) for (i in 0 until w.requestCount) sorted[at++] = w.requestSpans[i]
    sorted.sort()
    var sum = 0.0
    for (v in sorted) sum += v

    fun exactPercentile(p: Double): Double =
        sorted[minOf(sorted.size - 1, maxOf(0, Math.ceil(p * sorted.size).toInt() - 1))].toDouble()

    // The workers' spans through the profiler's own histogram, so the comparison is like for like.
    val ref = LongArray(SpanHistogram.BUCKETS)
    for (v in sorted) ref[SpanHistogram.bucketOf(v)]++
    val refCount = sorted.size.toLong()

    println(
        String.format(
            Locale.ROOT, "%n  %-8s %11s %11s %8s %12s   (%,d requests, every one timed by its worker)",
            "request", "profiler", "workers", "diff", "workers,exact", sorted.size
        )
    )
    // Exact on both sides: the worker records one span per request and so does the profiler. One
    // per thread of slack, for the request the deadline cut short.
    val countBad = abs(reqAgg.count - refCount) > bench.activeThreads
    if (countBad) ok = false
    println(
        String.format(
            Locale.ROOT, "  %-8s %11s %11s %+7.2f%%%s",
            "count", "%,d".format(reqAgg.count), "%,d".format(refCount),
            (reqAgg.count.toDouble() / refCount - 1.0) * 100, if (countBad) "   MISMATCH" else ""
        )
    )
    val checks = listOf(
        Triple("mean", reqAgg.sumNanos.toDouble() / reqAgg.count, sum / sorted.size),
        Triple("p50", SpanHistogram.percentile(reqAgg.hist, reqAgg.count, 0.50), SpanHistogram.percentile(ref, refCount, 0.50)),
        Triple("p90", SpanHistogram.percentile(reqAgg.hist, reqAgg.count, 0.90), SpanHistogram.percentile(ref, refCount, 0.90)),
        Triple("p99", SpanHistogram.percentile(reqAgg.hist, reqAgg.count, 0.99), SpanHistogram.percentile(ref, refCount, 0.99)),
    )
    val exactOf = mapOf(
        "mean" to sum / sorted.size,
        "p50" to exactPercentile(0.50),
        "p90" to exactPercentile(0.90),
        "p99" to exactPercentile(0.99),
    )
    for ((name, got, want) in checks) {
        val rel = if (want == 0.0) 0.0 else got / want - 1.0
        val bad = abs(rel) > SPAN_TOLERANCE
        if (bad) ok = false
        println(
            String.format(
                Locale.ROOT, "  %-8s %11s %11s %+7.2f%% %12s%s",
                name, duration(got), duration(want), rel * 100, duration(exactOf[name]!!),
                if (bad) "   OUT OF TOLERANCE" else ""
            )
        )
    }
    // Reported, never gated. A maximum is one observation, and the worker's stopwatch brackets the
    // profiler's, so a single preemption between the two clock readings lands entirely on it — on
    // Windows that is a 15.6 ms quantum against a 900 us request. Measured once at -16.93%, which is
    // one descheduled thread and not a defect in anything.
    println(
        String.format(
            Locale.ROOT, "  %-8s %11s %11s %+7.2f%%   (one observation - reported, not gated)",
            "max", duration(reqAgg.maxNanos.toDouble()), duration(sorted.last().toDouble()),
            reqAgg.maxNanos.toDouble() / sorted.last() - 1.0
        )
    )

    // --- 3. the cross-tabulation ----------------------------------------------------
    // Under a coarse operation the share of fine operation k is what the graph says it is:
    // subtree[coarse][k] x the self duration of k, over the operation's inclusive duration.
    //
    // Against truth B — the durations the workers *measured* — and not truth A. The distinction is
    // not pedantry here. A share taken over the whole run divides by everything, so a 3% fit error
    // on one operation moves its share by a fraction of a point. This denominator is one coarse
    // operation, so scoreNode holds 54% of rankBatch and the same 3% error moves it by 1.6 pp.
    // Truth A measured against a 1.5 pp budget failed on exactly that and the instrument was fine.
    println("\n  cross-tabulation - sampled share under each coarse operation / the graph's")
    var worstPp = 0.0
    var worstWhere = "-"
    for (id in COARSE_OPS) {
        val t = coarseTypeOf[id]
        val row = LongArray(OP_COUNT) { sampler.pairHits[t * (MAX_OPERATIONS + 1) + it] }
        var rowTotal = 0.0
        for (v in row) rowTotal += v
        if (rowTotal == 0.0) continue
        var inclB = 0.0
        for (k in 0 until OP_COUNT) inclB += subtree[id][k] * o.measuredSelf[k]
        val parts = ArrayList<String>()
        for (k in 0 until OP_COUNT) {
            val want = subtree[id][k] * o.measuredSelf[k] / inclB
            val got = row[k] / rowTotal
            if (want < 0.02 && got < 0.02) continue
            val pp = abs(got - want) * 100
            if (pp > worstPp) {
                worstPp = pp
                worstWhere = "${OPS[k].name} under ${OPS[id].name}"
            }
            parts.add(String.format(Locale.ROOT, "%s %.1f/%.1f%%", OPS[k].name, got * 100, want * 100))
        }
        println("    ${OPS[id].name}: " + parts.joinToString(", "))
    }
    // Not gated when the bench has already failed its own two-truths check. This comparison divides
    // by truth B, so a run where truth B is itself out of tolerance cannot say anything about the
    // profiler — and printing OUT OF TOLERANCE there would be an accusation manufactured by a fault
    // somewhere else entirely. Live: a 60 s run whose `rehash` scatter came to 18.08% against a 6%
    // budget put `rehash under maintain` 5.17 pp out, and nothing in the coarse tier had moved.
    val breakdownBad = worstPp > BREAKDOWN_TOLERANCE_PP
    println(
        String.format(
            Locale.ROOT, "  worst gap %.2f pp (%s), tolerance %.2f pp%s",
            worstPp, worstWhere, BREAKDOWN_TOLERANCE_PP,
            if (!breakdownBad) "" else if (o.ok) "   OUT OF TOLERANCE"
            else "   (not gated: the bench failed its own two-truths check, so truth B is not a truth here)"
        )
    )
    if (breakdownBad && o.ok) ok = false

    // --- 4. parallelism, whose answer is known --------------------------------------
    println("\n  parallelism - 1.0000 by construction until contexts cross threads (phase 5)")
    for (t in 0 until Profiler.coarseCount()) {
        val instTicks = sampler.coarseInstanceTicks[t]
        if (instTicks == 0L) continue
        val par = sampler.coarseInclusiveHits[t].toDouble() / instTicks
        val inFlight = instTicks.toDouble() / sampler.coarseActiveTicks[t]
        val count = maxOf(1L, totals[t]?.count ?: 1L)
        // Exactly 1.0, not approximately. Every occupied execution is occupied by the thread that
        // created it, so the two counters move in lockstep; a tolerance here would hide the very
        // defect the check exists to catch.
        val bad = par != 1.0
        if (bad) ok = false
        println(
            String.format(
                Locale.ROOT, "    %-14s parallelism %.4f, in flight %.2f/%d, busy/exec %s%s",
                Profiler.coarseNameOf(t), par, inFlight, bench.activeThreads,
                duration(sampler.coarseRunningInclusiveHits[t] * stepNanos / count),
                if (bad) "   NOT 1.0 — the instance stamping is wrong" else ""
            )
        )
    }

    // --- what it cost, which is not what the boundary rule prices ---------------------
    // The tier boundary — d >= max(800 ns, 4 us x share) — comes from the ~40 ns of CPU a context
    // costs to make and stamp. It says nothing about the *garbage*, and a context is never recycled
    // by design, so the allocation rate is the execution rate. Printed because it is a real cost the
    // rule does not price, and because of an open question it does not answer:
    //
    // **--coarse makes the bench's own two-truths check fail more often, and the cause is not
    // established.** Measured: 60 s runs with the coarse tier put the scatter at 18.08% and 68.43%
    // against a 6% budget, on a different operation each time, while the same run without it came in
    // at 3.62%. Garbage was the first hypothesis and these two counters refute it — a run at this
    // rate collects 8 times for 16 ms, which cannot move a median of trials. A 6 GB young generation
    // did bring one run back to 4.87%, which is evidence the other way and may be coincidence at this
    // sample size. Recorded in findings.md as open rather than explained away.
    var executions = 0L
    for (t in 0 until Profiler.coarseCount()) executions += totals[t]?.count ?: 0L
    val seconds = o.runNanos / 1e9
    println(
        String.format(
            Locale.ROOT,
            "%n  cost: %,d executions in %.1f s = %,.0f contexts/s, about %.0f MB/s of garbage",
            executions, seconds, executions / seconds, executions / seconds * CONTEXT_BYTES / 1e6
        )
    )
    println(
        String.format(
            Locale.ROOT,
            "        %,d collections taking %,d ms during the run%s",
            o.gcCollections, o.gcMillis,
            if (o.ok) ""
            else " - and the bench failed its own two-truths check, which --coarse makes more likely" +
                    " for reasons not yet established (findings.md)"
        )
    )

    println("\n  coarse tier: ${if (ok) "OK" else "FAILED"}")
    return ok
}

/**
 * Bytes a [CoarseContext] occupies: a 12-byte header, an int type, an int depth, two references and
 * a long, rounded to an 8-byte boundary with compressed oops. Approximate on purpose — it is here to
 * turn an execution rate into a megabytes-per-second a reader can weigh, not to be exact.
 */
private const val CONTEXT_BYTES = 40.0

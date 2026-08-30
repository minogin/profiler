package com.minogin.profiler.bench

import com.minogin.profiler.NO_OP_INDEX
import com.minogin.profiler.Sampler
import com.minogin.profiler.duration
import java.util.Locale

/**
 * Below this the pool was saturated and the run genuinely had no fan-out to see, so nothing is
 * asserted about it.
 *
 * Two drivers' worth of threads on one request. It is a threshold on the **bench's own stopwatch**,
 * not on anything the profiler said, which is what makes it safe to gate the checks with: a
 * configuration where fan-out did not happen cannot be evidence either way about propagation, and
 * asserting on it would turn a scheduling accident into a failed run.
 */
private const val MIN_FANOUT = 2.0

/**
 * At most this much of the truth may show up as the request's busy time before the escape stops
 * looking like an escape.
 *
 * Half, and generously so. With no propagation the driver is parked on a join for nearly all of
 * every request and contributes almost no running samples at all, so the honest expectation is
 * close to zero — the slack is here to absorb a driver that happened to be scheduled during the
 * dispatch loop, not to make a marginal result pass.
 */
private const val ESCAPED_WORK_MAX = 0.5

/** Below this share of labelled thread-time outside every span, work is not visibly escaping. */
private const val OUTSIDE_MIN_SHARE = 0.5

/**
 * One fan-out configuration, as the bench measured it and as the profiler reported it.
 *
 * The two halves are kept side by side on purpose. Every quantity on the bench's side is either a
 * count or a pair of clock readings the bench took itself; every quantity on the profiler's side is
 * sampled. That is the comparison, and it is the same discipline the span check already uses.
 */
internal class FanoutRow(
    val drivers: Int,
    val helpers: Int,
    val requests: Long,
    /** Root calls the helpers actually ran. */
    val rootCalls: Long,
    /** Root calls the drivers asked for: chunks dispatched times [CLOCK_CHUNK]. Exact. */
    val expectedRootCalls: Long,
    /** Helper occupancy over request span, by the bench's own stopwatch. */
    val benchParallelism: Double,
    /** `inclusiveHits / instanceTicks` for the request type, by the sampler. */
    val profilerParallelism: Double,
    /** Total work the run did, over the requests that did it. Thread-nanoseconds per execution. */
    val truthWorkPerExecNanos: Double,
    /** What the profiler thinks one execution's busy time was. */
    val profilerBusyPerExecNanos: Double,
    /** Labelled thread-time that fell under no coarse span at all. */
    val outsideShare: Double,
    val meanSpanNanos: Double,
) {
    /** Whether this configuration achieved enough fan-out for anything to be asserted about it. */
    val fannedOut: Boolean get() = benchParallelism >= MIN_FANOUT

    /** How much of the work the request did that the request's own busy time can account for. */
    val workAccounted: Double
        get() = if (truthWorkPerExecNanos <= 0.0) Double.NaN
        else profilerBusyPerExecNanos / truthWorkPerExecNanos
}

/** Gathers one configuration's row. Nothing is judged here; [reportFanout] does that. */
internal fun fanoutRow(bench: Bench, o: Outcome, sampler: Sampler, stepMillis: Double): FanoutRow {
    val stepNanos = stepMillis * 1e6
    val t = requestType
    val requests = bench.workers.sumOf { it.requestCount.toLong() }
    val count = maxOf(1L, o.coarseTotals[t]?.count ?: 1L)
    val labelled = sampler.totalSamples() - sampler.counters[NO_OP_INDEX]
    val instanceTicks = sampler.coarseInstanceTicks[t]
    return FanoutRow(
        drivers = bench.activeThreads,
        helpers = bench.helpers,
        requests = requests,
        rootCalls = o.rootCalls,
        expectedRootCalls = bench.workers.sumOf { it.fanoutChunks } * CLOCK_CHUNK,
        benchParallelism = bench.measuredParallelism(),
        profilerParallelism =
            if (instanceTicks == 0L) Double.NaN
            else sampler.coarseInclusiveHits[t].toDouble() / instanceTicks,
        truthWorkPerExecNanos = if (requests == 0L) Double.NaN else o.totB / requests,
        profilerBusyPerExecNanos = sampler.coarseRunningInclusiveHits[t] * stepNanos / count,
        outsideShare = if (labelled == 0L) 0.0 else sampler.labelledOutsideCoarse.toDouble() / labelled,
        meanSpanNanos = o.coarseTotals[t]?.let { it.sumNanos.toDouble() / maxOf(1L, it.count) } ?: Double.NaN,
    )
}

/**
 * The phase 5a verdict: fan-out happens, the bench can measure it, and the profiler cannot see it.
 *
 * **This asserts that a defect is present, which is a strange thing for a test to do and is
 * deliberate.** The whole discipline of this project is to build the truth before the instrument,
 * and the one chance to establish that the bench really reproduces Lucene's problem is while the
 * fix does not exist. Every one of these checks is inverted in 5b: `parallelism` stops being 1.0,
 * the work comes back inside the span, and the outside-coarse share collapses. They are written to
 * be inverted rather than deleted, so the same numbers keep being watched from both sides.
 *
 * The two checks that are **not** about the defect stay true in both phases, and they are the ones
 * that are clock-independent — counts against counts:
 *
 * 1. **Root calls are conserved.** The helpers ran exactly the chunks the drivers dispatched, no
 *    more and no fewer. Fan-out must move work, never invent or lose it, and a bench that quietly
 *    dropped a chunk would make every share below wrong in a way nothing else here would catch.
 * 2. **Fan-out actually happened**, by the bench's own stopwatch, in at least one configuration.
 *    Without that the rest is vacuous — a run where nothing was handed anywhere would pass every
 *    "the profiler cannot see it" check trivially, which is exactly the shape of vacuous pass this
 *    project has already been caught by once.
 */
internal fun reportFanout(rows: List<FanoutRow>): Boolean {
    var ok = true
    println("\n" + "=".repeat(110))
    println("PHASE 5a — FAN-OUT, WITH NO PROPAGATION")
    println("=".repeat(110))
    println(
        String.format(
            Locale.ROOT, "%9s %9s %12s %11s %11s %12s %12s %10s",
            "drivers", "helpers", "requests", "bench par", "prof par", "work/exec", "busy/exec", "outside"
        )
    )
    println("-".repeat(110))
    for (r in rows) {
        println(
            String.format(
                Locale.ROOT, "%9d %9d %,12d %11.2f %11.4f %12s %12s %9.1f%%",
                r.drivers, r.helpers, r.requests, r.benchParallelism, r.profilerParallelism,
                duration(r.truthWorkPerExecNanos), duration(r.profilerBusyPerExecNanos),
                r.outsideShare * 100
            )
        )
    }
    println("-".repeat(110))
    println("bench par is helper occupancy / request span, measured by the bench's own clock")
    println("prof par  is inclusiveHits / instanceTicks, sampled - it is what propagation will move")
    println("work/exec is the run's total work over its requests; busy/exec is what the span could see")
    println("outside   is labelled thread-time that fell under no coarse span at all")

    // --- 1. conservation, exact and clock-independent --------------------------------
    println("\n  root calls: dispatched against executed")
    for (r in rows) {
        val bad = r.rootCalls != r.expectedRootCalls
        if (bad) ok = false
        println(
            String.format(
                Locale.ROOT, "    %d x %d   %,14d executed, %,14d dispatched   %s",
                r.drivers, r.helpers, r.rootCalls, r.expectedRootCalls,
                if (bad) "MISMATCH — fan-out lost or invented work" else "exact"
            )
        )
    }

    // --- 2. did fan-out happen at all --------------------------------------------------
    val fanned = rows.filter { it.fannedOut }
    if (fanned.isEmpty()) {
        ok = false
        println(
            String.format(
                Locale.ROOT,
                "\n  ! no configuration reached %.1f threads per request by the bench's own stopwatch,",
                MIN_FANOUT
            )
        )
        println("    so nothing below would mean anything and the checks are not run")
        println("\n  fan-out: FAILED")
        return false
    }

    // --- 3. the defect, where fan-out actually happened ---------------------------------
    println("\n  the defect, asserted only where the bench says fan-out happened (5b inverts all three)")
    for (r in fanned) {
        // Exactly 1.0, not approximately, and for the same structural reason the same-thread check
        // uses equality: with no propagation every occupied instance is occupied by the one thread
        // that made it, so the two counters move in lockstep. A tolerance would let a partly working
        // propagation look like no propagation at all.
        val parBad = r.profilerParallelism != 1.0
        val workBad = !(r.workAccounted < ESCAPED_WORK_MAX)
        val outsideBad = r.outsideShare < OUTSIDE_MIN_SHARE
        if (parBad || workBad || outsideBad) ok = false
        println(String.format(Locale.ROOT, "    %d drivers x %d helpers, mean span %s:", r.drivers, r.helpers, duration(r.meanSpanNanos)))
        println(
            String.format(
                Locale.ROOT,
                "      bench measured %.2f threads per request, the profiler reports %.4f   %s",
                r.benchParallelism, r.profilerParallelism,
                if (parBad) "UNEXPECTED — propagation is not supposed to exist yet" else "as expected"
            )
        )
        println(
            String.format(
                Locale.ROOT,
                "      the span accounts for %.1f%% of the work its request did                %s",
                r.workAccounted * 100,
                if (workBad) "UNEXPECTED — too much work is still inside the span" else "as expected"
            )
        )
        println(
            String.format(
                Locale.ROOT,
                "      %.1f%% of labelled thread-time is under no span at all                   %s",
                r.outsideShare * 100,
                if (outsideBad) "UNEXPECTED — the escape is not visible" else "as expected"
            )
        )
    }

    // Reported and never gated. It is the honest answer rather than a defect: with as many drivers
    // as helpers there is nothing left to fan out to, so a request gets about one thread and the
    // parallelism the code could have had is invisible for want of a free core. That is the caveat
    // CoarseStat.parallelism already carries in its own documentation, observed.
    val saturated = rows.filter { !it.fannedOut }
    for (r in saturated) {
        println(
            String.format(
                Locale.ROOT,
                "%n  %d drivers x %d helpers reached only %.2f threads per request: the pool is saturated,",
                r.drivers, r.helpers, r.benchParallelism
            )
        )
        println("    so there was no fan-out to miss and nothing is asserted about it")
    }

    println("\n  fan-out: ${if (ok) "OK — the defect reproduces against a measured truth" else "FAILED"}")
    return ok
}

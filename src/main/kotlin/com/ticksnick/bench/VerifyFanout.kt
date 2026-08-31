package com.ticksnick.bench

import com.ticksnick.NO_OP_INDEX
import com.ticksnick.Sampler
import com.ticksnick.duration
import java.util.Locale
import kotlin.math.abs

/**
 * Below this the pool was saturated and the run genuinely had no fan-out to see, so nothing is
 * asserted about it.
 *
 * Two helpers' worth of threads on one request, and it is a threshold on the **bench's own
 * stopwatch** rather than on anything the profiler said — which is what makes it safe to gate with.
 * A configuration where fan-out did not happen cannot be evidence either way about propagation, and
 * asserting on it would turn a scheduling accident into a failed run.
 */
private const val MIN_FANOUT = 2.0

/**
 * At most this much of the truth may show up as the request's busy time before the escape stops
 * looking like an escape. Propagation off only.
 *
 * Half, and generously so. With no propagation the driver is parked on a join for nearly all of
 * every request and contributes almost no running samples at all, so the honest expectation is close
 * to zero — measured at 0.3%. The slack is here to absorb a driver that happened to be scheduled
 * during its dispatch loop, not to make a marginal result pass.
 */
private const val ESCAPED_WORK_MAX = 0.5

/** Below this share of labelled thread-time outside every span, work is not visibly escaping. */
private const val OUTSIDE_MIN_SHARE = 0.5

/**
 * How far the sampled `inside` may sit from the bench's own count of the threads in a request, as a
 * fraction. Propagation on.
 *
 * Set from measurement, as everything here is, and the agreement is far closer than expected:
 * **-0.1% on a 60 s run and -0.3% on a 10 s one**, 4.00 sampled against 4.00 measured. So this is a
 * factor of ten on the worst observed, and it is deliberately not tighter — two runs is two runs.
 *
 * They agree that closely because they are genuinely the same quantity by two routes: the bench sums
 * stopwatch intervals over helpers, the profiler counts slot samples at 1 ms against a mean span of
 * about 300 us. What makes the comparison honest is that both count a thread that is *in* the
 * execution, running or not — which is why the bench side has to add its parked driver, and why
 * [FanoutRow.benchInside] is `1 + helpers` rather than `helpers`.
 */
private const val INSIDE_TOLERANCE = 0.03

/**
 * How far the request's sampled busy time may sit from the work its requests actually did, as a
 * fraction. Propagation on.
 *
 * The plan's *"work per execution must not depend on thread count"* invariant, and the coarsest of
 * the three checks — because unlike `inside` it is a **sampled** quantity against a **computed** one,
 * so it carries truth B's error as well as its own.
 *
 * Set from measurement, and the two observations are far apart for a reason worth stating:
 * **5.1% on the 60 s run, 20.2% on the 10 s smoke.** Truth B is measured in a stage where every
 * thread is busy, while a fanned-out run has its driver parked, so the two do not price an operation
 * under quite the same load — and on a short run there is not enough of either for that to average
 * out. 25% passes both with the 60 s figure, which is the configuration this check is for, having
 * five times the margin.
 */
private const val WORK_TOLERANCE = 0.25

/**
 * The most labelled thread-time that may fall outside every span once propagation is on.
 *
 * Not zero on principle: the bench leaves `traverse` and the operations under it in the fine tier on
 * purpose, and a driver between `exitCoarse` and its next `enterCoarse` is briefly outside
 * everything. Measured, it rounds to **0.0%** in both configurations — against **76.4%** with
 * propagation off, on the same binary minutes apart. That is the collapse this phase exists to
 * produce, and it is the same signal that reads 88.5% on Lucene.
 */
private const val OUTSIDE_MAX_SHARE = 0.10

/**
 * The most coarse thread-time that may be billed to a finished execution when nothing is staged.
 *
 * Zero is the honest expectation and not merely the hope: a helper releases the context before it
 * counts its latch down, so a driver cannot close a span while a helper it is waiting for is still
 * mounted. Measured, it rounds to **0.00%** in both configurations.
 *
 * It did not, at first. Before the sampler confirmed that a thread was *still* mounted on a context
 * it had seen closed, a clean run read **1.14%** — the benign ordering above, caught between the
 * sampler's two reads — which was over the strict threshold and stopped a correct sixty-second run
 * one second in. The floor is kept rather than demanding exact zero because this is a share of a
 * sampled denominator, not because anything is expected to land in it.
 */
private const val STALE_MAX_SHARE = 0.001

/**
 * The least stale thread-time that must show up when a chunk per request is deliberately left
 * un-joined.
 *
 * Set from measurement: **12.58% to 18.33%** across four runs and both configurations. This sits a
 * factor of two and a half below the lowest of them, because what it tests is that the detector is
 * wired up at all rather than how much escapes — and how much escapes depends on how the scheduler
 * happens to order a race.
 *
 * The first version of the staging made the escaping chunk the same size as the rest, and it read
 * **0.81%**: an un-joined chunk usually finished while the request was still open, so only its tail
 * was ever stale. True, and far too close to a clean run for a threshold to separate the two. The
 * chunk is four times longer now — see `ESCAPE_CHUNK`.
 */
private const val STALE_MIN_STAGED_SHARE = 0.05

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
    /** Root calls the drivers dispatched to the pool. Exact. */
    val expectedRootCalls: Long,
    /**
     * Helper occupancy over request span, by the bench's own stopwatch. The fan-out the pool
     * achieved, and the gate for every check below.
     */
    val benchHelperThreads: Double,
    /** `inclusiveHits / instanceTicks` — threads in the execution, a parked one counted. */
    val profilerInside: Double,
    /** `runningInclusiveHits / instanceTicks` — of those, the ones a sample caught on a CPU. */
    val profilerWorking: Double,
    /** Total work the run did, over the requests that did it. Thread-nanoseconds per execution. */
    val truthWorkPerExecNanos: Double,
    /** What the profiler thinks one execution's busy time was. */
    val profilerBusyPerExecNanos: Double,
    /** Labelled thread-time that fell under no coarse span at all. */
    val outsideShare: Double,
    /** Of the thread-time inside coarse executions, the share inside one already closed. */
    val staleShare: Double,
    val meanSpanNanos: Double,
) {
    /** Whether this configuration achieved enough fan-out for anything to be asserted about it. */
    val fannedOut: Boolean get() = benchHelperThreads >= MIN_FANOUT

    /**
     * Threads in one request, by the bench's own reckoning: its helpers, **plus the driver**.
     *
     * The driver holds the context for the whole of its own span — it opened it, and it is parked
     * inside it waiting on the join — so it contributes exactly one thread per tick the span is
     * live. That is what the profiler's `inside` counts, and comparing against helper occupancy
     * alone would report a one-thread disagreement that is not a disagreement at all. It is also why
     * `inside` reads exactly 1.0000 with propagation off: the driver, and nothing else.
     */
    val benchInside: Double get() = 1.0 + benchHelperThreads

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
    val ticks = sampler.coarseInstanceTicks[t]
    return FanoutRow(
        drivers = bench.activeThreads,
        helpers = bench.helpers,
        requests = requests,
        rootCalls = o.rootCalls,
        expectedRootCalls = bench.workers.sumOf { it.fanoutDispatchedCalls },
        benchHelperThreads = bench.measuredParallelism(),
        profilerInside = if (ticks == 0L) Double.NaN
        else sampler.coarseInclusiveHits[t].toDouble() / ticks,
        profilerWorking = if (ticks == 0L) Double.NaN
        else sampler.coarseRunningInclusiveHits[t].toDouble() / ticks,
        truthWorkPerExecNanos = if (requests == 0L) Double.NaN else o.totB / requests,
        profilerBusyPerExecNanos = sampler.coarseRunningInclusiveHits[t] * stepNanos / count,
        outsideShare = if (labelled == 0L) 0.0 else sampler.labelledOutsideCoarse.toDouble() / labelled,
        staleShare = if (sampler.coarseSampleHits == 0L) 0.0
        else sampler.staleContextHits.toDouble() / sampler.coarseSampleHits,
        meanSpanNanos = o.coarseTotals[t]?.let { it.sumNanos.toDouble() / maxOf(1L, it.count) } ?: Double.NaN,
    )
}

/**
 * The phase 5 verdict, and it runs in whichever direction the switch is set.
 *
 * **With propagation off it asserts that the defect is present**, which is a strange thing for a test
 * to do and is deliberate: the discipline here is to build the truth before the instrument, and the
 * one chance to establish that the bench really reproduces Lucene's problem was while the fix did not
 * exist. Those checks were not deleted when 5b landed — `--propagate=off` still runs them, so the
 * before and the after are an A/B inside one binary rather than a claim about a build nobody can run.
 *
 * **With propagation on it asserts the same three statements inverted**: the sampled `inside` matches
 * the bench's own count of threads per request, the span accounts for the work its request did, and
 * the outside-every-span share has collapsed.
 *
 * Two checks belong to neither direction and hold in both, and they are the clock-independent ones —
 * counts against counts:
 *
 * 1. **Root calls are conserved.** The helpers ran exactly the chunks the drivers dispatched, no more
 *    and no fewer. Fan-out must move work, never invent or lose it, and a bench that quietly dropped
 *    a chunk would make every share below wrong in a way nothing else here would catch.
 * 2. **Fan-out actually happened**, by the bench's own stopwatch, in at least one configuration.
 *    Without that the rest is vacuous — a run where nothing was handed anywhere would pass every
 *    "the profiler cannot see it" check trivially, which is the shape of vacuous pass this project
 *    has already been caught by once.
 */
internal fun reportFanout(rows: List<FanoutRow>, propagate: Boolean, escape: Boolean): Boolean {
    var ok = true
    println("\n" + "=".repeat(118))
    println(
        "PHASE 5 - FAN-OUT, PROPAGATION " + (if (propagate) "ON" else "OFF") +
                (if (escape) ", ONE CHUNK PER REQUEST DELIBERATELY NOT JOINED" else "")
    )
    println("=".repeat(118))
    println(
        String.format(
            Locale.ROOT, "%9s %9s %12s %10s %10s %10s %12s %12s %9s %8s",
            "drivers", "helpers", "requests", "bench in", "inside", "working",
            "work/exec", "busy/exec", "outside", "stale"
        )
    )
    println("-".repeat(118))
    for (r in rows) {
        println(
            String.format(
                Locale.ROOT, "%9d %9d %,12d %10.2f %10.2f %10.2f %12s %12s %8.1f%% %7.2f%%",
                r.drivers, r.helpers, r.requests, r.benchInside, r.profilerInside, r.profilerWorking,
                duration(r.truthWorkPerExecNanos), duration(r.profilerBusyPerExecNanos),
                r.outsideShare * 100, r.staleShare * 100
            )
        )
    }
    println("-".repeat(118))
    println("bench in  is 1 + helper occupancy / request span, by the bench's own clock - the driver")
    println("            holds the context for its whole span, so it is one of the threads inside")
    println("inside    is inclusiveHits / instanceTicks, sampled; working is the running half of it")
    println("work/exec is the run's total work over its requests; busy/exec is what the span could see")
    println("outside   is labelled thread-time that fell under no coarse span at all")
    println("stale     is the share of time inside coarse executions that was inside a CLOSED one")

    // --- 1. conservation, exact and clock-independent --------------------------------
    println("\n  root calls: dispatched against executed")
    for (r in rows) {
        val bad = r.rootCalls != r.expectedRootCalls
        if (bad) ok = false
        println(
            String.format(
                Locale.ROOT, "    %d x %d   %,14d executed, %,14d dispatched   %s",
                r.drivers, r.helpers, r.rootCalls, r.expectedRootCalls,
                if (bad) "MISMATCH - fan-out lost or invented work" else "exact"
            )
        )
    }

    // --- 2. did fan-out happen at all --------------------------------------------------
    val fanned = rows.filter { it.fannedOut }
    if (fanned.isEmpty()) {
        println(
            String.format(
                Locale.ROOT,
                "%n  ! no configuration reached %.1f helper threads per request by the bench's own",
                MIN_FANOUT
            )
        )
        println("    stopwatch, so nothing below would mean anything and the checks are not run")
        println("\n  fan-out: FAILED")
        return false
    }

    // --- 3. the direction the switch selected -------------------------------------------
    if (escape) {
        println()
        println("  the fan-out numbers below are REPORTED AND NOT GATED while an escape is staged:")
        println("    the bench sums helper occupancy at the join, and an un-joined chunk is not there,")
        println("    so its own truth is short by exactly the thing being staged")
    }
    for (r in fanned) {
        println(
            String.format(
                Locale.ROOT,
                "%n    %d drivers x %d helpers, mean span %s, %.2f threads per request measured:",
                r.drivers, r.helpers, duration(r.meanSpanNanos), r.benchInside
            )
        )
        val good = if (propagate) checkPropagated(r) else checkEscaped(r)
        // Not gated when a chunk per request is deliberately un-joined, and this is not leniency.
        // The bench's own truth for `inside` is helper occupancy summed *at the join*, and an
        // un-joined chunk is by definition not there — so the bench under-reports the threads that
        // were in the request while the sampler counts them all. The gap is the staging, not the
        // instrument, and gating on it would report a fault the moment we staged a different one.
        if (!good && !escape) ok = false
    }

    // --- 4. the stale-context detector, asserted in both directions --------------------
    //
    // Both directions, because a check asserted only when it is supposed to fire would pass just as
    // happily if it were wired to nothing at all. That is the mistake --leakcheck exists to avoid
    // and the reasoning is identical here.
    println()
    println(
        if (escape) "  the stale-context detector, with one chunk per request left un-joined:"
        else "  the stale-context detector, with nothing staged - it must stay silent:"
    )
    for (r in rows) {
        val bad = if (escape) r.staleShare < STALE_MIN_STAGED_SHARE else r.staleShare > STALE_MAX_SHARE
        if (bad) ok = false
        println(
            String.format(
                Locale.ROOT, "    %d x %d   %.2f%% of coarse thread-time was inside a closed execution   %s",
                r.drivers, r.helpers, r.staleShare * 100,
                if (!bad) (if (escape) "detected" else "silent")
                else if (escape) "NOT DETECTED - the escape was staged and nothing saw it"
                else "UNEXPECTED - nothing was staged, so nothing should be stale"
            )
        )
    }

    // Reported and never gated. It is the honest answer rather than a defect: with as many drivers as
    // helpers there is nothing left to fan out to, so a request gets about one thread and whatever
    // parallelism the code could have had is invisible for want of a free one. That is the caveat
    // CoarseStat.working carries in its own documentation, observed.
    for (r in rows.filter { !it.fannedOut }) {
        println(
            String.format(
                Locale.ROOT,
                "%n  %d drivers x %d helpers reached only %.2f helper threads per request: the pool is",
                r.drivers, r.helpers, r.benchHelperThreads
            )
        )
        println("    saturated, so there was no fan-out to miss and nothing is asserted about it")
    }

    println()
    println(
        "  fan-out: " + when {
            !ok -> "FAILED"
            escape -> "OK - the detector sees work outliving the span that forked it"
            propagate -> "OK - the context crosses the thread and the span sees the work"
            else -> "OK - the defect reproduces against a measured truth"
        }
    )
    return ok
}

/** Propagation off: the three statements of the defect, as 5a established them. */
private fun checkEscaped(r: FanoutRow): Boolean {
    // Exactly 1.0, not approximately, and for the same structural reason the same-thread check uses
    // equality: with no propagation every occupied instance is occupied by the one thread that made
    // it, so the two counters move in lockstep. A tolerance would let a partly working propagation
    // look like no propagation at all.
    val insideBad = r.profilerInside != 1.0
    val workBad = !(r.workAccounted < ESCAPED_WORK_MAX)
    val outsideBad = r.outsideShare < OUTSIDE_MIN_SHARE
    println(
        String.format(
            Locale.ROOT, "      inside reports %.4f against the %.2f measured               %s",
            r.profilerInside, r.benchInside,
            if (insideBad) "UNEXPECTED - propagation is off, so only the driver should count"
            else "as expected"
        )
    )
    println(
        String.format(
            Locale.ROOT, "      the span accounts for %.1f%% of the work its request did     %s",
            r.workAccounted * 100,
            if (workBad) "UNEXPECTED - too much work is still inside the span" else "as expected"
        )
    )
    println(
        String.format(
            Locale.ROOT, "      %.1f%% of labelled thread-time is under no span at all       %s",
            r.outsideShare * 100,
            if (outsideBad) "UNEXPECTED - the escape is not visible" else "as expected"
        )
    )
    return !insideBad && !workBad && !outsideBad
}

/** Propagation on: the same three statements, inverted. */
private fun checkPropagated(r: FanoutRow): Boolean {
    val insideBad = abs(r.profilerInside / r.benchInside - 1.0) > INSIDE_TOLERANCE
    val workBad = abs(r.workAccounted - 1.0) > WORK_TOLERANCE
    val outsideBad = r.outsideShare > OUTSIDE_MAX_SHARE
    println(
        String.format(
            Locale.ROOT, "      inside reports %.2f against the %.2f measured, %+.1f%%         %s",
            r.profilerInside, r.benchInside, (r.profilerInside / r.benchInside - 1.0) * 100,
            if (insideBad) "OUT OF TOLERANCE" else "agrees"
        )
    )
    println(
        String.format(
            Locale.ROOT, "      the span accounts for %.1f%% of the work its request did     %s",
            r.workAccounted * 100,
            if (workBad) "OUT OF TOLERANCE - work is still escaping" else "agrees"
        )
    )
    println(
        String.format(
            Locale.ROOT, "      %.1f%% of labelled thread-time is under no span at all       %s",
            r.outsideShare * 100,
            if (outsideBad) "TOO HIGH - work is still escaping its context" else "collapsed"
        )
    )
    println(
        String.format(
            Locale.ROOT, "      working reports %.2f, so %.2f of the %.2f threads inside were parked",
            r.profilerWorking, r.profilerInside - r.profilerWorking, r.profilerInside
        )
    )
    return !insideBad && !workBad && !outsideBad
}

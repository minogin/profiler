package com.minogin.profiler.bench

import com.minogin.profiler.*
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Tolerance for the divergence of shares between the two truths, in percentage points.
 *
 * Set from measurement, not guesswork: across a 1/2/4/8/16-thread sweep the worst observed value
 * is 0.17 pp, so this leaves a factor of three for run-to-run variance and nothing more. The
 * first version allowed 1.0 pp and a real 0.67 pp defect passed it three times unnoticed.
 */
internal const val SHARE_TOLERANCE_PP = 0.5

/** How closely the fit is required to hit the configured duration on a single thread. */
private const val FIT_TOLERANCE = 0.03

/**
 * Tolerance for the divergence of an operation's duration — after the systematic shift is taken
 * out. The shift is common to all operations (under load they all get more expensive by the same
 * factor, their instruction mix is identical) and cancels out in the shares; the scatter around
 * it does not.
 *
 * Also set from measurement: worst observed across the thread sweep is 2.9%, so this is a little
 * over double. It used to be 12%, which let an 11% measurement artefact pass as healthy.
 */
internal const val DURATION_TOLERANCE = 0.06

/**
 * How much more expensive an operation may get under parallel load. The ceiling is high on
 * purpose. Two honest reasons an operation costs more with every thread busy: all-core clock is
 * below single-core boost, and on a hybrid chip some threads land on efficiency cores, which are
 * slower per clock. Both are properties of the machine, not a broken bench. Outside this range
 * it is something else.
 */
internal val LOAD_FACTOR_RANGE = 0.85..2.5

/** Four half-second slices. Two seconds of workload is tens of millions of root calls. */
private const val WARMUP_SLICE_MS = 500L
private const val WARMUP_SLICES = 4

/** How much throughput may still be climbing across the warm-up before the JIT is suspect. */
private const val CLIMB_EPS = 0.05

/** How many times the clock is probed during a measured run, spread evenly across it. */
private const val RUN_CLOCK_PROBES = 3

/** How long each observer-effect configuration runs. */
internal const val OBSERVER_SECONDS = 4

/** Rounds of the round-robin. Median per configuration, so drift cannot land on one of them. */
internal const val OBSERVER_ROUNDS = 3

/**
 * How large the hook may be relative to a mean operation before it stops being cheap.
 *
 * Gated on the direct measurement, not on a throughput comparison. Comparing whole-run throughput
 * between configurations cannot resolve this: the machine drifts by more than the effect, and the
 * same comparison has reported the hook at -13.84% and +3.55% on consecutive runs.
 */
internal const val OBSERVER_TOLERANCE = 0.10

/**
 * How far the two readings of the duty cycle — the OS accounting and the workers' own account of
 * their preemptions — may differ, in percentage points.
 *
 * From measurement, six configurations tabulated in findings.md: worst 0.76 pp at 4 working
 * threads (90.63% against 91.39%), 0.17 and 0.36 pp in starvation mode, and 0.06 pp on a
 * standalone probe. The OS reads the lower of the two every time, which is the expected direction —
 * the workers cannot see a preemption shorter than half a millisecond, so their figure is a lower
 * bound on stalling and an upper bound on the duty cycle.
 */
internal const val DUTY_TOLERANCE_PP = 1.5

/**
 * How far the sampled waiting share may sit from the workers' own stopwatches before the check
 * complains. Sampling error alone is a few tenths of a percent at these hit counts; the slack above
 * that is because the two do not cover exactly the same window.
 */
internal const val WAITING_TOLERANCE_PCT = 3.0

/** How long each sweep entry re-warms before its measured run. Everything is already compiled. */
internal const val SWEEP_REWARM_NANOS = 2_000_000_000L

fun main(args: Array<String>) {
    val opt = args.filter { it.startsWith("--") }
        .associate { val p = it.removePrefix("--").split("=", limit = 2); p[0] to p.getOrElse(1) { "true" } }
    val cores = Runtime.getRuntime().availableProcessors()

    // A sweep varies only the thread count, sharing one calibration and one set of iteration
    // counts across every entry. Giving each entry its own calibration would confound the thing
    // being varied with the thing being held fixed.
    val sweep = opt["sweep"]?.split(",")?.map { it.trim().toInt() }

    // Half the cores by default. Filling every core starves the sampler thread, the JIT compiler
    // threads during warm-up, and whatever else the machine is running, and the workers then get
    // preempted by all three. Throughput is not what this bench is for; accuracy is.
    val threads = opt["threads"]?.toInt() ?: maxOf(1, cores / 2)
    val activeThreads = opt["active"]?.toInt() ?: threads
    val seconds = opt["seconds"]?.toInt() ?: 60

    // Two separate switches on purpose. The hook's cost and the sampler thread's cost are
    // different questions: labels on with the sampler off measures the first alone, labels off
    // with the sampler on measures the second alone. Conflating them would answer neither.
    val labels = opt["labels"] != "off"
    // A sweep is about the bench, not the instrument. A spinning sampler would occupy a core and
    // change the very thing being varied.
    val sampling = sweep == null && opt["sampler"] != "off"
    val stepMillis = opt["step"]?.toDouble() ?: 1.0
    val verify = opt["verify"] != null

    // The coarse tier, off by default and a switch of its own. Its cost is an order of magnitude
    // above the fine hook's, so it must be measurable against its own absence — and leaving it off
    // by default keeps every figure already in findings.md comparable with a fresh run.
    val coarseLabels = opt["coarse"] != null

    // --fanout=<helpers>. Phase 5a: requests hand their chunks to a pool instead of running them
    // inline, so a coarse context has something to fail to cross. It runs two configurations rather
    // than one — one driver and many, against the same pool — because the second is not a bigger
    // version of the first: with as many drivers as helpers the pool saturates and each request gets
    // roughly one thread, so parallelism legitimately falls back to 1 and the defect goes quiet.
    // Seeing both is what stops the check being read as a statement about fan-out in general.
    val fanoutHelpers = opt["fanout"]?.toInt()

    // --propagate=off puts the fan-out run back to what 5a measured, so the phase 5 checks are an
    // A/B in one binary rather than a claim about a build that no longer exists. On by default,
    // because from 5b onwards the tool having propagation is the normal state of affairs.
    val propagate = opt["propagate"] != "off"
    require(fanoutHelpers == null || (coarseLabels && labels)) {
        "--fanout needs --coarse and labels: the context it is trying to cross is a coarse one"
    }


    // Whether each hit also records the owning thread's state. A switch for the same reason
    // --labels and --sampler are: a cost that is always on can only be priced by argument.
    val sampleState = opt["state"] != "off"

    // --lock=<hold micros>,<interval millis>. The one operation here that genuinely blocks, and
    // the only way anything in phase 3.5 can be checked against a known amount of waiting.
    val contended = opt["lock"]?.split(",")?.let { p ->
        ContendedLock(
            holdNanos = (p[0].trim().toDouble() * 1_000).toLong(),
            intervalNanos = (p.getOrElse(1) { "25" }.trim().toDouble() * 1_000_000).toLong(),
        )
    }

    require(activeThreads in 1..threads) { "--active=$activeThreads is outside 1..$threads" }

    // The sampler needs a core of its own. Spinning it is the only way to hold a 1 ms step under
    // load, and a spinning thread never yields — so with the sampler on, the workers can never
    // have every core. A genuinely saturated machine is only measurable with --sampler=off, and
    // that measurement is therefore always uninstrumented. Not a problem, but a real limit.
    //
    // --oversubscribe lifts it, and that is a mode rather than an escape hatch: with more runnable
    // threads than cores the sampler reads slots, not cores, so occupancy over-reads CPU by exactly
    // the oversubscription factor. It is the one configuration whose duty cycle can be predicted
    // from the configuration alone — roughly cores/threads — which makes it a truth to check
    // against rather than a hazard to avoid.
    val oversubscribe = opt["oversubscribe"] != null
    val maxThreads = if (oversubscribe) 1024 else if (sampling) cores - 1 else cores
    // The helpers count against the ceiling too. A fan-out run holds drivers *and* helpers at once,
    // and the largest configuration it runs is `threads` drivers against the whole pool — so the
    // number to check is their sum, not the driver count. Without this, --fanout=8 --threads=8 would
    // pass a guard written for 8 threads and then put 16 of them on the machine.
    for (n in sweep ?: listOf(threads + (fanoutHelpers ?: 0))) {
        require(n in 1..maxThreads) {
            (if (fanoutHelpers != null) "drivers + helpers = $threads + $fanoutHelpers " else "") +
                    "thread count $n is outside 1..$maxThreads " +
                    (if (sampling) "(the sampler needs one of the $cores cores; --sampler=off lifts this, " +
                            "--oversubscribe allows more threads than cores on purpose)"
                    else "($cores cores; --oversubscribe allows more on purpose)")
        }
    }

    // Before anything else: the demo must see a profiler nobody has touched, or the bench's own
    // twenty operations appear in its report carrying counts from the warm-up.
    if (opt["demo"] != null) {
        runApiDemo(threads, seconds)
        return
    }

    // What a cross-thread stack costs — the measurement that decides whether the tool may ever take
    // one to say *where* its unlabelled time went. Its own mode because it registers no operations
    // and needs no calibration beyond the busy loop.
    if (opt["stackcost"] != null) {
        runStackCost(victims = activeThreads)
        return
    }

    // The one thing `strict` still stops, provoked on purpose. Its own mode because it registers an
    // operation of its own, which would otherwise show up as a twenty-first line of the bench's
    // report carrying no samples.
    if (opt["leakcheck"] != null) {
        if (!checkLeakStopsTheSession()) exitProcess(1)
        return
    }

    println("=".repeat(96))
    println("PHASE 1 — BENCH + PHASE 2 — SAMPLER")
    if (sweep != null) {
        println("sweep over thread counts: ${sweep.joinToString(", ")}, $seconds s each")
    } else {
        println("threads: $threads, of them working: $activeThreads, run: $seconds s")
    }
    println("labels:  ${if (labels) "on" else "off"}${if (coarseLabels) ", coarse tier on" else ""}")
    println("sampler: ${if (sampling) "on, step $stepMillis ms" else "off"}")
    if (sampling && !labels) println("  (sampler with no labels: every sample lands on 'no operation' by design)")
    println("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, cores: $cores")
    if (activeThreads < threads) println("STARVATION MODE: ${threads - activeThreads} threads sit idle")
    if (oversubscribe) println("OVERSUBSCRIBED: $threads threads on $cores cores")
    contended?.let {
        println(
            String.format(
                Locale.ROOT,
                "CONTENDED LOCK: hold %.0f us every %.1f ms per thread — lock utilisation %.2f at %d threads%s",
                it.holdNanos / 1e3, it.intervalNanos / 1e6, it.utilisation(activeThreads), activeThreads,
                if (it.utilisation(activeThreads) >= 1.0) "  (over 1: the queue will run away, which is a mode too)" else ""
            )
        )
    }
    println("=".repeat(96))

    // --- Provisional calibration ----------------------------------------------------
    // The cost of a busy-loop iteration is not a constant: it is taken at runtime, and only
    // after a warm-up.
    registerOperations()
    if (coarseLabels) registerCoarseOperations(violate = opt["coarse"] == "violate")
    // After the catalogue, so it takes the id straight after the bench's twenty and none of the
    // truth machinery — which is indexed by operation id up to OP_COUNT — has to know about it.
    // Its truth is not the configured one anyway: it is whatever the workers measured.
    val lockOpId = if (contended != null) Profiler.register("lockedUpdate") else -1
    warmUpBurn(500)
    val provisional = calibrate()
    println("\n--- Busy-loop calibration (provisional, before the workload warm-up) ---")
    println("  $provisional")

    val workload = Workload(coarseLabels)
    workload.applyCalibration(provisional)

    // --- Workload warm-up ------------------------------------------------------------
    // Warm at the widest thread count in play, so every path is compiled before anything is
    // calibrated or measured.
    val warmThreads = sweep?.max() ?: threads
    val warmBench = Bench(warmThreads, warmThreads, labels, workload)
    warmBench.start()
    println("\n--- JIT warm-up ($warmThreads threads, $WARMUP_SLICES x $WARMUP_SLICE_MS ms) ---")
    val warmedUp = warmUp(warmBench)
    warmBench.stop()

    // --- Final calibration ----------------------------------------------------------
    val cal = calibrate()
    println("\n--- Busy-loop calibration (final, everything hot) ---")
    println("  $cal")
    println(
        String.format(
            Locale.ROOT, "  drift of the iteration cost against the provisional one: %+.2f%%",
            (cal.nsPerIter / provisional.nsPerIter - 1.0) * 100
        )
    )
    workload.applyCalibration(cal)
    // The critical section is filled with the same busy loop as everything else, so its length
    // rides on the same calibration. It is not fitted at its own working point the way the
    // catalogue is — a millisecond-scale hold does not need to be accurate to a nanosecond, and
    // the workers time what it actually came to anyway.
    contended?.holdIters = cal.itersFor(contended.holdNanos.toDouble())

    // The very first check — did the JIT optimise the busy loop away? If the cost per iteration
    // is physically impossible, everything else is already garbage and not worth computing.
    if (cal.nsPerIter < MIN_PLAUSIBLE_NS_PER_ITER) {
        println(
            String.format(
                Locale.ROOT,
                "\nBENCH IS BROKEN: %.4f ns/iteration is physically impossible (< %.1f) — the JIT collapsed the busy loop",
                cal.nsPerIter, MIN_PLAUSIBLE_NS_PER_ITER
            )
        )
        exitProcess(1)
    }
    if (cal.r2 < 0.99) {
        println(String.format(Locale.ROOT, "\nBENCH IS BROKEN: calibration is non-linear, R2 = %.5f", cal.r2))
        exitProcess(1)
    }
    val minSelf = OPS.minOf { it.selfNanos }
    if (cal.minNanos > minSelf) {
        println(
            String.format(
                Locale.ROOT,
                "\nBENCH IS BROKEN: the shortest reachable duration %.2f ns exceeds the shortest operation %.0f ns",
                cal.minNanos, minSelf
            )
        )
        exitProcess(1)
    }

    // --- Fitting the iteration counts to the configured durations --------------------
    // The linear model is not enough here; we settle it by measuring at each operation's own
    // working point.
    println("\n--- Fitting the iteration counts (single thread, quiet machine) ---")
    println(
        String.format(
            Locale.ROOT, "  %-14s %9s %8s %12s %9s",
            "operation", "target,ns", "iters", "achieved", "error"
        )
    )
    var worstFitErr = 0.0
    var worstFitOp = 0
    for (id in 0 until OP_COUNT) {
        val fit = refineIters(OPS[id].selfNanos, cal.itersFor(OPS[id].selfNanos))
        workload.iters[id] = fit.iters
        val err = abs(fit.nanos / OPS[id].selfNanos - 1.0)
        if (err > worstFitErr) {
            worstFitErr = err
            worstFitOp = id
        }
        println(
            String.format(
                Locale.ROOT, "  %-14s %9.0f %8d %11.2fn %8.2f%%",
                OPS[id].name, OPS[id].selfNanos, fit.iters, fit.nanos,
                (fit.nanos / OPS[id].selfNanos - 1.0) * 100
            )
        )
    }
    if (worstFitErr > FIT_TOLERANCE) {
        println(
            String.format(
                Locale.ROOT, "\nBENCH IS BROKEN: the fit did not converge, %s misses by %.2f%%",
                OPS[worstFitOp].name, worstFitErr * 100
            )
        )
        exitProcess(1)
    }

    FIT_CLOCK = clockNow()
    println(String.format(Locale.ROOT, "  clock at fit time: %.4f ns/iteration", FIT_CLOCK))

    if (opt["hook"] != null) {
        runHookAnalysis(threads, workload)
        println("\n(sink: ${Sink.value})")
        return
    }

    val ok = if (verify) {
        runVerify(threads, workload)
    } else if (sweep != null) {
        runSweep(sweep, labels, workload, seconds, warmedUp)
    } else if (fanoutHelpers != null) {
        runFanout(fanoutHelpers, threads, labels, workload, seconds, stepMillis, sampleState, propagate)
    } else {
        val bench = Bench(threads, activeThreads, labels, workload, contended, lockOpId)
        bench.start()
        println("\n--- Run of $seconds s ---")
        // The sampler covers the measured run only, never the warm-up.
        val sampler = if (sampling)
            Sampler((stepMillis * 1_000_000).toLong(), sampleState = sampleState)
        else null
        val outcome = measureOnce(bench, seconds, sampler)
        printDetail(bench, outcome, sampler, stepMillis)
        // A, B and C side by side is the point of the whole exercise, so it belongs in the
        // ordinary run rather than only inside --verify.
        if (sampler != null) printComparisonDetail(compare(Cell(stepMillis, seconds), outcome, sampler))
        // Before printVerdict, so a coarse failure is part of the verdict rather than a note beside
        // it. Only with both switches on: the coarse numbers need labels to be placed and a sampler
        // to have read them.
        val coarseOk = if (coarseLabels && sampler != null && labels) checkCoarse(bench, outcome, sampler, stepMillis) else true
        val good = printVerdict(outcome, warmedUp) && coarseOk
        bench.stop()
        good
    }

    println("\n(sink: ${Sink.value})")
    if (!ok) exitProcess(1)
}

/** Everything one thread count produced. Computed once, printed by whoever wants it. */
internal class Outcome(
    val threads: Int,
    val runNanos: Long,
    val rootCalls: Long,
    val totalCalls: LongArray,
    val measuredSelf: DoubleArray,
    val measuredIncl: DoubleArray,
    val shareA: DoubleArray,
    val shareB: DoubleArray,
    val totA: Double,
    val totB: Double,
    val loadFactor: Double,
    val maxShareDiff: Double,
    val worstShare: Int,
    val maxScatter: Double,
    val worstScatter: Int,
    /** Scatter per operation, not only the worst. Kept so a run can say whether one operation blew
     *  up or the whole set drifted - those have different causes, and the maximum alone hides which. */
    val scatter: DoubleArray = DoubleArray(OP_COUNT),
    val clockDuringRun: DoubleArray,
    /** Calls per operation as counted by the hook itself, for cross-checking the graph expansion. */
    val hookCalls: LongArray,
    /** Garbage collections during the measured run, and the time they took. See checkCoarse. */
    val gcCollections: Long = 0,
    val gcMillis: Long = 0,
    /** Span statistics as they stood when the sampler stopped, before the batch measurement. */
    val coarseTotals: Array<CoarseAgg?> = arrayOfNulls(MAX_COARSE_TYPES),
) {
    val throughput: Double get() = rootCalls / (runNanos / 1e9)
    val ok: Boolean
        get() = maxShareDiff <= SHARE_TOLERANCE_PP &&
                maxScatter <= DURATION_TOLERANCE &&
                loadFactor in LOAD_FACTOR_RANGE
}

/** Runs the bench once at its configured thread count and computes both truths. */
internal fun measureOnce(bench: Bench, seconds: Int, sampler: Sampler?): Outcome {
    bench.resetCounters()
    // Started here, not by the caller: the sampler must cover the measured run and nothing else.
    sampler?.start()

    // Probed from the main thread while the workers are actually running, spread across the run.
    // Probing before or after would measure an idle machine, which is not the clock the run got —
    // and the gap between the fit's clock and the run's clock is the whole question.
    val durationNanos = seconds * 1_000_000_000L
    // Across the measured run only. The coarse tier allocates a context per execution and never
    // recycles one, so how much garbage that is and what it costs has to be a number rather than an
    // assurance — see checkCoarse, and findings.md.
    val gcBefore = gcTotals()
    val startedAt = bench.runStart(durationNanos)
    val clockDuringRun = DoubleArray(RUN_CLOCK_PROBES)
    for (i in 0 until RUN_CLOCK_PROBES) {
        val at = startedAt + durationNanos * (2L * i + 1) / (2L * RUN_CLOCK_PROBES)
        LockSupport.parkNanos(at - System.nanoTime())
        clockDuringRun[i] = clockNow()
    }
    val runNanos = bench.runAwait(startedAt)
    val gcAfter = gcTotals()
    sampler?.shutdown()

    val active = bench.workers.filter { it.active }

    // Snapshot before the batch measurement. That stage runs execLabeled tens of thousands of
    // times to time the labelled path, and every one of those calls increments the same counters.
    // Reading afterwards counted the measurement as if it were the workload.
    // Helper slots included: in fan-out mode nearly every labelled call happens on one of them, and
    // a cross-check that ignored them would compare the graph's expansion against a fraction of the
    // hook's own count and call the difference a defect.
    val countingSlots = active.map { it.slot } + (bench.fanout?.slots() ?: emptyList())
    val hookCalls = LongArray(OP_COUNT) { id -> countingSlots.sumOf { it.countOf(id) } }
    // And the coarse aggregates for the same reason and more urgently: the batch measurement calls
    // execLabeled tens of thousands of times, so with the coarse tier on it records tens of
    // thousands of spans that belong to no request and are outside the sampled window entirely.
    val coarseTotals = Profiler.coarseTotals()

    bench.measure()
    val measuredIncl = DoubleArray(OP_COUNT) { id -> active.map { it.measuredInclusive[id] }.average() }

    // Summed per thread, not averaged across them. Threads do not run at the same speed — on a
    // hybrid chip some sit on efficiency cores — so a thread's own calls have to meet that
    // thread's own measured durations. Averaging first is only valid while every thread runs the
    // same mix, which is true today and is exactly the kind of assumption that breaks silently.
    //
    // Truth A needs no such treatment: configured durations do not depend on which thread ran the
    // operation, so summing per thread gives the same number as multiplying the totals.
    val subtree = subtreeCounts()
    val totalCalls = LongArray(OP_COUNT)
    val selfB = DoubleArray(OP_COUNT)
    // Every thread that ran root calls, each paired with the durations it measured itself. In
    // fan-out mode the calls are on the helpers and the drivers contribute nothing but zeroes, so
    // the pairing is what keeps truth B a per-thread quantity rather than an average over threads
    // that were doing different things.
    val counted = active.map { it.rootCalls to it.measuredSelf } +
            (bench.fanout?.let { f -> f.rootCalls().zip(f.measuredSelf()) } ?: emptyList())
    for ((rootCallsHere, selfHere) in counted) {
        val callsHere = expandCalls(rootCallsHere, subtree)
        for (op in 0 until OP_COUNT) {
            totalCalls[op] += callsHere[op]
            selfB[op] += callsHere[op] * selfHere[op]
        }
    }


    val selfA = DoubleArray(OP_COUNT) { totalCalls[it] * OPS[it].selfNanos }
    // Call-weighted mean duration — the value truth B actually used, not a flat average.
    val measuredSelf = DoubleArray(OP_COUNT) { if (totalCalls[it] == 0L) 0.0 else selfB[it] / totalCalls[it] }
    val totA = selfA.sum()
    val totB = selfB.sum()
    val shareA = DoubleArray(OP_COUNT) { selfA[it] / totA }
    val shareB = DoubleArray(OP_COUNT) { selfB[it] / totB }

    val worstShare = (0 until OP_COUNT).maxByOrNull { abs(shareB[it] - shareA[it]) }!!

    // The systematic shift: how much more everything costs under load than on a quiet machine.
    // It is common to all operations and cancels in the shares, so it does not touch the truth.
    val ratios = DoubleArray(OP_COUNT) { measuredSelf[it] / OPS[it].selfNanos }
    val loadFactor = ratios.sorted().let { (it[OP_COUNT / 2 - 1] + it[OP_COUNT / 2]) / 2 }
    val scatter = DoubleArray(OP_COUNT) { abs(ratios[it] / loadFactor - 1.0) }
    val worstScatter = (0 until OP_COUNT).maxByOrNull { scatter[it] }!!


    return Outcome(
        threads = bench.activeThreads,
        runNanos = runNanos,
        rootCalls = bench.totalRootCalls(),
        totalCalls = totalCalls,
        measuredSelf = measuredSelf,
        measuredIncl = measuredIncl,
        shareA = shareA,
        shareB = shareB,
        totA = totA,
        totB = totB,
        loadFactor = loadFactor,
        maxShareDiff = abs(shareB[worstShare] - shareA[worstShare]) * 100,
        worstShare = worstShare,
        maxScatter = scatter[worstScatter],
        worstScatter = worstScatter,
        scatter = scatter,
        clockDuringRun = clockDuringRun,
        hookCalls = hookCalls,
        coarseTotals = coarseTotals,
        gcCollections = gcAfter.first - gcBefore.first,
        gcMillis = gcAfter.second - gcBefore.second,
    )
}

/**
 * Phase 5a: the same pool of helpers driven by one request at a time and by many.
 *
 * Two configurations rather than one, because the second is not more of the first. With one driver
 * the pool is free and a request really does get several threads; with as many drivers as helpers
 * the pool is saturated and each request gets about one, so the parallelism the code could have had
 * cannot be observed at all. Both are true statements about the same code, and printing only the
 * first would read as a claim that fan-out always shows up.
 *
 * The sampler stays **on**, unlike [runSweep]. That sweep varies the bench and a spinning sampler
 * would occupy a core and change what is being varied; this one is a measurement *of* the sampler,
 * so there is nothing to hold apart. One calibration and one warm-up across both entries, for the
 * same reason the thread sweep shares them.
 */
private fun runFanout(
    helpers: Int,
    drivers: Int,
    labels: Boolean,
    workload: Workload,
    seconds: Int,
    stepMillis: Double,
    sampleState: Boolean,
    propagate: Boolean,
): Boolean {
    val configs = listOf(1, drivers).distinct()
    val rows = ArrayList<FanoutRow>()
    for (d in configs) {
        println("\n--- Fan-out: $d driver(s) x $helpers helpers, $seconds s ---")
        val bench = Bench(d, d, labels, workload, helpers = helpers, propagate = propagate)
        bench.start()
        // The helpers are threads the JIT has never seen run this code. Without a re-warm the first
        // entry would measure compilation and the second would not, and the difference would be
        // read as the thing being varied.
        bench.run(SWEEP_REWARM_NANOS)
        val sampler = Sampler((stepMillis * 1_000_000).toLong(), sampleState = sampleState)
        val outcome = measureOnce(bench, seconds, sampler)
        println(
            String.format(
                Locale.ROOT, "  %,.0f root calls/s, price x%.3f, scatter %.2f%%",
                outcome.throughput, outcome.loadFactor, outcome.maxScatter * 100
            )
        )
        rows.add(fanoutRow(bench, outcome, sampler, stepMillis))
        // Before the next entry starts: an open context would be billed across the boundary, and a
        // driver that leaked one is a defect this phase must not be allowed to introduce quietly.
        if (!Profiler.expectBalanced()) println("  ! a label was left open at the end of this entry")
        bench.stop()
    }
    return reportFanout(rows, propagate)
}

/**
 * Varies only the thread count. One calibration, one set of iteration counts, one warm-up —
 * everything else held fixed, so the summary column really is a function of the thread count.
 */
private fun runSweep(
    counts: List<Int>,
    labels: Boolean,
    workload: Workload,
    seconds: Int,
    warmedUp: Boolean,
): Boolean {
    val outcomes = ArrayList<Outcome>()
    for (n in counts) {
        println("\n--- Sweep entry: $n threads, $seconds s ---")
        val bench = Bench(n, n, labels, workload)
        bench.start()
        bench.run(SWEEP_REWARM_NANOS)
        val outcome = measureOnce(bench, seconds, null)
        bench.stop()
        outcomes.add(outcome)
        println(
            String.format(
                Locale.ROOT, "  %,.0f root calls/s, price x%.3f, scatter %.2f%%, A-B %.3f pp — %s",
                outcome.throughput, outcome.loadFactor, outcome.maxScatter * 100,
                outcome.maxShareDiff, if (outcome.ok) "ok" else "FAIL"
            )
        )
    }

    println("\n" + "=".repeat(96))
    println("SWEEP OVER THREAD COUNTS")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "%8s %16s %9s %10s %-14s %10s %-14s %7s",
            "threads", "root calls/s", "price", "scatter", "worst", "A-B,pp", "worst", "verdict"
        )
    )
    println("-".repeat(96))
    for (o in outcomes) {
        println(
            String.format(
                Locale.ROOT, "%8d %,16.0f %8.3fx %9.2f%% %-14s %10.3f %-14s %7s",
                o.threads, o.throughput, o.loadFactor,
                o.maxScatter * 100, OPS[o.worstScatter].name,
                o.maxShareDiff, OPS[o.worstShare].name,
                if (o.ok) "ok" else "FAIL"
            )
        )
    }
    println("-".repeat(96))
    println(
        String.format(
            Locale.ROOT, "tolerances: scatter %.0f%%, A-B %.1f pp, price %.2f..%.2f",
            DURATION_TOLERANCE * 100, SHARE_TOLERANCE_PP,
            LOAD_FACTOR_RANGE.start, LOAD_FACTOR_RANGE.endInclusive
        )
    )
    println("counts ran in ascending order, so thermal drift is confounded with the thread count")
    if (!warmedUp) println("WARNING: throughput was still climbing at the end of the warm-up")

    val allOk = outcomes.all { it.ok } && warmedUp
    println(if (allOk) "\nEvery thread count holds. The bench is sound." else "\nAt least one thread count fails. The bench is not sound.")
    return allOk
}

/**
 * The one fatal condition, staged on purpose — because a check that never fires is not a check.
 *
 * A below-floor label used to be what `strict` stopped, and the bench tested that simply by
 * running: four of its twenty operations sit under the floor by design. Now that this is the only
 * fatal condition left, and nothing here leaks a label by accident, it has to be staged.
 *
 * Both directions, since "a leak stops the session" is only half the claim. The other half is that
 * it stops *only* under strict, and a check that asserted the first alone would still pass if the
 * flag had been wired to nothing at all.
 */
private fun checkLeakStopsTheSession(): Boolean {
    val id = Profiler.register("leakedOnPurpose")
    println("--- Leak check: does a label left open stop the session? ---")
    var ok = true

    for (strict in listOf(true, false)) {
        Profiler.start(stepMillis = 1.0, strict = strict)
        // On a thread of its own. The leak lives in one slot, and this thread's slot has to be
        // clean going into the second half or it would inherit the first half's mess.
        val leaker = Thread {
            Profiler.enter(id)
            if (Profiler.expectBalanced()) {
                println("  FAIL: a leaked label reported itself balanced")
                ok = false
            }
        }
        leaker.start()
        leaker.join()
        val report = Profiler.stop()

        val stopped = report.failure != null
        val named = report.failure?.contains("leakedOnPurpose") == true
        // One leak, one session, one count. This read `round + 1` while the counter ran for the
        // life of the process, and kept reading it for twenty minutes after that stopped being
        // true — long enough for every run to print THE LADDER IS BROKEN. Nothing caught it,
        // because the assertions also live in RegistryTest and the suite was green while this mode
        // was not: a check that only runs when somebody types the flag only runs that often.
        val counted = report.imbalances == 1
        val good = stopped == strict && (!strict || named) && counted
        if (!good) ok = false
        println(
            String.format(
                Locale.ROOT, "  strict=%-5s  stopped: %-5s (expected %-5s)  named the operation: %-5s  " +
                        "imbalances: %d  %s",
                strict, stopped, strict, named, report.imbalances, if (good) "ok" else "FAIL"
            )
        )
        report.failure?.let { println("    $it") }
    }
    println(if (ok) "  the ladder is real: one rung, and it fires" else "  THE LADDER IS BROKEN")
    return ok
}

/**
 * Everything a third party has to do to use this, and nothing else. No bench, no truth, no
 * calibration — register some names, wrap some code, start, stop, print.
 *
 * This exists to be copied. It is also the only test that the public surface works without the
 * bench's machinery propping it up.
 */
private fun runApiDemo(threads: Int, seconds: Int) {
    // 1. Register at startup. Idempotent, so a `val` in an object works; keep the id.
    val parse = Profiler.register("parseRecord")
    val validate = Profiler.register("validateRecord")
    val index = Profiler.register("indexRecord")

    println("profiling $threads threads for $seconds s\n")

    // Read from an array, not written as literals. With constant trip counts the JIT unrolls all
    // three loops, inlines them into one body, and interleaves them freely — and opaque access
    // does not prevent that. It stops the label writes being eliminated or reordered against each
    // other; it creates no ordering with anything else. Written as literals this demo reported
    // indexRecord at 0.46% against 8.7% by construction, because the work had been shuffled across
    // the boundaries its labels claimed.
    val work = intArrayOf(40, 120, 15)

    // 2. Start sampling. Strict off, deliberately: indexRecord is fifteen busy-loop iterations,
    // about 12 ns, which is below the floor — and this demo is the very place that hazard was
    // found, since it was here that C2 shuffled work across three adjacent short labels and the
    // shortest one lost 95% of itself. With strict on, the profiler would refuse to report it,
    // which is the correct behaviour and would leave nothing to demonstrate.
    Profiler.start(stepMillis = 1.0, strict = false)

    // A fourth operation placed the other way, for code that cannot be wrapped in a block. One
    // worker leaks it on purpose, every thousandth pass, to show what the balance check is for.
    val flush = Profiler.register("flushBatch")

    // 2b. A coarse label, in an id space of its own. It goes around a *batch* of passes and not
    // around one, and that is the tier boundary doing its job rather than an arbitrary choice: one
    // pass here is about 700 ns, and a context costs tens of nanoseconds to allocate and stamp. The
    // rule is d >= max(800 ns, 4 us x share), so a single pass cannot carry one. A batch can, and
    // then the report can say how long a request took, which no fine label ever can.
    val request = Profiler.registerCoarse("request")

    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    val workers = List(threads) { n ->
        Thread {
            var s = (n + 1).toLong()
            var pass = 0L
            try {
                while (System.nanoTime() < deadline) {
                    // 3. One request. The batch size varies from pass to pass so that p50, p90 and
                    // p99 are three different numbers rather than three copies of one — a
                    // percentile over a distribution with no spread describes nothing.
                    val batch = 64 + ((pass.toInt() * 37) and 255)
                    coarse(request) {
                        repeat(batch) {
                            // 3a. Wrap the work. Nesting is fine; the innermost label wins.
                            s = op(parse) { burn(s, work[0]) }
                            s = op(validate) { burn(s, work[1]) }
                            s = op(index) { burn(s, work[2]) }

                            // 3b. The non-lexical form, for a boundary that is not a block — a
                            // listener, a before/after callback, a span across several methods. It
                            // has no `finally`, which is the whole risk: thread 0 here "throws"
                            // every thousandth pass and never calls exit, exactly as a Calcite rule
                            // that throws would.
                            Profiler.enter(flush)
                            s = burn(s, work[1])
                            val leak = n == 0 && (++pass % 1000L) == 0L
                            if (!leak) Profiler.exit()
                        }
                    }

                    // 3c. And the check, at a point this thread is known to be quiescent — which
                    // now means *between* requests. Inside one, the coarse context is legitimately
                    // open and the check would report every request as a leak.
                    Profiler.expectBalanced()
                }
            } finally {
                Sink.consume(s)
                // 4. Release on thread exit, so a dead thread stops reading as an idle one.
                Profiler.release()
            }
        }.also { it.start() }
    }
    workers.forEach { it.join() }

    // 5. Stop and read.
    println(Profiler.stop().render())

    // flushBatch burns the same amount as validateRecord, so by construction the two do equal
    // shares of the busy-loop work. They do not read equal, and the difference is the point.
    val total = work.sum() + work[1]
    val ratio = work[1].toDouble() / total
    println(
        String.format(
            Locale.ROOT,
            "%nby construction validateRecord and flushBatch each do %.1f%% of the busy-loop work.",
            ratio * 100
        )
    )
    println("validateRecord should land there. flushBatch reads high, and that is the injected leak:")
    println("one worker enters it and never exits every thousandth pass, so everything that thread did")
    println("afterwards was billed to flushBatch until the next check. The balance check counts it; the")
    println("share does not, which is why a leaked label looks like a finding rather than like an error.")
}

/**
 * What the hook costs, read two independent ways: timed alone, and as the difference between a
 * leaf operation with and without it, across every duration the bench has.
 *
 * If the two agree the hook cost is known. Where they stop agreeing marks the operation length
 * below which a differential measurement still resolves it — above that, the hook is a fraction
 * of a percent of what is being timed and the subtraction is all noise.
 */
private fun runHookAnalysis(threads: Int, workload: Workload) {
    val bench = Bench(threads, threads, true, workload)
    bench.start()
    bench.run(SWEEP_REWARM_NANOS)
    bench.measureHook()
    val active = bench.workers.filter { it.active }

    val direct = active.map { it.hookDirect }.average()
    val directSpread = active.map { it.hookDirect }

    println("\n" + "=".repeat(96))
    println("HOOK COST — measured directly, and by difference")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "  1. the hook alone: %.3f ns per call  (per thread %.3f .. %.3f)",
            direct, directSpread.min(), directSpread.max()
        )
    )
    println("     ThreadLocal lookup + opaque read + opaque write + counter + opaque restore\n")

    println(
        String.format(
            Locale.ROOT, "  2/3. by difference, leaf operations only, same loop, one hook per call\n" +
                    "  %-14s %9s %9s %9s %9s %9s %9s",
            "operation", "target", "plain", "hooked", "diff", "vs direct", "of op"
        )
    )
    println("  " + "-".repeat(76))
    for (id in 0 until OP_COUNT) {
        if (OPS[id].children.isNotEmpty()) continue
        val plain = active.map { it.opPlain[id] }.average()
        val hooked = active.map { it.opHooked[id] }.average()
        val diff = hooked - plain
        println(
            String.format(
                Locale.ROOT, "  %-14s %8.0fn %8.2fn %8.2fn %+8.2fn %8.2fx %8.1f%%",
                OPS[id].name, OPS[id].selfNanos, plain, hooked, diff, diff / direct, diff / plain * 100
            )
        )
    }
    println("  " + "-".repeat(76))
    println("  'vs direct' is the differential divided by the direct reading — 1.00x means they agree")
    println(
        String.format(
            Locale.ROOT, "%n  against a call-weighted mean operation of %.0f ns, the hook is %.2f%%",
            meanOperationNanos(), direct / meanOperationNanos() * 100
        )
    )
    bench.stop()
}

/** One (sampler step, run duration) point of the phase 3 matrix. */
private fun clockNow(): Double = measureBurn(1024, trials = 3) / 1024.0

/**
 * JIT warm-up, and only that.
 *
 * C2 compiles after something like 10^4 invocations, and the bench does tens of millions of root
 * calls a second, so this is settled in well under a second — the slices below are generous by
 * orders of magnitude. Nor is there deoptimisation to wait out: one xor-shift loop, no
 * polymorphism, nothing loaded after startup, and the single branch in the hot loop is constant
 * for the whole run.
 *
 * The check is deliberately one-directional. Throughput still climbing means the JIT is not
 * finished and waiting helps. Throughput drifting *down* means the clock is falling under
 * sustained load, and no amount of waiting fixes that — it is measured, not waited out. The
 * previous version demanded stability in both directions and so kept hunting for a plateau that
 * a thermally drifting machine can never provide.
 */
private fun warmUp(bench: Bench): Boolean {
    val rates = DoubleArray(WARMUP_SLICES)
    for (i in 0 until WARMUP_SLICES) {
        val before = bench.totalRootCalls()
        val nanos = bench.run(WARMUP_SLICE_MS * 1_000_000)
        rates[i] = (bench.totalRootCalls() - before) / (nanos / 1e9)
        println(String.format(Locale.ROOT, "  slice %d: %,12.0f root calls/s", i + 1, rates[i]))
    }
    val half = WARMUP_SLICES / 2
    val early = rates.take(half).average()
    val late = rates.drop(half).average()
    val climb = late / early - 1.0
    val settled = climb <= CLIMB_EPS
    println(
        String.format(
            Locale.ROOT, "  second half %+.2f%% against the first — %s",
            climb * 100,
            if (settled) "settled" else "STILL CLIMBING, the JIT is not done"
        )
    )
    return settled
}

/**
 * The clock the iteration fit was taken at, in ns per busy-loop iteration. Recorded once, right
 * after the fit, so every later phase can be compared against the conditions the durations were
 * chosen under.
 */
internal var FIT_CLOCK: Double = Double.NaN


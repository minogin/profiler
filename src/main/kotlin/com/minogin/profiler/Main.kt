package com.minogin.profiler

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
private const val SHARE_TOLERANCE_PP = 0.5

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
private const val DURATION_TOLERANCE = 0.06

/**
 * How much more expensive an operation may get under parallel load. The ceiling is high on
 * purpose. Two honest reasons an operation costs more with every thread busy: all-core clock is
 * below single-core boost, and on a hybrid chip some threads land on efficiency cores, which are
 * slower per clock. Both are properties of the machine, not a broken bench. Outside this range
 * it is something else.
 */
private val LOAD_FACTOR_RANGE = 0.85..2.5

/** Four half-second slices. Two seconds of workload is tens of millions of root calls. */
private const val WARMUP_SLICE_MS = 500L
private const val WARMUP_SLICES = 4

/** How much throughput may still be climbing across the warm-up before the JIT is suspect. */
private const val CLIMB_EPS = 0.05

/** How many times the clock is probed during a measured run, spread evenly across it. */
private const val RUN_CLOCK_PROBES = 3

/** How long each observer-effect configuration runs. */
private const val OBSERVER_SECONDS = 4

/** Rounds of the round-robin. Median per configuration, so drift cannot land on one of them. */
private const val OBSERVER_ROUNDS = 3

/**
 * How large the hook may be relative to a mean operation before it stops being cheap.
 *
 * Gated on the direct measurement, not on a throughput comparison. Comparing whole-run throughput
 * between configurations cannot resolve this: the machine drifts by more than the effect, and the
 * same comparison has reported the hook at -13.84% and +3.55% on consecutive runs.
 */
private const val OBSERVER_TOLERANCE = 0.10

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
private const val DUTY_TOLERANCE_PP = 1.5

/**
 * How far the sampled waiting share may sit from the workers' own stopwatches before the check
 * complains. Sampling error alone is a few tenths of a percent at these hit counts; the slack above
 * that is because the two do not cover exactly the same window.
 */
private const val WAITING_TOLERANCE_PCT = 3.0

/** How long each sweep entry re-warms before its measured run. Everything is already compiled. */
private const val SWEEP_REWARM_NANOS = 2_000_000_000L

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
    val wait = WaitStrategy.valueOf((opt["wait"] ?: "spin").uppercase())
    val jitter = if (opt["jitter"] == "off") 0.0 else 0.25
    val verify = opt["verify"] != null

    // Now that a below-floor label only warns, strict governs one thing: whether a leaked label
    // stops the session. This bench never leaks one, so the flag changes nothing here and the
    // stopping is tested where it can be provoked on purpose — see [checkLeakStopsTheSession].
    val strict = opt["strict"] != null

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
    for (n in sweep ?: listOf(threads)) {
        require(n in 1..maxThreads) {
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
    println("labels:  ${if (labels) "on" else "off"}")
    println("sampler: ${if (sampling) "on, step $stepMillis ms, wait $wait" else "off"}")
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
    // After the catalogue, so it takes the id straight after the bench's twenty and none of the
    // truth machinery — which is indexed by operation id up to OP_COUNT — has to know about it.
    // Its truth is not the configured one anyway: it is whatever the workers measured.
    val lockOpId = if (contended != null) Profiler.register("lockedUpdate") else -1
    warmUpBurn(500)
    val provisional = calibrate()
    println("\n--- Busy-loop calibration (provisional, before the workload warm-up) ---")
    println("  $provisional")

    val workload = Workload()
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
        runVerify(threads, workload, wait, jitter)
    } else if (sweep != null) {
        runSweep(sweep, labels, workload, seconds, warmedUp)
    } else {
        val bench = Bench(threads, activeThreads, labels, workload, contended, lockOpId)
        bench.start()
        println("\n--- Run of $seconds s ---")
        // The sampler covers the measured run only, never the warm-up.
        val sampler = if (sampling)
            Sampler((stepMillis * 1_000_000).toLong(), wait, jitter, strict = strict, sampleState = sampleState)
        else null
        val outcome = measureOnce(bench, seconds, sampler)
        printDetail(bench, outcome, sampler, stepMillis)
        // A, B and C side by side is the point of the whole exercise, so it belongs in the
        // ordinary run rather than only inside --verify.
        if (sampler != null) printComparisonDetail(compare(Cell(stepMillis, seconds), outcome, sampler))
        val good = printVerdict(outcome, warmedUp)
        bench.stop()
        good
    }

    println("\n(sink: ${Sink.value})")
    if (!ok) exitProcess(1)
}

/** Everything one thread count produced. Computed once, printed by whoever wants it. */
private class Outcome(
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
    val clockDuringRun: DoubleArray,
    /** Calls per operation as counted by the hook itself, for cross-checking the graph expansion. */
    val hookCalls: LongArray,
) {
    val throughput: Double get() = rootCalls / (runNanos / 1e9)
    val ok: Boolean
        get() = maxShareDiff <= SHARE_TOLERANCE_PP &&
                maxScatter <= DURATION_TOLERANCE &&
                loadFactor in LOAD_FACTOR_RANGE
}

/** Runs the bench once at its configured thread count and computes both truths. */
private fun measureOnce(bench: Bench, seconds: Int, sampler: Sampler?): Outcome {
    bench.resetCounters()
    // Started here, not by the caller: the sampler must cover the measured run and nothing else.
    sampler?.start()

    // Probed from the main thread while the workers are actually running, spread across the run.
    // Probing before or after would measure an idle machine, which is not the clock the run got —
    // and the gap between the fit's clock and the run's clock is the whole question.
    val durationNanos = seconds * 1_000_000_000L
    val startedAt = bench.runStart(durationNanos)
    val clockDuringRun = DoubleArray(RUN_CLOCK_PROBES)
    for (i in 0 until RUN_CLOCK_PROBES) {
        val at = startedAt + durationNanos * (2L * i + 1) / (2L * RUN_CLOCK_PROBES)
        LockSupport.parkNanos(at - System.nanoTime())
        clockDuringRun[i] = clockNow()
    }
    val runNanos = bench.runAwait(startedAt)
    sampler?.shutdown()

    val active = bench.workers.filter { it.active }

    // Snapshot before the batch measurement. That stage runs execLabeled tens of thousands of
    // times to time the labelled path, and every one of those calls increments the same counters.
    // Reading afterwards counted the measurement as if it were the workload.
    val hookCalls = LongArray(OP_COUNT) { id -> active.sumOf { it.slot.countOf(id) } }

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
    for (w in active) {
        val callsHere = expandCalls(w.rootCalls, subtree)
        for (op in 0 until OP_COUNT) {
            totalCalls[op] += callsHere[op]
            selfB[op] += callsHere[op] * w.measuredSelf[op]
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
        clockDuringRun = clockDuringRun,
        hookCalls = hookCalls,
    )
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

    for ((round, strict) in listOf(true, false).withIndex()) {
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
        // Cumulative, not per session: the imbalance counter runs for the life of the process,
        // unlike the call counts, which the sampler rebases at every start. Expected here rather
        // than corrected, because correcting it is not this change — see ideas.md.
        val counted = report.imbalances == round + 1
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

    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    val workers = List(threads) { n ->
        Thread {
            var s = (n + 1).toLong()
            var pass = 0L
            try {
                while (System.nanoTime() < deadline) {
                    // 3. Wrap the work. Nesting is fine; the innermost label wins.
                    s = op(parse) { burn(s, work[0]) }
                    s = op(validate) { burn(s, work[1]) }
                    s = op(index) { burn(s, work[2]) }

                    // 3b. The non-lexical form, for a boundary that is not a block — a listener, a
                    // before/after callback, a span across several methods. It has no `finally`,
                    // which is the whole risk: thread 0 here "throws" every thousandth pass and
                    // never calls exit, exactly as a Calcite rule that throws would.
                    Profiler.enter(flush)
                    s = burn(s, work[1])
                    val leak = n == 0 && (++pass % 1000L) == 0L
                    if (!leak) Profiler.exit()

                    // 3c. And the check, at a point this thread is known to be quiescent.
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
private class Cell(val stepMillis: Double, val seconds: Int)

/**
 * Four points spanning a 20x range of sample counts. That spread is the whole point: if the
 * sampler carries a bias, the error bar shrinks with samples while the bias does not, so the
 * disagreement measured in error bars grows by about sqrt(20) across this range. A narrower
 * matrix could not tell a biased method from a noisy one.
 */
private val VERIFY_CELLS = listOf(
    Cell(20.0, 60),
    Cell(1.0, 10),
    Cell(5.0, 60),
    Cell(1.0, 60),
)

/**
 * How many of the top operations the sampler has to rank in the same order as the truth.
 *
 * The tail is a different matter: the last few operations are separated by hundredths of a
 * percentage point and hold under 1% of the time between them, so their order is decided by
 * counting noise and nothing hangs on it.
 */
private const val RANKED_CORRECTLY = 10

/** How far the sampler may sit from the truth in absolute terms — reported, not gated. */
private const val VERIFY_TOLERANCE_PP = 0.5

/** What the sampler said, placed next to what the truth said. */
private class Comparison(
    val cell: Cell,
    val outcome: Outcome,
    val labelled: Long,
    val idleShare: Double,
    val measured: DoubleArray,
    val diffPp: DoubleArray,
    val z: DoubleArray,
    val rmsZ: Double,
    val worstZ: Int,
    val worstDiff: Int,
) {
    val maxAbsZ: Double get() = abs(z[worstZ])
    val maxDiffPp: Double get() = abs(diffPp[worstDiff])
}

/**
 * The sampler's shares are normalised over labelled samples only. Samples that caught a thread
 * between root calls, or idle, belong to no operation — and the truth's denominator excludes that
 * time too, so dropping them is what makes the two comparable rather than a convenience.
 *
 * The z column is the point of the exercise. An operation with share p measured over N samples
 * has an expected error of sqrt(p(1-p)/N) purely from chance; dividing the observed gap by that
 * says whether the gap is bigger than chance allows. Percentage points alone cannot: they shrink
 * as samples accumulate whether the method is sound or not.
 */
private fun compare(cell: Cell, outcome: Outcome, sampler: Sampler): Comparison {
    val hits = LongArray(OP_COUNT) { sampler.counters[it] }
    val labelled = hits.sum()
    val total = sampler.totalSamples()
    val idleShare = if (total == 0L) 0.0 else sampler.counters[NO_OP_INDEX].toDouble() / total

    val measured = DoubleArray(OP_COUNT) { if (labelled == 0L) 0.0 else hits[it].toDouble() / labelled }
    val diffPp = DoubleArray(OP_COUNT) { (measured[it] - outcome.shareA[it]) * 100 }
    val z = DoubleArray(OP_COUNT) { i ->
        val p = outcome.shareA[i]
        val se = sqrt(p * (1 - p) / labelled)
        if (se <= 0.0) 0.0 else (measured[i] - p) / se
    }
    val rmsZ = sqrt(z.sumOf { it * it } / OP_COUNT)

    return Comparison(
        cell = cell,
        outcome = outcome,
        labelled = labelled,
        idleShare = idleShare,
        measured = measured,
        diffPp = diffPp,
        z = z,
        rmsZ = rmsZ,
        worstZ = (0 until OP_COUNT).maxByOrNull { abs(z[it]) }!!,
        worstDiff = (0 until OP_COUNT).maxByOrNull { abs(diffPp[it]) }!!,
    )
}

/** Phase 3: run the sampler over the bench and put the measured shares beside the true ones. */
private fun runVerify(threads: Int, workload: Workload, wait: WaitStrategy, jitter: Double): Boolean {
    println("\n" + "=".repeat(96))
    println("PHASE 3 — THE SAMPLER AGAINST THE TRUTH")
    println("=".repeat(96))

    val comparisons = ArrayList<Comparison>()
    for (cell in VERIFY_CELLS) {
        println("\n--- ${cell.stepMillis} ms step, ${cell.seconds} s ---")
        val bench = Bench(threads, threads, true, workload)
        bench.start()
        bench.run(SWEEP_REWARM_NANOS)
        val sampler = Sampler((cell.stepMillis * 1_000_000).toLong(), wait, jitter, strict = false)
        val outcome = measureOnce(bench, cell.seconds, sampler)
        bench.stop()
        val c = compare(cell, outcome, sampler)
        comparisons.add(c)
        println(
            String.format(
                Locale.ROOT,
                "  %,d labelled samples, idle %.2f%%, RMS z %.2f, worst gap %.3f pp (%s), bench %s",
                c.labelled, c.idleShare * 100, c.rmsZ, c.maxDiffPp, OPS[c.worstDiff].name,
                if (outcome.ok) "ok" else "FAIL"
            )
        )
    }

    // --- What clock did each phase actually get? ---------------------------------------
    // The fit picks iteration counts at the clock it sees, and the count is then frozen. If the
    // run happens at a different clock, every operation is stretched by that ratio — uniform, so
    // it cancels in the shares, but the workload is no longer the one that was specified.
    println("\n" + "=".repeat(96))
    println("CLOCK PER PHASE — ns per busy-loop iteration")
    println("=".repeat(96))
    println(String.format(Locale.ROOT, "  fitting (workers parked): %.4f", FIT_CLOCK))
    println(String.format(Locale.ROOT, "  %-10s %10s %10s %10s %10s", "run", "early", "middle", "late", "vs fit"))
    for (c in comparisons) {
        val cl = c.outcome.clockDuringRun
        println(
            String.format(
                Locale.ROOT, "  %5.0fms/%3ds %10.4f %10.4f %10.4f %9.2fx",
                c.cell.stepMillis, c.cell.seconds, cl[0], cl[1], cl[2], cl.average() / FIT_CLOCK
            )
        )
    }

    val richest = comparisons.maxByOrNull { it.labelled }!!
    printComparisonDetail(richest)

    // --- Hypothesis 1: does it work on nanosecond operations? --------------------------
    println("\n" + "=".repeat(96))
    println("HYPOTHESIS 1 — the method works on operations four orders shorter than a tick")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "%14s %8s %7s %8s %8s %-14s %10s %-14s",
            "samples", "step", "secs", "RMS z", "max |z|", "worst z", "max gap,pp", "worst gap"
        )
    )
    println("-".repeat(96))
    for (c in comparisons.sortedBy { it.labelled }) {
        println(
            String.format(
                Locale.ROOT, "%,14d %7.0fms %7d %8.2f %8.2f %-14s %10.3f %-14s",
                c.labelled, c.cell.stepMillis, c.cell.seconds, c.rmsZ, c.maxAbsZ,
                OPS[c.worstZ].name, c.maxDiffPp, OPS[c.worstDiff].name
            )
        )
    }
    println("-".repeat(96))

    val sorted = comparisons.sortedBy { it.labelled }
    val sampleRatio = sorted.last().labelled.toDouble() / sorted.first().labelled
    val zGrowth = sorted.last().rmsZ / sorted.first().rmsZ
    println(
        String.format(
            Locale.ROOT,
            "  across a %.0fx range of samples the RMS z grew %.2fx",
            sampleRatio, zGrowth
        )
    )
    println(
        String.format(
            Locale.ROOT,
            "  1.00x would mean pure chance, %.2fx would mean a bias the samples cannot wash out",
            sqrt(sampleRatio)
        )
    )

    // --- Hypothesis 2: does the observer disturb the observed? -------------------------
    val observerOk = observerEffect(threads, workload, wait, jitter)

    // --- Hypothesis 3 ------------------------------------------------------------------
    println("\n" + "=".repeat(96))
    println("HYPOTHESIS 3 — reading another thread's slot without synchronisation")
    println("=".repeat(96))
    println("  The sampler reads slots it does not own, with no fence and no lock, so it may see a")
    println("  value a few nanoseconds stale. There is no separate experiment for this: if stale")
    println("  reads smeared the picture, hypothesis 1 would not hold. It does, so the question is")
    println("  closed by measurement rather than by reasoning about the memory model.")

    // The gate is the ranking of the operations that carry the time, not an absolute number of
    // percentage points. The point of the exercise is deciding where to apply the hammer, and a
    // threshold in pp was something I picked by analogy rather than from what the answer is for.
    val byTruth = (0 until OP_COUNT).sortedByDescending { richest.outcome.shareA[it] }
    val bySampler = (0 until OP_COUNT).sortedByDescending { richest.measured[it] }
    val agree = (0 until OP_COUNT).firstOrNull { byTruth[it] != bySampler[it] } ?: OP_COUNT
    val hyp1 = agree >= RANKED_CORRECTLY

    println("\n" + "=".repeat(96))
    println("VERDICT")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "  1. nanosecond operations:  top %d of %d ranked correctly (need %d), " +
                    "worst gap %.3f pp at %,d samples — %s",
            agree, OP_COUNT, RANKED_CORRECTLY, richest.maxDiffPp, richest.labelled,
            if (hyp1) "HOLDS" else "FAILS"
        )
    )
    println("     self time only — whether self is the right quantity is a separate question")
    println("  2. observer effect:        ${if (observerOk) "HOLDS" else "FAILS"}")
    println("  3. unsynchronised reads:   ${if (hyp1) "HOLDS" else "FAILS"} (follows from 1)")
    println("=".repeat(96))
    return hyp1 && observerOk
}

/** Every operation, what the truth says and what the sampler said, worst disagreement first. */
private fun printComparisonDetail(c: Comparison) {
    println("\n" + "=".repeat(96))
    println("SAMPLER AGAINST TRUTH — ${c.cell.stepMillis} ms step, ${c.cell.seconds} s, ${"%,d".format(c.labelled)} labelled samples")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "%-14s %11s %9s %9s %9s %10s %8s %8s",
            "operation", "hits", "A config", "B batch", "C sampler", "C-A rel", "pp", "noise"
        )
    )
    println("-".repeat(96))
    // Sorted by share: this is the order the question is asked in — where does the time go.
    for (id in (0 until OP_COUNT).sortedByDescending { c.outcome.shareA[it] }) {
        val hits = (c.measured[id] * c.labelled).toLong()
        val relative = if (c.outcome.shareA[id] > 0) c.diffPp[id] / (c.outcome.shareA[id] * 100) * 100 else 0.0
        // 1/sqrt(hits): the error chance alone would give at this sample count. A miss inside this
        // is noise and more samples cure it; a miss far outside it is bias and they will not.
        val floor = if (hits > 0) 100.0 / sqrt(hits.toDouble()) else Double.NaN
        println(
            String.format(
                Locale.ROOT, "%-14s %,11d %8.3f%% %8.3f%% %8.3f%% %+9.2f%% %+8.3f %7.2f%%",
                OPS[id].name, hits,
                c.outcome.shareA[id] * 100, c.outcome.shareB[id] * 100, c.measured[id] * 100,
                relative, c.diffPp[id], floor
            )
        )
    }
    println("-".repeat(96))

    // The question is where to apply the hammer, so the test is whether the order survives.
    val byTruth = (0 until OP_COUNT).sortedByDescending { c.outcome.shareA[it] }
    val bySampler = (0 until OP_COUNT).sortedByDescending { c.measured[it] }
    val agree = (0 until OP_COUNT).firstOrNull { byTruth[it] != bySampler[it] } ?: OP_COUNT
    println(
        if (agree == OP_COUNT) "  ranking: all $OP_COUNT operations in the same order as the truth"
        else "  ranking: the first $agree of $OP_COUNT positions match; first swap is " +
                "${OPS[byTruth[agree]].name} and ${OPS[bySampler[agree]].name}, " +
                String.format(Locale.ROOT, "%.3f pp apart in truth",
                    abs(c.outcome.shareA[byTruth[agree]] - c.outcome.shareA[bySampler[agree]]) * 100)
    )
    println(
        String.format(
            Locale.ROOT,
            "  the two truths themselves disagree by up to %.3f pp, which is the floor on what any",
            c.outcome.maxShareDiff
        )
    )
    println("  comparison here can resolve — a smaller gap than that is not measurable, only lucky")
}

/**
 * Hypothesis 2. Three configurations back to back inside one JVM, sharing one calibration and one
 * warm-up, because separate launches wobble by around 1.5% on their own and the effect being
 * looked for is the same size.
 */
private fun observerEffect(
    threads: Int,
    workload: Workload,
    wait: WaitStrategy,
    jitter: Double,
): Boolean {
    println("\n" + "=".repeat(96))
    println("HYPOTHESIS 2 — the observer does not disturb the observed")
    println("=".repeat(96))

    // Round-robin, not one configuration after another. Sequentially, minutes separate the first
    // configuration from the last, and on a drifting machine that gap swamps the effect — the
    // previous version reported the instrumented bench as 15% faster than the clean one.
    val configs = listOf(
        Triple("no labels, no sampler", false, false),
        Triple("labels, no sampler", true, false),
        Triple("labels + sampler", true, true),
    )
    val benches = listOf(false, true).associateWith { labels ->
        Bench(threads, threads, labels, workload).also { it.start(); it.run(SWEEP_REWARM_NANOS) }
    }
    val rounds = Array(configs.size) { DoubleArray(OBSERVER_ROUNDS) }
    for (round in 0 until OBSERVER_ROUNDS) {
        for (i in configs.indices) {
            val (_, labels, sampling) = configs[i]
            val bench = benches.getValue(labels)
            bench.resetCounters()
            val sampler = if (sampling) Sampler(1_000_000, wait, jitter, strict = false).also { it.start() } else null
            val nanos = bench.run(OBSERVER_SECONDS * 1_000_000_000L)
            sampler?.shutdown()
            rounds[i][round] = bench.totalRootCalls() / (nanos / 1e9)
        }
    }
    benches.values.forEach { it.stop() }
    val rates = rounds.map { it.sorted()[OBSERVER_ROUNDS / 2] }

    val baseline = rates[0]
    println(String.format(Locale.ROOT, "  %-24s %16s %10s", "configuration", "root calls/s", "vs clean"))
    for (i in configs.indices) {
        println(
            String.format(
                Locale.ROOT, "  %-24s %,16.0f %+9.2f%%",
                configs[i].first, rates[i], (rates[i] / baseline - 1.0) * 100
            )
        )
    }
    val hookCost = (rates[1] / baseline - 1.0) * 100
    val samplerCost = (rates[2] / rates[1] - 1.0) * 100
    println(String.format(Locale.ROOT, "\n  apparent hook cost %+.2f%%, apparent sampler cost %+.2f%%", hookCost, samplerCost))
    println("  INCONCLUSIVE — read the signs: these two disagree about which direction the effect")
    println("  goes, and across runs the same comparison has swung by seventeen points. The clock")
    println("  drifts by more than the thing being measured, so no arrangement of rounds can")
    println("  resolve it. The verdict below rests on the direct measurement instead.")

    // Timing the hook on its own is stable to a few percent between threads, because there is no
    // large number to subtract — the signal is the whole measurement.
    val bench = Bench(threads, threads, true, workload)
    bench.start()
    bench.run(SWEEP_REWARM_NANOS)
    bench.measureHook()
    val active = bench.workers.filter { it.active }
    val direct = active.map { it.hookDirect }.average()
    bench.stop()

    val meanOpNanos = meanOperationNanos()
    val share = direct / meanOpNanos
    println(
        String.format(
            Locale.ROOT, "\n  the hook timed directly: %.3f ns per call (per thread %.3f .. %.3f)",
            direct, active.minOf { it.hookDirect }, active.maxOf { it.hookDirect }
        )
    )
    println(
        String.format(
            Locale.ROOT, "  against a call-weighted mean operation of %.0f ns that is %.2f%%, tolerance %.0f%%",
            meanOpNanos, share * 100, OBSERVER_TOLERANCE * 100
        )
    )
    println("  (in isolation, so an upper bound: inside a real operation the CPU overlaps most of it)")
    return share <= OBSERVER_TOLERANCE
}

/** The full phase-1 tables for a single run. */
private fun printDetail(bench: Bench, o: Outcome, sampler: Sampler?, stepMillis: Double) {
    println(
        String.format(
            Locale.ROOT, "  actual: %.3f s, root calls: %,d", o.runNanos / 1e9, o.rootCalls
        )
    )

    println("\n--- Root calls per thread ---")
    for (w in bench.workers) {
        println(
            String.format(
                Locale.ROOT, "  thread %2d %-10s %,15d calls", w.id,
                if (w.active) "working" else "IDLE", w.rootCalls.sum()
            )
        )
    }

    if (sampler != null) printSampler(sampler, stepMillis, o.runNanos, bench)

    val subtree = subtreeCounts()
    val inclA = inclusiveNanos(subtree)
    val inclShare = DoubleArray(OP_COUNT) { id ->
        var s = 0.0
        for (op in 0 until OP_COUNT) s += o.totalCalls[id] * subtree[id][op] * OPS[op].selfNanos
        s / o.totA
    }

    println("\n" + "=".repeat(96))
    println("TRUTH: share of an operation's self time")
    println("A — configuration (configured duration x number of calls)")
    println("B — batch measurement (measured duration x number of calls)")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "%-14s %14s %9s %9s %9s %9s %8s %9s",
            "operation", "calls", "target", "measured", "share A", "share B", "diff,pp", "incl.share"
        )
    )
    println("-".repeat(96))
    for (id in (0 until OP_COUNT).sortedByDescending { o.shareA[it] }) {
        println(
            String.format(
                Locale.ROOT, "%-14s %,14d %8.1fn %8.1fn %8.3f%% %8.3f%% %+8.3f %8.3f%%",
                OPS[id].name, o.totalCalls[id], OPS[id].selfNanos, o.measuredSelf[id],
                o.shareA[id] * 100, o.shareB[id] * 100,
                (o.shareB[id] - o.shareA[id]) * 100, inclShare[id] * 100
            )
        )
    }
    println("-".repeat(96))

    val mismatch = (0 until OP_COUNT).filter { o.hookCalls[it] != 0L && o.hookCalls[it] != o.totalCalls[it] }
    println(
        if (o.hookCalls.sum() == 0L) "  (hook counters idle — labels are off)"
        else if (mismatch.isEmpty()) "  hook counters match the graph expansion exactly on all $OP_COUNT operations"
        else "  HOOK COUNTERS DISAGREE with the graph expansion on: " + mismatch.joinToString { OPS[it].name }
    )

    // Dispatch (graph recursion, schedule lookup, counters) belongs to no operation. This is
    // where it shows up.
    //
    // These numbers are indicative, and that is fundamental: a batch runs the busy loop back to
    // back, without recursion and with a perfectly predictable trip count, which is a different
    // instruction environment than the run. It does not affect the shares — the distortion is
    // common to all operations and cancels under normalisation — but it does affect the
    // absolute seconds.
    println("\n--- How much of the run time the truth accounts for (estimate) ---")
    val threadNanos = o.runNanos.toDouble() * o.threads
    println(String.format(Locale.ROOT, "  worker thread time:  %.3f s", threadNanos / 1e9))
    println(String.format(Locale.ROOT, "  accounted by truth A: %.3f s (%.2f%%)", o.totA / 1e9, o.totA / threadNanos * 100))
    println(String.format(Locale.ROOT, "  accounted by truth B: %.3f s (%.2f%%)", o.totB / 1e9, o.totB / threadNanos * 100))
    println(String.format(Locale.ROOT, "  %-14s %12s %12s %10s", "root", "incl.target", "incl.meas.", "surcharge"))
    for (id in 0 until OP_COUNT) {
        if (ROOT_WEIGHTS[id] == 0) continue
        println(
            String.format(
                Locale.ROOT, "  %-14s %11.1fn %11.1fn %+9.2f%%",
                OPS[id].name, inclA[id], o.measuredIncl[id], (o.measuredIncl[id] / inclA[id] - 1.0) * 100
            )
        )
    }
}

private fun printVerdict(o: Outcome, warmedUp: Boolean): Boolean {
    println("\n" + "=".repeat(96))
    println("VERDICT")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "  divergence of shares:     %.3f pp (%s), tolerance %.1f pp",
            o.maxShareDiff, OPS[o.worstShare].name, SHARE_TOLERANCE_PP
        )
    )
    println(
        String.format(
            Locale.ROOT, "  price of parallelism:     x%.3f, allowed %.2f..%.2f",
            o.loadFactor, LOAD_FACTOR_RANGE.start, LOAD_FACTOR_RANGE.endInclusive
        )
    )
    println(
        "    (how much more an operation costs under ${o.threads} threads than on a quiet machine;" +
                " a factor common to all operations, cancels in the shares)"
    )
    println(
        String.format(
            Locale.ROOT, "  scatter around the shift: %.2f%% (%s), tolerance %.0f%%",
            o.maxScatter * 100, OPS[o.worstScatter].name, DURATION_TOLERANCE * 100
        )
    )
    println("  warm-up settled:          ${if (warmedUp) "yes" else "NO"}")

    val ok = o.ok && warmedUp
    if (ok) {
        println("  THE TWO TRUTHS AGREE. The bench is sound; the share table above is the reference for phase 3.")
    } else {
        println("  THE TWO TRUTHS DISAGREE. The bench is broken, going further makes no sense.")
    }
    println("=".repeat(96))
    return ok
}

/**
 * What phase 2 has to show: that the sampler actually ran at the rate it claims, that every tick
 * produced one sample per slot, and what the raw hit counts look like. Nothing here is compared
 * against the truth table — that is phase 3.
 */
private fun printSampler(
    sampler: Sampler,
    stepMillis: Double,
    runNanos: Long,
    bench: Bench,
) {
    val slots = Profiler.slots().size
    val samples = sampler.totalSamples()
    val expected = sampler.ticks * slots
    val achieved = if (sampler.ticks > 1) sampler.span.toDouble() / (sampler.ticks - 1) else Double.NaN

    println("\n--- Sampler ---")
    // Without this the run merely looks as though the sampler died.
    sampler.failure?.let {
        println("  PROFILING STOPPED — these numbers were not going to be right:")
        println("  $it")
    }
    println(String.format(Locale.ROOT, "  requested step: %.3f ms", stepMillis))
    println(
        String.format(
            Locale.ROOT, "  achieved step:  %.3f ms  (min %.3f, max %.3f)",
            achieved / 1e6, sampler.minStep / 1e6, sampler.maxStep / 1e6
        )
    )
    // What the walk costs, per slot, so the state read can be priced against its own absence
    // rather than argued about. Runs on the sampler's own thread, which has a core.
    if (sampler.walkVisits > 0) println(
        String.format(
            Locale.ROOT, "  slot walk:      %.1f ns per slot (%.1f us per tick over %,d visits)",
            sampler.walkNanos.toDouble() / sampler.walkVisits,
            sampler.walkNanos.toDouble() / sampler.ticks.coerceAtLeast(1) / 1e3,
            sampler.walkVisits
        )
    )
    println(
        String.format(
            Locale.ROOT, "  ticks: %,d over %.3f s, slots: %d, samples: %,d, resynced: %,d",
            sampler.ticks, sampler.span / 1e9, slots, samples, sampler.lagged
        )
    )
    println(
        "  one sample per slot per tick: " +
                if (samples == expected) "yes" else "NO — $samples against an expected $expected"
    )
    // A slot left behind by a dead thread reads empty forever and quietly inflates the
    // denominator. This is how that gets noticed rather than eyeballed.
    println(
        "  one slot per live worker: " +
                if (slots == bench.threads) "yes"
                else "NO — $slots slots against ${bench.threads} worker threads (stale slots in the registry)"
    )
    println(String.format(Locale.ROOT, "  covered %.2f%% of the run", sampler.span.toDouble() / runNanos * 100))

    val labelled = samples - sampler.counters[NO_OP_INDEX]
    // The same object a library user gets from Profiler.stop(), built here from the sampler the
    // bench drove itself. Everything below then goes through the library's own arithmetic rather
    // than a second copy of it written for the bench — which is how the two came to disagree once
    // already.
    val report = Report(
        operations = (0 until Profiler.registeredCount()).map { id ->
            OperationStat(
                id, Profiler.nameOf(id), sampler.counters[id], sampler.sessionCalls(id),
                sampler.stuckHits[id], sampler.stuckInstances[id],
                sampler.waitingHits[id], sampler.stuckWaitingHits[id], sampler.activeTicks[id],
            )
        },
        idleHits = sampler.counters[NO_OP_INDEX],
        ticks = sampler.ticks,
        samplingSpanNanos = sampler.span,
        durationNanos = runNanos,
        threads = slots,
        duty = sampler.duty(),
        failure = sampler.failure,
        stateSampled = sampler.sampleState,
    )
    printDuty(sampler.duty(), bench, runNanos, report.stuckBaseline)

    printDetector(report, achieved, bench.contended, lockOpIdOf(bench), bench.stalls())
    printWaitingCheck(sampler, bench)

    println(
        String.format(
            Locale.ROOT, "\n  %-14s %14s %9s %9s %9s %8s",
            "operation", "hits", "of total", "waiting", "elapsed", "threads"
        )
    )
    val step = if (sampler.ticks > 1) sampler.span.toDouble() / (sampler.ticks - 1) else Double.NaN
    for (id in (0 until Profiler.registeredCount()).sortedByDescending { sampler.counters[it] } + listOf(NO_OP_INDEX)) {
        val hits = sampler.counters[id]
        val name = if (id == NO_OP_INDEX) "(no operation)" else Profiler.nameOf(id)
        // NO_OP_INDEX has no entry in the per-operation arrays: nothing was inside a label, so
        // there is no operation for the state or the footprint to belong to.
        val waiting = if (id == NO_OP_INDEX || hits == 0L) Double.NaN
        else sampler.waitingHits[id] * 100.0 / hits
        val active = if (id == NO_OP_INDEX) 0L else sampler.activeTicks[id]
        println(
            String.format(
                Locale.ROOT, "  %-14s %,14d %8.3f%% %8s %9s %8s",
                name, hits, if (samples == 0L) 0.0 else hits * 100.0 / samples,
                if (waiting.isNaN()) "-" else String.format(Locale.ROOT, "%.1f%%", waiting),
                if (active == 0L) "-" else duration(active * step),
                if (active == 0L) "-" else String.format(Locale.ROOT, "%.2f", hits.toDouble() / active),
            )
        )
    }

    // The warning a library user gets from render(), which the bench does not call — it prints its
    // own tables. Printed here because this is the one workload where the answer is known in
    // advance: five of the twenty operations are configured under the 50 ns floor, so a run that
    // flags none of them is a statement about the machine's clock and not about the check.
    val small = report.tooSmall()
    println("\n  below the floor: ${if (small.isEmpty()) "none flagged" else small.joinToString { it.name }}")
    for (op in small) println("  ! " + tooSmallMessage(op.name, op.calls, report.impliedUpperNanosOf(op)))
    if (small.isNotEmpty()) println("  the run finished anyway — this is a warning, not a verdict")
}

/**
 * The long-instance detector against a bench that cannot block.
 *
 * The test is two words per slot per tick: same operation as last tick, and that thread's entry
 * counter for it unmoved, means nobody entered in between — so this is one execution still running
 * a whole tick later. For a 20 ns operation that is fifty thousand times its claimed size.
 *
 * Here nothing should stand out, because nothing in this workload waits for anything. What is left
 * is the floor: the operating system preempting a runnable thread for tens of milliseconds, which
 * lands on whichever operation was executing at the time and in proportion to its occupancy. So
 * every operation should sit near the run-wide rate, and an operation well above it would be a
 * defect in the detector rather than a discovery about the bench.
 *
 * The implied duration column is checked against the configuration, which is the point of having a
 * bench whose true answer is known: `hits × step / calls` should reproduce the duration the
 * operation was built to have, times the load factor the machine is imposing.
 */
/**
 * The lock operation's id, which is always the one straight after the catalogue: the bench
 * registers its twenty in order and this immediately after them.
 */
private fun lockOpIdOf(bench: Bench): Int = if (bench.contended != null) OP_COUNT else -1

/**
 * What an operation was built to cost. For the catalogue that is its configured self duration; for
 * the contended lock it is the critical section alone, so the ratio column shows the waiting as
 * the amount by which the operation exceeds what it was asked to be.
 */
private fun configuredNanos(id: Int, lock: ContendedLock?): Double =
    if (id < OP_COUNT) OPS[id].selfNanos else lock?.holdNanos?.toDouble() ?: Double.NaN

private fun printDetector(
    report: Report,
    stepNanos: Double,
    lock: ContendedLock?,
    lockOpId: Int,
    stalls: Stalls,
) {
    val machineFloor = report.machineFloor
    println("\n--- Long executions: is a fine operation actually fine? ---")
    println(
        String.format(
            Locale.ROOT,
            "  run-wide: %.2f%% of labelled occupancy sat inside executions that outlived a tick, " +
                    "of which the machine accounts for up to %.2f%%",
            report.stuckBaseline * 100, machineFloor * 100
        )
    )
    println(String.format(Locale.ROOT, "  %-14s %12s %12s %10s %9s %8s", "operation", "configured", "implied/call", "ratio", "over 1 tick", "long runs"))
    // Past OP_COUNT sits the contended lock, which is registered separately precisely so that the
    // truth machinery — indexed by operation id — never has to know about an operation whose
    // duration is not its configured one.
    var worst: OperationStat? = null
    for (op in report.operations.sortedByDescending { it.hits }) {
        if (op.hits == 0L) continue
        if (worst == null || op.stuckShare > worst.stuckShare) worst = op
        val configured = configuredNanos(op.id, lock)
        val implied = report.impliedNanosOf(op)
        println(
            String.format(
                Locale.ROOT, "  %-14s %12s %12s %9.2fx %10.2f%% %8d",
                op.name, duration(configured), duration(implied),
                implied / configured, op.stuckShare * 100, op.stuckInstances
            )
        )
    }
    println(
        String.format(
            Locale.ROOT, "  worst is %s at %.2f%%, %.2fx the machine floor on %,d long executions",
            worst?.name ?: "-", (worst?.stuckShare ?: 0.0) * 100,
            if (machineFloor > 0) (worst?.stuckShare ?: 0.0) / machineFloor else 0.0,
            worst?.stuckInstances ?: 0
        )
    )
    // The library's own rule, applied to the library's own report object rather than to a second
    // copy of the arithmetic written for the bench — which is how the two came to disagree once.
    val flagged = report.suspect()
    val names = flagged.joinToString { it.name }
    println(
        when {
            lock == null && flagged.isEmpty() ->
                "  nothing is flagged, which is the right answer: this bench has nothing that could block"

            lock == null ->
                "  FLAGGED: $names — and without --lock this bench has nothing that could block"

            flagged.map { it.id } == listOf(lockOpId) ->
                "  FLAGGED: lockedUpdate, and nothing else — which is the right answer, since it is the " +
                        "only thing here that waits"

            flagged.isEmpty() ->
                "  NOTHING FLAGGED, but lockedUpdate blocks by construction — the detector missed it"

            else -> "  FLAGGED: $names — expected lockedUpdate alone"
        }
    )
    println("  (the floor is one tick: a stall shorter than ${String.format(Locale.ROOT, "%.2f", stepNanos / 1e6)} ms cannot be seen at all)")

    // Working or waiting? The bound is arithmetic on the duty cycle and this operation's long-run
    // occupancy — see Report.runningFloorOf — and here it can be checked, because the workers timed
    // both halves of what lockedUpdate did.
    for (op in flagged) {
        println("  ${op.name}: ${report.verdictFor(op)}")
        if (op.id != lockOpId) continue
        val busy = stalls.lockWaitNanos + stalls.lockHeldNanos
        if (busy == 0L) continue
        val truth = stalls.lockHeldNanos.toDouble() / busy
        val floor = report.runningFloorOf(op)
        println(
            String.format(
                Locale.ROOT,
                "    the workers timed it: %.1f%% of that operation was holding the lock and %.1f%% waiting, " +
                        "against a bound of at least %.1f%% running — %s",
                truth * 100, (1 - truth) * 100, floor * 100,
                if (floor <= truth + 1e-9) "the bound holds" else "THE BOUND IS VIOLATED, which cannot happen"
            )
        )
    }
}

/**
 * The duty cycle, and the bench's own check on it.
 *
 * The measurement is checked rather than admired, in the manner of phase 1's two truths — but the
 * second truth here cannot come from the configuration. The first attempt had it do so: every
 * worker registers a slot whether it works or not, an inactive one sits parked on the barrier
 * using no CPU, so the expectation was exactly active/registered, and 100% in the ordinary case
 * because nothing in this bench ever blocks.
 *
 * It read 78%. The reason is not the duty cycle: **the operating system does not give a thread a
 * core merely because one is free**. On this machine, 8 workers and a spinning sampler on 16
 * logical cores lose of the order of 14% of their wall time to the scheduler, in preemptions of
 * milliseconds. "Never blocks" is a property of the code; being on a CPU is not.
 *
 * So the second truth is the workers' own account of the same quantity, taken from the gaps in the
 * clock readings the run loop already makes — see [Worker.stallNanos]. It is measured by the
 * victim, owes nothing to the OS accounting it is checking, and the two agreeing is a much
 * stronger statement than either matching a number we assumed.
 *
 * Starvation mode still contributes its known component: an inactive worker is parked for the
 * whole run and contributes wall time and no CPU, so the expectation scales by active/registered.
 */
private fun printDuty(
    d: DutyReport,
    bench: Bench,
    runNanos: Long,
    stuckShare: Double,
) {
    println("\n--- CPU duty cycle: how much of the occupancy was CPU ---")
    for (l in d.lines()) println("  $l")
    if (!d.available) return

    val s = bench.stalls()
    // Denominator over every registered thread, since that is the set the duty cycle walks; an
    // inactive worker is parked on the barrier and brings wall time with no CPU under it.
    val wall = runNanos.toDouble() * bench.threads
    val expected = (s.wallNanos - s.totalOffCpuNanos) / wall
    val diffPp = abs(d.duty - expected) * 100
    println(
        String.format(
            Locale.ROOT,
            "  the workers' own account: %,d preemptions over %.3f ms, worst %.3f ms, %.2f%% of their wall time",
            s.events, s.offCpuNanos / 1e6, s.worstNanos / 1e6, s.offCpuNanos * 100.0 / s.wallNanos
        )
    )
    // Two causes, kept apart on purpose: one is the machine misbehaving, the other is the workload
    // doing exactly what it was configured to do. The duty cycle cannot tell them apart — it is an
    // aggregate and says so — but the bench can, and that is how the aggregate gets checked.
    if (s.lockAcquisitions > 0) println(
        String.format(
            Locale.ROOT,
            "  and waiting for the contended lock: %,d acquisitions, %.3f s waited, worst %.1f ms, %.2f%% of their wall time",
            s.lockAcquisitions, s.lockWaitNanos / 1e9, s.maxLockWaitNanos / 1e6,
            s.lockWaitNanos * 100.0 / s.wallNanos
        )
    )
    println(
        String.format(
            Locale.ROOT, "  so the duty cycle should read %.2f%%%s",
            expected * 100,
            if (bench.activeThreads < bench.threads)
                " (${bench.activeThreads} of ${bench.threads} threads work; the rest are parked)" else ""
        )
    )
    println(
        String.format(
            Locale.ROOT, "  %s — the two differ by %.2f pp, tolerance %.2f pp",
            if (diffPp <= DUTY_TOLERANCE_PP) "the two independent readings agree" else "THEY DO NOT AGREE",
            diffPp, DUTY_TOLERANCE_PP
        )
    )
    println("  (the workers see only preemptions longer than 0.5 ms, so their figure is a lower bound)")
    if (bench.threads > Runtime.getRuntime().availableProcessors()) println(
        String.format(
            Locale.ROOT,
            "  oversubscribed: %d threads on %d cores, so occupancy over-reads CPU by about %.1fx and the duty cycle says so",
            bench.threads, Runtime.getRuntime().availableProcessors(),
            bench.threads.toDouble() / Runtime.getRuntime().availableProcessors()
        )
    )
    // A third view of the same thing, and the cheapest of the three: a thread preempted for longer
    // than a tick is still holding its label, so the detector sees it as an execution that outlived
    // a tick. It cannot see the shorter preemptions at all — its floor is a whole tick against the
    // workers' half-millisecond — so it should read the lowest of the three, and the three should
    // be the same order. Anything else means one of them is measuring something other than the
    // scheduler.
    println(
        String.format(
            Locale.ROOT,
            "  and a third view: %.2f%% of occupancy sat inside executions that outlived a tick (floor 1 tick, so the lowest of the three)",
            stuckShare * 100
        )
    )
}

/**
 * The cost of a busy-loop iteration right now, in the bench's own units. About 6 ms, and it says
 * directly what clock this phase is getting — no external counters, no sampling resolution to
 * argue about, and the same units the calibration speaks.
 */
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
private var FIT_CLOCK: Double = Double.NaN

/**
 * The sampled waiting share against the workers' own stopwatches — a second truth for the one
 * quantity this bench can inject on purpose.
 *
 * Every thread waiting for the contended lock times its own wait with `nanoTime` and adds it to
 * `lockWaitNanos`; the sampler independently counts hits where the slot held `lockedUpdate` and
 * the owning thread was not runnable. The two share nothing — one is a stopwatch on the waiting
 * thread, the other is a state read from a different thread a millisecond at a time — so agreement
 * is evidence and disagreement is a defect in one of them.
 *
 * The tolerance is loose on purpose. Sampling error alone at these hit counts is a few tenths of a
 * percent, and the two windows are not identical: the workers' figure covers the whole run while
 * the sampler covers only the ticks it took.
 */
private fun printWaitingCheck(sampler: Sampler, bench: Bench) {
    val id = lockOpIdOf(bench)
    if (id < 0 || bench.contended == null) return
    val s = bench.stalls()
    if (s.lockAcquisitions == 0L) return

    val step = if (sampler.ticks > 1) sampler.span.toDouble() / (sampler.ticks - 1) else return
    val sampled = sampler.waitingHits[id] * step
    val truth = s.lockWaitNanos.toDouble()
    val diffPct = if (truth == 0.0) Double.NaN else abs(sampled - truth) * 100.0 / truth

    println("\n--- Sampled waiting against the workers' own clocks ---")
    println(
        String.format(
            Locale.ROOT,
            "  lockedUpdate: %,d of its %,d hits caught a thread not runnable (%.1f%% of its occupancy)",
            sampler.waitingHits[id], sampler.counters[id],
            sampler.waitingHits[id] * 100.0 / sampler.counters[id].coerceAtLeast(1),
        )
    )
    println(
        String.format(
            Locale.ROOT, "  sampled waiting  %.3f s   workers' own stopwatch  %.3f s   differ by %.2f%%",
            sampled / 1e9, truth / 1e9, diffPct
        )
    )
    // Every other operation in this bench is incapable of blocking, so anything above the noise
    // floor there is the state read attributing waiting to the wrong label.
    val worst = (0 until Profiler.registeredCount())
        .filter { it != id && sampler.counters[it] > 0 }
        .maxByOrNull { sampler.waitingHits[it].toDouble() / sampler.counters[it] }
    if (worst != null) println(
        String.format(
            Locale.ROOT, "  worst false positive: %s at %.2f%% — nothing else here can block",
            Profiler.nameOf(worst), sampler.waitingHits[worst] * 100.0 / sampler.counters[worst]
        )
    )
    println(
        if (diffPct <= WAITING_TOLERANCE_PCT) "  the two independent readings agree"
        else String.format(
            Locale.ROOT, "  THEY DISAGREE by %.2f%%, tolerance %.2f%%", diffPct, WAITING_TOLERANCE_PCT
        )
    )
}

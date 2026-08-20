package com.minogin.profiler

import java.util.Locale
import kotlin.math.abs
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

private const val WARMUP_SLICE_MS = 1000L
private const val WARMUP_MIN_SLICES = 4
private const val WARMUP_MAX_SLICES = 15
private const val PLATEAU_EPS = 0.03

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

    require(activeThreads in 1..threads) { "--active=$activeThreads is outside 1..$threads" }

    // The sampler needs a core of its own. Spinning it is the only way to hold a 1 ms step under
    // load, and a spinning thread never yields — so with the sampler on, the workers can never
    // have every core. A genuinely saturated machine is only measurable with --sampler=off, and
    // that measurement is therefore always uninstrumented. Not a problem, but a real limit.
    val maxThreads = if (sampling) cores - 1 else cores
    for (n in sweep ?: listOf(threads)) {
        require(n in 1..maxThreads) {
            "thread count $n is outside 1..$maxThreads " +
                    (if (sampling) "(the sampler needs one of the $cores cores; --sampler=off lifts this)"
                    else "($cores cores)")
        }
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
    println("=".repeat(96))

    // --- Provisional calibration ----------------------------------------------------
    // The cost of a busy-loop iteration is not a constant: it is taken at runtime, and only
    // after a warm-up.
    warmUpBurn(500)
    val provisional = calibrate()
    println("\n--- Busy-loop calibration (provisional, before the workload warm-up) ---")
    println("  $provisional")

    val workload = Workload()
    workload.applyCalibration(provisional)

    // --- Workload warm-up to the plateau --------------------------------------------
    // Warm at the widest thread count in play, so every path is compiled before anything is
    // calibrated or measured.
    val warmThreads = sweep?.max() ?: threads
    val warmBench = Bench(warmThreads, warmThreads, labels, workload)
    warmBench.start()
    println("\n--- Workload warm-up to the plateau ($warmThreads threads, slice $WARMUP_SLICE_MS ms) ---")
    val plateau = warmUpToPlateau(warmBench)
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

    val ok = if (sweep != null) {
        runSweep(sweep, labels, workload, seconds, plateau)
    } else {
        val bench = Bench(threads, activeThreads, labels, workload)
        bench.start()
        println("\n--- Run of $seconds s ---")
        // The sampler covers the measured run only, never the warm-up.
        val sampler = if (sampling) Sampler((stepMillis * 1_000_000).toLong(), wait).also { it.start() } else null
        val outcome = measureOnce(bench, seconds, sampler)
        printDetail(bench, outcome, sampler, stepMillis)
        val good = printVerdict(outcome, plateau)
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
    val runNanos = bench.run(seconds * 1_000_000_000L)
    sampler?.shutdown()

    bench.measure()
    val active = bench.workers.filter { it.active }
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
    plateau: Boolean,
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
    if (!plateau) println("WARNING: the warm-up never reached a plateau")

    val allOk = outcomes.all { it.ok } && plateau
    println(if (allOk) "\nEvery thread count holds. The bench is sound." else "\nAt least one thread count fails. The bench is not sound.")
    return allOk
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

    if (sampler != null) printSampler(sampler, stepMillis, o.runNanos, bench.threads)

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

private fun printVerdict(o: Outcome, plateau: Boolean): Boolean {
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
    println("  warm-up reached plateau:  ${if (plateau) "yes" else "NO"}")

    val ok = o.ok && plateau
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
private fun printSampler(sampler: Sampler, stepMillis: Double, runNanos: Long, workerThreads: Int) {
    val slots = Profiler.slots().size
    val samples = sampler.totalSamples()
    val expected = sampler.ticks * slots
    val achieved = if (sampler.ticks > 1) sampler.span.toDouble() / (sampler.ticks - 1) else Double.NaN

    println("\n--- Sampler ---")
    println(String.format(Locale.ROOT, "  requested step: %.3f ms", stepMillis))
    println(
        String.format(
            Locale.ROOT, "  achieved step:  %.3f ms  (min %.3f, max %.3f)",
            achieved / 1e6, sampler.minStep / 1e6, sampler.maxStep / 1e6
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
                if (slots == workerThreads) "yes"
                else "NO — $slots slots against $workerThreads worker threads (stale slots in the registry)"
    )
    println(String.format(Locale.ROOT, "  covered %.2f%% of the run", sampler.span.toDouble() / runNanos * 100))

    println(String.format(Locale.ROOT, "\n  %-14s %14s %9s", "operation", "hits", "of total"))
    for (id in (0..OP_COUNT).sortedByDescending { sampler.counters[it] }) {
        val hits = sampler.counters[id]
        val name = if (id == OP_COUNT) "(no operation)" else OPS[id].name
        println(
            String.format(
                Locale.ROOT, "  %-14s %,14d %8.3f%%",
                name, hits, if (samples == 0L) 0.0 else hits * 100.0 / samples
            )
        )
    }
}

/**
 * Warm-up to the plateau. Throughput is measured in slices; measurements taken before the
 * plateau do not count towards the truth, which is why the counters are reset afterwards.
 */
private fun warmUpToPlateau(bench: Bench): Boolean {
    val rates = ArrayList<Double>()
    var plateau = false
    while (rates.size < WARMUP_MAX_SLICES) {
        val before = bench.totalRootCalls()
        val nanos = bench.run(WARMUP_SLICE_MS * 1_000_000)
        val rate = (bench.totalRootCalls() - before) / (nanos / 1e9)
        rates.add(rate)
        val delta = if (rates.size > 1)
            String.format(Locale.ROOT, "%+.2f%%", (rate / rates[rates.size - 2] - 1.0) * 100) else ""
        println(String.format(Locale.ROOT, "  slice %2d: %,12.0f root calls/s   %s", rates.size, rate, delta))
        if (rates.size >= WARMUP_MIN_SLICES) {
            val last3 = rates.takeLast(3)
            if ((last3.max() - last3.min()) / last3.min() < PLATEAU_EPS) {
                plateau = true
                break
            }
        }
    }
    println(if (plateau) "  plateau reached" else "  plateau NOT reached in $WARMUP_MAX_SLICES slices")
    return plateau
}

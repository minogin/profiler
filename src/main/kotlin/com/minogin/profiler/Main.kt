package com.minogin.profiler

import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess

/** Tolerance for the divergence of shares between the two truths, in percentage points. */
private const val SHARE_TOLERANCE_PP = 1.0

/** How closely the fit is required to hit the configured duration on a single thread. */
private const val FIT_TOLERANCE = 0.03

/**
 * Tolerance for the divergence of an operation's duration — after the systematic shift is taken
 * out. The shift is common to all operations (under load they all get more expensive by the same
 * factor, their instruction mix is identical) and cancels out in the shares; the scatter around
 * it does not.
 */
private const val DURATION_TOLERANCE = 0.12

/**
 * How much more expensive an operation may get under parallel load. The ceiling is high on
 * purpose: on 16 hyperthreads over 8 physical cores the honest price is around x1.8, and that is
 * a property of the machine rather than a broken bench. Outside this range it is something else.
 */
private val LOAD_FACTOR_RANGE = 0.85..2.5

private const val WARMUP_SLICE_MS = 1000L
private const val WARMUP_MIN_SLICES = 4
private const val WARMUP_MAX_SLICES = 15
private const val PLATEAU_EPS = 0.03

fun main(args: Array<String>) {
    val opt = args.filter { it.startsWith("--") }
        .associate { val p = it.removePrefix("--").split("=", limit = 2); p[0] to p.getOrElse(1) { "true" } }
    val threads = opt["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val activeThreads = opt["active"]?.toInt() ?: threads
    val seconds = opt["seconds"]?.toInt() ?: 60

    require(activeThreads in 1..threads) { "--active=$activeThreads is outside 1..$threads" }

    println("=".repeat(96))
    println("PHASE 1 — BENCH")
    println("threads: $threads, of them working: $activeThreads, run: $seconds s")
    println(
        "JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, " +
                "cores: ${Runtime.getRuntime().availableProcessors()}"
    )
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

    val bench = Bench(threads, activeThreads, workload)
    bench.start()

    // --- Workload warm-up to the plateau --------------------------------------------
    println("\n--- Workload warm-up to the plateau (slice $WARMUP_SLICE_MS ms) ---")
    val plateau = warmUpToPlateau(bench)

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
        bench.stop()
        exitProcess(1)
    }
    if (cal.r2 < 0.99) {
        println(String.format(Locale.ROOT, "\nBENCH IS BROKEN: calibration is non-linear, R2 = %.5f", cal.r2))
        bench.stop()
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
        bench.stop()
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
        bench.stop()
        exitProcess(1)
    }

    // --- Main run --------------------------------------------------------------------
    bench.resetCounters()
    println("\n--- Run of $seconds s ---")
    val runNanos = bench.run(seconds * 1_000_000_000L)
    println(
        String.format(
            Locale.ROOT, "  actual: %.3f s, root calls: %,d",
            runNanos / 1e9, bench.totalRootCalls()
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

    // --- Batch measurement -----------------------------------------------------------
    println("\n--- Batch measurement of operations (on all worker threads, nanoTime around the batch) ---")
    bench.measure()
    val active = bench.workers.filter { it.active }
    val measuredSelf = DoubleArray(OP_COUNT) { id -> active.map { it.measuredSelf[id] }.average() }
    val measuredIncl = DoubleArray(OP_COUNT) { id -> active.map { it.measuredInclusive[id] }.average() }

    // --- The two truths ---------------------------------------------------------------
    val subtree = subtreeCounts()
    val rootTotals = LongArray(OP_COUNT) { id -> bench.workers.sumOf { it.rootCalls[id] } }
    val totalCalls = LongArray(OP_COUNT)
    for (root in 0 until OP_COUNT) {
        if (rootTotals[root] == 0L) continue
        for (op in 0 until OP_COUNT) totalCalls[op] += rootTotals[root] * subtree[root][op]
    }

    val selfA = DoubleArray(OP_COUNT) { totalCalls[it] * OPS[it].selfNanos }
    val selfB = DoubleArray(OP_COUNT) { totalCalls[it] * measuredSelf[it] }
    val totA = selfA.sum()
    val totB = selfB.sum()
    val shareA = DoubleArray(OP_COUNT) { selfA[it] / totA }
    val shareB = DoubleArray(OP_COUNT) { selfB[it] / totB }

    // An operation's total time including children — for context; the sampler will measure self.
    val inclA = inclusiveNanos(subtree)
    val inclShare = DoubleArray(OP_COUNT) { id ->
        var s = 0.0
        for (op in 0 until OP_COUNT) s += totalCalls[id] * subtree[id][op] * OPS[op].selfNanos
        s / totA
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
    for (id in (0 until OP_COUNT).sortedByDescending { shareA[it] }) {
        println(
            String.format(
                Locale.ROOT, "%-14s %,14d %8.1fn %8.1fn %8.3f%% %8.3f%% %+8.3f %8.3f%%",
                OPS[id].name, totalCalls[id], OPS[id].selfNanos, measuredSelf[id],
                shareA[id] * 100, shareB[id] * 100, (shareB[id] - shareA[id]) * 100, inclShare[id] * 100
            )
        )
    }
    println("-".repeat(96))

    // --- Overhead audit ----------------------------------------------------------------
    // Dispatch (graph recursion, schedule lookup, counters) belongs to no operation.
    // This is where it shows up.
    //
    // These numbers are indicative, and that is fundamental: a batch runs the busy loop
    // back to back, without recursion and with a perfectly predictable trip count, which is a
    // different instruction environment than the run. It does not affect the shares — the
    // distortion is common to all operations and cancels under normalisation — but it does
    // affect the absolute seconds.
    println("\n--- How much of the run time the truth accounts for (estimate) ---")
    val threadNanos = runNanos.toDouble() * activeThreads
    println(String.format(Locale.ROOT, "  worker thread time:  %.3f s", threadNanos / 1e9))
    println(String.format(Locale.ROOT, "  accounted by truth A: %.3f s (%.2f%%)", totA / 1e9, totA / threadNanos * 100))
    println(String.format(Locale.ROOT, "  accounted by truth B: %.3f s (%.2f%%)", totB / 1e9, totB / threadNanos * 100))
    println(String.format(Locale.ROOT, "  %-14s %12s %12s %10s", "root", "incl.target", "incl.meas.", "surcharge"))
    for (id in 0 until OP_COUNT) {
        if (ROOT_WEIGHTS[id] == 0) continue
        println(
            String.format(
                Locale.ROOT, "  %-14s %11.1fn %11.1fn %+9.2f%%",
                OPS[id].name, inclA[id], measuredIncl[id], (measuredIncl[id] / inclA[id] - 1.0) * 100
            )
        )
    }

    // --- Verdict -----------------------------------------------------------------------
    val maxShareDiff = (0 until OP_COUNT).maxOf { abs(shareB[it] - shareA[it]) * 100 }
    val worstShare = (0 until OP_COUNT).maxByOrNull { abs(shareB[it] - shareA[it]) }!!

    // The systematic shift: how much more everything costs under load than on a quiet machine.
    // It is common to all operations and cancels in the shares, so it does not touch the truth.
    val ratios = DoubleArray(OP_COUNT) { measuredSelf[it] / OPS[it].selfNanos }
    val loadFactor = ratios.sorted().let { (it[OP_COUNT / 2 - 1] + it[OP_COUNT / 2]) / 2 }
    val durErr = DoubleArray(OP_COUNT) { abs(ratios[it] / loadFactor - 1.0) }
    val worstDur = (0 until OP_COUNT).maxByOrNull { durErr[it] }!!

    println("\n" + "=".repeat(96))
    println("VERDICT")
    println("=".repeat(96))
    println(
        String.format(
            Locale.ROOT, "  divergence of shares:     %.3f pp (%s), tolerance %.1f pp",
            maxShareDiff, OPS[worstShare].name, SHARE_TOLERANCE_PP
        )
    )
    println(
        String.format(
            Locale.ROOT, "  price of parallelism:     x%.3f, allowed %.2f..%.2f",
            loadFactor, LOAD_FACTOR_RANGE.start, LOAD_FACTOR_RANGE.endInclusive
        )
    )
    println(
        "    (how much more an operation costs under $activeThreads threads than on a quiet machine;" +
                " a factor common to all operations, cancels in the shares)"
    )
    println(
        String.format(
            Locale.ROOT, "  scatter around the shift: %.2f%% (%s), tolerance %.0f%%",
            durErr[worstDur] * 100, OPS[worstDur].name, DURATION_TOLERANCE * 100
        )
    )
    println("  warm-up reached plateau:  ${if (plateau) "yes" else "NO"}")

    val ok = maxShareDiff <= SHARE_TOLERANCE_PP && durErr[worstDur] <= DURATION_TOLERANCE &&
            loadFactor in LOAD_FACTOR_RANGE && plateau
    if (ok) {
        println("  THE TWO TRUTHS AGREE. The bench is sound; the share table above is the reference for phase 3.")
    } else {
        println("  THE TWO TRUTHS DISAGREE. The bench is broken, going further makes no sense.")
    }
    println("=".repeat(96))
    println("\n(sink: ${Sink.value})")

    bench.stop()
    if (!ok) exitProcess(1)
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

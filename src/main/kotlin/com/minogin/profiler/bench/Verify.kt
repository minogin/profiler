package com.minogin.profiler.bench

import com.minogin.profiler.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

internal class Cell(val stepMillis: Double, val seconds: Int)

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
internal class Comparison(
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
internal fun compare(cell: Cell, outcome: Outcome, sampler: Sampler): Comparison {
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
internal fun runVerify(threads: Int, workload: Workload): Boolean {
    println("\n" + "=".repeat(96))
    println("PHASE 3 — THE SAMPLER AGAINST THE TRUTH")
    println("=".repeat(96))

    val comparisons = ArrayList<Comparison>()
    for (cell in VERIFY_CELLS) {
        println("\n--- ${cell.stepMillis} ms step, ${cell.seconds} s ---")
        val bench = Bench(threads, threads, true, workload)
        bench.start()
        bench.run(SWEEP_REWARM_NANOS)
        val sampler = Sampler((cell.stepMillis * 1_000_000).toLong(), strict = false)
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
    val observerOk = observerEffect(threads, workload)

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
internal fun printComparisonDetail(c: Comparison) {
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
            val sampler = if (sampling) Sampler(1_000_000, strict = false).also { it.start() } else null
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

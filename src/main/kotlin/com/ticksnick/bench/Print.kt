package com.ticksnick.bench

import com.ticksnick.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** The full phase-1 tables for a single run. */
internal fun printDetail(bench: Bench, o: Outcome, sampler: Sampler?, stepMillis: Double) {
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
    println("A - configuration (configured duration x number of calls)")
    println("B - batch measurement (measured duration x number of calls)")
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
        if (o.hookCalls.sum() == 0L) "  (hook counters idle - labels are off)"
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

internal fun printVerdict(o: Outcome, warmedUp: Boolean): Boolean {
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
    // The five worst, not only the worst one. A maximum cannot tell "one operation blew up" from
    // "everything drifted together", and those have different causes: the first is a measurement
    // that caught something — a pause, a migration — and the second is the machine changing speed
    // under the run. Printed always, because the question only arises on the runs that fail and by
    // then the run is over.
    println(
        "  scatter, five worst:      " +
                (0 until OP_COUNT).sortedByDescending { o.scatter[it] }.take(5).joinToString(", ") {
                    String.format(Locale.ROOT, "%s %.2f%%", OPS[it].name, o.scatter[it] * 100)
                }
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
        println("  PROFILING STOPPED - these numbers were not going to be right:")
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
                if (samples == expected) "yes" else "NO - $samples against an expected $expected"
    )
    // A slot left behind by a dead thread reads empty forever and quietly inflates the
    // denominator. This is how that gets noticed rather than eyeballed.
    println(
        "  one slot per live worker: " +
                if (slots == bench.threads) "yes"
                else "NO - $slots slots against ${bench.threads} worker threads (stale slots in the registry)"
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
    if (small.isNotEmpty()) println("  the run finished anyway - this is a warning, not a verdict")
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
                "  FLAGGED: $names - and without --lock this bench has nothing that could block"

            flagged.map { it.id } == listOf(lockOpId) ->
                "  FLAGGED: lockedUpdate, and nothing else - which is the right answer, since it is the " +
                        "only thing here that waits"

            flagged.isEmpty() ->
                "  NOTHING FLAGGED, but lockedUpdate blocks by construction - the detector missed it"

            else -> "  FLAGGED: $names - expected lockedUpdate alone"
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
                        "against a bound of at least %.1f%% running - %s",
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
            Locale.ROOT, "  %s - the two differ by %.2f pp, tolerance %.2f pp",
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
            Locale.ROOT, "  worst false positive: %s at %.2f%% - nothing else here can block",
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

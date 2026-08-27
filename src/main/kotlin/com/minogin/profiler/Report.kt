package com.minogin.profiler

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** See [Report.impliedUpperNanosOf]. Shared so the run-time check and the report cannot disagree. */
fun impliedUpperNanos(hits: Long, calls: Long, stepNanos: Double): Double =
    if (calls == 0L) Double.NaN else (hits + 2 * sqrt(hits.toDouble()) + 3) * stepNanos / calls

/** Whether a label is on something too small for the instrument to describe. See [Report.tooSmall]. */
fun isTooSmall(hits: Long, calls: Long, stepNanos: Double): Boolean =
    calls > 0 && impliedUpperNanos(hits, calls, stepNanos) * Report.FLOOR_BIAS_ALLOWANCE < Report.FLOOR_NANOS

/**
 * What to tell someone whose label is below the floor. Both reasons, because the second is the one
 * they cannot check for themselves.
 */
fun tooSmallMessage(name: String, calls: Long, upperNanos: Double): String = String.format(
    Locale.ROOT,
    "%s: %,d calls at under %s each, below the %.0f ns floor.%n" +
            "    The hook is a large fraction of an operation that size, the sampler reads it low by " +
            "5-9%%,%n" +
            "    and C2 can move work across the boundaries of adjacent short labels without leaving a " +
            "trace in the numbers.%n" +
            "    Label the enclosing loop instead and divide by the iteration count.",
    name, calls, duration(upperNanos), Report.FLOOR_NANOS
)

/**
 * What to tell someone whose label leaked. Names the operation that was open, because that is the
 * one whose share is now wrong and the reader has no other way to know which.
 */
fun leakMessage(open: String, thread: String = Thread.currentThread().name): String = String.format(
    Locale.ROOT,
    "%s was still open on thread %s at a point the caller said should be quiescent.%n" +
            "    Every sample taken on that thread between the leak and this check was billed to " +
            "%s,%n" +
            "    which does not look like an error — it looks like a finding, with a plausible " +
            "number beside it.%n" +
            "    A label placed with enter/exit has no finally: a body that throws leaves it set.",
    open, thread, open
)

/**
 * Whether one operation's long executions are evidence of anything, given what the machine was
 * doing to every operation at the same time — see [Report.machineFloor].
 *
 * All three conditions matter and the third was learned the moment this first ran: on a quiet
 * machine the floor falls to 1%, so a single long execution out of two thousand samples clears
 * three times it and means nothing whatever. A ratio against a small number is not evidence. The
 * floor on the share and the minimum count are what keep the answer honest when the machine is
 * behaving perfectly.
 *
 * One function so that the library's report and the bench's own check cannot drift apart, which
 * they did within an hour of the bench check being written by hand.
 */
fun isSuspect(hits: Long, stuckHits: Long, stuckInstances: Long, machineFloor: Double): Boolean =
    hits > 0 && stuckInstances >= Report.SUSPECT_MIN_INSTANCES &&
            stuckHits.toDouble() / hits > maxOf(machineFloor * Report.SUSPECT_OVER_BASELINE, Report.SUSPECT_MIN_SHARE)

/**
 * A duration in whatever unit keeps it readable. The fine tier spans four orders of magnitude, and
 * a column of nanoseconds makes 1.5 ms and 9 µs look like the same kind of number, which is the
 * one distinction this column exists to draw.
 */
fun duration(nanos: Double): String = when {
    nanos.isNaN() -> "-"
    nanos < 1_000 -> String.format(Locale.ROOT, "%.1f ns", nanos)
    nanos < 1_000_000 -> String.format(Locale.ROOT, "%.1f us", nanos / 1e3)
    else -> String.format(Locale.ROOT, "%.2f ms", nanos / 1e6)
}

/**
 * A total of thread-time, in whatever unit keeps it readable.
 *
 * Separate from [duration] because the two live at opposite ends of the scale: a per-call duration
 * runs to nanoseconds and an occupancy total to seconds, and one formatter covering both would
 * print 40 seconds as "40080.00 ms".
 */
fun threadTime(nanos: Double): String = when {
    nanos.isNaN() -> "-"
    nanos < 1_000_000_000.0 -> String.format(Locale.ROOT, "%.1f ms", nanos / 1e6)
    else -> String.format(Locale.ROOT, "%.2f s", nanos / 1e9)
}

/** One operation's line in a [Report]. */
class OperationStat(
    val id: Int,
    val name: String,
    val hits: Long,
    val calls: Long,
    /** Hits that caught an execution already running a tick earlier. */
    val stuckHits: Long,
    /** How many distinct executions those hits represent. */
    val stuckInstances: Long,
    /** Hits where the owning thread was not runnable. Zero when state sampling is off. */
    val waitingHits: Long = 0,
    /** Hits that were both long and waiting: a long execution caught with its thread parked. */
    val stuckWaitingHits: Long = 0,
    /** Ticks at which at least one thread was inside this operation. */
    val activeTicks: Long = 0,
) {
    /** The fraction of this operation's occupancy spent inside executions longer than a tick. */
    val stuckShare: Double get() = if (hits == 0L) 0.0 else stuckHits.toDouble() / hits

    /** The fraction of this operation's occupancy where its thread was parked, blocked or waiting. */
    val waitingShare: Double get() = if (hits == 0L) 0.0 else waitingHits.toDouble() / hits

    /**
     * Threads inside this operation at once, averaged over the ticks where it was running at all.
     *
     * The divisor that turns occupancy into elapsed time, and a diagnosis in its own right: the
     * same hundred thread-seconds of waiting is a convoy at fifteen threads and mild persistent
     * contention at 1.7, and the two want opposite fixes.
     */
    val concurrency: Double get() = if (activeTicks == 0L) Double.NaN else hits.toDouble() / activeTicks
}

/**
 * What a sampling session collected.
 *
 * Shares are over *labelled* samples. Samples that caught a thread outside any operation are
 * reported separately as [idleHits] rather than folded into the denominator — mixing them in would
 * make every share depend on how much uninstrumented code happened to be running.
 */
class Report(
    val operations: List<OperationStat>,
    val idleHits: Long,
    val ticks: Long,
    val samplingSpanNanos: Long,
    val durationNanos: Long,
    val threads: Int,
    val duty: DutyReport,
    /**
     * Why the session stopped early, or null. Non-null means the numbers below describe a run the
     * profiler could not have measured correctly, and they are shown only so the reader can see
     * what led to the verdict.
     */
    val failure: String? = null,
    /** Times a thread was found holding a label open where the caller said it should not be. */
    val imbalances: Int = 0,
    /** Whether each hit also recorded its thread's state. Without it the verdicts fall back. */
    val stateSampled: Boolean = false,
    /** Of [idleHits], those where the thread was not runnable. See Sampler.idleWaitingHits. */
    val idleWaitingHits: Long = 0,
    /** Threads still inside a hand-placed label when the session ended. */
    val openAtEnd: Int = 0,
    /**
     * Threads that arrived past the slot ceiling and are invisible to the long-execution detector.
     *
     * Captured at `stop()` rather than read from [Profiler] at render time, which is what it used
     * to do — and that had the two faults this project has now met twice. It is a process-lifetime
     * counter that never decreases, so in a process that profiles repeatedly every later report
     * warned about threads that arrived during an earlier session; and reading live global state
     * while rendering means a report can describe thread churn that happened *after* the session it
     * claims to be about.
     */
    val untrackedSlots: Int = 0,
) {
    /** False when a fatal finding stopped the session. See the severity ladder in plan.md. */
    val ok: Boolean get() = failure == null

    val labelledHits: Long get() = operations.sumOf { it.hits }

    fun shareOf(op: OperationStat): Double =
        if (labelledHits == 0L) 0.0 else op.hits.toDouble() / labelledHits

    /** Error chance alone would produce at this hit count — anything smaller is not measurable. */
    fun noiseFloorOf(op: OperationStat): Double =
        if (op.hits > 0) 1.0 / sqrt(op.hits.toDouble()) else Double.NaN

    /** What one sample is worth in thread-time: the step the sampler actually achieved. */
    val stepNanos: Double get() = if (ticks > 1) samplingSpanNanos.toDouble() / (ticks - 1) else Double.NaN

    /**
     * This operation's occupancy as thread-time rather than as a fraction — and it is the column to
     * compare between two runs.
     *
     * [shareOf] is a share of *labelled* samples, which is the right denominator (see the note on
     * [Report]) and a treacherous number to compare across runs: it re-scales every row at once
     * whenever the set of labels changes.
     *
     * Measured on the Lucene trial, two runs of each placement, where the only difference is that
     * one labels the factory that builds a clause's scorer and the other does not:
     *
     * ```
     *                 share                    occupancy
     *   phrase   58.4% 57.2%  ->  42.9% 43.6%   38.2 s 41.5 s  ->  41.0 s 40.1 s
     *   prefix   30.9% 32.3%  ->  48.6% 47.7%   20.2 s 23.5 s  ->  46.5 s 44.0 s
     * ```
     *
     * By share, the phrase clause appears to have become **fourteen points cheaper** — a change
     * with a story attached, and the story is false. Its occupancy does not separate by placement at
     * all: 38–41 seconds in every run, which is this machine's run-to-run spread. The clause that
     * actually changed doubled, and the coverage gained by the fix (65–73 s → 92–96 s) is that one
     * clause and nothing else.
     *
     * A misplaced label is invisible in the share column and obvious in this one.
     */
    fun occupancyNanosOf(op: OperationStat): Double = op.hits * stepNanos

    /**
     * How much *wall clock* had at least one thread inside this operation — as against
     * [occupancyNanosOf], which sums across threads.
     *
     * The distinction is the whole point and it only matters when threads wait. Eight threads
     * computing for five seconds is forty thread-seconds of occupancy and the sum is real: on one
     * thread it would genuinely take forty seconds. A hundred threads parked one second on one lock
     * is also a hundred thread-seconds, and there the sum is fiction — the clock advanced one
     * second and removing the lock would save one second, not a hundred. Elapsed is the number that
     * says so, and it costs one counter per operation.
     *
     * It is not latency: it is the union of every execution's interval, so it says the operation had
     * *somebody* inside it for this long and nothing about any single execution. And it is not a
     * counterfactual — see the warning at the foot of the report.
     */
    fun elapsedNanosOf(op: OperationStat): Double = op.activeTicks * stepNanos

    /** Thread-time spent inside any label. */
    val labelledNanos: Double get() = labelledHits * stepNanos

    /** Of [labelledHits], those that caught a thread that was not runnable. Zero without state. */
    val labelledWaitingHits: Long get() = operations.sumOf { it.waitingHits }

    /**
     * Coverage with both sides restricted to occupancy where the thread was actually runnable.
     *
     * The plain coverage figure divides labelled samples by *every* sample, and on a workload with
     * idle threads that reads as "the labels miss most of the run" when what it means is "most of
     * the run was nobody doing anything". On Lucene 79.2% of the unlabelled samples caught a thread
     * that was not runnable, which turns 49.8% coverage into something quite different.
     *
     * Both sides, not just the unlabelled one: a label can be held across a wait — the bench's
     * `lockedUpdate` spends 73% of itself parked — so leaving that inside the numerator while
     * removing it from the denominator would be the same mismatch of denominators this fix exists
     * to remove, pointing the other way. NaN when state was not sampled and the question cannot be
     * asked.
     */
    val runnableCoverage: Double
        get() {
            if (!stateSampled) return Double.NaN
            val labelled = labelledHits - labelledWaitingHits
            val idle = idleHits - idleWaitingHits
            return if (labelled + idle <= 0) Double.NaN else labelled.toDouble() / (labelled + idle)
        }

    /**
     * Thread-time the sampler observed at all — every slot read on every tick, labelled or not.
     *
     * The honest denominator for "how much of this run do my labels account for", and the number
     * that makes a coverage figure actionable: *"labels cover 94 s of 181"* is a sentence you can do
     * something about in a way that *"52%"* is not.
     */
    val observedNanos: Double get() = (labelledHits + idleHits) * stepNanos

    /** Occupancy that was not CPU, in samples. The whole run's supply of stalling. */
    val offCpuSamples: Double
        get() = if (duty.available) (1 - duty.duty) * (labelledHits + idleHits) else Double.NaN

    /**
     * How much of an operation's long-running time was certainly spent *running*, as a fraction.
     *
     * The question this answers is the one the long-execution signal cannot answer on its own, and
     * it decides what the reader should do about it. An execution that outlived a tick was either
     * waiting for something or working for a millisecond, and those want opposite responses: the
     * first means the share is occupancy and not CPU, the second means the share is honest and the
     * operation merely belongs in the coarse tier.
     *
     * The answer is arithmetic on two numbers already in the report. The whole run had only
     * [offCpuSamples] of stalling in it, from every cause together, so an operation whose long
     * executions occupy more than that must have been *running* for the difference — whatever the
     * rest of the run was doing, and without attributing a single sample to anybody.
     *
     * A lower bound and deliberately a loose one: it charges the operation with every stall in the
     * run, including stalls that happened somewhere else entirely. When it still comes out high, it
     * is certain.
     */
    fun runningFloorOf(op: OperationStat): Double {
        val offCpu = offCpuSamples
        if (offCpu.isNaN() || op.stuckHits == 0L) return Double.NaN
        return 1 - min(1.0, offCpu / op.stuckHits)
    }

    /**
     * The most an execution can plausibly have cost, from the sampling alone.
     *
     * The hit count is a count of rare events, so its upper confidence bound is
     * `hits + 2√hits + 3` — and that is `3` when nothing was ever sampled, which is the rule of
     * three and the reason an operation the sampler never caught is still measurable. Seeing it
     * zero times in *n* samples means its whole occupancy is under three ticks however large *n*
     * was, so its cost per call is under `3 × tick / calls`. Forty million calls at a 1 ms tick
     * puts that under 0.075 ns, a fraction of one cycle.
     *
     * This is what makes "too small" provable rather than suspected: if even the upper bound is
     * below the floor, no amount of missing evidence can rescue the label.
     */
    fun impliedUpperNanosOf(op: OperationStat): Double = impliedUpperNanos(op.hits, op.calls, stepNanos)

    /**
     * Operations labelled on something too small for the instrument to describe.
     *
     * The bound is inflated by [FLOOR_BIAS_ALLOWANCE] first: the sampler reads short operations
     * 5-9% low below 45 ns — measured against the configured truth — and that bias points the wrong way
     * here, since it would make an innocent operation look smaller than it is.
     */
    fun tooSmall(): List<OperationStat> = operations
        .filter { isTooSmall(it.hits, it.calls, stepNanos) }
        .sortedByDescending { it.calls }

    /**
     * What one execution appears to have cost, from the sampling alone: `hits × step / calls`.
     *
     * The smell test the person who wrote the code can apply and the tool cannot. An operation
     * known to be 20 ns showing 500 ns here is stalling on something, and no amount of share
     * arithmetic would have said so. It is occupancy per call, not CPU per call, and it inherits
     * the same bound as every other number here.
     */
    fun impliedNanosOf(op: OperationStat): Double =
        if (op.calls == 0L) Double.NaN else op.hits * stepNanos / op.calls

    /**
     * The run-wide rate of executions caught spanning a tick. Reported, but *not* what an operation
     * is judged against — see [machineFloor].
     */
    val stuckBaseline: Double
        get() = if (labelledHits == 0L) 0.0 else operations.sumOf { it.stuckHits }.toDouble() / labelledHits

    /**
     * How much of an operation's long-running time the *machine* can account for, and therefore the
     * floor an operation has to clear before it has anything to answer for.
     *
     * **The lesson this phase kept relearning: a baseline must not contain the effect it is used to
     * detect.** Three attempts, each broken by a workload the previous one had not met.
     *
     * 1. *The run-wide rate of long executions.* Assumes long executions are the exception. Against
     *    Calcite's planner that rate is 53.52%, because most of those labels genuinely are coarse,
     *    so three times it was unreachable and nothing could ever be named.
     * 2. *The non-CPU fraction from the duty cycle.* Correct for preemption and GC, which are what
     *    the machine does to everything — but a thread blocked on a lock is also off the CPU, so a
     *    blocking operation raises this floor and hides itself behind it. Measured: an operation
     *    with 88.61% of its occupancy in executions over a tick, against a floor of 35.43% that it
     *    had itself created. Not named.
     * 3. *The lower of that and what a typical operation experienced* — [typicalStuckShare]. The
     *    machine can only be blamed for what both estimates agree on, and the median across
     *    operations cannot be moved by the one operation that blocks.
     *
     * Being conservative about blaming the machine means being liberal about naming operations,
     * which is why [SUSPECT_MIN_SHARE] and [SUSPECT_MIN_INSTANCES] exist: they are what stops a
     * near-zero floor turning noise into an accusation.
     */
    val machineFloor: Double
        get() {
            val fromDuty = if (duty.available) 1 - duty.duty else Double.MAX_VALUE
            val typical = typicalStuckShare()
            val fromOperations = if (typical.isNaN()) Double.MAX_VALUE else typical
            val floor = minOf(fromDuty, fromOperations)
            return if (floor == Double.MAX_VALUE) stuckBaseline else floor
        }

    /**
     * What a *typical* operation's rate of long executions was — the median over operations with
     * enough hits to have a meaningful rate.
     *
     * The second estimate of the same quantity as the duty cycle, and it fails where the duty cycle
     * fails, in the opposite direction. Preemption and GC are charged to whatever was executing, in
     * proportion to its occupancy, so they raise *every* operation's rate together and the median
     * sees them. Blocking is concentrated in the operation doing the blocking, so the median does
     * not see it — which is exactly what a floor must not see.
     *
     * The median rather than the mean, because the mean is the run-wide rate that Calcite already
     * disproved: one operation holding most of the occupancy drags it up until nothing can clear it.
     */
    fun typicalStuckShare(): Double {
        val rates = operations.filter { it.hits >= MEDIAN_MIN_HITS }.map { it.stuckShare }.sorted()
        if (rates.isEmpty()) return Double.NaN
        return if (rates.size % 2 == 1) rates[rates.size / 2]
        else (rates[rates.size / 2 - 1] + rates[rates.size / 2]) / 2
    }

    /**
     * Operations whose executions span a tick far more often than the run as a whole — the ones
     * that are not the size their label claims.
     *
     * Both conditions are provisional and marked as such: this project sets tolerances from
     * measurement, and the measurements that would settle these are the contended bench operation
     * and the Calcite trial, neither of which has been run against this yet. The second condition
     * exists so that a single GC pause, which can freeze one operation across several ticks,
     * cannot on its own accuse it.
     */
    fun suspect(): List<OperationStat> = operations
        .filter { isSuspect(it.hits, it.stuckHits, it.stuckInstances, machineFloor) }
        .sortedByDescending { it.stuckShare }

    /**
     * What to do about an operation whose executions outlive a tick — which is not one answer, and
     * the trial is why.
     *
     * Calcite's rule labels are all flagged by this signal, and their shares were *correct*: they
     * agreed with an independent stack profiler to about a percentage point and produced the one
     * finding this project has to its name. Stopping a run over them would have destroyed it. So a
     * long execution is never fatal. What differs is the advice, and [runningFloorOf] decides
     * which advice applies.
     */
    fun verdictFor(op: OperationStat): String {
        // Sampled state answers this directly and per operation, so it is asked first. The
        // aggregate below is what this had to make do with before phase 6, and it survives only as
        // the fallback for a session that turned state sampling off.
        if (stateSampled && op.stuckHits > 0) {
            val waiting = op.stuckWaitingHits.toDouble() / op.stuckHits
            return when {
                // "Runnable", not "on a core" — the distinction is the whole known limit of this
                // signal. A thread the scheduler preempted is still runnable, so this rules out
                // waiting on another thread and does not rule out waiting for a core. The duty
                // cycle above bounds that second kind, and only in aggregate.
                waiting <= WAITING_NONE -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread runnable — it is not waiting on anything, " +
                            "so the share is honest and the operation wants a coarse label for its per-execution " +
                            "statistics (runnable is not the same as scheduled: see the duty cycle above)",
                    (1 - waiting) * 100
                )

                // The claim the old aggregate test could never make. It is now a measurement of
                // this operation rather than an alibi drawn from the whole run's budget.
                waiting >= WAITING_MOSTLY -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread parked or blocked — it is *waiting*, " +
                            "not working. Read this share as occupancy: it does not add up across threads the " +
                            "way CPU does, and %s of wall clock had anyone inside it at all",
                    waiting * 100, threadTime(elapsedNanosOf(op))
                )

                else -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread parked — part working, part waiting, " +
                            "and the split is measured rather than bounded",
                    waiting * 100
                )
            }
        }

        val running = runningFloorOf(op)
        return when {
            running.isNaN() ->
                "long, and with no duty cycle there is no way to say whether it was working or waiting"

            running >= RUNNING_CERTAIN -> String.format(
                Locale.ROOT,
                "at least %.0f%% of that was on CPU, so it is working and not waiting: the share is honest, and " +
                        "the operation wants a coarse label for its per-execution statistics",
                running * 100
            )

            // Not "it is waiting" — that is a claim this test cannot make without sampled state.
            // The whole run's off-CPU time is a single budget and it is charged in full against
            // every operation separately, so a small operation will always come out ambiguous
            // however innocent it is. What the reader can act on is the size of that budget: at 4%
            // nothing here can be mostly waiting, at 35% something is.
            else -> String.format(
                Locale.ROOT,
                "cannot say which — state sampling is off. The run's whole off-CPU time is %.1f%% of occupancy, " +
                        "and that is enough to account for all of it. Read this share as occupancy rather than " +
                        "as time on a core",
                (1 - duty.duty) * 100
            )
        }
    }

    fun render(): String = buildString {
        val achieved = if (ticks > 1) samplingSpanNanos.toDouble() / (ticks - 1) / 1e6 else Double.NaN
        if (failure != null) {
            appendLine("!".repeat(WIDTH))
            appendLine("PROFILING STOPPED — these numbers were not going to be right:")
            appendLine("  $failure")
            appendLine("Everything below is what led to that verdict, not a result. Pass strict=false to")
            appendLine("profile anyway, which is what to do when the code is not yours to change.")
            appendLine("!".repeat(WIDTH))
        }
        appendLine("=".repeat(WIDTH))
        appendLine(
            String.format(
                Locale.ROOT, "%,d labelled samples over %.1f s, %,d ticks at %.3f ms, %d threads",
                labelledHits, durationNanos / 1e9, ticks, achieved, threads
            )
        )
        // Coverage in units, not only as a ratio. A percentage is a comparison against a total the
        // reader has to take on trust; the total itself is the thing that makes the gap actionable,
        // and it is the first place a label in the wrong place shows up as a number.
        appendLine(
            String.format(
                Locale.ROOT,
                "labels cover %s of the %s of thread-time observed (%.1f%%); %s was outside every label, in %,d samples",
                threadTime(labelledNanos), threadTime(observedNanos),
                labelledHits * 100.0 / (labelledHits + idleHits).coerceAtLeast(1),
                threadTime(observedNanos - labelledNanos), idleHits
            )
        )
        // What the unlabelled time *was* is the reader's first question and the one they have no
        // other instrument for. "Most of the run is outside every label" means two opposite things —
        // work nobody labelled, or threads doing nothing — and only this line separates them.
        if (stateSampled && idleHits > 0) appendLine(
            String.format(
                Locale.ROOT,
                "  of that unlabelled time, %s was a thread not runnable (%.1f%%) and %s was a thread " +
                        "runnable with no label on it",
                threadTime(idleWaitingHits * stepNanos), idleWaitingHits * 100.0 / idleHits,
                threadTime((idleHits - idleWaitingHits) * stepNanos)
            )
        )
        // The coverage figure a reader should act on. The one above divides by every sample taken,
        // so a pool of idle threads reads as missing labels; this one asks the question only of
        // time when a thread was running, on both sides of the division.
        //
        // Printed only when it differs. On a workload where nothing waits the two are the same
        // number twice, and a line that restates the line above it is worse than no line: it costs
        // the reader a second of "what is different about this one" for nothing.
        val plainCoverage = labelledHits.toDouble() / (labelledHits + idleHits).coerceAtLeast(1)
        if (!runnableCoverage.isNaN() && abs(runnableCoverage - plainCoverage) >= COVERAGE_GAP) appendLine(
            String.format(
                Locale.ROOT,
                "  of the thread-time that was runnable at all, labels cover %s of %s (%.1f%%)",
                threadTime((labelledHits - labelledWaitingHits) * stepNanos),
                threadTime((labelledHits - labelledWaitingHits + idleHits - idleWaitingHits) * stepNanos),
                runnableCoverage * 100
            )
        )
        // The bound belongs beside the sampling rate, not in a footnote: both say how much the
        // numbers below are worth, one against chance and one against stalling.
        for (l in duty.lines()) appendLine(l)
        appendLine("=".repeat(WIDTH))
        appendLine(
            String.format(
                Locale.ROOT, "%-26s %8s %10s %8s %9s %7s %13s %8s %6s %10s %7s",
                "operation", "share", "occupancy", "waiting", "elapsed", "threads",
                "calls", "hits", "noise", "impl/call", "over 1t"
            )
        )
        appendLine("-".repeat(WIDTH))
        // Operations the sampler never caught are folded away rather than printed as a screen of
        // zeroes — Calcite's report carried twenty-five rules at 0.000%. Folded, not dropped: the
        // count says how many there were, and a *called* operation with no samples is a real
        // finding, since it means the label is on something too small to see.
        val (seen, unseen) = operations.partition { it.hits > 0 }
        for (op in seen.sortedByDescending { it.hits }) {
            appendLine(
                String.format(
                    Locale.ROOT, "%-26s %7.3f%% %10s %7.1f%% %9s %7.2f %,13d %8d %5.2f%% %10s %6.2f%%",
                    op.name, shareOf(op) * 100, threadTime(occupancyNanosOf(op)),
                    op.waitingShare * 100, threadTime(elapsedNanosOf(op)), op.concurrency,
                    op.calls, op.hits, noiseFloorOf(op) * 100,
                    duration(impliedNanosOf(op)), op.stuckShare * 100
                )
            )
        }
        appendLine("-".repeat(WIDTH))
        if (unseen.isNotEmpty()) {
            val called = unseen.filter { it.calls > 0 }
            appendLine(
                "${unseen.size} operations were never sampled and are folded away" +
                        (if (called.isEmpty()) " (none of them ran at all)"
                        else "; ${called.size} of them did run: " +
                                called.sortedByDescending { it.calls }.take(4).joinToString { it.name } +
                                (if (called.size > 4) ", …" else ""))
            )
        }
        appendLine("share is of labelled samples and is occupancy: waiting counts in full, which is what the")
        appendLine("  latency question wants — the duty cycle above bounds how much of it was waiting")
        appendLine("occupancy is hits x step as thread-time: absolute, so unlike share it does not move when a")
        appendLine("  label is added, moved or removed — which makes it the column to compare between two runs")
        appendLine("waiting is the share of those samples whose thread was parked, blocked or waiting; a thread")
        appendLine("  the scheduler merely preempted still reads runnable, so this is waiting on another thread")
        appendLine("elapsed is wall clock with at least one thread inside, and threads is occupancy / elapsed —")
        appendLine("  occupancy sums across threads and elapsed does not, so 100 s of waiting is a convoy to be")
        appendLine("  broken up at 15 threads and steady contention to be designed out at 1.7. Not latency: it")
        appendLine("  is every execution's interval unioned, so it says nothing about any single one of them")
        appendLine("noise is 1/sqrt(hits), the error chance alone gives")
        appendLine(
            String.format(
                Locale.ROOT,
                "implied/call is hits x step / calls; 'over 1 tick' is occupancy inside executions that outlived a tick",
            )
        )
        appendLine(
            String.format(
                Locale.ROOT,
                "  run-wide %.2f%%, of which the machine itself accounts for up to %.2f%% (preemption and GC pauses)",
                stuckBaseline * 100, machineFloor * 100
            )
        )
        for (op in suspect()) {
            appendLine(
                String.format(
                    Locale.ROOT,
                    "  ! %s: %,d executions lasted over a tick (%.1f%% of its occupancy against a %.1f%% machine floor, %s per call)",
                    op.name, op.stuckInstances, op.stuckShare * 100, machineFloor * 100,
                    duration(impliedNanosOf(op))
                )
            )
            appendLine("    " + verdictFor(op))
        }
        // The other end of the same question, and a warning rather than a verdict: a label below
        // the floor cannot move a ranking, so the cost of being wrong about one is the loudest
        // failure this tool has — stopping a run that was fine. It did exactly that in two
        // consecutive trials. Every offender is named; the reader decides.
        for (op in tooSmall()) {
            appendLine("  ! " + tooSmallMessage(op.name, op.calls, impliedUpperNanosOf(op)))
        }
        if (untrackedSlots > 0) {
            appendLine("    (${untrackedSlots} threads arrived past the $MAX_SLOTS-slot ceiling and are not checked)")
        }
        // A leaked label does not look like an error. It looks like a finding: one operation
        // quietly accumulating everybody else's samples, with a plausible number beside it.
        if (imbalances > 0 || openAtEnd > 0) {
            appendLine("-".repeat(WIDTH))
            if (imbalances > 0) appendLine(
                "! $imbalances labels were still open at a point the caller said should be quiescent — " +
                        "everything after each leak was billed to the leaked operation"
            )
            if (openAtEnd > 0) appendLine(
                "! $openAtEnd threads were still inside a hand-placed label when sampling stopped, which " +
                        "nothing can close now"
            )
            appendLine("  a label placed with enter/exit has no finally: a body that throws leaves it set")
        }
        appendLine("-".repeat(WIDTH))
        // Said in the output rather than in a document, because the one time it mattered it was
        // worth a factor of 275 and nothing on the screen hinted at it.
        appendLine("A share is where time went. It is not what removing the operation would save:")
        appendLine("in the one trial run against real code, an operation holding 46% of the time was worth")
        appendLine("275x when removed, because it was creating work for everything else as well as doing")
        appendLine("its own. The two numbers are different questions and the gap can be orders of magnitude.")
    }

    companion object {
        /**
         * How far above the run-wide rate an operation has to sit before it is named, how many
         * long executions it needs behind that rate, and the floor below which the rate is not
         * worth mentioning at all.
         *
         * All three are provisional. The measurements that would settle them — a bench operation
         * contending on a real lock at a known rate, and the Calcite trial, which has a rule of
         * ~1.5 ms per firing and one of ~9 µs per firing in the same run — have not been made yet.
         * Written here rather than buried so that the day they are measured, this is the one place
         * that changes.
         */
        const val SUSPECT_OVER_BASELINE = 3.0
        const val SUSPECT_MIN_INSTANCES = 20L
        const val SUSPECT_MIN_SHARE = 0.02

        /**
         * Below this, a label is not describing the operation it names.
         *
         * Measured rather than chosen: each leaf's sampled share against the configured truth, over
         * a hundredfold range of durations. From 70 ns upward every leaf lands within ±2.4% and
         * most of them inside their own noise floor; at 45 ns and below they read 4.5–9.1% low
         * against noise floors of 1.4–1.7%, so three to six times noise and all in the same
         * direction. The break sits between 45 and 70 ns.
         *
         * The hook's own cost is *not* what sets this. At 20 ns the hook is 8.5% of the operation
         * and is correctable in principle, since the call count measures exactly the quantity the
         * correction needs. What is not correctable is the bias, and beneath it the hazard that
         * C2 will shuffle work across the boundaries of adjacent short labels altogether.
         */
        const val FLOOR_NANOS = 50.0

        /**
         * How far coverage-over-runnable must sit from plain coverage before it is worth a line of
         * its own. Below this the two are the same number twice — see where it is used.
         */
        const val COVERAGE_GAP = 0.005

        /** How wide the report's rules and column layout are. */
        const val WIDTH = 126

        /** The short-operation bias, so the check cannot accuse an operation of the sampler's own error. */
        const val FLOOR_BIAS_ALLOWANCE = 1.2

        /** Hits below which an operation's rate of long executions is too noisy to enter the median. */
        const val MEDIAN_MIN_HITS = 100L

        /**
         * How much of an operation's long-running time must be *certainly* on CPU before the report
         * says it is working rather than waiting.
         *
         * A judgement, and a cautious one, because the bound behind it is already conservative — it
         * charges the operation with every stall in the whole run. Calcite's slowest rule clears it
         * at 91%; the bench's lock operation, which does nothing but wait, comes nowhere near.
         */
        const val RUNNING_CERTAIN = 0.5

        /**
         * Below this share of waiting among an operation's long samples, it was working.
         *
         * Not zero, because one sample in a thousand catching a GC pause or a page fault is not
         * the operation waiting on anything, and a verdict that hedged over it would hedge over
         * every operation on a real machine.
         */
        const val WAITING_NONE = 0.05

        /** Above this share, calling it waiting rather than working is the honest description. */
        const val WAITING_MOSTLY = 0.5
    }
}


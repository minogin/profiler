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
 * The shortest a coarse operation may be, given how much of the run it holds. See [Report.coarseTooSmall].
 *
 * The tier boundary from profiler.md, as arithmetic rather than a rule of thumb. Two conditions,
 * protecting different things and neither implying the other:
 *
 * 1. `40 ns x executions <= 1% of the run` protects **the program** — that you are measuring what
 *    you started with. In terms of share this is `d >= 4 us x share`.
 * 2. `40 ns <= 5% of the operation's own duration` protects **the number** — that it describes the
 *    code and not the instrument. This is the flat `d >= 800 ns`, and it is what binds below about
 *    20% share, which is nearly always.
 *
 * A 40 ns operation called rarely passes the first comfortably and is then reported at 80 ns: the
 * program is undisturbed and the measurement is 100% wrong. That is why both are needed.
 */
fun coarseFloorNanos(share: Double): Double =
    maxOf(Report.COARSE_FLOOR_NANOS, Report.COARSE_SHARE_NANOS * share)

/**
 * What to tell someone whose coarse label is on something too small to carry a context.
 *
 * Names the measured duration rather than an inferred one, and says which of the two conditions
 * bound, because the remedies differ: below the flat floor the label is simply in the wrong tier,
 * while an operation that fails only the share condition is big enough to measure and too big a
 * fraction of the run to afford measuring.
 */
fun coarseTooSmallMessage(name: String, count: Long, meanNanos: Double, requiredNanos: Double): String {
    // Which of the two conditions bound decides what the reader is being told, and they are
    // different complaints with different remedies. Saying "the number would describe the
    // instrument" about an operation whose per-execution overhead is a comfortable 4% is simply
    // false, and a warning that misdiagnoses is worse than one that does not fire.
    val shareBound = requiredNanos > Report.COARSE_FLOOR_NANOS
    val why = if (shareBound) String.format(
        Locale.ROOT,
        "    It runs often enough that %,d contexts at about %.0f ns come to over 1%% of the whole run,%n" +
                "    so the program you measured is not the program you started with. Per execution the%n" +
                "    overhead is only %.1f%%, so the label is accurate - there is just too much of it.",
        count, Report.COARSE_CONTEXT_NANOS, Report.COARSE_CONTEXT_NANOS / meanNanos * 100
    ) else String.format(
        Locale.ROOT,
        "    A context costs about %.0f ns to allocate, timestamp and stamp, which is %.0f%% of an%n" +
                "    operation this size - the number would describe the instrument as much as the code.",
        Report.COARSE_CONTEXT_NANOS, Report.COARSE_CONTEXT_NANOS / meanNanos * 100
    )
    return String.format(
        Locale.ROOT,
        "%s: %,d executions averaging %s, under the %s a coarse label needs here.%n%s%n" +
                "    Use a fine label, op(id) { }, or move the coarse label out to a batch of these.",
        name, count, duration(meanNanos), duration(requiredNanos), why
    )
}

/**
 * What to tell someone whose work is being billed to executions that have already finished.
 *
 * Names the coarse type, because that is the one whose numbers were about to be wrong and the reader
 * has no other way to know which. Says what to do, because unlike a leak there are two quite
 * different causes and they want opposite fixes: a task that was never meant to be part of the
 * request, and a request that closed before the work it was waiting for.
 */
fun staleContextMessage(worst: String, hits: Long, share: Double): String = String.format(
    Locale.ROOT,
    "%,d samples caught a thread working inside a coarse execution that had already been%n" +
            "    closed by the thread that opened it - mostly %s. That is %.1f%% of all the%n" +
            "    thread-time spent inside coarse executions.%n" +
            "    The time belongs to an execution which no longer exists, so it is excluded from%n" +
            "    every number %s reports rather than quietly inflating them.%n" +
            "    Either work was handed to another thread and never waited for, so it outlived the%n" +
            "    span carrying it - propagate only what the request actually joins - or a span is%n" +
            "    closed too early, before the work it covers has finished.",
    hits, worst, share * 100, worst
)

/**
 * What to tell someone who closed a label that was not the one open.
 *
 * Names both, because the interesting part is the pair: closing `parse` while `serialize` is open
 * means one of them is covering work that was never inside it, and which one depends on how the two
 * were meant to nest. The no-argument `exit()` this replaced could not see the difference at all.
 */
fun mismatchMessage(closed: String, open: String): String = String.format(
    Locale.ROOT,
    "exit(%s) was called while %s was the operation open on this thread.%n" +
            "    One of them now covers work that was never inside it, and the number that comes " +
            "out of%n" +
            "    that is plausible rather than obviously wrong — which is the direction that costs " +
            "you a day.%n" +
            "    The thread was unwound anyway, so this is one bad label and not every label after " +
            "it.",
    closed, open
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
class OperationStat internal constructor(
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
     * **Executions of this operation in flight at once**, averaged over the ticks where any were.
     *
     * It counts executions, not threads serving one of them, and the distinction is structural
     * rather than a convention we have chosen. A fine operation is atomic and never leaves the
     * thread that entered it — a body that suspends or hands off is by construction not fine — so
     * one thread inside the label *is* one execution of it, and the two counts are the same count.
     * The parallelism of a **single** execution across threads is not a quantity this tier can hold at
     * all: it needs an execution to have an identity that a second thread can be handed, which is
     * exactly what a coarse context is and what an integer in a slot can never be.
     *
     * So this is one factor of a product, and the other factor does not exist yet — it is
     * **parallelism** in the work-span sense, `work / span` for one execution, and it needs the
     * coarse tier:
     *
     *     threads inside = executions in flight x parallelism per execution
     *
     * Here the second factor is 1 by construction, so the product collapses and either reading
     * gives the same number. That is exactly why the column must stop being called "threads" now
     * rather than when a coarse column arrives beside it and the two factors come apart. See
     * plan.md, phase 5.
     *
     * **What this number depends on, stated because it is not the code.** Little's law relates the
     * mean over *all* time to the load — `L = lambda x W` — and this column is not that mean: it
     * divides by the ticks where the operation was occupied rather than by every tick, so
     * `column = lambda x W x parallelism / p_active`, and it is capped by the threads there are:
     *
     *     threads inside = min(lambda x W x parallelism, P)
     *
     * Below the cap it tracks the arrival rate; at the cap it reports the pool size and stops
     * tracking the load at all. Twice the clients is twice this number with the code unchanged, so
     * it is a property of the deployment in both regimes. It earns its place because [elapsedNanosOf]
     * cannot be computed without it, and because printed over the thread count — see
     * [Report.inFlightOf] — the ratio says which of the two regimes produced it.
     *
     * The divisor that turns occupancy into elapsed time: the same hundred thread-seconds of
     * waiting is a convoy at fifteen executions in flight and mild persistent contention at 1.7,
     * and the two want opposite fixes.
     */
    val inFlight: Double get() = if (activeTicks == 0L) Double.NaN else hits.toDouble() / activeTicks
}

/**
 * One coarse operation's line in a [Report].
 *
 * The asymmetry with [OperationStat] is the point of the tier. **Span is measured**, exactly, by two
 * timestamps per execution — it is the only quantity in this whole tool that is not sampled, and it
 * is the one a fine label can never produce. Everything about *where the time went* is still
 * sampled, because that is the only way to see inside an execution without pricing it.
 */
class CoarseStat internal constructor(
    val type: Int,
    val name: String,
    /** Executions that completed inside the session. Exact. */
    val count: Long,
    val spanSumNanos: Long,
    val spanMinNanos: Long,
    val spanMaxNanos: Long,
    internal val hist: LongArray,
    /** Samples that caught a thread inside this operation and not inside a nested coarse one. */
    val hits: Long,
    /** Of those, the ones whose thread was runnable. */
    val runningHits: Long,
    /** Samples caught anywhere under this operation, itself included. */
    val inclusiveHits: Long,
    /** Of those, the ones whose thread was runnable. `mean span - busy` is the waiting, quantified. */
    val runningInclusiveHits: Long,
    /** Distinct executions occupied at each tick, summed over ticks. See [parallelism]. */
    val instanceTicks: Long,
    /** Ticks at which at least one thread was anywhere under this operation. */
    val activeTicks: Long,
    /** Inclusive samples broken down by the fine operation they caught. Last entry: no fine label. */
    internal val fine: LongArray,
    /**
     * Samples that caught a thread under an execution of this type that had already been closed.
     *
     * **Not included in any other number on this line.** They are work billed to an execution that
     * no longer exists, and folding them in would let [busyPerExecutionNanos] exceed the mean span
     * it is supposed to sit inside — impossible arithmetic that reads as a finding. See
     * `Sampler.coarseStaleHits`.
     */
    val staleHits: Long = 0,
) {
    /** Mean measured duration of one execution. Exact, not sampled. */
    val meanSpanNanos: Double get() = if (count == 0L) Double.NaN else spanSumNanos.toDouble() / count

    /**
     * A percentile of the measured durations, to within [SpanHistogram.PRECISION] — and never above
     * [spanMaxNanos], which is exact.
     *
     * The histogram reports a percentile at the **top** of the bucket it fell into, because a
     * latency figure may overstate and must not understate. On many executions that rounding
     * disappears into the distribution. On few it does not: a single execution of 713.2 µs lands in
     * the bucket `[655.4, 720.9]` and every percentile prints as **720.9 µs**, against a `max` of
     * 713.2 — a p99 larger than the maximum, which is nonsense on its face and was the first thing a
     * reader noticed.
     *
     * Clamping to the measured maximum fixes it and cannot cost the guarantee: `max` is two
     * timestamps rather than a bucket, and the true p-th percentile of a set is never above its
     * true maximum. So the clamp can only ever move a value *down* to something still at or above
     * the truth. The result is exact whenever the top bucket is the one being asked about, which is
     * every percentile of a single execution.
     */
    fun percentileNanos(p: Double): Double =
        minOf(SpanHistogram.percentile(hist, count, p), spanMaxNanos.toDouble())

    /** Of the samples caught under this operation, those whose thread was parked or blocked. */
    val waitingHits: Long get() = inclusiveHits - runningInclusiveHits

    /**
     * **Threads inside one execution at once**, waiting ones counted in full.
     *
     * This and [working] are the same sum over the same ticks, split by whether the thread was on a
     * CPU, and both are needed because they answer different questions. This one is the **capacity**
     * answer: how many threads one execution ties up. A caller parked on its own join is not doing
     * work, but it is not available for anything else either, so with sixteen threads and five tied
     * up per request you can serve three at once and not four.
     *
     * It is the number that keeps the identity in [Report.inFlightOf]'s note exact — `threads inside
     * = executions in flight x this` — because [inFlight] counts an execution whether or not its
     * threads are running, and a factorisation has to count both sides the same way. It is also
     * consistent with the rest of the report, where `share`, `occupancy` and `in flight` all count a
     * waiting thread in full.
     *
     * **1.0 by construction until contexts cross threads.** Without propagation a context lives on
     * the thread that made it, so every occupied instance is occupied by exactly one — which made it
     * a known answer to check the instance stamping against before phase 5 could move it. Measured
     * at exactly 1.0000 on the bench with propagation off, and at 5.24 with it on, against a
     * stopwatch that said 4.24 helpers plus the one parked driver.
     */
    val inside: Double
        get() = if (instanceTicks == 0L) Double.NaN else inclusiveHits.toDouble() / instanceTicks

    /**
     * **Threads actually working on one execution at once** — `work / span`, the work-span model's
     * `T1/T8`, and what the literature means by *parallelism*.
     *
     * The **speedup** answer, and the sibling of [inside]: of the threads in the execution, the ones
     * a sample caught on a CPU. This is the one that says what splitting the work bought you, and
     * the one to invert through Amdahl when asking whether more threads would help. A caller parked
     * on a join contributes nothing here, correctly — it made the request no faster.
     *
     * `working = inside x (1 - waiting)`, so the two differ by exactly the waiting share the coarse
     * table already prints. They are shown side by side rather than one being derived by the reader,
     * because the arithmetic is not the point and both readings are wanted at a glance.
     *
     * This is the number the fine tier structurally cannot produce, and the one that is a property
     * of the code rather than of the load — a request that splits four ways splits four ways for one
     * client or a thousand.
     *
     * **Two limits, in opposite directions, and both are real.** It reads *low* when the pool is
     * saturated, because what gets measured is `min(what the code could do, threads actually free)`:
     * on the bench, seven drivers against eight helpers leaves nothing to fan out to and it falls
     * back to about 1. And it reads *high* as a speedup, because it is `work / span` of the run it
     * measured and parallelising usually costs extra work. On Lucene the same search is 14.04 ms on
     * one thread and 4.01 ms on eight — a 3.50x speedup — while this reads **6.32**, the difference
     * being the 1.80x more total CPU the parallel run spends. Re-running at another thread count is
     * the only thing that measures what parallelism bought; see `ideas.md` item 22.
     */
    val working: Double
        get() = if (instanceTicks == 0L) Double.NaN else runningInclusiveHits.toDouble() / instanceTicks

    /**
     * Executions of this operation running at once — the same quantity as [OperationStat.inFlight]
     * and with the same caveat: it tracks the load, not the code.
     */
    val inFlight: Double
        get() = if (activeTicks == 0L) Double.NaN else instanceTicks.toDouble() / activeTicks
}

/**
 * What a sampling session collected.
 *
 * Shares are over *labelled* samples. Samples that caught a thread outside any operation are
 * reported separately as [idleHits] rather than folded into the denominator — mixing them in would
 * make every share depend on how much uninstrumented code happened to be running.
 */
class Report internal constructor(
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
    /**
     * Threads that exited without calling `Profiler.release()` and had to be reclaimed.
     *
     * Reported rather than silently absorbed. A user who is quietly rescued learns nothing, and the
     * rescue depends on a garbage collection - in a process that does not collect, the slots stay in
     * the walk reading empty and every share is taken over a denominator inflated by dead threads.
     */
    val reclaimedSlots: Int = 0,
    /** The coarse tier's half of the report, empty when no coarse label was placed. */
    val coarse: List<CoarseStat> = emptyList(),
    /**
     * Coarse executions still open when the session ended, which is a leak by definition — nothing
     * can close them now, and their spans are missing from [coarse] for exactly that reason.
     */
    val openContextsAtEnd: Int = 0,
    /**
     * Labelled samples that fell under no coarse span. See [labelledOutsideCoarseShare], and
     * `Sampler.labelledOutsideCoarse` for what it is for.
     */
    val labelledOutsideCoarse: Long = 0,
    /** Samples caught under a coarse execution that had already been closed. See [CoarseStat.staleHits]. */
    val staleContextHits: Long = 0,
    /** Samples caught inside a coarse execution at all — the population [staleContextHits] is part of. */
    val coarseSampleHits: Long = 0,
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

    /**
     * [OperationStat.inFlight] printed **over the threads it could have been**, as `3.28/8`.
     *
     * The denominator is not decoration and it is not there to save the reader an arithmetic step.
     * The numerator alone invites the one reading it cannot support — *"this operation used 3.28
     * threads"*, as though that were a property of the operation — when it is a property of the
     * deployment, and of two different parts of it depending on which regime the run is in:
     *
     * - **below saturation** it tracks the arrival rate, so twice the clients is twice the number
     *   and the code is unchanged;
     * - **at the ceiling** it stops tracking the load at all and reports the pool size, because
     *   there is nothing left to spread the work onto.
     *
     * The ratio is the only part of the column that survives that objection: it says which of the
     * two regimes produced the number. `3.28/8` is *tracking your load*; `7.9/8` is *the pool is
     * pinned inside this label*, which is a finding — about the pool, not about the operation.
     */
    fun inFlightOf(op: OperationStat): String {
        val n = op.inFlight
        return if (n.isNaN()) "-" else String.format(Locale.ROOT, "%.2f/%d", n, threads)
    }

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

    /**
     * The whole report as text.
     *
     * ## ⚠ CHANGING THE OUTPUT? UPDATE `docs/output.md` IN THE SAME COMMIT ⚠
     *
     * Three things say what these numbers mean and they must agree: this method, the legend it
     * prints at the foot of every report, and [docs/output.md](../../../../../../docs/output.md).
     * The legend stays — a reader with a terminal and no browser needs the short version — so the
     * duplication is deliberate and the drift is the risk. It has already happened twice in this
     * codebase: a KDoc that described a function it had been moved away from, and a `1c` reword
     * that changed the duty lines and missed the column footer thirty lines below.
     *
     * The rule: **a line you add here needs a paragraph there, and a line you reword here needs
     * that paragraph reworded.** `output.md` carries the same warning pointing back at this file.
     */
    /**
     * Thread-time actually on a CPU inside one execution of [c], on average — **summed over every
     * thread that was in it**, which is why it can be larger than the span.
     *
     * `busy/exec = working x mean span`, so an execution fanned across six threads reports six spans
     * of thread-time. Measured on Lucene: a 4.01 ms search with `working` 6.32 reports 25.31 ms here.
     *
     * That makes `mean - busy/exec` the waiting **only while `inside` is 1**, which was every case
     * this tier had until a context could cross a thread. Use [CoarseStat.waitingHits] as a share
     * for the general answer; the subtraction is a shortcut that fan-out invalidates, and the
     * report's legend now says so.
     */
    fun busyPerExecutionNanos(c: CoarseStat): Double =
        if (c.count == 0L) Double.NaN else c.runningInclusiveHits * stepNanos / c.count

    /**
     * The most of [c]'s `working` the measured CPU duty cycle can support.
     *
     * `working` is built on [Thread.getState], and **thread state cannot see through a native
     * call**: a thread stopped inside a socket read is `RUNNABLE` as far as Java is concerned, so it
     * is counted as working. On a workload that waits on something outside the process that is not a
     * small error. Measured on PostgreSQL over a socket: `working` read **2.85** while the operating
     * system said the whole process used 1.03 s of CPU in a 20 s run — the report was crediting
     * **55x** more CPU than the machine ever spent.
     *
     * The duty cycle already knows. [DutyReport.labelledDuty] is measured from `getThreadCpuTime`,
     * which counts time on a processor and does see through the boundary, so `inside x labelledDuty`
     * is what that much occupancy could at most have been doing.
     *
     * **It is a run-wide figure applied to one operation, not a per-type bound**, exactly as the
     * fine tier's *"at most N pp of any share is a thread waiting"* line already is. One operation
     * can be more CPU-bound than the run it sits in, which is why [workingIsContradicted] allows a
     * factor before it complains rather than treating any excess as a fault.
     */
    fun workingCeilingOf(c: CoarseStat): Double =
        if (!duty.available) Double.NaN else c.inside * duty.labelledDuty

    /**
     * Whether [c]'s `working` claims more CPU than the machine was measured to have given it.
     *
     * The factor is slack for the run-wide-versus-per-type mismatch above, and it is deliberately
     * generous: the failure this catches is a factor of fifty, not a factor of two, so nothing is
     * gained by being strict and a false accusation costs the reader their trust in the column.
     */
    fun workingIsContradicted(c: CoarseStat): Boolean {
        val ceiling = workingCeilingOf(c)
        return ceiling.isFinite() && c.working.isFinite() && c.working > ceiling * WORKING_CEILING_SLACK
    }

    /** How much of the observed thread-time happened anywhere under [c]. */
    fun shareOf(c: CoarseStat): Double =
        if (observedNanos <= 0.0) 0.0 else c.inclusiveHits * stepNanos / observedNanos

    /**
     * The shortest [c] was allowed to be, from the tier boundary. See [coarseFloorNanos].
     *
     * The share is **reduced by its own sampling noise** before the share condition is applied.
     * `d ≥ 4 µs × share` only binds above about 20%, so an operation near that line could be pushed
     * over it by counting error alone — and this check prints an accusation. Taking the share a
     * standard error low means an operation is told off for holding too much of the run only when it
     * confidently does. The flat 800 ns floor needs no such treatment, which is the point of the
     * whole tier: **the duration here is measured, not inferred.**
     */
    fun coarseFloorNanosOf(c: CoarseStat): Double {
        val noise = if (c.inclusiveHits <= 0L) 1.0 else 1.0 / sqrt(c.inclusiveHits.toDouble())
        return coarseFloorNanos((shareOf(c) * (1 - noise)).coerceAtLeast(0.0))
    }

    /**
     * Coarse labels on something too small to carry a context.
     *
     * The counterpart of [tooSmall], and it can be strict where that one cannot. The fine floor has
     * to *infer* an operation's duration from hits ÷ calls, so it carries a statistical bound —
     * `hits + 2√hits + 3` — and a bias allowance, because accusing an innocent label is the failure
     * that matters. Here the duration is **measured**, two timestamps per execution, so the
     * comparison is exact and needs no slack at all. The only sampled input is the share, and that
     * is handled in [coarseFloorNanosOf].
     *
     * A warning and never fatal, for the reason the fine floor was demoted to one: a check that
     * stops a run has to be right about a machine it has never seen.
     */
    fun coarseTooSmall(): List<CoarseStat> = coarse
        .filter { it.count > 0 && it.meanSpanNanos < coarseFloorNanosOf(it) }
        .sortedByDescending { it.count }

    /**
     * Of the thread-time inside a fine label, the fraction that fell under **no** coarse span.
     *
     * The one number that can see work escaping its context. A coarse context stays on the thread
     * that created it, so an operation that hands work to a pool has that work running under the
     * callers' fine labels with no context — and its own span reports the gap as *waiting*. There is
     * no other signal for this in a single run: the floor check sees labels that are too small, the
     * balance check sees contexts left open, and neither sees a context that is simply not where the
     * work is.
     *
     * **Two readings, and the report gives both** because it cannot tell them apart:
     *
     * - *you bracketed part of the program and not the rest* — legitimate, and common;
     * - *work is escaping to threads your context never reached* — silent misattribution, and the
     *   thing worth knowing.
     *
     * What separates them is whether the operations named are ones you expected to be inside a span.
     * That is a question about your program, so the report states the measurement and stops.
     */
    val labelledOutsideCoarseShare: Double
        get() = if (labelledHits == 0L) 0.0 else labelledOutsideCoarse.toDouble() / labelledHits

    /**
     * Of labelled thread-time, the share billed to coarse executions that had already finished.
     *
     * The sibling of [labelledOutsideCoarseShare] and the opposite fault. That one is attribution
     * **lost** — work that reached no span — and it is recoverable by wrapping the hand-off. This is
     * attribution **invented**, and it is the direction the whole design is built to avoid, which is
     * why it is the one thing the sampler stops a strict session over on its own.
     */
    val staleContextShare: Double
        get() = if (coarseSampleHits == 0L) 0.0 else staleContextHits.toDouble() / coarseSampleHits

    /** The fine operations most of that time was in — where to look if it should have been covered. */
    fun outsideCoarseSuspects(): List<OperationStat> {
        if (coarse.isEmpty()) return emptyList()
        val under = LongArray(MAX_OPERATIONS + 1)
        for (c in coarse) for (i in under.indices) under[i] += c.fine[i]
        // Inclusive rows double-count a sample under nested types, so an operation is only named
        // when its labelled hits exceed what every span between them could account for. Erring
        // towards naming nothing is the right direction for a hint.
        return operations
            .filter { it.hits > 0 && it.hits > under[it.id] }
            .sortedByDescending { it.hits - under[it.id] }
    }

    /**
     * The coarse table, or nothing at all when no coarse label was placed.
     *
     * Printed after the fine one and not instead of it, because the two answer different halves of
     * the same question and the report is worth less if a reader has to choose. Fine sampling says
     * what is hot; a span says how long one execution took. Only the pair says *of the time this
     * request spent, this much was that operation*.
     */
    private fun StringBuilder.renderCoarse() {
        val seen = coarse.filter { it.count > 0 || it.inclusiveHits > 0 }
        if (seen.isEmpty()) return
        appendLine("=".repeat(WIDTH))
        appendLine(
            String.format(
                // 130 columns exactly, to the report's WIDTH. Two new columns had to come out of
                // the existing ones rather than out of the page: this table is read on a console.
                Locale.ROOT, "%-20s %11s %9s %9s %9s %9s %9s %9s %8s %6s %9s %11s",
                "coarse operation", "executions", "mean", "p50", "p90", "p99", "max",
                "busy/exec", "waiting", "inside", "working", "in flight"
            )
        )
        appendLine("-".repeat(WIDTH))
        for (c in seen.sortedByDescending { it.inclusiveHits }) {
            val waitShare =
                if (c.inclusiveHits == 0L) 0.0
                else (c.inclusiveHits - c.runningInclusiveHits).toDouble() / c.inclusiveHits
            appendLine(
                String.format(
                    Locale.ROOT, "%-20s %,11d %9s %9s %9s %9s %9s %9s %7.1f%% %6s %9s %11s",
                    c.name, c.count,
                    duration(c.meanSpanNanos), duration(c.percentileNanos(0.50)),
                    duration(c.percentileNanos(0.90)), duration(c.percentileNanos(0.99)),
                    duration(c.spanMaxNanos.toDouble()), duration(busyPerExecutionNanos(c)),
                    waitShare * 100,
                    if (c.inside.isNaN()) "-" else String.format(Locale.ROOT, "%.2f", c.inside),
                    // Value over its ceiling when the duty cycle contradicts it, the same idiom
                    // `in flight` already uses for a number read against the limit it must respect.
                    if (c.working.isNaN()) "-"
                    else if (workingIsContradicted(c))
                        String.format(Locale.ROOT, "%.2f/%.2f", c.working, workingCeilingOf(c))
                    else String.format(Locale.ROOT, "%.2f", c.working),
                    if (c.inFlight.isNaN()) "-" else String.format(Locale.ROOT, "%.2f/%d", c.inFlight, threads)
                )
            )
        }
        appendLine("-".repeat(WIDTH))
        // Share and occupancy, which the fine table has had all along and this one did not.
        //
        // **A coarse label gives you everything a fine one does and more** — the same hits, the same
        // ticks, plus measured spans and this breakdown — so the only reason to choose fine is the
        // ~40 ns a context costs. That was true of the data and not of the printed report, which
        // silently lost two columns when an operation was promoted. Nothing was missing; nobody had
        // printed it.
        //
        // On its own line rather than in the table because the table is already exactly [WIDTH]
        // columns and these two need nineteen more. Grouped with the `was:` line, since "how much of
        // the run" and "what it was made of" are the same question at two levels of detail.
        for (c in seen.sortedByDescending { it.inclusiveHits }) {
            if (c.inclusiveHits == 0L) continue
            appendLine(
                String.format(
                    Locale.ROOT, "  %s: %.3f%% of labelled thread-time, %s occupancy",
                    c.name, shareOf(c) * 100, threadTime(c.inclusiveHits * stepNanos)
                )
            )
            val parts = operations
                .map { it.name to c.fine[it.id] }
                .plus("unlabelled" to c.fine[NO_OP_INDEX])
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            if (parts.isEmpty()) continue
            val shown = parts.take(6).joinToString(", ") {
                String.format(Locale.ROOT, "%s %.1f%%", it.first, it.second * 100.0 / c.inclusiveHits)
            }
            val rest = parts.size - 6
            appendLine("  ${c.name} was: $shown" + if (rest > 0) ", and $rest more" else "")
        }
        appendLine("-".repeat(WIDTH))
        // ⚠ THE COARSE LEGEND. Also a paragraph in docs/output.md § The coarse table.
        appendLine("executions, mean and the percentiles are MEASURED, two timestamps per execution - the only")
        appendLine("  numbers here that are not sampled. Percentiles round UP to their bucket: at most 12.5%")
        appendLine("  high and never low, because a latency figure may overstate and must not understate")
        appendLine("busy/exec is thread-time on a CPU per execution, sampled, SUMMED OVER THE THREADS in it -")
        appendLine("  so it exceeds mean whenever inside is above 1, and busy/exec = working x mean. Where the")
        appendLine("  work does not leave its thread, mean - busy/exec is the WAITING, which is the one thing a")
        appendLine("  fine label can never tell you. waiting is that as a share, and is the reading that holds")
        appendLine("  either way")
        appendLine("working printed as a/b means the measured CPU duty cycle only supports b of it - the threads")
        appendLine("  are stopped in NATIVE calls, which Java thread state reports as RUNNABLE. See the warning below")
        appendLine("inside is THREADS in one execution at once, a parked one counted: what a request ties up.")
        appendLine("  working is the ones on a CPU - what splitting the work bought you, the T1/T8 of the")
        appendLine("  work-span model. working = inside x (1 - waiting), and a caller parked on its own join")
        appendLine("  is the gap between them. A saturated pool reads working low: it is min(what the code")
        appendLine("  could do, threads actually free)")
        appendLine("in flight is executions at once out of the threads there were — your load, not your code")
        appendLine("share and occupancy mean what they do in the fine table, and are here because a coarse")
        appendLine("  label gives you everything a fine one does and more - the only reason to prefer fine is")
        appendLine("  what a context costs. occupancy is INCLUSIVE: everything under this operation")
        appendLine("the 'was:' lines are the cross-tabulation: which fine operations ran under this one")
        // The one signal for work escaping its context, which neither the floor check nor the
        // balance check can see. Stated as a measurement with both readings, because it cannot tell
        // "I bracketed part of the program" from "work escaped to another thread" and guessing would
        // be worse than saying so.
        if (labelledOutsideCoarseShare >= OUTSIDE_COARSE_MIN_SHARE) {
            appendLine(
                String.format(
                    Locale.ROOT,
                    "%n%.1f%% of labelled thread-time (%s) was inside NO coarse span.",
                    labelledOutsideCoarseShare * 100, threadTime(labelledOutsideCoarse * stepNanos)
                )
            )
            val suspects = outsideCoarseSuspects().take(4)
            if (suspects.isNotEmpty()) {
                appendLine("  mostly: " + suspects.joinToString(", ") { it.name })
            }
            appendLine("  Two readings and this cannot tell them apart. Either you bracketed part of the program")
            appendLine("  and not the rest - fine - or work is ESCAPING its context onto threads it never reached,")
            appendLine("  and then those threads' time is missing from its busy/exec and shows up as its waiting.")
            appendLine("  Ask whether the operations above are ones you expected to be inside a span.")
        }
        // The opposite fault to the line above, and the more serious one. That one is attribution
        // lost and says so; this is attribution invented, and it is stated with the remedy because
        // there are two quite different causes with opposite fixes.
        // The column cannot see a native wait, and on a workload that does nothing else that is not a
        // rounding error. Named per type, with both readings, because the number is not useless —
        // it is right on a CPU-bound operation and it is the *reader* who knows which they have.
        val contradicted = coarse.filter { it.count > 0 && workingIsContradicted(it) }
        if (contradicted.isNotEmpty()) {
            appendLine()
            appendLine("!".repeat(WIDTH))
            appendLine("`working` READS HIGHER THAN THE MEASURED CPU DUTY CYCLE CAN SUPPORT")
            for (c in contradicted.sortedByDescending { it.working }) {
                appendLine(
                    String.format(
                        Locale.ROOT, "  %-22s working %.2f, but the duty cycle bounds it at %.2f",
                        c.name, c.working, workingCeilingOf(c)
                    )
                )
            }
            appendLine(
                String.format(
                    Locale.ROOT,
                    "  Threads inside these were on a CPU %.2f%% of the time, measured from the operating",
                    duty.labelledDuty * 100
                )
            )
            appendLine("  system's own clock. `working` is built on thread state, and a thread stopped inside a")
            appendLine("  NATIVE call - a socket read, a file read, an epoll wait - reads RUNNABLE to Java. So it")
            appendLine("  is counted as working, and `waiting` is short by the same amount.")
            appendLine("  `inside` is unaffected: it counts threads in the execution whatever they were doing.")
            appendLine("!".repeat(WIDTH))
        }
        if (staleContextHits > 0) {
            val worst = coarse.maxByOrNull { it.staleHits }
            appendLine()
            appendLine("!".repeat(WIDTH))
            appendLine(
                String.format(
                    Locale.ROOT,
                    "%.2f%% of the thread-time inside coarse executions (%s) was inside one that " +
                            "had ALREADY BEEN CLOSED",
                    staleContextShare * 100, threadTime(staleContextHits * stepNanos)
                )
            )
            if (worst != null && worst.staleHits > 0) appendLine("  mostly under: ${worst.name}")
            appendLine("  It is excluded from every number in the table above rather than inflating them.")
            appendLine("  Either work was handed to another thread and never waited for, so it outlived the span")
            appendLine("  carrying it - propagate only what the request actually joins - or a span is closed too")
            appendLine("  early, before the work it covers has finished.")
            appendLine("!".repeat(WIDTH))
        }
        if (openContextsAtEnd > 0) {
            appendLine(
                "! $openContextsAtEnd coarse executions were still open when sampling stopped: their spans are " +
                        "missing here, and every sample taken inside them was billed to them"
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
                Locale.ROOT, "%-26s %8s %10s %8s %9s %11s %13s %8s %6s %10s %7s",
                "operation", "share", "occupancy", "waiting", "elapsed", "in flight",
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
                    Locale.ROOT, "%-26s %7.3f%% %10s %7.1f%% %9s %11s %,13d %8d %5.2f%% %10s %6.2f%%",
                    op.name, shareOf(op) * 100, threadTime(occupancyNanosOf(op)),
                    op.waitingShare * 100, threadTime(elapsedNanosOf(op)), inFlightOf(op),
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
        // ⚠ THE LEGEND. Every line below is also a paragraph in docs/output.md § The table.
        // Reword one, reword the other, in the same commit. See the note on render().
        appendLine("share is of labelled samples and is occupancy: waiting counts in full, which is what the")
        appendLine("  latency question wants — the duty cycle above bounds how much of it was waiting")
        appendLine("occupancy is hits x step as thread-time: absolute, so unlike share it does not move when a")
        appendLine("  label is added, moved or removed — which makes it the column to compare between two runs")
        appendLine("waiting is the share of those samples whose thread was parked, blocked or waiting; a thread")
        appendLine("  the scheduler merely preempted still reads runnable, so this is waiting on another thread")
        appendLine("elapsed is wall clock with at least one thread inside, and in flight is occupancy / elapsed —")
        appendLine("  occupancy sums across threads and elapsed does not, so 100 s of waiting is a convoy to be")
        appendLine("  broken up at 15 in flight and steady contention to be designed out at 1.7. Not latency: it")
        appendLine("  is every execution's interval unioned, so it says nothing about any single one of them")
        appendLine("in flight counts EXECUTIONS at once, over the threads there were — never threads spent on one")
        appendLine("  execution. It tracks your arrival rate until it saturates at your pool, so it is a property")
        appendLine("  of this run and not of your code; read it against the denominator, where near the ceiling")
        appendLine("  means the pool is pinned in this label. It is here because elapsed needs it as the divisor")
        appendLine("noise is 1/sqrt(hits), the error chance alone gives")
        appendLine(
            String.format(
                Locale.ROOT,
                "implied/call is hits x step / calls; 'over 1 tick' is occupancy inside executions that outlived a tick",
            )
        )
        renderCoarse()
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
        // The same boundary from the other side. Unlike the fine floor this one is exact, because
        // the duration it compares is measured rather than inferred — see coarseTooSmall.
        for (c in coarseTooSmall()) {
            appendLine(
                "  ! " + coarseTooSmallMessage(c.name, c.count, c.meanSpanNanos, coarseFloorNanosOf(c))
            )
        }
        if (reclaimedSlots > 0) {
            appendLine("    ! $reclaimedSlots threads exited without Profiler.release() and were reclaimed —")
            appendLine("      call it when a thread finishes; until a slot is reclaimed it reads as an idle")
            appendLine("      thread and inflates the denominator every share above is taken over")
        }
        if (untrackedSlots > 0) {
            appendLine("    ! $untrackedSlots threads arrived past the $MAX_SLOTS-slot ceiling and were NOT SAMPLED —")
            appendLine("      their occupancy is missing from every number above, including the denominators")
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
        /**
         * How far past its duty-cycle ceiling `working` may read before the report says so.
         *
         * Slack for a run-wide duty cycle being applied to one operation, which can honestly be more
         * CPU-bound than the run around it. Generous on purpose: the failure this exists to catch was
         * measured at **55x** on PostgreSQL over a socket, so strictness buys nothing and a false
         * accusation costs the reader their trust in the column.
         */
        const val WORKING_CEILING_SLACK = 1.5

        const val FLOOR_NANOS = 50.0

        /**
         * What one coarse context costs: an allocation, two `nanoTime` calls and the accumulate.
         *
         * 21.5 ns of it is measured — the timed-wrapper fit from the Lucene trial — and the
         * allocation is not, so this is the tier boundary's working figure rather than a
         * measurement. See findings.md, "What a coarse label will cost per execution".
         */
        const val COARSE_CONTEXT_NANOS = 40.0

        /** `40 ns ≤ 5% of the operation`. The flat floor, and the one that binds below ~20% share. */
        const val COARSE_FLOOR_NANOS = 800.0

        /** `40 ns × executions ≤ 1% of the run`, rewritten as `d ≥ this × share`. */
        const val COARSE_SHARE_NANOS = 4000.0

        /**
         * Below this share, labelled time outside every coarse span is not worth a word.
         *
         * The same reasoning as "negligible operations" in profiler.md: something under a fraction
         * of a percent of the work cannot be where the work went, and a check that chased it would
         * spend its credibility on false positives. Netty is the case that set it — its request span
         * covers the pipeline, and a handful of samples still land outside it during connection
         * setup and at the tail of a write. That came to **0.0%, three milliseconds**, and printed
         * six lines of warning about it, which is alarm for nothing.
         */
        const val OUTSIDE_COARSE_MIN_SHARE = 0.01

        /**
         * How far coverage-over-runnable must sit from plain coverage before it is worth a line of
         * its own. Below this the two are the same number twice — see where it is used.
         */
        const val COVERAGE_GAP = 0.005

        /** How wide the report's rules and column layout are. */
        const val WIDTH = 130

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


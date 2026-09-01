package com.ticksnick

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
            "    Put an operation label on the enclosing loop instead and divide by the iteration count.",
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
            "    that is plausible rather than obviously wrong - which is the direction that costs " +
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
            "    which does not look like an error - it looks like a finding, with a plausible " +
            "number beside it.%n" +
            "    An operation label placed with enter/exit has no finally: a body that throws leaves it set.",
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

/**
 * `key            value`, with every value starting at the same column.
 *
 * The summary used to be four sentences of prose that began the moment the banner ended. A reader
 * looking for "how many threads" had to read a clause to find it, and a reader looking for whether
 * anything was wrong had no way to tell a fact from a caveat from a verdict — they were all just
 * text. Keys fix both: you scan the left column, and the key says what kind of thing the line is.
 */
internal fun row(key: String, value: String): String = String.format(Locale.ROOT, "%-13s %s", key, value)

/** A row that qualifies the one above it — a reason, a bound, a verdict. Values still align. */
internal fun subRow(key: String, value: String): String =
    String.format(Locale.ROOT, "  %-11s %s", key, value)

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
    /**
     * The band of group labels printed over a table's column heads.
     *
     * Ruled rather than centred text alone: the point of the band is to say where a group starts
     * and stops, and unruled words leave a reader to guess the boundaries from spacing.
     *
     * Groups are given as column ranges into [widths], which are the widths of the **data** row -
     * the band has to sit over the numbers, and the header row borrows two characters from the
     * first column to fit `Thread-time% v`. A range with an empty label is not a group; a span left
     * out entirely simply has no band over it.
     */
    /** Where each column starts, and where the group bars fall, given [FINE_COLUMNS]. */
    private fun layout(widths: IntArray, breaks: IntArray): Pair<IntArray, IntArray> {
        val starts = IntArray(widths.size)
        val bars = ArrayList<Int>()
        var pos = 0
        for (i in widths.indices) {
            starts[i] = pos
            pos += widths[i] + 1
            if (i in breaks && i < widths.size - 1) {
                bars += pos
                pos += 2
            }
        }
        return starts to bars.toIntArray()
    }

    /** The full width of a table with these columns and these bars. */
    private fun widthOf(widths: IntArray, breaks: IntArray): Int {
        val (starts, _) = layout(widths, breaks)
        return starts.last() + widths.last()
    }

    /** The rule under a grouped header: dashes, with a cross where each bar comes down. */
    private fun rule(widths: IntArray, breaks: IntArray): String {
        val (_, bars) = layout(widths, breaks)
        val line = CharArray(widthOf(widths, breaks)) { '-' }
        for (b in bars) line[b] = '+'
        return String(line)
    }

    /**
     * The band of group labels over a table's column heads.
     *
     * The labels are centred and unruled because the bars below them already say where each group
     * starts and stops - dashes as well would be the same boundary drawn twice. A group whose label
     * is empty still gets its bars: an unlabelled span says *these belong together and we have not
     * settled why*, which is the honest state of `Calls` and `Impl/call`.
     */
    private fun band(widths: IntArray, breaks: IntArray, vararg groups: Pair<IntRange, String>): String {
        val (starts, bars) = layout(widths, breaks)
        val line = CharArray(widthOf(widths, breaks)) { ' ' }
        for ((range, name) in groups) {
            val from = starts[range.first]
            val span = starts[range.last] + widths[range.last] - from
            if (name.isEmpty() || span < name.length) continue
            val pad = (span - name.length) / 2
            for (i in name.indices) line[from + pad + i] = name[i]
        }
        for (b in bars) line[b] = '|'
        return String(line).trimEnd()
    }

    /**
     * The two halves of an operation's thread-time, as `7.2% / 92.8%`, summing to 100.
     *
     * Printed as a pair because a reader could not tell whether `waiting` was a part of the
     * thread-time beside it or an addition to it, and no unit settles that - seconds against
     * seconds is as ambiguous as a percentage. Two shares that add to the whole are not readable
     * as an addition to it.
     *
     * The left half is **runnable**, which is not *working*: it counts a preempted thread and a
     * thread stopped inside a native call, both of which the JVM reports as `RUNNABLE`. It is an
     * upper bound on the work, and the time-on-CPU block is the only thing that bounds it in turn.
     */
    fun runnableSplit(waiting: Double): String =
        String.format(Locale.ROOT, "%.1f%% / %.1f%%", (1 - waiting) * 100, waiting * 100)

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
        return if (n.isNaN()) "-" else String.format(Locale.ROOT, "%.2f / %d", n, threads)
    }

    /** Thread-time spent inside any label. */
    val labelledNanos: Double get() = labelledHits * stepNanos

    /** Of [labelledHits], those that caught a thread that was not runnable. Zero without state. */
    val labelledWaitingHits: Long get() = operations.sumOf { it.waitingHits }

    /**
     * Coverage with both sides restricted to occupancy where the thread was actually runnable.
     *
     * The plain coverage figure divides labelled samples by *every* sample, and on a workload with
     * idle threads that reads as "the operations miss most of the run" when what it means is "most of
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
     * The honest denominator for "how much of this run do my operations account for", and the number
     * that makes a coverage figure actionable: *"operations cover 94 s of 181"* is a sentence you can do
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
                    "and %.1f%% of those long samples caught the thread runnable - it is not waiting on anything, " +
                            "so the share is honest and the operation wants a coarse label for its per-execution " +
                            "statistics (runnable is not the same as scheduled: see the duty cycle above)",
                    (1 - waiting) * 100
                )

                // The claim the old aggregate test could never make. It is now a measurement of
                // this operation rather than an alibi drawn from the whole run's budget.
                waiting >= WAITING_MOSTLY -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread parked or blocked - it is *waiting*, " +
                            "not working. Read this share as thread-time: it does not add up across threads the " +
                            "way CPU does, and %s of wall clock had anyone inside it at all",
                    waiting * 100, threadTime(elapsedNanosOf(op))
                )

                else -> String.format(
                    Locale.ROOT,
                    "and %.1f%% of those long samples caught the thread parked - part working, part waiting, " +
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
                "cannot say which - state sampling is off. The run's whole off-CPU time is %.1f%% of the thread-time, " +
                        "and that is enough to account for all of it. Read this share as thread-time rather than " +
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
    /**
     * Every explanation, collected, after every number.
     *
     * It used to sit *between* the two tables, so the report read numbers, prose, numbers, prose —
     * with `=` and `-` rules doing duty as both table borders and section breaks and not a blank
     * line anywhere. That was reported the first time somebody who had not written it tried to read
     * one: *"a wall of text, good for AI, very bad for a human"*. Explaining a column and
     * interleaving the explanation with the data are not the same decision, and only the first one
     * had ever been made.
     *
     * It is also shorter than it was, and not by cutting. Two of the paragraphs existed to correct a
     * name that promised the wrong thing — `share`, which is occupancy and not CPU, and `busy/exec`,
     * which summed over threads and so could exceed the span beside it. The first was renamed to
     * `occupancy%` and the second dropped as derivable from `working x mean`. A name that carries
     * its own caveat needs no paragraph, and unlike a paragraph it still works on the tenth run.
     *
     * ⚠ Every line here is also a paragraph in docs/output.md. Reword one, reword the other, in the
     * same commit.
     */
    private fun StringBuilder.renderLegend(full: Boolean) {
        appendLine()
        appendLine("HOW TO READ THIS")
        // Four lines, always. Not a summary of the rest — the four things that will make a reader
        // draw the wrong conclusion, and nothing else. Everything below them is reference, and
        // reference belongs in output.md where it can be read once rather than skipped thirty times.
        appendLine("  thread-time is sampled and summed across threads, NOT CPU")
        appendLine("  runnable/wait splits the thread-time beside it; runnable is NOT the same as working")
        appendLine("  concurrency is threads INSIDE at once, running or parked - not a speedup")
        appendLine("  a share is where time went, NOT what removing the operation would save")
        if (coarse.any { it.count > 0 || it.inclusiveHits > 0 }) {
            appendLine("  'on a CPU' is an upper bound on the speedup, not the speedup")
        }
        if (!full) {
            appendLine("  full notes: docs/output.md, or render(legend = true)")
            return
        }
        appendLine()
        appendLine("a share is where time went. It is not what removing the operation would save: in the one")
        appendLine("  trial run against real code, an operation holding 46% of the time was worth 275x when")
        appendLine("  removed, because it was creating work for everything else as well as doing its own. The")
        appendLine("  two numbers are different questions and the gap can be orders of magnitude")
        appendLine("thread-time% and thread-time are the same quantity, relative and absolute: sampled and")
        appendLine("  summed across threads, with waiting counted in full. NOT CPU - the time-on-CPU block above")
        appendLine("  bounds how much of it was waiting. The absolute one does not move when a label is added or")
        appendLine("  removed, which makes it the column to compare between two runs")
        appendLine("runnable / wait are the two halves of the thread-time beside them and add to 100%: the")
        appendLine("  share of its samples whose thread was parked, blocked or waiting, and the rest. Runnable is")
        appendLine("  NOT working - a thread the scheduler merely preempted reads runnable, and so does one")
        appendLine("  stopped inside a native call, which the JVM cannot see into. So the left half is an UPPER")
        appendLine("  BOUND on the work and the time-on-CPU block above is the only thing that bounds it in turn;")
        appendLine("  the right half is waiting that some other thread caused")
        appendLine("wall-time is the clock with at least one thread inside, and it is NOT summed across threads,")
        appendLine("  which thread-time is. Not latency either: it is every execution's interval unioned, so it")
        appendLine("  says nothing about any single one of them")
        appendLine("concurrency is thread-time / wall-time, printed over the threads there were: how many were")
        appendLine("  INSIDE this operation at once, running or parked. It is what turns a big thread-time back")
        appendLine("  into real cost - 100 s of waiting is a convoy to break up at 15 and steady contention to")
        appendLine("  design out at 1.7. NOT parallelism: threads inside are not threads executing, and two")
        appendLine("  threads asleep in the same label is a concurrency of 2 with nothing running at all.")
        appendLine("  Multiply by the runnable half for that. And it is a property of your LOAD, not your code:")
        appendLine("  it tracks the arrival rate until it saturates, so read it against the denominator - 3.28/8")
        appendLine("  is tracking your load, 7.9/8 is the pool pinned inside this label")
        appendLine("noise is 1/sqrt(hits), the error chance alone gives")
        appendLine("implied/call is hits x step / calls; 'over 1 tick' is thread-time inside executions that")
        appendLine("  outlived a tick")
        if (coarse.any { it.count > 0 || it.inclusiveHits > 0 }) {
            appendLine()
            appendLine("  for coarse operations:")
            appendLine("  executions, mean and the percentiles are MEASURED, two timestamps per execution - the")
            appendLine("    only numbers here that are not sampled. Percentiles round UP to their bucket, at most")
            appendLine("    12.5% high and never low, and never above max, which is exact")
            appendLine("  the 'parallelism:' line is two questions, and the semicolon separates them. Threads per")
            appendLine("    execution is your CODE - whether one execution is split at all, 1.00 unless a context")
            appendLine("    crossed a thread. Executions at once is your LOAD - how many were in the system.")
            appendLine("  'of it on a CPU' is the share of those threads a sample caught running: what splitting")
            appendLine("    the work bought you, the T1/T8 of the work-span model. It is inside x (1 - waiting),")
            appendLine("    so a caller parked on its own join is the gap. A saturated pool reads it low, and it")
            appendLine("    is an upper bound on the speedup rather than the speedup itself")
            appendLine("  'at most b' beside it means the measured time on CPU only supports b - the threads are")
            appendLine("    stopped in NATIVE calls, which Java thread state reports as RUNNABLE")
            appendLine("  the 'was:' lines are the cross-tabulation: which fine operations ran under this one")
        }
    }

    private fun StringBuilder.renderCoarse() {
        val seen = coarse.filter { it.count > 0 || it.inclusiveHits > 0 }
        if (seen.isEmpty()) return
        appendLine()
        appendLine("COARSE OPERATIONS")
        appendLine(
            String.format(
                // `busy/exec` is gone: it was `working x mean`, both of which are printed beside it,
                // and its name did not say it summed over the threads in the execution — which is
                // why it could exceed the span next to it and look like a defect. It existed for the
                // old headline "mean - busy/exec is the waiting", and that stopped being true the
                // moment work could cross a thread. `waiting` is the reading that survives.
                // `Total` is the sum of the measured spans - exact, since every one of them was
                // timed - and it is what the table is sorted by. It is the ranking that answers
                // "what do I fix first": mean alone puts a rare slow operation above a frequent one
                // costing ten times more, and a percentile ranks by tail, which is a latency
                // question rather than a cost one.
                //
                // Inclusive, so a parent contains its children and always sorts above them. Self
                // time is the honest answer once spans nest and the coarse tier cannot measure it
                // yet - ideas.md item 28.
                // `inside`, `working` and `in flight` left this table for the per-operation lines
                // below it. Asked where the parallelism was, a reader could not find it in a report
                // carrying three columns of it: the word never appeared, and the two questions the
                // columns answer - does one execution use several threads, and how many executions
                // run at once - were adjacent columns with nothing saying they were different
                // questions. A prose line can say both, and a column head cannot.
                Locale.ROOT, "%-25s %11s %10s %10s %10s %10s %10s %10s %15s",
                "Coarse operation", "Executions", "Total v", "Mean", "p50", "p90", "p99", "Max",
                "Runnable / Wait"
            )
        )
        appendLine("-".repeat(COARSE_WIDTH))
        for (c in seen.sortedByDescending { it.spanSumNanos }) {
            val waitShare =
                if (c.inclusiveHits == 0L) 0.0
                else (c.inclusiveHits - c.runningInclusiveHits).toDouble() / c.inclusiveHits
            appendLine(
                String.format(
                    Locale.ROOT, "%-25s %,11d %10s %10s %10s %10s %10s %10s %15s",
                    c.name, c.count,
                    duration(c.spanSumNanos.toDouble()),
                    duration(c.meanSpanNanos), duration(c.percentileNanos(0.50)),
                    duration(c.percentileNanos(0.90)), duration(c.percentileNanos(0.99)),
                    duration(c.spanMaxNanos.toDouble()),
                    runnableSplit(waitShare)
                )
            )
        }
        appendLine("-".repeat(COARSE_WIDTH))
        // Share and occupancy, which the fine table has had all along and this one did not.
        //
        // **A coarse label gives you everything a fine one does and more** — the same hits, the same
        // ticks, plus measured spans and this breakdown — so the only reason to choose fine is the
        // ~40 ns a context costs. That was true of the data and not of the printed report, which
        // silently lost two columns when an operation was promoted. Nothing was missing; nobody had
        // printed it.
        //
        // On its own line rather than in the table because these two need nineteen more columns,
        // and the table had already grown once to take `Total`. Grouped with the `was:` line, since
        // "how much of the run" and "what it was made of" are the same question at two levels of
        // detail. This occupancy is also no longer the sort key: the table is ordered by the exact
        // span total above, and these lines follow that order.
        for (c in seen.sortedByDescending { it.spanSumNanos }) {
            if (c.inclusiveHits == 0L) continue
            appendLine(
                String.format(
                    Locale.ROOT, "  %s: %.3f%% of the thread-time inside operations, %s of it",
                    c.name, shareOf(c) * 100, threadTime(c.inclusiveHits * stepNanos)
                )
            )
            // The word the report never said, and the reason a reader with a parallelism question
            // could not answer it from three columns about parallelism. Two questions, not one, and
            // in prose they can be told apart:
            //
            //   - threads per execution - a property of the code. Is *this request* split at all?
            //   - executions at once     - a property of the load. How many are in the system?
            //
            // `inside 1.00` reads as "no parallelism" and is compatible with a program running as
            // parallel as its thread count allows, which is exactly what the sandbox was doing.
            // Not on a single-threaded run, where every number on it is fixed by construction:
            // one thread per execution, one execution at once, over the one thread there was. The
            // only figure that would vary is `on a CPU`, and with one thread inside it is exactly
            // `1 - waiting`, which is a column in the table above. A line that can only restate its
            // neighbours is how a report earns the wall-of-text complaint - three of them per
            // operation, in the report a first run produces.
            if (threads > 1 && (!c.inside.isNaN() || !c.inFlight.isNaN())) {
                val perExecution =
                    if (c.inside.isNaN()) "thread count per execution unknown"
                    else String.format(
                        Locale.ROOT, "%.2f thread per execution%s", c.inside,
                        // The ceiling idiom the column used, kept: a `working` the duty cycle
                        // contradicts is printed over the most the machine can support.
                        if (c.working.isNaN()) ""
                        else if (workingIsContradicted(c))
                            String.format(
                                Locale.ROOT, ", %.2f of it on a CPU (at most %.2f)",
                                c.working, workingCeilingOf(c)
                            )
                        else String.format(Locale.ROOT, ", %.2f of it on a CPU", c.working)
                    )
                val atOnce =
                    if (c.inFlight.isNaN()) ""
                    else String.format(
                        Locale.ROOT, "; %.2f executions at once over %s",
                        c.inFlight, plural(threads.toLong(), "thread")
                    )
                appendLine("  ${c.name} parallelism: $perExecution$atOnce")
            }
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
        if (seen.any { it.inclusiveHits > 0 }) appendLine("-".repeat(COARSE_WIDTH))
        // The one signal for work escaping its context, which neither the floor check nor the
        // balance check can see. Stated as a measurement with both readings, because it cannot tell
        // "I bracketed part of the program" from "work escaped to another thread" and guessing would
        // be worse than saying so.
        if (labelledOutsideCoarseShare >= OUTSIDE_COARSE_MIN_SHARE) {
            appendLine(
                String.format(
                    Locale.ROOT,
                    "%n%.1f%% of thread-time inside fine operations (%s) was inside NO coarse span.",
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
            appendLine("`on a CPU` READS HIGHER THAN THE MEASURED TIME ON CPU CAN SUPPORT")
            for (c in contradicted.sortedByDescending { it.working }) {
                appendLine(
                    String.format(
                        Locale.ROOT, "  %-22s on a CPU %.2f, but the measured time on CPU bounds it at %.2f",
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
            appendLine("  system's own clock. `on a CPU` is built on thread state, and a thread stopped inside a")
            appendLine("  NATIVE call - a socket read, a file read, an epoll wait - reads RUNNABLE to Java. So it")
            appendLine("  is counted as working, and `waiting` is short by the same amount.")
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

    /**
     * @param legend every column explained, at the bottom. Off by default: see [renderLegend].
     */
    fun render(legend: Boolean = false): String = buildString {
        val achieved = if (ticks > 1) samplingSpanNanos.toDouble() / (ticks - 1) / 1e6 else Double.NaN
        if (failure != null) {
            appendLine("!".repeat(WIDTH))
            appendLine("PROFILING STOPPED - these numbers were not going to be right:")
            appendLine("  $failure")
            appendLine("Everything below is what led to that verdict, not a result. Pass strict=false to")
            appendLine("profile anyway, which is what to do when the code is not yours to change.")
            appendLine("!".repeat(WIDTH))
        }
        // The report used to open on a rule and a statistics line, so nothing on screen said what
        // the block of text was and a reader scrolling back had no landmark to stop at.
        //
        // Five lines against a report of twenty-three is not free, and it was chosen knowing that.
        // Backslashes are doubled here and nowhere else in this file, which is the one way this
        // block can be broken by a well-meaning edit.
        appendLine(" _____ ___ ____ _  ______  _   _ ___ ____ _  __")
        appendLine("|_   _|_ _/ ___| |/ / ___|| \\ | |_ _/ ___| |/ /")
        appendLine("  | |  | | |   | ' /\\___ \\|  \\| || | |   | ' /")
        appendLine("  | |  | | |___| . \\ ___) | |\\  || | |___| . \\")
        appendLine("  |_| |___\\____|_|\\_\\____/|_| \\_|___\\____|_|\\_\\")
        appendLine()
        appendLine(
            row(
                // "3 labelled, 1 unlabelled" read as a count of labels rather than of samples of
                // labelled work, which is the wrong noun and the one a first-time reader lands on.
                // A sample is one photograph of one thread, so saying how many were taken and then
                // where they landed makes the total the subject and the split a property of it.
                "Samples", String.format(
                    Locale.ROOT, "%,d taken over %.1f s - %,d inside an operation, %,d outside every operation",
                    labelledHits + idleHits, durationNanos / 1e9, labelledHits, idleHits
                )
            )
        )
        appendLine(
            row(
                // Where the sample count comes from, which the report never said and which is the
                // arithmetic everything below rests on. Deliberately "one per thread per tick"
                // rather than an equation: a thread that registers partway through the run
                // contributes fewer, so ticks x threads is a ceiling and not an identity - 7 ticks
                // and 1 thread produced 4 samples on a short run, and that gap is the slot
                // registering on the first labelled call.
                //
                // "mean (jittered)" because this is the achieved interval, not the requested step,
                // and the two differ on purpose: the interval is drawn +/-25% around the step so
                // the sampler cannot lock onto a workload with a rhythm of its own. Over a short
                // run the mean of a handful of draws is visibly off the step - 5 ticks printed
                // 0.969 ms against a 1 ms request - and without this word that reads as a defect.
                "Sampling", String.format(
                    Locale.ROOT, "%,d ticks at %.3f ms mean (jittered) x %s - one sample per thread per tick",
                    ticks, achieved, plural(threads.toLong(), "thread")
                )
            )
        )
        appendLine(
            row(
                "Coverage", String.format(
                    Locale.ROOT, "%s of %s thread-time observed (%.1f%%)",
                    threadTime(labelledNanos), threadTime(observedNanos),
                    labelledHits * 100.0 / (labelledHits + idleHits).coerceAtLeast(1)
                )
            )
        )
        // What the unlabelled time *was* is the reader's first question and the one they have no
        // other instrument for. "Most of the run is outside every label" means two opposite things,
        // and which one it is decides whether the labels are in the wrong place or the program is
        // simply idle.
        if (stateSampled && idleHits > 0) {
            val parked = idleWaitingHits * (observedNanos - labelledNanos) / idleHits.toDouble()
            appendLine(
                subRow(
                    "Outside", String.format(
                        Locale.ROOT, "%s - %s parked (%.1f%%), %s runnable inside no operation",
                        threadTime(observedNanos - labelledNanos), threadTime(parked),
                        idleWaitingHits * 100.0 / idleHits,
                        threadTime(observedNanos - labelledNanos - parked)
                    )
                )
            )
            val runnable = observedNanos - parked
            // Only when it differs from `Coverage` above. It exists to answer a second question -
            // of the thread-time that was doing anything at all, how much do the labels cover -
            // and when almost nothing was parked it is the same figure to a decimal place, which
            // is a line that teaches the reader the block repeats itself.
            val sameAsCoverage =
                observedNanos > 0 && runnable > 0 &&
                        Math.round(labelledNanos * 1000.0 / runnable) ==
                        Math.round(labelledNanos * 1000.0 / observedNanos)
            if (runnable > 0 && !sameAsCoverage) appendLine(
                subRow(
                    "Runnable", String.format(
                        Locale.ROOT, "operations cover %s of %s (%.1f%%)",
                        threadTime(labelledNanos), threadTime(runnable), labelledNanos * 100.0 / runnable
                    )
                )
            )
        } else if (idleHits > 0) {
            appendLine(
                subRow(
                    "Outside",
                    "${threadTime(observedNanos - labelledNanos)} - thread state was not sampled"
                )
            )
        }

        for (l in duty.lines(durationNanos, reclaimedSlots)) appendLine(l)
        appendLine()
        // FINE, not bare OPERATIONS. `OPERATIONS` beside `COARSE OPERATIONS` makes one tier the
        // default and the other the exception, which is the asymmetry `registerFine`/`registerCoarse`
        // was introduced to remove — reproduced in the output an hour after it was fixed in the API.
        appendLine("FINE OPERATIONS")
        // The band over the column heads. Eleven columns is more than anyone reads as a list, and
        // the groups are the reading order: how much time went here, how it was spread over
        // threads, and how far the row can be trusted.
        //
        // The third band repeats the name of a column inside it, which is provisional and known to
        // be so - "just name the group Calls for now". The group's natural name is the noun its
        // first column already carries, and the ways out are to rename that column (`Count`, with
        // the band acting as its prefix) or to leave the band empty and let the two heads speak for
        // themselves. Recorded in ideas.md rather than settled here.
        if (operations.any { it.hits > 0 }) appendLine(
            band(
                FINE_COLUMNS, FINE_BREAKS,
                1..3 to "Load", 4..5 to "Spread", 6..7 to "Calls", 8..10 to "Trust"
            )
        )
        if (operations.any { it.hits > 0 }) appendLine(
            String.format(
                // `v` marks the column the table is sorted by, which nothing said before - and at
                // two rows a reader cannot tell a sorted table from an unsorted one by looking.
                // ASCII, not an arrow glyph: a Windows console is not UTF-8 by default.
                //
                // 22 and 12 rather than 26 and 8 so the wider label still ends where the values
                // end. The header was already two columns out of step with its rows before the
                // marker was added, because `Occupancy%` never fitted the 8 it was given.
                // The bars force the header and the data rows onto the same column widths. They
                // had differed - 20/14 against 26/8 - so that `Thread-time% v` could sit over an
                // 8-wide number without shifting everything after it, and that trick is invisible
                // until a vertical line is drawn through it and comes out crooked.
                Locale.ROOT, "%-26s | %14s %11s %15s | %9s %21s | %13s %20s | %8s %6s %7s",
                // `occupancy%` and not `share`, because it is the same quantity as the column beside
                // it — one relative, one absolute. Calling one of them `share` invented a
                // distinction that does not exist and hid the one that does: neither is CPU. A
                // reader does not misread `occupancy%` as processor time, which is what a paragraph
                // of legend used to be for.
                // `Thread-time` and `Wall-time`, not `occupancy` and `elapsed`. The pair is the
                // point of having both columns - eight threads computing five seconds is forty
                // thread-seconds and the sum is real, a hundred threads parked one second on a lock
                // is also a hundred and there the sum is fiction - and neither old name said which
                // was which. `occupancy` was the worse of the two: the rest of the report already
                // called that quantity thread-time, in `Coverage` and on the coarse lines, so one
                // quantity had two names.
                //
                // The concurrency column is gone. It was `threads`, renamed to `in flight` to stop
                // it being read as one execution spread over several threads, and neither name
                // landed: a reader who wanted exactly this number could not find it under the
                // first name and could not read it under the second.
                //
                // It was `Thread-time / Wall-time` with both operands printed beside it, which is
                // the argument that deleted `busy/exec`. A quantity needing a paragraph before it
                // can be read, and derivable from its neighbours, does not earn a column - and now
                // that those neighbours are named for what they are, the division says it plainly.
                // Both halves of the split, summing to 100%, rather than the waiting half alone.
                // Asked whether waiting was part of thread-time or on top of it, neither the unit
                // nor a header phrasing settles it - but two shares that add to the whole cannot be
                // read as an addition to it. Andrey's suggestion, and better than the "of which
                // waiting" header it replaced.
                //
                // `Runnable`, not `Run` or `CPU`. The `-able` is the whole distinction: it means
                // *not blocked*, not *executing*. A preempted thread reads runnable - 14-18% of
                // wall time on this machine on a bench that never blocks - and so does a thread
                // stopped in a native call, which is how the JDBC trial got a number 55x above what
                // the machine had spent. The left half is an upper bound on work; only the
                // time-on-CPU block above can say how much of it was real.
                // `Concurrency / Threads` rather than leaving it to be divided out of the two
                // columns before it. It came back after being dropped, on the grounds that a report
                // about a threaded program should not make a person do the arithmetic that tells
                // them whether it was threaded - which is right, and is the same standard the
                // `Runnable / Wait` split is held to.
                //
                // `Concurrency` and not `Parallelism`: the number counts threads INSIDE the label
                // whether they are running or parked, which is what concurrency means; parallelism
                // needs them executing. The run that asked for this column is the argument - two
                // threads inside `work1` at 7.2% runnable, so concurrency 2.00 and a parallelism of
                // about 0.14, and calling the 2.00 parallelism would have been flatly false.
                "Operation", "Thread-time% v", "Thread-time", "Runnable / Wait", "Wall-time",
                "Concurrency / Threads",
                // `Impl/call` sits beside `Calls` because it is hits x step / calls, and it had
                // been stranded among the statistics.
                // `Thread-time per call` and not `Impl/call`, which was Andrey's: "implied" singled
                // this column out as inferred when every number in the table except `Calls` is
                // sampled, and `Impl` reads as implementation. The name states the relationship
                // instead - both operands are columns on the same row - and inherits the caveat
                // that `Thread-time` already carries. It also drops a third meaning for the slash,
                // which reads as "per" here and as "two values" twice to its left.
                "Calls", "Thread-time per call", "Hits", "Noise", "Over 1t"
            )
        )
        if (operations.any { it.hits > 0 }) appendLine(rule(FINE_COLUMNS, FINE_BREAKS))
        // Operations the sampler never caught are folded away rather than printed as a screen of
        // zeroes — Calcite's report carried twenty-five rules at 0.000%. Folded, not dropped: the
        // count says how many there were, and a *called* operation with no samples is a real
        // finding, since it means the label is on something too small to see.
        val (seen, unseen) = operations.partition { it.hits > 0 }
        // A table with no rows is two rules around nothing, and the twenty lines of column
        // explanation that used to follow it explained columns that had no data. Say what happened
        // instead: an empty table is nearly always a run too short for the sampler to catch, and
        // that is worth naming rather than leaving the reader to infer from a blank.
        if (seen.isEmpty()) {
            appendLine(
                if (labelledHits == 0L && ticks < MIN_TICKS_FOR_A_TABLE)
                    "  nothing was sampled - $ticks ticks is too few for the sampler to catch anything. " +
                            "Run for longer, or lower stepMillis."
                else "  nothing was sampled."
            )
        }
        for (op in seen.sortedByDescending { it.hits }) {
            appendLine(
                String.format(
                    Locale.ROOT, "%-26s | %13.3f%% %11s %15s | %9s %21s | %,13d %20s | %8d %5.2f%% %6.2f%%",
                    op.name, shareOf(op) * 100, threadTime(occupancyNanosOf(op)),
                    runnableSplit(op.waitingShare), threadTime(elapsedNanosOf(op)),
                    inFlightOf(op),
                    op.calls, duration(impliedNanosOf(op)),
                    op.hits, noiseFloorOf(op) * 100, op.stuckShare * 100
                )
            )
        }
        if (operations.any { it.hits > 0 }) appendLine(rule(FINE_COLUMNS, FINE_BREAKS))
        if (unseen.isNotEmpty()) {
            val called = unseen.filter { it.calls > 0 }
            // "called anyway" rather than "N of them did run", which reads as nonsense at N = 1 —
            // and one is the common case on a short run, which is exactly when a reader is most
            // likely to be new to the report.
            appendLine(
                "  ${plural(unseen.size.toLong(), "operation")} never sampled and folded away" +
                        (if (called.isEmpty()) " (none of them ran at all)"
                        else "; called anyway: " +
                                called.sortedByDescending { it.calls }.take(4).joinToString { it.name } +
                                (if (called.size > 4) ", ..." else ""))
            )
        }
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
                    "  ! %s: %,d executions lasted over a tick (%.1f%% of its thread-time against a %.1f%% machine floor, %s per call)",
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
            appendLine("    ! $reclaimedSlots threads exited without Profiler.release() and were reclaimed -")
            appendLine("      call it when a thread finishes; until a slot is reclaimed it reads as an idle")
            appendLine("      thread and inflates the denominator every share above is taken over")
        }
        if (untrackedSlots > 0) {
            appendLine("    ! $untrackedSlots threads arrived past the $MAX_SLOTS-slot ceiling and were NOT SAMPLED -")
            appendLine("      their thread-time is missing from every number above, including the denominators")
        }
        // A leaked label does not look like an error. It looks like a finding: one operation
        // quietly accumulating everybody else's samples, with a plausible number beside it.
        if (imbalances > 0 || openAtEnd > 0) {
            appendLine("-".repeat(WIDTH))
            if (imbalances > 0) appendLine(
                "! $imbalances operation labels were still open at a point the caller said should be quiescent - " +
                        "everything after each leak was billed to the leaked operation"
            )
            if (openAtEnd > 0) appendLine(
                "! $openAtEnd threads were still inside a hand-placed operation label when sampling stopped, " +
                        "nothing can close now"
            )
            appendLine("  an operation label placed with enter/exit has no finally: a body that throws leaves it set")
        }
        appendLine("-".repeat(WIDTH))
        // Said in the output rather than in a document, because the one time it mattered it was
        // worth a factor of 275 and nothing on the screen hinted at it.
        renderLegend(legend)
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
         *
         * **Was 1.5, which contradicted every word above it.** It fired on a sandbox run at 2.2x -
         * a program of two threads and a `Thread.sleep`, with no native call anywhere in it - and
         * blamed a socket read. The real gap there is preemption: a thread that is runnable but
         * waiting for a core reads `RUNNABLE` without being on one, and that is 14-18% of wall time
         * on this machine on a bench that never blocks (see `Sampler.kt`). A threshold below that
         * is measuring the scheduler, not the program.
         *
         * 4.0 still catches the case this exists for by a factor of thirteen. What it gives up is a
         * genuine native-wait problem between 1.5x and 4x, which is a band this machine fills with
         * noise anyway.
         */
        const val WORKING_CEILING_SLACK = 4.0

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
        /** Below this many ticks, an empty table is the run being short rather than a finding. */
        const val MIN_TICKS_FOR_A_TABLE = 50

        // The report-level rules and the warning banners, matching the widest table on the page.
        // The fine table no longer has a width constant of its own - it is computed from its
        // columns and bars, because a hand-kept number is exactly how the rules came to print at
        // three different lengths on one page.
        const val WIDTH = 168

        /**
         * The coarse table alone, which is narrower than [WIDTH] since the three parallelism
         * columns moved to the lines under it.
         *
         * Drawn to the table rather than to a round number. It briefly went the other way - 141,
         * to fit `Total` - on the grounds that the 130 had never been measured against anything,
         * which is true and is in the friction log. Then the parallelism columns left and took
         * more than `Total` had added.
         */
        const val COARSE_WIDTH = 119

        /**
         * The fine table, likewise drawn to itself.
         *
         * A run printed rules of three different lengths on one page - 130 around the fine table,
         * 112 around the coarse one, 130 again to close - because the tables had been renamed and
         * resized while the rules stayed pinned to a constant that no longer described either.
         */
        /**
         * The fine table's data-row widths, in order, so the group band can be laid over them.
         *
         * Operation, thread-time% and thread-time, runnable/wait, wall-time,
         * concurrency/threads, calls, implied per call, hits, noise, over one tick.
         */
        val FINE_COLUMNS = intArrayOf(26, 14, 11, 15, 9, 21, 13, 20, 8, 6, 7)

        /** After which columns a group bar comes down. */
        val FINE_BREAKS = intArrayOf(0, 3, 5, 7)

        /**
         * `1 thread` rather than `1 threads`.
         *
         * A report that cannot count is a report a reader trusts slightly less, and the places this
         * matters are exactly the small runs where every other number is already marginal.
         */
        internal fun plural(n: Long, noun: String): String =
            if (n == 1L) "1 $noun" else String.format(Locale.ROOT, "%,d ${noun}s", n)

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


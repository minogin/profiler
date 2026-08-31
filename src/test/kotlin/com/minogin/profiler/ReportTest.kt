package com.minogin.profiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the report works out from what the sampler collected, and what it promises to say about it.
 *
 * The rendering tests assert **invariants, not wording**. A golden file would be the obvious thing
 * and it would be wrong here: the wording of this report is edited often and on purpose — three of
 * its lines were rewritten this week — and a test that fails on every rewrite teaches people to
 * regenerate it without reading. What must not change is that a below-floor label always produces a
 * warning, that a bound which found nothing never prints a number, and that a stopped session
 * cannot render as though it were a result.
 */
class ReportTest {


    /**
     * Everything the report can print is ASCII.
     *
     * Not a style rule. A Windows console does not run in UTF-8 by default, and an em-dash arrives
     * there as `?` in a black diamond — which is what a reader actually saw:
     *
     * ```
     * CPU duty cycle: unavailable ? no window completed
     * nothing was sampled ? 4 ticks is too few
     * ```
     *
     * It was recorded as `ideas.md` item 20 and left open on the grounds that it was cosmetic. It is
     * not: the mangled character always lands exactly where a sentence explains *why*, because that
     * is what a dash is for. Sixteen strings carried one.
     *
     * The library cannot fix this at the other end — [Report.render] returns a `String` and somebody
     * else decides the encoding it is printed with — so the only reliable answer is to emit nothing
     * that needs an encoding.
     */
    @Test
    fun `every line the report can print is ASCII`() {
        val offenders = mutableListOf<String>()
        fun check(what: String, text: String) {
            for (line in text.lines()) {
                val bad = line.filter { it.code > 127 }
                if (bad.isNotEmpty()) offenders += "$what: [$bad] in: ${line.trim().take(70)}"
            }
        }
        // The message builders, each of which is a path the render only reaches under a fault.
        check("tooSmall", tooSmallMessage("op", 1_000, 25.0))
        check("coarseTooSmall", coarseTooSmallMessage("req", 100, 200.0, 800.0))
        check("leak", leakMessage("op", "worker-1"))
        check("mismatch", mismatchMessage("a", "b"))
        check("stale", staleContextMessage("req", 997, 0.42))
        // And a report carrying as many of the optional blocks at once as one can.
        check("render", loadedReport().render())
        check("render(legend)", loadedReport().render(legend = true))
        assertTrue(
            offenders.isEmpty(),
            "non-ASCII in printed output:\n" + offenders.joinToString("\n")
        )
    }

    /** A report with the warnings, the duty block and both tables all present at once. */
    private fun loadedReport(): Report {
        val fine = OperationStat(0, "work", 100, 1_000_000, 60, 3, 20, 10, 100)
        val c = CoarseStat(
            0, "request", 10, 10_000_000, 100, 2_000_000, LongArray(SpanHistogram.BUCKETS),
            80, 40, 80, 40, 40, 40, LongArray(MAX_OPERATIONS + 1) { if (it == 0) 80 else 0 }, 5,
        )
        return Report(
            operations = listOf(fine), idleHits = 50, ticks = 1_000,
            samplingSpanNanos = 1_000_000_000, durationNanos = 1_000_000_000, threads = 4,
            duty = DutyReport(
                labelledDuty = 0.02, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
                resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 1, threads = 4,
                cpuNanos = 20_000_000, wallNanos = 1_000_000_000,
                minWindowDuty = 0.02, maxWindowDuty = 0.02, anomalies = 1, maxSampleNanos = 200_000,
            ),
            failure = "something went wrong", imbalances = 2, openAtEnd = 1,
            untrackedSlots = 3, reclaimedSlots = 4, idleWaitingHits = 5,
            coarse = listOf(c), openContextsAtEnd = 1, labelledOutsideCoarse = 60,
            staleContextHits = 20, coarseSampleHits = 100,
        )
    }

    private fun stat(
        name: String, hits: Long, calls: Long,
        stuck: Long = 0, instances: Long = 0, waiting: Long = 0, active: Long = hits,
    ) = OperationStat(0, name, hits, calls, stuck, instances, waiting, 0, active)

    /** A duty report with no live measurement behind it, for the arithmetic that hangs off one. */
    private fun duty(
        labelled: Double = 0.99,
        aggregate: Double = 0.99,
        invisible: Double = 0.0,
        labelledFraction: Double = 1.0,
        reason: String? = null,
    ) = DutyReport(
        labelledDuty = labelled,
        invisibleOffCpu = invisible,
        labelledFraction = labelledFraction,
        reason = reason,
        resolutionNanos = 15_625_000,
        windowNanos = 1_000_000_000,
        windows = 20,
        threads = 8,
        cpuNanos = (aggregate * 1e9).toLong(),
        wallNanos = 1_000_000_000,
        minWindowDuty = aggregate,
        maxWindowDuty = aggregate,
        anomalies = 0,
        maxSampleNanos = 200_000,
    )

    private fun report(
        ops: List<OperationStat>,
        idle: Long = 0,
        idleWaiting: Long = 0,
        state: Boolean = true,
        d: DutyReport = duty(),
        failure: String? = null,
    ) = Report(
        operations = ops,
        idleHits = idle,
        ticks = 20_000,
        samplingSpanNanos = 20_000_000_000,
        durationNanos = 20_000_000_000,
        threads = 8,
        duty = d,
        failure = failure,
        stateSampled = state,
        idleWaitingHits = idleWaiting,
    )

    // ---------------------------------------------------------------- shares and coverage

    /**
     * Shares are over labelled samples, not over every sample.
     *
     * The denominator is the point: folding unlabelled time in would make every share depend on how
     * much uninstrumented code happened to be running, so adding a label anywhere would move every
     * other number in the report.
     */
    @Test
    fun `a share does not move when unlabelled time changes`() {
        val ops = listOf(stat("a", hits = 300, calls = 1000), stat("b", hits = 100, calls = 1000))
        val quiet = report(ops, idle = 0)
        val noisy = report(ops, idle = 1_000_000)
        assertEquals(0.75, quiet.shareOf(ops[0]), 1e-9)
        assertEquals(quiet.shareOf(ops[0]), noisy.shareOf(ops[0]), 1e-9, "a share moved with idle time")
    }

    /**
     * Coverage over runnable occupancy restricts **both** sides.
     *
     * Lucene is the case: 79% of its unlabelled samples were a thread that was not runnable, which
     * turns "the labels cover half the run" into something quite different. Taking it off only the
     * denominator would be the same mismatch of denominators pointing the other way, because a
     * label can be held across a wait — `lockedUpdate` spends 73% of itself parked.
     */
    @Test
    fun `runnable coverage restricts both sides`() {
        // 600 labelled of which 100 parked; 400 unlabelled of which 300 parked.
        val ops = listOf(stat("a", hits = 600, calls = 1000, waiting = 100))
        val r = report(ops, idle = 400, idleWaiting = 300)
        assertEquals(0.6, r.labelledHits.toDouble() / (r.labelledHits + r.idleHits), 1e-9)
        // Runnable: 500 labelled against 100 unlabelled.
        assertEquals(500.0 / 600.0, r.runnableCoverage, 1e-9)
        assertTrue(r.runnableCoverage > 0.6, "restricting to runnable time should raise coverage here")
    }

    /** Without the state read the question cannot be asked, and NaN says so rather than guessing. */
    @Test
    fun `runnable coverage is unavailable without thread state`() {
        val r = report(listOf(stat("a", hits = 600, calls = 1000)), idle = 400, state = false)
        assertTrue(r.runnableCoverage.isNaN())
    }

    /** The noise floor is what chance alone would produce, so it must fall as evidence accumulates. */
    @Test
    fun `the noise floor shrinks with hits`() {
        val small = stat("small", hits = 100, calls = 1000)
        val large = stat("large", hits = 10_000, calls = 1000)
        val r = report(listOf(small, large))
        assertTrue(r.noiseFloorOf(large) < r.noiseFloorOf(small))
    }

    // ---------------------------------------------------------------- the long-execution verdict

    /**
     * A baseline must not contain the effect it is used to detect.
     *
     * Three attempts are recorded in findings.md, two of them broken by a workload the previous one
     * had not met. The surviving rule takes the *lower* of what the duty cycle blames on the machine
     * and what a typical operation experienced, and the second term is what stops one blocking
     * operation raising the floor it is then measured against. Measured case: an operation with
     * 88.61% of its occupancy in long executions, against a floor of 35.43% it had created itself.
     */
    @Test
    fun `one blocking operation cannot raise the floor it is judged against`() {
        val blocker = stat("blocks", hits = 10_000, calls = 10_000, stuck = 8_861, instances = 500)
        val quiet = List(6) { stat("quiet$it", hits = 10_000, calls = 1_000_000, stuck = 20, instances = 2) }
        val r = report(listOf(blocker) + quiet, d = duty(labelled = 0.6455, aggregate = 0.6455))
        // The duty cycle alone would blame the machine for 35.45 points of it.
        assertTrue(r.machineFloor < 0.35, "the floor is still the duty cycle's, which the blocker moved")
        assertTrue(
            isSuspect(blocker.hits, blocker.stuckHits, blocker.stuckInstances, r.machineFloor),
            "the operation that created the floor hid behind it"
        )
    }

    /** A single long execution on a quiet machine is not evidence, whatever ratio it clears. */
    @Test
    fun `a ratio against a small number is not evidence`() {
        assertFalse(
            isSuspect(hits = 2000, stuckHits = 1, stuckInstances = 1, machineFloor = 0.0001),
            "one long execution out of two thousand was treated as a finding"
        )
    }

    // ---------------------------------------------------------------- rendering invariants

    /** A label below the floor always produces a warning line, and never stops the run. */
    @Test
    fun `a below-floor label always warns`() {
        val tiny = stat("tiny", hits = 559, calls = 31_497_063)
        val r = report(listOf(tiny))
        assertEquals(listOf(tiny), r.tooSmall())
        val text = r.render()
        assertTrue(text.contains("tiny"), "the below-floor label was not named")
        assertTrue(text.contains("floor"), "no floor warning in the report")
        assertTrue(r.ok, "a below-floor label invalidated the report")
    }

    /**
     * When the bound found nothing, the report says so in words and never prints a percentage.
     *
     * Netty is the case — event loops whose waiting is native, so 34.4% of thread-time is off the
     * CPU while reading `RUNNABLE` and labels cover 13.9%. Printing "0.00%" and "100 pp" there
     * would read as a measurement, and a reader would rightly distrust shares that are fine.
     */
    @Test
    fun `an unbounded duty explains itself instead of printing a number`() {
        val d = duty(labelled = 0.0, aggregate = 0.6563, invisible = 0.344, labelledFraction = 0.139)
        assertTrue(d.unbounded, "the Netty regime is no longer recognised as unbounded")
        val lines = d.lines().joinToString("\n")
        assertTrue(lines.contains("Bound") && lines.contains("none -"), "no diagnosis for an unbounded run")
        assertFalse(lines.contains("inside labels"), "printed a labelled duty it does not have")
        assertFalse(lines.contains("at most"), "printed a bound it does not have")
    }

    /** When the bound *did* find something, it prints it — and the diagnosis stays away. */
    @Test
    fun `a real bound is printed as a bound`() {
        val d = duty(labelled = 0.9846, aggregate = 0.6927, invisible = 0.014, labelledFraction = 0.595)
        assertFalse(d.unbounded)
        val lines = d.lines().joinToString("\n")
        assertTrue(lines.contains("inside labels"), "the labelled duty was not printed")
        assertTrue(lines.contains("at most"), "the bound was not printed")
        assertFalse(lines.contains("none -"), "printed the diagnosis over a real bound")
        assertEquals(1.56, d.boundPp, 0.05, "Lucene's recorded 1.57 pp moved")
    }

    /** Shares are bounded by the labelled duty, not the aggregate one. */
    @Test
    fun `the bound comes from labelled occupancy and not from every thread`() {
        val starved = duty(labelled = 0.9694, aggregate = 0.1940, labelledFraction = 0.2)
        assertEquals(0.9694, starved.shareDuty, 1e-9)
        assertEquals(3.16, starved.boundPp, 0.05, "starvation's recorded 3.16 pp moved")
    }

    /** With no per-thread figure the aggregate stands in, which is the old behaviour, not a wrong one. */
    @Test
    fun `the aggregate stands in when there is no per-thread figure`() {
        val d = duty(labelled = Double.NaN, aggregate = 0.80)
        assertEquals(0.80, d.shareDuty, 1e-9)
    }

    /**
     * A stopped session cannot render as though it were a result. The banner leads, and `ok` is
     * false — everything below it is evidence for the verdict, not a measurement.
     */
    @Test
    fun `a stopped session leads with why`() {
        val r = report(listOf(stat("a", hits = 100, calls = 1000)), failure = leakMessage("leaked", "worker-1"))
        assertFalse(r.ok)
        val text = r.render()
        assertTrue(text.contains("PROFILING STOPPED"), "a stopped session rendered without its banner")
        assertTrue(text.indexOf("PROFILING STOPPED") < text.indexOf("operation"), "the banner did not lead")
        assertTrue(text.contains("leaked"), "the failure did not name the operation")
    }

    /** A leak that did not stop the session is still counted and still printed. */
    @Test
    fun `a non-fatal leak is still reported`() {
        val r = Report(
            operations = listOf(stat("a", hits = 100, calls = 1000)),
            idleHits = 0, ticks = 20_000, samplingSpanNanos = 20_000_000_000,
            durationNanos = 20_000_000_000, threads = 8, duty = duty(),
            imbalances = 3, stateSampled = true,
        )
        assertTrue(r.ok, "a counted leak invalidated a non-strict report")
        assertTrue(r.render().contains("3 labels were still open"), "the leak count was not printed")
    }

    // ---------------------------------------------------------------- formatting

    /** Four orders of magnitude in one column: a duration picks the unit that keeps it readable. */
    @Test
    fun `durations pick a readable unit`() {
        assertTrue(duration(20.0).contains("n"), "20 ns should print in nanoseconds")
        assertTrue(duration(9_000.0).contains("u"), "9 us should print in microseconds")
        assertTrue(duration(1_500_000.0).contains("m"), "1.5 ms should print in milliseconds")
        assertEquals("-", duration(Double.NaN))
    }

    /** Thread-time crosses to seconds at a second, so 40 s never prints as "40080.00 ms". */
    @Test
    fun `thread time crosses to seconds at a second`() {
        assertTrue(threadTime(999_000_000.0).endsWith("ms"))
        assertTrue(threadTime(40_080_000_000.0).endsWith("s"))
        assertFalse(threadTime(40_080_000_000.0).contains("ms"))
    }
}

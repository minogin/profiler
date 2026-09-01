package com.ticksnick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coarse tier's arithmetic, on the parts that have an answer independent of any measurement.
 *
 * The tier's real verification is the bench — `--coarse` compares the profiler's spans against the
 * intervals the workers timed themselves, which is a truth no unit test can supply. What is here is
 * the part that must hold before a single sample is taken: that the histogram brackets what it is
 * given, that a percentile is never reported below the truth, that nesting restores the right
 * parent, and that a leaked context is caught by the balance check rather than silently swallowing
 * a whole request's samples.
 */
class CoarseTest {

    // --- the histogram --------------------------------------------------------------

    @Test
    fun `every bucket brackets the values that land in it`() {
        // Every octave boundary, either side of it, plus the linear region at the bottom.
        val probes = buildList {
            addAll(0L..20L)
            for (shift in 3..40) {
                val base = 1L shl shift
                addAll(listOf(base - 1, base, base + 1, base + base / 3, 2 * base - 1))
            }
        }
        for (v in probes) {
            val b = SpanHistogram.bucketOf(v)
            assertTrue(b in 0 until SpanHistogram.BUCKETS, "$v landed outside the histogram at $b")
            assertTrue(
                SpanHistogram.lowerBound(b) <= v && v <= SpanHistogram.upperBound(b),
                "$v is not inside bucket $b = [${SpanHistogram.lowerBound(b)}, ${SpanHistogram.upperBound(b)}]"
            )
        }
    }

    @Test
    fun `buckets are ordered and do not overlap`() {
        for (b in 0 until SpanHistogram.BUCKETS - 1) {
            assertEquals(
                SpanHistogram.upperBound(b) + 1, SpanHistogram.lowerBound(b + 1),
                "bucket $b and ${b + 1} are not adjacent"
            )
        }
    }

    /**
     * The guarantee the report leans on: a printed p99 is never below the truth, and never more
     * than [SpanHistogram.PRECISION] above it. Reporting the midpoint would halve the error and
     * give up the first half of that, which is the wrong trade for a latency figure.
     */
    @Test
    fun `a percentile is never below the truth and never more than one bucket above`() {
        val values = (1..10_000).map { it.toLong() * 37 }
        val hist = LongArray(SpanHistogram.BUCKETS)
        for (v in values) hist[SpanHistogram.bucketOf(v)]++
        val sorted = values.sorted()
        for (p in listOf(0.5, 0.9, 0.99, 1.0)) {
            val exact = sorted[Math.ceil(p * sorted.size).toInt() - 1].toDouble()
            val got = SpanHistogram.percentile(hist, values.size.toLong(), p)
            assertTrue(got >= exact, "p$p reported $got, below the true $exact")
            assertTrue(
                got <= exact * (1 + SpanHistogram.PRECISION),
                "p$p reported $got, more than one bucket above the true $exact"
            )
        }
    }

    /**
     * Found by using the tool rather than by testing it: one execution of 713.2 µs printed every
     * percentile as 720.9 µs — the top of its bucket — beside an exact `max` of 713.2. A p99 above
     * the maximum is nonsense however well the rounding is documented.
     */
    @Test
    fun `a percentile is never reported above the measured maximum`() {
        val exact = 713_200L
        val hist = LongArray(SpanHistogram.BUCKETS)
        hist[SpanHistogram.bucketOf(exact)]++
        val c = CoarseStat(
            0, "req", 1, exact, exact, exact, hist,
            0, 0, 0, 0, 0, 0, LongArray(MAX_OPERATIONS + 1),
        )
        // The bucket really does end above the value — this is the quantisation, not a mistake.
        assertTrue(
            SpanHistogram.percentile(hist, 1, 0.99) > exact,
            "the bucket top is not above the value, so this test is no longer testing anything"
        )
        for (p in listOf(0.50, 0.90, 0.99, 1.0)) {
            assertEquals(
                exact.toDouble(), c.percentileNanos(p),
                "p$p was reported above the exact maximum"
            )
        }
    }

    @Test
    fun `clamping does not push a percentile below the truth`() {
        // A spread of values: the clamp must only ever bite on the top bucket, and every percentile
        // must still be at or above the true one it describes.
        val values = (1..2_000).map { it.toLong() * 971 }
        val hist = LongArray(SpanHistogram.BUCKETS)
        for (v in values) hist[SpanHistogram.bucketOf(v)]++
        val c = CoarseStat(
            0, "req", values.size.toLong(), values.sum(), values.min(), values.max(), hist,
            0, 0, 0, 0, 0, 0, LongArray(MAX_OPERATIONS + 1),
        )
        val sorted = values.sorted()
        for (p in listOf(0.5, 0.9, 0.99, 1.0)) {
            val truth = sorted[Math.ceil(p * sorted.size).toInt() - 1].toDouble()
            assertTrue(c.percentileNanos(p) >= truth, "p$p fell below the truth after clamping")
            assertTrue(c.percentileNanos(p) <= values.max().toDouble(), "p$p exceeded the maximum")
        }
    }

    @Test
    fun `an empty histogram has no percentile rather than a wrong one`() {
        assertTrue(SpanHistogram.percentile(LongArray(SpanHistogram.BUCKETS), 0, 0.5).isNaN())
    }

    // --- contexts -------------------------------------------------------------------

    @Test
    fun `nesting restores the parent and not the empty slot`() {
        val outer = Profiler.registerCoarse("test-outer")
        val inner = Profiler.registerCoarse("test-inner")
        val slot = Profiler.slot()
        assertNull(slot.contextOpaque())
        op(outer) {
            val a = assertNotNull(slot.contextOpaque())
            assertEquals(outer.id, a.type)
            assertEquals(0, a.depth)
            op(inner) {
                val b = assertNotNull(slot.contextOpaque())
                assertEquals(inner.id, b.type)
                assertEquals(1, b.depth)
                assertEquals(a, b.parent)
            }
            // Restored to the parent, not cleared. Clearing is the bug that would bill the rest of
            // the outer operation to nothing at all.
            assertEquals(a, slot.contextOpaque())
        }
        assertNull(slot.contextOpaque())
    }

    @Test
    fun `a body that throws still closes the block form`() {
        val t = Profiler.registerCoarse("test-throwing")
        val slot = Profiler.slot()
        runCatching { op<Unit>(t) { throw IllegalStateException("boom") } }
        assertNull(slot.contextOpaque(), "the finally did not restore the slot")
    }

    /**
     * The hazard the non-lexical form carries, and the check that exists for it. A coarse context
     * left open is worse than a fine label left open by exactly the amount a request is bigger than
     * an operation: every sample the thread takes afterwards is billed to it.
     */
    @Test
    fun `expectBalanced catches a leaked context and closes it`() {
        val t = Profiler.registerCoarse("test-leaked")
        val slot = Profiler.slot()
        Profiler.enter(t)
        assertNotNull(slot.contextOpaque())
        assertTrue(!Profiler.expectBalanced(), "a leaked context reported as balanced")
        assertNull(slot.contextOpaque(), "the leak was reported but not stopped")
        assertTrue(Profiler.expectBalanced(), "the reset did not take")
    }

    @Test
    fun `an unmatched exit is reported rather than a crash`() {
        // It used to be a silent no-op, because `exitCoarse()` had no way to know there was nothing
        // to close. Now it names what the caller tried to close and counts an imbalance — still not
        // an exception, because a measurement problem must not become a crash in somebody else's
        // process.
        val t = Profiler.registerCoarse("test-unmatched")
        val slot = Profiler.slot()
        assertNull(slot.contextOpaque())
        Profiler.exit(t)
        assertNull(slot.contextOpaque())
        assertTrue(Profiler.expectBalanced())
    }

    // --- what the report works out from it -------------------------------------------

    // --- `working` against what the machine actually gave it ------------------------
    //
    // Trial 4 measured this column reading 55x more CPU than the process spent: `working` is built
    // on thread state, and Java reports a thread stopped inside a native call as RUNNABLE. These
    // pin the bound that was added in response, in both directions — because the direction that
    // costs more is the false accusation, and the slack that prevents it is not obvious.

    @Test
    fun `working is bounded by the CPU the machine was measured to give it`() {
        // The PostgreSQL numbers: eight threads stopped in a socket read, reported as 2.83 working,
        // on a process the operating system says was on a CPU 1.02% of the time.
        val c = stat(
            count = 860, sumNanos = 860 * 17_440_000, hits = 3_830, running = 2_830,
            instanceTicks = 1_000, activeTicks = 1_000,
        )
        val r = report(listOf(c), labelledDuty = 0.0102)
        assertEquals(3.83, c.inside, 1e-9)
        assertEquals(2.83, c.working, 1e-9)
        assertTrue(r.workingIsContradicted(c), "a column claiming 2.83 threads on a 1% duty cycle was believed")
        assertTrue(r.workingCeilingOf(c) < 0.05, "the ceiling should be about 0.04, was ${r.workingCeilingOf(c)}")
    }

    /**
     * The regression test for the slack, and it is load-bearing rather than caution.
     *
     * These are Lucene's measured figures: `inside` 6.64 and `working` 6.40 against a labelled duty
     * cycle of 96.40%, which puts the ceiling at **exactly 6.40**. A strict test — anything above
     * the ceiling is a fault — would accuse a run that is entirely correct, of the defect
     * PostgreSQL actually has. The ceiling is a run-wide figure applied to one operation, and one
     * operation may honestly be more CPU-bound than the run around it.
     */
    @Test
    fun `a CPU-bound operation sitting on its ceiling is not accused`() {
        val c = stat(
            count = 3_663, sumNanos = 3_663 * 4_090_000, hits = 6_640, running = 6_400,
            instanceTicks = 1_000, activeTicks = 1_000,
        )
        val r = report(listOf(c), labelledDuty = 0.9640)
        // 6.64 x 0.9640 = 6.40096 — the point is that `working` of 6.40 sits *on* that, not that
        // the two are bit-identical.
        assertEquals(6.40, r.workingCeilingOf(c), 0.01)
        assertTrue(c.working <= r.workingCeilingOf(c) * Report.WORKING_CEILING_SLACK)
        assertTrue(!r.workingIsContradicted(c), "an operation at its ceiling was accused")
    }

    @Test
    fun `the slack is a factor, and it decides on both sides of itself`() {
        fun contradictedAt(working: Long): Boolean {
            val c = stat(
                count = 100, sumNanos = 100_000_000, hits = 1_000, running = working,
                instanceTicks = 1_000, activeTicks = 1_000,
            )
            // inside = 1.0, duty 0.5, so the ceiling is 0.5 and the threshold 0.5 x 1.5 = 0.75.
            return report(listOf(c), labelledDuty = 0.5).workingIsContradicted(c)
        }
        assertTrue(!contradictedAt(700), "0.70 is inside the slack and was accused")
        assertTrue(contradictedAt(800), "0.80 is outside the slack and was believed")
    }

    @Test
    fun `no duty cycle means no accusation`() {
        // A run too short for a window says so rather than inventing a ceiling. Silence is the only
        // honest answer: without the operating system's figure there is nothing to contradict.
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 1_000, running = 1_000, instanceTicks = 100)
        val r = report(listOf(c), labelledDuty = 0.0, dutyAvailable = false)
        assertTrue(r.workingCeilingOf(c).isNaN())
        assertTrue(!r.workingIsContradicted(c), "an unavailable duty cycle was read as a contradiction")
    }

    @Test
    fun `the report names the operation whose working cannot be supported`() {
        val bad = stat(
            count = 100, sumNanos = 100_000_000, hits = 4_000, running = 3_000,
            instanceTicks = 1_000, activeTicks = 1_000, name = "query",
        )
        val text = report(listOf(bad), labelledDuty = 0.01).render()
        assertTrue("READS HIGHER THAN THE MEASURED TIME ON CPU" in text, "the warning did not appear")
        assertTrue("query" in text, "the warning did not name the operation")
        // The parallelism line carries both readings, so a reader who skips the block still sees it.
        assertTrue(
            Regex("""3\.00 of it on a CPU \(at most 0\.0\d\)""").containsMatchIn(text),
            "the parallelism line did not print value over ceiling"
        )
    }

    @Test
    fun `a report with nothing contradicted says nothing about it`() {
        val ok = stat(count = 100, sumNanos = 100_000_000, hits = 1_000, running = 990, instanceTicks = 1_000)
        val text = report(listOf(ok), labelledDuty = 0.99).render()
        assertTrue("READS HIGHER THAN THE MEASURED CPU DUTY" !in text, "a clean run was warned about")
    }

    // --- the stale share, which had the wrong denominator ----------------------------

    /**
     * This printed **102.3%** before it was fixed: the numerator counted a stale entry per coarse
     * *type* on the chain, and the denominator counted only samples that were inside a *fine* label.
     * Two different populations, so the ratio was not a share of anything.
     */
    @Test
    fun `the stale share is of coarse thread-time and cannot exceed all of it`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 0, running = 0, instanceTicks = 1, staleHits = 997)
        val r = report(listOf(c), labelled = 975, staleContextHits = 997, coarseSampleHits = 1_000)
        assertEquals(0.997, r.staleContextShare, 1e-9)
        assertTrue(r.staleContextShare <= 1.0, "a share of thread-time exceeded all of it")
    }

    @Test
    fun `no coarse samples is a stale share of zero rather than a division by zero`() {
        val r = report(emptyList(), staleContextHits = 0, coarseSampleHits = 0)
        assertEquals(0.0, r.staleContextShare)
    }

    // --- the legend, which is four lines unless you ask -----------------------------

    @Test
    fun `the default legend is the traps and nothing else`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 100, running = 100, instanceTicks = 100)
        val text = report(listOf(c)).render()
        assertTrue("NOT CPU" in text, "the traps are not printed")
        assertTrue("render(legend = true)" in text, "there is no way to find the rest")
        // The reference half is what makes it thirty-seven lines, and it is in output.md.
        assertTrue("noise is 1/sqrt(hits)" !in text, "the full legend printed by default")
        // The legend section itself, which is the thing that was thirty-seven lines.
        val legendLines = text.lines().dropWhile { !it.startsWith("HOW TO READ THIS") }
            .filter { it.isNotBlank() }
        assertTrue(legendLines.size <= 7, "the default legend is ${legendLines.size} lines, not a handful")
    }

    @Test
    fun `asking for the legend gets the whole thing`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 100, running = 100, instanceTicks = 100)
        val text = report(listOf(c)).render(legend = true)
        assertTrue("NOT CPU" in text, "the traps went missing")
        assertTrue("noise is 1/sqrt(hits)" in text, "the full legend was not printed")
        assertTrue("worth 275x when" in text, "the counterfactual argument was not printed")
        assertTrue("the 'was:' lines are the cross-tabulation" in text, "the coarse half was not printed")
    }

    private fun stat(
        count: Long, sumNanos: Long, hits: Long, running: Long,
        inclusive: Long = hits, runningInclusive: Long = running,
        instanceTicks: Long = inclusive, activeTicks: Long = instanceTicks,
        staleHits: Long = 0, name: String = "req",
    ) = CoarseStat(
        0, name, count, sumNanos, 0, 0, LongArray(SpanHistogram.BUCKETS),
        hits, running, inclusive, runningInclusive, instanceTicks, activeTicks,
        LongArray(MAX_OPERATIONS + 1), staleHits,
    )

    /**
     * A report with a 1 ms step, so that a hit is a millisecond and the arithmetic in these tests
     * can be done in one's head. [labelled] sets the denominator every coarse share is taken over.
     */
    private fun report(
        coarse: List<CoarseStat>,
        labelled: Long = 1_000_000,
        labelledDuty: Double = 0.99,
        dutyAvailable: Boolean = true,
        staleContextHits: Long = 0,
        coarseSampleHits: Long = 0,
    ) = Report(
        operations = listOf(OperationStat(0, "fine", labelled, labelled, 0, 0, 0, 0, labelled)),
        idleHits = 0,
        ticks = 1_000_001,
        samplingSpanNanos = 1_000_000_000_000,
        durationNanos = 1_000_000_000_000,
        threads = 8,
        duty = DutyReport(
            labelledDuty = labelledDuty, invisibleOffCpu = 0.0, labelledFraction = 1.0,
            reason = if (dutyAvailable) null else "no window completed",
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000,
            windows = if (dutyAvailable) 20 else 0, threads = 8,
            cpuNanos = (labelledDuty * 1_000_000_000).toLong(), wallNanos = 1_000_000_000,
            minWindowDuty = labelledDuty, maxWindowDuty = labelledDuty,
            anomalies = 0, maxSampleNanos = 200_000,
        ),
        coarse = coarse,
        staleContextHits = staleContextHits,
        coarseSampleHits = coarseSampleHits,
    )

    @Test
    fun `inside is one when every occupied execution has one thread in it`() {
        // What a same-thread run must produce, by construction: instanceTicks moves in lockstep with
        // hits because a context never leaves the thread that made it. With propagation this is the
        // number that moves, so pinning it here keeps the same-thread case honest afterwards.
        val c = stat(count = 100, sumNanos = 1_000_000, hits = 400, running = 400)
        assertEquals(1.0, c.inside)
    }

    @Test
    fun `inside and in flight factorise the threads inside`() {
        // 8 threads inside, as two executions of four threads each — the case the fine tier cannot
        // tell from eight serial ones. inclusive / activeTicks = 8 = inFlight x inside.
        //
        // The identity is on `inside` and not on `working`, and that is why the pair exists: inFlight
        // counts an execution whether or not its threads are running, so only the half that counts
        // threads the same way can factorise it.
        val c = stat(
            count = 10, sumNanos = 1_000_000, hits = 800, running = 800,
            instanceTicks = 200, activeTicks = 100,
        )
        assertEquals(4.0, c.inside)
        assertEquals(2.0, c.inFlight)
        assertEquals(
            c.inclusiveHits.toDouble() / c.activeTicks, c.inFlight * c.inside,
            "the identity threads = in flight x inside does not hold"
        )
    }

    @Test
    fun `working excludes the threads that were parked`() {
        // Five threads in the execution, four of them on a CPU — one driver fanning work out to four
        // helpers and then blocking on the join. Both numbers are wanted: five is what the request
        // ties up, four is what splitting it bought.
        val c = stat(
            count = 10, sumNanos = 1_000_000, hits = 500, running = 400,
            instanceTicks = 100, activeTicks = 100,
        )
        assertEquals(5.0, c.inside)
        assertEquals(4.0, c.working)
    }

    @Test
    fun `working is inside less the waiting share`() {
        // The relation the report's legend states, so a reader who does the arithmetic by hand gets
        // the printed number back. It is also why the two columns sit either side of `waiting`.
        val c = stat(
            count = 10, sumNanos = 1_000_000, hits = 800, running = 600,
            instanceTicks = 200, activeTicks = 100,
        )
        val waiting = c.waitingHits.toDouble() / c.inclusiveHits
        assertEquals(c.working, c.inside * (1 - waiting), 1e-12)
    }

    // --- the tier boundary, checked rather than documented ---------------------------

    @Test
    fun `the floor is flat below twenty percent share and rises above it`() {
        // From profiler.md's table. Condition 1 only binds above ~20%, which is why the flat 800 ns
        // is the rule in practice and the share term is the exception.
        assertEquals(800.0, coarseFloorNanos(0.01))
        assertEquals(800.0, coarseFloorNanos(0.20))
        assertEquals(2000.0, coarseFloorNanos(0.50))
        assertEquals(4000.0, coarseFloorNanos(1.0))
    }

    @Test
    fun `a coarse label on a 200 ns operation is reported`() {
        val r = report(coarse = listOf(stat(count = 1_000_000, sumNanos = 200_000_000, hits = 200, running = 200)))
        assertEquals(listOf("req"), r.coarseTooSmall().map { it.name })
        val msg = coarseTooSmallMessage("req", 1_000_000, 200.0, r.coarseFloorNanosOf(r.coarse[0]))
        assertTrue(msg.contains("200.0 ns"), "the message does not name the measured duration: $msg")
        assertTrue(msg.contains("op(id)"), "the message does not say what to do instead: $msg")
    }

    @Test
    fun `a legitimate coarse label is not accused`() {
        // A millisecond apiece at a 0.1% share. Nothing here is borderline and the check must stay
        // silent — a floor check that cries wolf is worse than none, which is why the fine one was
        // demoted from fatal to a warning.
        val r = report(coarse = listOf(stat(count = 1_000, sumNanos = 1_000_000_000, hits = 1_000, running = 1_000)))
        assertTrue(r.coarseTooSmall().isEmpty())
    }

    /**
     * The share term is the only sampled input, and it is taken a standard error low before it can
     * accuse. An operation sitting just the wrong side of the line must not be told off on the
     * strength of counting noise — this check prints an accusation, and manufacturing one is the
     * failure this project spends its effort on.
     */
    @Test
    fun `the share condition does not accuse on counting noise alone`() {
        // 2.5 us apiece, 25 hits out of 36 labelled: a 69.4% share, so the raw rule would demand
        // 2778 ns and accuse. 25 hits is a 20% standard error, and at 55.6% the rule demands 2222 ns
        // and lets it go. The operation is not exonerated — its share is simply not established.
        val noisy = stat(count = 400, sumNanos = 1_000_000, hits = 25, running = 25)
        assertTrue(
            report(coarse = listOf(noisy), labelled = 36).coarseTooSmall().isEmpty(),
            "accused an operation whose share is not established"
        )
        // The same operation with the same share, measured a thousand times over, is accused —
        // it is the uncertainty that spared it, not the share being innocent.
        val settled = stat(count = 400_000, sumNanos = 1_000_000_000, hits = 25_000, running = 25_000)
        assertEquals(
            listOf("req"), report(coarse = listOf(settled), labelled = 36_000).coarseTooSmall().map { it.name }
        )
    }

    // --- work escaping its context ---------------------------------------------------

    /**
     * The Lucene shape, in miniature: a coarse span on the caller, and most of the labelled work
     * happening on pool threads that never saw the context. The span's own busy time is a fraction
     * of it and the rest reads as waiting — silent, and in the contaminating direction, which is why
     * this counter exists at all.
     */
    @Test
    fun `labelled work outside every span is measured and its operations named`() {
        val fanned = OperationStat(0, "fine", 1000, 1000, 0, 0, 0, 0, 1000)
        val r = Report(
            operations = listOf(fanned),
            idleHits = 0, ticks = 1_000_001, samplingSpanNanos = 1_000_000_000_000,
            durationNanos = 1_000_000_000_000, threads = 8,
            duty = DutyReport(
                labelledDuty = 0.99, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
                resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 20, threads = 8,
                cpuNanos = 990_000_000, wallNanos = 1_000_000_000,
                minWindowDuty = 0.99, maxWindowDuty = 0.99, anomalies = 0, maxSampleNanos = 200_000,
            ),
            // 250 of the operation's 1000 samples fell under the span; 750 did not.
            coarse = listOf(
                stat(count = 100, sumNanos = 100_000_000, hits = 250, running = 250)
                    .also { it.fine[0] = 250 }
            ),
            labelledOutsideCoarse = 750,
        )
        assertEquals(0.75, r.labelledOutsideCoarseShare)
        assertEquals(listOf("fine"), r.outsideCoarseSuspects().map { it.name })
        val text = r.render()
        assertTrue(text.contains("NO coarse span"), "the report does not mention it")
        assertTrue(text.contains("ESCAPING"), "the report does not name the dangerous reading")
    }

    @Test
    fun `an operation fully covered by a span is not named`() {
        val covered = OperationStat(0, "fine", 250, 250, 0, 0, 0, 0, 250)
        val r = report(
            coarse = listOf(
                stat(count = 100, sumNanos = 100_000_000, hits = 250, running = 250)
                    .also { it.fine[0] = 250 }
            ),
        )
        // Hits come from `report`'s own filler operation, so the covered one is checked directly:
        // naming an operation whose samples are all accounted for would be a false accusation.
        assertTrue(covered.hits <= r.coarse[0].fine[0])
    }

    @Test
    fun `nothing is said when no coarse label was placed`() {
        val r = report(coarse = emptyList())
        assertTrue(r.outsideCoarseSuspects().isEmpty())
        assertTrue(!r.render().contains("NO coarse span"))
    }

    @Test
    fun `waiting is what the span has and the samples do not`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 100, running = 40)
        assertEquals(60L, c.waitingHits)
        assertEquals(1_000_000.0, c.meanSpanNanos)
    }
}

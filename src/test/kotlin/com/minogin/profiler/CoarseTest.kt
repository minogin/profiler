package com.minogin.profiler

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
        coarse(outer) {
            val a = assertNotNull(slot.contextOpaque())
            assertEquals(outer, a.type)
            assertEquals(0, a.depth)
            coarse(inner) {
                val b = assertNotNull(slot.contextOpaque())
                assertEquals(inner, b.type)
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
        runCatching { coarse<Unit>(t) { throw IllegalStateException("boom") } }
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
        enterCoarse(t)
        assertNotNull(slot.contextOpaque())
        assertTrue(!Profiler.expectBalanced(), "a leaked context reported as balanced")
        assertNull(slot.contextOpaque(), "the leak was reported but not stopped")
        assertTrue(Profiler.expectBalanced(), "the reset did not take")
    }

    @Test
    fun `an unmatched exit is a no-op rather than a crash`() {
        val slot = Profiler.slot()
        assertNull(slot.contextOpaque())
        exitCoarse()
        assertNull(slot.contextOpaque())
        assertTrue(Profiler.expectBalanced())
    }

    // --- what the report works out from it -------------------------------------------

    private fun stat(
        count: Long, sumNanos: Long, hits: Long, running: Long,
        inclusive: Long = hits, runningInclusive: Long = running,
        instanceTicks: Long = inclusive, activeTicks: Long = instanceTicks,
    ) = CoarseStat(
        0, "req", count, sumNanos, 0, 0, LongArray(SpanHistogram.BUCKETS),
        hits, running, inclusive, runningInclusive, instanceTicks, activeTicks,
        LongArray(MAX_OPERATIONS + 1),
    )

    /**
     * A report with a 1 ms step, so that a hit is a millisecond and the arithmetic in these tests
     * can be done in one's head. [labelled] sets the denominator every coarse share is taken over.
     */
    private fun report(coarse: List<CoarseStat>, labelled: Long = 1_000_000) = Report(
        operations = listOf(OperationStat(0, "fine", labelled, labelled, 0, 0, 0, 0, labelled)),
        idleHits = 0,
        ticks = 1_000_001,
        samplingSpanNanos = 1_000_000_000_000,
        durationNanos = 1_000_000_000_000,
        threads = 8,
        duty = DutyReport(
            labelledDuty = 0.99, invisibleOffCpu = 0.0, labelledFraction = 1.0, reason = null,
            resolutionNanos = 15_625_000, windowNanos = 1_000_000_000, windows = 20, threads = 8,
            cpuNanos = 990_000_000, wallNanos = 1_000_000_000,
            minWindowDuty = 0.99, maxWindowDuty = 0.99, anomalies = 0, maxSampleNanos = 200_000,
        ),
        coarse = coarse,
    )

    @Test
    fun `parallelism is one when every occupied execution has one thread in it`() {
        // What phase 4 must produce, by construction: instanceTicks moves in lockstep with hits
        // because a context never leaves the thread that made it.
        val c = stat(count = 100, sumNanos = 1_000_000, hits = 400, running = 400)
        assertEquals(1.0, c.parallelism)
    }

    @Test
    fun `parallelism and in flight factorise the threads inside`() {
        // 8 threads inside, as two executions of four threads each — the case the fine tier cannot
        // tell from eight serial ones. inclusive / activeTicks = 8 = inFlight x parallelism.
        val c = stat(
            count = 10, sumNanos = 1_000_000, hits = 800, running = 800,
            instanceTicks = 200, activeTicks = 100,
        )
        assertEquals(4.0, c.parallelism)
        assertEquals(2.0, c.inFlight)
        assertEquals(
            c.inclusiveHits.toDouble() / c.activeTicks, c.inFlight * c.parallelism,
            "the identity threads = in flight x parallelism does not hold"
        )
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

    @Test
    fun `waiting is what the span has and the samples do not`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 100, running = 40)
        assertEquals(60L, c.waitingHits)
        assertEquals(1_000_000.0, c.meanSpanNanos)
    }
}

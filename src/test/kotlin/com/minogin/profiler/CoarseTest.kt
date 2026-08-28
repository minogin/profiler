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

    @Test
    fun `waiting is what the span has and the samples do not`() {
        val c = stat(count = 10, sumNanos = 10_000_000, hits = 100, running = 40)
        assertEquals(60L, c.waitingHits)
        assertEquals(1_000_000.0, c.meanSpanNanos)
    }
}

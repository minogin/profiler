package com.minogin.profiler

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bound on every share, checked without starting anything.
 *
 * This exists because the first version of it shipped and was wrong. `min(stall, labelled)` is a
 * sound upper bound and a vacuous one on a thread pool, and nothing caught that until a trial was
 * re-run by hand against real Lucene. The inputs it needed to fail are six numbers.
 *
 * Two kinds of test here and they fail for different reasons. The **properties** hold whatever the
 * formula is, and a rewrite that breaks one is broken. The **regimes** are the four workloads the
 * trials actually put through it, modelled from the figures recorded in findings.md — a rewrite
 * that breaks one of those may be right, but the recorded number is then wrong and somebody has to
 * say which.
 */
class DutyBoundTest {

    /**
     * One thread's contribution, in the terms the bound is written in.
     *
     * Fractions rather than counts, because that is how the finding is stated and how a reader
     * checks it. `hits` scales them into the arrays the sampler actually keeps.
     */
    private class Thread(
        val labelled: Double,
        val offCpu: Double,
        val notRunnable: Double = 0.0,
        val labelledNotRunnable: Double = 0.0,
        val hits: Long = 100_000,
    )

    private fun bound(vararg threads: Thread, state: Boolean = true): LabelledDuty {
        val n = threads.size
        // Wall is one nanosecond per hit, so the CPU figure is a clean complement of offCpu and
        // nothing here depends on a tick length.
        return labelledDuty(
            slotHits = LongArray(n) { threads[it].hits },
            slotLabelled = LongArray(n) { (threads[it].labelled * threads[it].hits).toLong() },
            slotWaiting = LongArray(n) { (threads[it].notRunnable * threads[it].hits).toLong() },
            slotLabelledWaiting = LongArray(n) { (threads[it].labelledNotRunnable * threads[it].hits).toLong() },
            cpuNanos = LongArray(n) { ((1 - threads[it].offCpu) * threads[it].hits).toLong() },
            wallNanos = LongArray(n) { threads[it].hits },
            stateSampled = state,
        )
    }

    /** The bound the report prints, in percentage points. Clamped the way [DutyReport.boundPp] is. */
    private fun pp(duty: Double) = min(100.0, (1 - duty) / duty * 100)

    // ---------------------------------------------------------------- properties

    /**
     * A thread that is never inside a label cannot move the bound, however much it stalls.
     *
     * The whole reason the duty cycle was taken per thread. Before it, twelve parked pool threads
     * turned a 3 pp error into a formally unbounded one.
     */
    @Test
    fun `idle threads contribute nothing`() {
        val worker = Thread(labelled = 1.0, offCpu = 0.03)
        val idle = Thread(labelled = 0.0, offCpu = 1.0, notRunnable = 1.0)
        val alone = bound(worker)
        val crowded = bound(worker, idle, idle, idle, idle, idle, idle, idle, idle, idle, idle, idle)
        assertEquals(alone.duty, crowded.duty, 1e-9, "parked threads moved a bound they are not in")
    }

    /**
     * Using the state read must never produce a worse bound than ignoring it.
     *
     * A property and not an accident: it is what the `min(f, …)` cap in the formula is for. The two
     * instruments have different resolutions — the CPU clock moves in 15.6 ms steps and the state
     * read does not — so they disagree in the last digits on every real run, and without the cap
     * that disagreement could make the extra evidence harmful.
     */
    @Test
    fun `the state read never makes the bound worse`() {
        val cases = listOf(
            Thread(labelled = 0.67, offCpu = 0.22, notRunnable = 0.21),
            Thread(labelled = 1.0, offCpu = 0.61, notRunnable = 0.59, labelledNotRunnable = 0.59),
            Thread(labelled = 0.14, offCpu = 0.34),
            // The disagreement case: the state read sees more waiting than the clock sees off-CPU,
            // which is impossible in principle and routine in practice.
            Thread(labelled = 0.9, offCpu = 0.10, notRunnable = 0.30, labelledNotRunnable = 0.30),
        )
        for (t in cases) {
            val withState = bound(t, state = true).duty
            val without = bound(t, state = false).duty
            assertTrue(
                withState >= without - 1e-9,
                "state read made the bound worse: $withState against $without"
            )
        }
    }

    /**
     * Whatever the inputs, a duty is a fraction. A bound outside 0..1 is a broken formula.
     *
     * **This test was written once and could not fail.** Every case it had used a labelled fraction
     * of exactly 0 or exactly 1, so `l = slotLabelled / hits` was integer-exact and multiplying it
     * back up by `hits` returned exactly what it started from. The sum that has to stay ordered —
     * `stall <= labelled` — is accumulated one way exactly and the other way through a division and
     * a multiplication, so it is only ever a fraction like `0.139` that can round the wrong way and
     * push the duty a ULP below zero. Which is reachable, prints
     * `at most -764160581304320300.00 pp`, and is precisely the Netty regime.
     */
    @Test
    fun `the result is always a fraction`() {
        val odd = listOf(
            Thread(labelled = 1.0, offCpu = 1.0, notRunnable = 1.0, labelledNotRunnable = 1.0),
            Thread(labelled = 0.5, offCpu = 0.0),
            Thread(labelled = 1.0, offCpu = 0.999),
            // Labelled fractions that no double can hold exactly, which is the only shape that
            // breaks the ordering. 0.139 is Netty's; the rest are neighbours of it.
            Thread(labelled = 0.139, offCpu = 0.34401),
            Thread(labelled = 0.07, offCpu = 0.9),
            Thread(labelled = 0.3, offCpu = 0.7, notRunnable = 0.1, labelledNotRunnable = 0.05),
        )
        for (t in odd) {
            for (state in listOf(true, false)) {
                val d = bound(t, state = state).duty
                assertTrue(d in 0.0..1.0, "duty $d is not a fraction (state=$state, l=${t.labelled})")
            }
        }
    }

    /**
     * The degenerate Netty case with the state read switched off.
     *
     * Worth its own test because this is the combination that got the negative bound into a printed
     * report: `unbounded` needs `invisibleOffCpu > 0`, and that term is only ever accumulated on the
     * state-sampled path — so with `--state=off` nothing catches a duty that has gone below zero and
     * the report prints a percentage with eighteen digits in it.
     */
    @Test
    fun `the degenerate case is still a fraction without the state read`() {
        val r = bound(*Array(4) { Thread(labelled = 0.139, offCpu = 0.344) }, state = false)
        assertTrue(r.duty >= 0.0, "duty went below zero: ${r.duty}")
        assertTrue(pp(r.duty) in 0.0..100.0, "bound ${pp(r.duty)} pp is not a percentage")
    }

    /** With no labelled samples anywhere there is nothing to bound, and NaN says so. */
    @Test
    fun `nothing labelled is not a duty of zero`() {
        val r = bound(Thread(labelled = 0.0, offCpu = 0.5, notRunnable = 0.5))
        assertTrue(r.duty.isNaN(), "an unlabelled run reported a duty of ${r.duty}")
    }

    /** A slot the duty walk never got a reading for is skipped rather than counted as fully stalled. */
    @Test
    fun `a slot with no wall reading is skipped`() {
        val r = labelledDuty(
            slotHits = longArrayOf(1000, 1000),
            slotLabelled = longArrayOf(1000, 1000),
            slotWaiting = LongArray(2),
            slotLabelledWaiting = LongArray(2),
            cpuNanos = longArrayOf(1000, 0),
            wallNanos = longArrayOf(1000, 0),      // second thread never sampled by the duty walk
            stateSampled = true,
        )
        assertEquals(1.0, r.duty, 1e-9, "a slot with no CPU reading was charged as stall")
    }

    // ---------------------------------------------------------------- the measured regimes
    //
    // Each is a homogeneous model of a run whose figures are recorded in findings.md — every thread
    // of a kind given that kind's average — not a replay of it. What is asserted is the number the
    // report printed, to the precision that model can carry.

    /**
     * The ordinary bench: every thread working, every thread labelled, nothing waiting.
     *
     * Recorded: aggregate 98.95%, inside labelled work 98.94%. The two must agree here, and a
     * change that moves this row is a regression rather than a fix.
     */
    @Test
    fun `ordinary bench - aggregate and labelled agree`() {
        val r = bound(*Array(8) { Thread(labelled = 0.995, offCpu = 0.0105) })
        assertEquals(0.9894, r.duty, 0.002, "the bench's own regime moved")
        assertTrue(pp(r.duty) < 2.0, "bound ${pp(r.duty)} pp on a bench that never waits")
    }

    /**
     * Starvation, 3 of 15 threads working. Recorded: aggregate 19.40%, labelled 96.94%, 3.16 pp.
     *
     * This is the row the per-thread work was done for, and the plan predicted "under 1 pp" for it,
     * which was wrong arithmetic on our own recorded 96%. 3.16 is the answer.
     */
    @Test
    fun `starvation - twelve parked threads do not poison the bound`() {
        val working = Thread(labelled = 1.0, offCpu = 0.0306)
        val parked = Thread(labelled = 0.0, offCpu = 1.0, notRunnable = 1.0)
        val r = bound(working, working, working, *Array(12) { parked })
        assertEquals(0.9694, r.duty, 0.002, "starvation regime moved")
        assertEquals(3.16, pp(r.duty), 0.25, "the bound is no longer the recorded 3.16 pp")
    }

    /**
     * The contended lock: the label wraps the acquisition on purpose, so the waiting really is
     * inside the operation. Recorded: aggregate 38.62%, labelled 38.56%.
     *
     * The bound must stay large. A formula that collapses here is one that has assumed labelled
     * work is running work, which is exactly the assumption the bench's lock mode exists to refute.
     */
    @Test
    fun `contended lock - the bound does not collapse when the waiting is real`() {
        val r = bound(*Array(8) {
            Thread(labelled = 1.0, offCpu = 0.606, notRunnable = 0.594, labelledNotRunnable = 0.594)
        })
        assertEquals(0.3940, r.duty, 0.01, "lock regime moved")
        assertTrue(pp(r.duty) > 50.0, "bound fell to ${pp(r.duty)} pp where the waiting is genuinely labelled")
    }

    /**
     * Lucene: eight pool threads that park between queries and are labelled while searching, plus a
     * main thread that waits. Recorded: aggregate 69.27%, labelled **98.46%**, bound **1.57 pp**.
     *
     * The regression test for the bug this whole file exists for. The same inputs through the
     * stateless formula give something far worse, and that contrast is asserted rather than assumed
     * because it is the entire argument for keeping the state read in the arithmetic.
     */
    @Test
    fun `lucene pool - parking outside the label is not charged to the label`() {
        val worker = Thread(labelled = 0.669, offCpu = 0.221, notRunnable = 0.211)
        val main = Thread(labelled = 0.0, offCpu = 1.0, notRunnable = 1.0)
        val threads = Array(8) { worker } + main

        val withState = bound(*threads, state = true)
        assertEquals(0.9846, withState.duty, 0.01, "the Lucene regime no longer reads 98.46%")
        assertTrue(pp(withState.duty) < 3.0, "bound ${pp(withState.duty)} pp against a recorded 1.57")

        val without = bound(*threads, state = false)
        assertTrue(
            without.duty < 0.75,
            "the stateless bound was supposed to be the vacuous one, and read ${without.duty}"
        )
    }

    /**
     * Netty: event loops whose waiting is native, so the state read cannot see it at all.
     * Recorded: 34.4% invisible off-CPU against labels covering 13.9%, and no bound available.
     *
     * The right answer is that there is no answer, and the report says so in words. What is
     * asserted is that the degenerate case is reachable and correctly identified — a formula that
     * quietly returned a plausible number here would be worse than one that returns nothing.
     */
    @Test
    fun `netty event loops - the bound correctly gives up`() {
        val r = bound(*Array(4) { Thread(labelled = 0.139, offCpu = 0.344, notRunnable = 0.0) })
        assertEquals(0.0, r.duty, 1e-9, "the invisible off-CPU should have swallowed every label")
        assertEquals(0.344, r.invisibleOffCpu, 0.005, "invisible off-CPU is what the diagnosis quotes")
        assertEquals(0.139, r.labelledFraction, 0.005, "labelled fraction is the other half of it")
    }
}

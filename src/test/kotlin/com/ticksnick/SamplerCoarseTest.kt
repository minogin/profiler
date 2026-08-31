package com.ticksnick

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the **sampler** does with coarse contexts, as opposed to what the arithmetic does with
 * numbers somebody typed in.
 *
 * `CoarseTest` checks that `inside` and `working` divide correctly given counters. Nothing checked
 * that the sampler *produces* those counters, or that it keeps work under a finished execution out
 * of them — that was covered only by `--fanout` and `--leakcheck`, which are bench modes and which
 * `gradlew test` has never run. A refactor could have broken any of it silently.
 *
 * **These start a real sampler and hold real threads in known states.** That is not the usual shape
 * of a unit test, and it is the only shape that can test a sampler: the thing under test is what a
 * photograph of the process contains. They are made reliable by holding each state for far longer
 * than the sampling step — a few hundred milliseconds at 1 ms is hundreds of samples — so nothing
 * here turns on catching a single tick.
 */
class SamplerCoarseTest {

    @AfterTest
    fun stopSampling() {
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling, which is the normal case when the test stopped it itself.
        }
    }

    /** How long a state is held. Hundreds of samples at a 1 ms step, so no result rests on one tick. */
    private val holdNanos = 400_000_000L

    /** Somewhere for the spin loops to land, so nothing can be optimised away. */
    @Volatile
    private var sink: Long = 0

    /** Spins until [stop] flips, doing arithmetic the JIT cannot delete. */
    private fun spinUntil(stop: AtomicBoolean) {
        var s = sink or 1L
        while (!stop.get()) {
            var i = 0
            while (i < 4096) {
                s = s * 31 + i
                i++
            }
        }
        sink = s
    }

    private fun spinFor(nanos: Long) {
        val until = System.nanoTime() + nanos
        var s = sink or 1L
        while (System.nanoTime() < until) {
            var i = 0
            while (i < 4096) {
                s = s * 31 + i
                i++
            }
        }
        sink = s
    }

    /** Runs [body] on a thread of its own and gives its slot back, as a caller is asked to. */
    private fun onThread(name: String, body: () -> Unit): Thread =
        Thread({
            try {
                body()
            } finally {
                Profiler.release()
            }
        }, name)

    // --- work under an execution that has finished -----------------------------------

    /**
     * The failure the stale detector exists for, driven through the sampler rather than asserted of
     * the flag alone.
     *
     * A task is wrapped inside a request, the request ends, and only then does the task run — which
     * is exactly what fire-and-forget does. Everything about it looks plausible: the right operation
     * name, a sensible-looking number. The only thing that distinguishes it is that the execution
     * carrying it is over.
     */
    @Test
    fun `work under a closed execution is counted stale and kept out of the type's totals`() {
        val t = Profiler.registerCoarse("sampler-stale")
        val fine = Profiler.registerFine("sampler-stale-work")
        Profiler.start(stepMillis = 1.0, strict = false)

        // Closed the moment the block ends, and captured on the way out.
        val ctx = assertNotNull(op(t) { captureCoarse() })
        val worker = onThread("stale-worker") {
            withCoarse(ctx) { op(fine) { spinFor(holdNanos) } }
        }
        worker.start()
        worker.join()

        val report = Profiler.stop()
        val stat = assertNotNull(report.coarse.firstOrNull { it.name == "sampler-stale" })

        assertTrue(report.staleContextHits > 100, "the sampler saw ${report.staleContextHits} stale samples")
        assertTrue(stat.staleHits > 100, "the type recorded ${stat.staleHits} stale samples")
        // The whole point of counting them separately: they must not reach anything the type
        // reports. Crediting them lets busy/exec exceed the span it sits inside.
        assertTrue(
            stat.inclusiveHits * 20 < stat.staleHits,
            "stale work reached the totals: ${stat.inclusiveHits} credited against ${stat.staleHits} stale"
        )
    }

    /**
     * The regression test for a defect I introduced and had to measure my way out of.
     *
     * The sampler reads a thread's context and reads the closed flag microseconds later. A thread
     * that leaves an execution just before it closes falls between the two reads and looks stale
     * though it never was — and that ordering is not rare, it is what *every* clean exit does. The
     * first version counted them: a correct run read **1.14%** stale, tripped the strict threshold,
     * and stopped a sixty-second session one second in.
     *
     * The fix is a second read: on seeing a closed context, ask whether the thread is *still* in it.
     * With the re-read removed this test reports **81** stale samples in 400 ms of correct work,
     * which is the regression it exists for.
     *
     * **What it does not test, and an earlier version of this comment claimed it did.** `coarse`'s
     * `finally` restores the slot *before* marking the context closed, which narrows the window
     * further. Swapping those two lines leaves this test green — the window it opens is a single
     * opaque store, a nanosecond or two against a 1 ms step, so a few hundred ticks have about one
     * chance in a million of landing in it. The ordering is still the right way round; it is simply
     * not what is under test here, and nothing in this suite covers it.
     */
    @Test
    fun `opening and closing executions on one thread never looks stale`() {
        val t = Profiler.registerCoarse("sampler-churn")
        val fine = Profiler.registerFine("sampler-churn-work")
        Profiler.start(stepMillis = 1.0, strict = false)

        val worker = onThread("churn-worker") {
            val until = System.nanoTime() + holdNanos
            var s = 1L
            // Short executions, opened and closed as fast as the machine will go, so the sampler
            // gets every chance to photograph one on the way out.
            while (System.nanoTime() < until) {
                op(t) {
                    op(fine) {
                        var i = 0
                        while (i < 2048) {
                            s = s * 31 + i
                            i++
                        }
                    }
                }
            }
            sink = s
        }
        worker.start()
        worker.join()

        val report = Profiler.stop()
        assertEquals(
            0L, report.staleContextHits,
            "a thread that closed its own executions was reported as working under a finished one"
        )
    }

    // --- what the sampler makes of one context on several threads --------------------

    /**
     * `inside` is `inclusiveHits / instanceTicks`, and the two counters come from different rules:
     * every thread in the execution is a hit, but the instance is stamped once per tick however
     * many threads are in it. That is the whole mechanism behind the column, and nothing tested
     * that the sampler applies it — only that the division works.
     */
    @Test
    fun `two threads inside one execution read as two threads inside`() {
        val t = Profiler.registerCoarse("sampler-shared")
        val fine = Profiler.registerFine("sampler-shared-work")
        Profiler.start(stepMillis = 1.0, strict = false)

        val stop = AtomicBoolean(false)
        val ctx = assertNotNull(run {
            var c: CoarseContext? = null
            // Held open for the whole measurement: enter/exit rather than the block form, because
            // the borrowers have to be inside it while it is still live.
            Profiler.enter(t)
            c = captureCoarse()
            c
        })

        val borrowers = (0 until 2).map { i ->
            onThread("borrower-$i") { withCoarse(ctx) { op(fine) { spinUntil(stop) } } }
        }
        borrowers.forEach { it.start() }
        spinFor(holdNanos)
        stop.set(true)
        borrowers.forEach { it.join() }
        Profiler.exit(t)

        val report = Profiler.stop()
        val stat = assertNotNull(report.coarse.firstOrNull { it.name == "sampler-shared" })

        // Three threads are in it: the two borrowers and this one, which never left. Generous
        // bounds — the borrowers start and stop at slightly different moments, and the point is
        // that the sampler counts threads rather than executions, not that it counts 3.00.
        assertTrue(stat.instanceTicks > 100, "only ${stat.instanceTicks} ticks saw the execution")
        assertTrue(
            stat.inside in 2.0..3.2,
            "three threads in one execution read inside ${stat.inside}"
        )
        assertEquals(0L, report.staleContextHits, "a live execution was reported as finished")
    }

    /**
     * A borrowed context is a real parent: a coarse label opened on the receiving thread nests
     * under the one it was handed, and both are credited.
     *
     * This is what makes the cross-tabulation work across a hand-off. Without it a helper's own
     * spans would float free of the request that forked them, which is the same escape phase 5 was
     * built to close, one level down.
     */
    @Test
    fun `a coarse label opened inside a borrowed context nests under it`() {
        val outer = Profiler.registerCoarse("sampler-outer")
        val inner = Profiler.registerCoarse("sampler-inner")
        val fine = Profiler.registerFine("sampler-nested-work")
        Profiler.start(stepMillis = 1.0, strict = false)

        Profiler.enter(outer)
        val ctx = assertNotNull(captureCoarse())
        val worker = onThread("nesting-worker") {
            withCoarse(ctx) {
                op(inner) {
                    // Checked here rather than only in the report: the chain is the mechanism, and
                    // a report that happened to look right would not prove the parent was set.
                    val mounted = assertNotNull(captureCoarse())
                    assertEquals(inner.id, mounted.type)
                    assertEquals(ctx, mounted.parent, "the borrowed context is not the parent")
                    op(fine) { spinFor(holdNanos) }
                }
            }
        }
        worker.start()
        worker.join()
        Profiler.exit(outer)

        val report = Profiler.stop()
        val out = assertNotNull(report.coarse.firstOrNull { it.name == "sampler-outer" })
        val inn = assertNotNull(report.coarse.firstOrNull { it.name == "sampler-inner" })

        assertTrue(inn.inclusiveHits > 100, "the nested execution was barely sampled: ${inn.inclusiveHits}")
        // Inclusive means the outer one is credited with everything under it, including work that
        // happened on a thread it never ran on.
        assertTrue(
            out.inclusiveHits >= inn.inclusiveHits,
            "the outer execution was credited ${out.inclusiveHits} against the inner's ${inn.inclusiveHits}"
        )
        assertEquals(0L, report.staleContextHits)
    }
}

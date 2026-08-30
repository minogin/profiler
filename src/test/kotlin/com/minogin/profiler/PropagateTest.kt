package com.minogin.profiler

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Carrying a coarse execution across a thread boundary.
 *
 * These check the mechanism rather than the measurement — that the right context arrives on the
 * right thread and is put back afterwards. What it does to the numbers is the bench's job, against a
 * stopwatch, in `--fanout`.
 *
 * The thing worth testing hardest is **where the capture happens**. Capturing on the receiving thread
 * instead of the forking one compiles, runs, and silently propagates nothing, which is the one way
 * to get this wrong that no type checker will catch.
 */
class PropagateTest {

    /** What the calling thread is inside, read the way the sampler reads it. */
    private fun current(): CoarseContext? = Profiler.slot().contextOpaque()

    /**
     * Runs [body] on a thread of its own and hands back what it returned.
     *
     * The `finally` is not tidiness. `Profiler` is process-wide and these tests share it: a thread
     * that takes a slot and dies without giving it back holds that index for the life of the JVM,
     * and `RegistryTest` asserts that indexes get reused. Releasing is also exactly what the library
     * asks a caller to do, so the tests may as well be an example of it.
     */
    private fun <T> onAnotherThread(body: () -> T): T {
        var result: Result<T>? = null
        val t = Thread {
            try {
                result = runCatching(body)
            } finally {
                Profiler.release()
            }
        }
        t.start()
        t.join()
        return result!!.getOrThrow()
    }

    /**
     * A pool whose threads give their slots back when they exit.
     *
     * Same reason as above, one level further out: a pool thread outlives any single task, so the
     * release belongs at the end of the thread's life rather than at the end of a task's — which is
     * what a thread factory is for, and what a real caller with a long-lived pool has to do too.
     */
    private fun pool(n: Int): ExecutorService = Executors.newFixedThreadPool(n) { r ->
        Thread {
            try {
                r.run()
            } finally {
                Profiler.release()
            }
        }
    }

    @Test
    fun `capture on a thread inside nothing is null`() {
        assertNull(captureCoarse(), "a thread inside no execution captured something")
    }

    @Test
    fun `withCoarse mounts the context and puts back what was there`() {
        val outer = Profiler.registerCoarse("prop-outer")
        val other = Profiler.registerCoarse("prop-other")
        coarse(outer) {
            val a = assertNotNull(current())
            val borrowed = CoarseContext(other, null, System.nanoTime())
            withCoarse(borrowed) {
                assertSame(borrowed, current(), "the context was not mounted")
            }
            // Restored to what this thread was inside, not cleared. Clearing would bill the rest of
            // the outer execution to nothing at all.
            assertSame(a, current(), "the mount did not restore the previous context")
        }
        assertNull(current())
    }

    @Test
    fun `withCoarse restores even when the body throws`() {
        val t = Profiler.registerCoarse("prop-throwing")
        coarse(t) {
            val a = assertNotNull(current())
            runCatching { withCoarse<Unit>(null) { throw IllegalStateException("boom") } }
            assertSame(a, current(), "the finally did not restore the slot")
        }
    }

    @Test
    fun `mounting null means run under no execution`() {
        val t = Profiler.registerCoarse("prop-unmount")
        coarse(t) {
            assertNotNull(current())
            withCoarse(null) {
                assertNull(current(), "null did not unmount the execution")
            }
            assertNotNull(current())
        }
    }

    /**
     * The whole point of the phase, in six lines: a context made on one thread, seen on another.
     */
    @Test
    fun `a wrapped runnable carries the execution to another thread`() {
        val t = Profiler.registerCoarse("prop-runnable")
        coarse(t) {
            val mine = assertNotNull(current())
            val task = Runnable { assertSame(mine, current(), "the context did not cross") }.propagating()
            onAnotherThread { task.run() }
        }
    }

    @Test
    fun `a wrapped callable carries the execution to another thread`() {
        val t = Profiler.registerCoarse("prop-callable")
        val seen = coarse(t) {
            val task = Callable { current() }.propagating()
            onAnotherThread { task.call() }
        }
        assertNotNull(seen, "the context did not cross")
        assertEquals(t, seen.type)
    }

    /**
     * Capture is at **wrap** time, and this is the test that says so.
     *
     * Wrapping outside the execution and running inside it must propagate nothing: at the moment of
     * wrapping there was nothing to capture. A version that captured when the task ran would pass
     * every other test here and fail this one — and in real use it would capture the pool thread's
     * context, which is empty, and quietly propagate nothing at all.
     */
    @Test
    fun `capture happens where the task is wrapped, not where it runs`() {
        val t = Profiler.registerCoarse("prop-wrap-site")
        val wrappedOutside = Callable { current() }.propagating()
        val seen = coarse(t) { wrappedOutside.call() }
        assertNull(seen, "the task captured a context it was never wrapped under")
    }

    @Test
    fun `a wrapped runnable leaves the borrowing thread as it found it`() {
        val mine = Profiler.registerCoarse("prop-restore-mine")
        val theirs = Profiler.registerCoarse("prop-restore-theirs")
        coarse(mine) {
            val task = Runnable { }.propagating()
            onAnotherThread {
                coarse(theirs) {
                    val before = assertNotNull(current())
                    task.run()
                    assertSame(before, current(), "the borrowed context was left mounted")
                }
                assertNull(current())
            }
        }
    }

    @Test
    fun `a propagating executor service carries the execution through submit`() {
        val t = Profiler.registerCoarse("prop-executor")
        val pool = pool(1).propagating()
        try {
            val seen = coarse(t) { pool.submit(Callable { current() }).get() }
            assertNotNull(seen, "submit did not carry the context")
            assertEquals(t, seen.type)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a propagating executor service carries the execution through invokeAll`() {
        val t = Profiler.registerCoarse("prop-invokeall")
        val pool = pool(2).propagating()
        try {
            val seen = coarse(t) {
                pool.invokeAll(listOf(Callable { current() }, Callable { current() })).map { it.get() }
            }
            assertTrue(seen.all { it != null && it.type == t }, "invokeAll did not carry the context")
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a propagating executor carries the execution through execute`() {
        // execute() is the one method on Executor, so a user who wrapped a bare Executor rather
        // than an ExecutorService has only this path. It was the only wrapper never exercised.
        val t = Profiler.registerCoarse("prop-execute")
        val pool = pool(1).propagating()
        try {
            val seen = java.util.concurrent.atomic.AtomicReference<CoarseContext?>()
            val done = java.util.concurrent.CountDownLatch(1)
            coarse(t) { pool.execute { seen.set(current()); done.countDown() } }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val ctx = assertNotNull(seen.get(), "execute did not carry the context")
            assertEquals(t, ctx.type)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a propagating executor service carries the execution through invokeAny`() {
        val t = Profiler.registerCoarse("prop-invokeany")
        val pool = pool(2).propagating()
        try {
            val seen = coarse(t) {
                pool.invokeAny(listOf(Callable { current()?.type ?: -1 }))
            }
            assertEquals(t, seen, "invokeAny did not carry the context")
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a propagating executor service carries the execution through submit with a result`() {
        val t = Profiler.registerCoarse("prop-submit-result")
        val pool = pool(1).propagating()
        try {
            val seen = java.util.concurrent.atomic.AtomicReference<CoarseContext?>()
            val handed = coarse(t) { pool.submit({ seen.set(current()) }, "done").get() }
            assertEquals("done", handed, "the result value was not passed through")
            val ctx = assertNotNull(seen.get(), "submit(Runnable, result) did not carry the context")
            assertEquals(t, ctx.type)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a propagating executor forwards lifecycle calls to the delegate`() {
        // The methods that carry no work must still reach the pool underneath, or a wrapped pool
        // could never be shut down and the wrapper would leak threads for the life of the process.
        val underlying = pool(1)
        val pool = underlying.propagating()
        pool.shutdown()
        assertTrue(underlying.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(underlying.isShutdown, "shutdown did not reach the delegate")
        assertTrue(pool.isTerminated, "isTerminated did not reach the delegate")
    }

    // --- a context that outlives its span ---------------------------------------------

    @Test
    fun `a context is not closed while its owner is inside it`() {
        val t = Profiler.registerCoarse("prop-open")
        coarse(t) {
            val ctx = assertNotNull(current())
            assertTrue(!ctx.isClosed(), "the execution reported itself finished while it was running")
        }
    }

    @Test
    fun `a context is closed once its owner leaves it`() {
        // What the sampler reads to tell "a thread is working on this request" from "a thread is
        // working on a request that ended". Nothing else can distinguish those: the owner's slot is
        // clean either way, so the balance check sees nothing wrong.
        val t = Profiler.registerCoarse("prop-closed")
        var captured: CoarseContext? = null
        coarse(t) { captured = current() }
        assertTrue(assertNotNull(captured).isClosed(), "a finished execution did not mark itself closed")
    }

    @Test
    fun `a context is closed by the non-lexical form too`() {
        // enter/exit is the form third-party code forces on you, and it is the form most likely to
        // be handing work around, so it is the one that must not be missed.
        val t = Profiler.registerCoarse("prop-closed-nonlexical")
        enterCoarse(t)
        val ctx = assertNotNull(current())
        exitCoarse()
        assertTrue(ctx.isClosed(), "exitCoarse did not mark the execution closed")
    }

    @Test
    fun `a borrowed context outliving its span is visible as closed`() {
        // The whole failure mode in one test: the task is wrapped inside the request, the request
        // ends, and only then does the task run. Its context is still mountable and still names the
        // right operation, which is exactly why this is the dangerous direction - everything looks
        // plausible. The flag is the only thing that says otherwise.
        val t = Profiler.registerCoarse("prop-outlives")
        val task = Callable { current() }
        val wrapped = coarse(t) { task.propagating() }
        val seen = assertNotNull(onAnotherThread { wrapped.call() }, "the context did not cross")
        assertEquals(t, seen.type)
        assertTrue(seen.isClosed(), "work outliving its span did not look stale")
    }

    /**
     * The delayed methods do not propagate, on purpose.
     *
     * A task that runs later will usually outlive the execution that scheduled it, and crediting it
     * there invents attribution rather than losing it. This is the one asymmetry in the file and it
     * is the one most likely to be "fixed" by somebody who has not read why.
     */
    @Test
    fun `schedule deliberately does not carry the execution`() {
        val t = Profiler.registerCoarse("prop-scheduled")
        val pool = Executors.newSingleThreadScheduledExecutor { r ->
            Thread {
                try {
                    r.run()
                } finally {
                    Profiler.release()
                }
            }
        }.propagating()
        try {
            val delayed = coarse(t) { pool.schedule(Callable { current() }, 1, TimeUnit.MILLISECONDS) }
            assertNull(delayed.get(), "a delayed task inherited a context it will usually outlive")

            // The same pool still propagates through the undelayed path, so this is a property of
            // the method and not of the wrapper having given up on scheduled pools.
            val immediate = coarse(t) { pool.submit(Callable { current() }).get() }
            assertNotNull(immediate, "submit on a scheduled pool did not carry the context")
            assertEquals(t, immediate.type)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

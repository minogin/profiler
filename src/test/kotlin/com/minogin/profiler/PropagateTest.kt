package com.minogin.profiler

import java.util.concurrent.Callable
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

    /** Runs [body] on a thread of its own and hands back what it returned. */
    private fun <T> onAnotherThread(body: () -> T): T {
        var result: Result<T>? = null
        val t = Thread { result = runCatching(body) }
        t.start()
        t.join()
        return result!!.getOrThrow()
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
        val pool = Executors.newSingleThreadExecutor().propagating()
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
        val pool = Executors.newFixedThreadPool(2).propagating()
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
    fun `a propagating executor forwards lifecycle calls to the delegate`() {
        // The methods that carry no work must still reach the pool underneath, or a wrapped pool
        // could never be shut down and the wrapper would leak threads for the life of the process.
        val underlying = Executors.newSingleThreadExecutor()
        val pool = underlying.propagating()
        pool.shutdown()
        assertTrue(underlying.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(underlying.isShutdown, "shutdown did not reach the delegate")
        assertTrue(pool.isTerminated, "isTerminated did not reach the delegate")
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
        val pool = Executors.newSingleThreadScheduledExecutor().propagating()
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

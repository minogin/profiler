package com.minogin.profiler

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The slot, the span stack, and what happens when a label is left open.
 *
 * The only tests here that touch threads, and they touch them because the things being checked are
 * about threads: a slot belongs to one, an index is released when one dies, and a leak is per
 * thread. Nothing here waits on a clock or measures a duration — a test that did would be measuring
 * the machine, which is the bench's job.
 *
 * `Profiler` is a process-wide object, so these run against shared state; each test cleans up after
 * itself and none of them may assume a fresh registry.
 */
class RegistryTest {

    @AfterTest
    fun stopSampling() {
        // A test that failed mid-session must not leave a sampler running for the next one.
        try {
            Profiler.stop()
        } catch (_: IllegalStateException) {
            // Not sampling, which is the normal case.
        }
    }

    /** Runs [body] on a thread of its own and waits, so one test's slot cannot pollute another's. */
    private fun onOwnThread(name: String = "test-worker", body: () -> Unit) {
        var thrown: Throwable? = null
        val t = Thread({
            try {
                body()
            } catch (e: Throwable) {
                thrown = e
            }
        }, name)
        t.start()
        t.join()
        thrown?.let { throw it }
    }

    // ---------------------------------------------------------------- the slot

    /** Registration is idempotent, so a `val` in an object is a safe place to keep an id. */
    @Test
    fun `registering the same name twice gives the same id`() {
        val a = Profiler.register("test:idempotent")
        val b = Profiler.register("test:idempotent")
        assertEquals(a, b)
        assertEquals("test:idempotent", Profiler.nameOf(a))
    }

    /** One slot per thread, and it is the same object every time that thread asks. */
    @Test
    fun `a thread gets one slot and keeps it`() {
        onOwnThread {
            assertSame(Profiler.slot(), Profiler.slot())
        }
    }

    /**
     * The padding is why `OpSlot` is 15 longs wide, and it is not decoration: four slots sharing a
     * 64-byte line would make every worker's write invalidate its neighbours' copies, tens of
     * nanoseconds against operations that last twenty.
     *
     * The layout cannot be asserted from Kotlin, but the field count that produces it can — if
     * somebody deletes the padding to tidy up, this says so.
     */
    @Test
    fun `the slot is still padded`() {
        val padding = OpSlot::class.java.declaredFields.count { it.name.matches(Regex("p\\d+")) }
        assertTrue(padding >= 15, "only $padding padding fields left; false sharing is back")
    }

    // ---------------------------------------------------------------- enter and exit

    /** `enter`/`exit` nest, and coming back out restores what was underneath rather than clearing. */
    @Test
    fun `labels nest and unwind to what was underneath`() {
        val outer = Profiler.register("test:outer")
        val inner = Profiler.register("test:inner")
        onOwnThread {
            val s = Profiler.slot()
            assertEquals(NO_OP, s.getOpaque())
            Profiler.enter(outer)
            assertEquals(outer, s.getOpaque())
            Profiler.enter(inner)
            assertEquals(inner, s.getOpaque())
            Profiler.exit()
            assertEquals(outer, s.getOpaque(), "unwinding lost the enclosing label")
            Profiler.exit()
            assertEquals(NO_OP, s.getOpaque())
            assertEquals(0, Profiler.depth())
        }
    }

    /** `op { }` and the explicit form nest with each other in either order. */
    @Test
    fun `the block form and the explicit form interleave`() {
        val a = Profiler.register("test:block")
        val b = Profiler.register("test:explicit")
        onOwnThread {
            val s = Profiler.slot()
            op(a) {
                assertEquals(a, s.getOpaque())
                Profiler.enter(b)
                assertEquals(b, s.getOpaque())
                Profiler.exit()
                assertEquals(a, s.getOpaque())
            }
            assertEquals(NO_OP, s.getOpaque())
        }
    }

    /** `op { }` has a `finally`; that is the whole reason it is the form documented first. */
    @Test
    fun `the block form closes its label when the body throws`() {
        val id = Profiler.register("test:throwing")
        onOwnThread {
            val s = Profiler.slot()
            runCatching { op<Unit>(id) { throw IllegalStateException("boom") } }
            assertEquals(NO_OP, s.getOpaque(), "a throwing body left the label set")
            assertEquals(0, Profiler.depth())
        }
    }

    // ---------------------------------------------------------------- the balance check

    /** A thread that closed everything it opened reports balanced and is not counted as a leak. */
    @Test
    fun `a balanced thread reports balanced`() {
        val id = Profiler.register("test:balanced")
        onOwnThread {
            op(id) { }
            assertTrue(Profiler.expectBalanced())
        }
    }

    /**
     * A leak is reported once and the stack is reset, so the next check is about the next interval
     * rather than about the same leak again.
     */
    @Test
    fun `a leak is reported once and then the slot is clean`() {
        val id = Profiler.register("test:leaky")
        onOwnThread {
            Profiler.enter(id)
            assertFalse(Profiler.expectBalanced(), "a leaked label reported itself balanced")
            assertEquals(NO_OP, Profiler.slot().getOpaque(), "the leak was not reset")
            assertTrue(Profiler.expectBalanced(), "the same leak was reported twice")
        }
    }

    /**
     * The one fatal condition, and the check that it is fatal *only* under strict.
     *
     * This was a CLI flag — `--leakcheck` — because there was nowhere else to put it. Asserting
     * only that a leak stops the session would still pass if `strict` were wired to nothing, which
     * is why both directions are here.
     */
    @Test
    fun `a leak stops the session under strict and only under strict`() {
        val id = Profiler.register("test:fatal")

        Profiler.start(stepMillis = 1.0, strict = true)
        onOwnThread { Profiler.enter(id); Profiler.expectBalanced() }
        val strict = Profiler.stop()
        assertTrue(strict.failure != null, "a leak did not stop a strict session")
        assertTrue(
            strict.failure!!.contains("test:fatal"),
            "the failure did not name the operation that leaked: ${strict.failure}"
        )
        assertFalse(strict.ok)

        Profiler.start(stepMillis = 1.0, strict = false)
        onOwnThread { Profiler.enter(id); Profiler.expectBalanced() }
        val lenient = Profiler.stop()
        assertNull(lenient.failure, "a leak stopped a non-strict session")
        assertTrue(lenient.ok)
    }

    /** An `exit` with no `enter` names nothing rather than reading off the end of the name table. */
    @Test
    fun `an unmatched exit does not crash the leak message`() {
        onOwnThread {
            Profiler.exit()
            // Depth is already zero, so this is balanced; what is checked is that getting here at
            // all — and formatting a message from a slot holding NO_OP — is safe.
            assertTrue(Profiler.expectBalanced())
        }
        assertTrue(leakMessage("an unnamed label", "t").contains("an unnamed label"))
    }

    // ---------------------------------------------------------------- the slot registry

    /**
     * A dead thread's slot must leave the walk list. One that does not reads empty forever, which
     * inflates the sampler's denominator and — worse for occupancy work — counts a dead thread as
     * an idle one.
     */
    @Test
    fun `releasing a slot removes it from the walk`() {
        val before = Profiler.slots().size
        onOwnThread {
            Profiler.slot()
            assertEquals(before + 1, Profiler.slots().size, "a new thread did not register a slot")
            Profiler.release()
        }
        assertEquals(before, Profiler.slots().size, "a released slot stayed in the walk list")
    }

    /**
     * A released index is handed to the next thread that arrives.
     *
     * Recorded here because the duty cycle's per-thread arrays are indexed by it, and a reused
     * index blends two threads' figures. That is a known and accepted inaccuracy — this test is
     * what makes it a *known* one rather than a surprise to whoever reads the arrays next.
     */
    @Test
    fun `a released index is reused`() {
        var first = -1
        onOwnThread { first = Profiler.slot().index; Profiler.release() }
        var second = -1
        onOwnThread { second = Profiler.slot().index; Profiler.release() }
        assertTrue(first >= 0 && second >= 0, "the registry was full")
        assertEquals(first, second, "a freed slot index was not handed on")
    }

    /** Two live threads never share an index, which is what makes the per-thread arrays meaningful. */
    @Test
    fun `two live threads get different indexes`() {
        var a = -1
        var b = -1
        val gate = java.util.concurrent.CountDownLatch(1)
        val one = Thread { a = Profiler.slot().index; gate.await(); Profiler.release() }
        val two = Thread { b = Profiler.slot().index; gate.await(); Profiler.release() }
        one.start(); two.start()
        while (a < 0 || b < 0) Thread.onSpinWait()
        assertNotEquals(a, b, "two live threads shared a slot index")
        gate.countDown()
        one.join(); two.join()
    }
}

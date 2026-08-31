package com.ticksnick.bench

import com.ticksnick.ThreadCpuClock
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * What it costs to ask the operating system how much CPU a thread has used — and at what resolution
 * it answers.
 *
 * **The measurement that decides a design.** Trial 4 measured `working` reading **55x** more CPU than
 * the process actually spent, because it is built on `Thread.getState` and Java reports a thread
 * inside a native call as `RUNNABLE`. The report now prints a bound beside the number
 * ([Report.workingCeilingOf]), but a bound says the figure cannot be trusted; it does not produce a
 * better one. The better one is CPU time attributed per label, and whether that is affordable turns
 * entirely on two numbers nobody here has measured.
 *
 * **Two constraints, and either one alone can kill it:**
 *
 * 1. **Cost per call.** The duty cycle already reports a *walk* of the live threads at about 130 µs,
 *    which is why it runs once a second and not once a tick. But a walk is not a call, and the whole
 *    question is what one call costs. At a 1 ms step, a walk of eight threads has to fit in a budget
 *    where 130 µs is already 13%.
 * 2. **Resolution.** A clock quantised to the scheduler tick cannot attribute anything to a
 *    millisecond, whatever it costs to read. This is the constraint that is easy to forget and
 *    impossible to work around, and on Windows the quantum is 15.625 ms — *fifteen times the
 *    sampling step*.
 *
 * Measured with the victims **busy**, as [StackCost] measures its walk, because reading the clock of
 * a running thread is not obviously the same price as reading an idle one and the sampler will only
 * ever do the former.
 */
private const val WARMUP_REPS = 200_000
private const val REPS = 200_000
private const val TRIALS = 9

/** Kept so the JIT cannot decide the readings are unused and delete the calls being timed. */
private var sink: Long = 0

internal fun runCpuCost(victims: Int) {
    val bean = ManagementFactory.getThreadMXBean()
    println("=".repeat(96))
    println("WHAT A PER-THREAD CPU READING COSTS, AND WHAT IT IS WORTH")
    println("=".repeat(96))
    println("  CPU time supported: ${bean.isThreadCpuTimeSupported}, enabled: ${bean.isThreadCpuTimeEnabled}")
    if (!bean.isThreadCpuTimeSupported) {
        println("  nothing to measure on this JVM")
        return
    }

    // Busy threads to read, because that is the only kind the sampler will ever read. They spin on
    // arithmetic and never block, so their CPU clock is genuinely advancing while it is being read.
    val running = java.util.concurrent.atomic.AtomicBoolean(true)
    val spinners = (0 until victims).map { i ->
        Thread({
            var s = i.toLong() or 1L
            while (running.get()) s = burn(s, 4096)
            sink += s
        }, "cpucost-victim-$i").also { it.isDaemon = true; it.start() }
    }
    val ids = spinners.map { it.threadId() }.toLongArray()
    // Let them reach steady state, and let the JIT compile the spin loop, before anything is timed.
    Thread.sleep(200)

    fun median(block: () -> Double): Double {
        val a = DoubleArray(TRIALS) { block() }
        a.sort()
        return a[TRIALS / 2]
    }

    // --- self, the cheapest form there is -------------------------------------------
    repeat(WARMUP_REPS) { sink += bean.currentThreadCpuTime }
    val self = median {
        val t0 = System.nanoTime()
        var r = 0
        while (r < REPS) {
            sink += bean.currentThreadCpuTime
            r++
        }
        (System.nanoTime() - t0).toDouble() / REPS
    }

    // --- another thread, which is the form a sampler actually needs -------------------
    repeat(WARMUP_REPS) { sink += bean.getThreadCpuTime(ids[it % ids.size]) }
    val other = median {
        val t0 = System.nanoTime()
        var r = 0
        while (r < REPS) {
            sink += bean.getThreadCpuTime(ids[r % ids.size])
            r++
        }
        (System.nanoTime() - t0).toDouble() / REPS
    }

    // --- the whole walk, measured rather than multiplied out --------------------------
    val walkReps = maxOf(1, REPS / maxOf(1, victims))
    val walk = median {
        val t0 = System.nanoTime()
        var r = 0
        while (r < walkReps) {
            for (id in ids) sink += bean.getThreadCpuTime(id)
            r++
        }
        (System.nanoTime() - t0).toDouble() / walkReps
    }

    // --- resolution, through the library's own probe rather than a second copy ---------
    // A check written twice is a check that drifts, and this bench has had that happen before.
    val resolution = ThreadCpuClock.probeResolutionNanos()

    running.set(false)
    spinners.forEach { it.join() }

    println(
        String.format(
            Locale.ROOT, "%n  %-38s %10.1f ns", "getCurrentThreadCpuTime(), own thread", self
        )
    )
    println(
        String.format(
            Locale.ROOT, "  %-38s %10.1f ns   <- the form a sampler needs",
            "getThreadCpuTime(id), another thread", other
        )
    )
    println(
        String.format(
            Locale.ROOT, "  %-38s %10.1f us   (%d threads, measured not multiplied)",
            "a walk of every victim", walk / 1e3, victims
        )
    )
    println(
        String.format(
            Locale.ROOT, "  %-38s %10.3f ms",
            "clock resolution", if (resolution < 0) Double.NaN else resolution / 1e6
        )
    )

    // --- what it means, which is the point of running it ------------------------------
    val stepNanos = 1_000_000.0
    println("\n  against a 1 ms sampling step:")
    println(
        String.format(
            Locale.ROOT, "    a walk every tick would take %.1f%% of the step at %d threads, %.1f%% at 64",
            walk / stepNanos * 100, victims, other * 64 / stepNanos * 100
        )
    )
    if (resolution > 0) {
        println(
            String.format(
                Locale.ROOT, "    the clock moves in steps of %.3f ms, which is %.0fx the sampling step",
                resolution / 1e6, resolution / stepNanos
            )
        )
    }
    println()
    when {
        resolution < 0 ->
            println("  VERDICT: the clock did not move during the probe, so nothing can be said about it here")

        resolution > stepNanos * 2 -> {
            println("  VERDICT: RESOLUTION, not cost, is what rules out CPU per label.")
            println("  A reading quantised to ${"%.3f".format(resolution / 1e6)} ms cannot attribute anything to a 1 ms tick,")
            println("  however cheap it is to take: nearly every read returns the same value as the last and")
            println("  the deltas land in whichever tick happened to cross a quantum boundary. That is why the")
            println("  duty cycle averages over a one-second window, and it is a bound on the whole idea and")
            println("  not a detail of this implementation.")
            println()
            println("  What survives it: a per-window figure, which is what the duty cycle already is, and")
            println("  which is what `working`'s ceiling is computed from. What does not: per-tick, per-label,")
            println("  or per-execution attribution for anything shorter than a scheduler quantum.")
        }

        walk > stepNanos * 0.1 ->
            println("  VERDICT: the resolution allows it and the cost does not - a walk is over 10% of the step.")

        else ->
            println("  VERDICT: both constraints allow it. CPU per label is affordable at this step and this")
    }
    println("\n(sink: $sink)")
}

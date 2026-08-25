package com.minogin.profiler

import java.util.Locale

/**
 * What a cross-thread stack costs, and therefore whether the tool may ever take one.
 *
 * The design refuses stack walking because its cost is per sample, and a sample here is an integer
 * read. But the Lucene trial produced a problem that only a stack can answer: when half the run is
 * outside every label, nothing in this design can say *where* — and a label in the wrong place is
 * then invisible. The idea under test is to walk a stack only on the ticks that found the slot
 * empty, and only a hundred times less often than the label walk: enough to tell "the gap is one
 * place" from "the gap is everywhere", and never on the hot path.
 *
 * That idea is worthless without a number, and there are **two** numbers, which is the trap. What
 * the *caller* pays is easy to measure and is not the question. What the *victim* pays is the
 * question: `Thread.getStackTrace` on another thread needs a handshake, and the target has to reach
 * a safepoint before it can be walked. A microbenchmark of the caller would report a cost the
 * workers actually pay, and report it as free.
 *
 * So both are measured, and the second is measured the way this project has learned to measure
 * anything comparative: interleaved, with the order swapped every round, because a sequential
 * comparison on this machine aliases clock drift onto the effect and has done so four times.
 */

/**
 * A thread that runs at a known stack depth.
 *
 * Recursion rather than a loop, because the thing being priced is depth and depth has to be real.
 * The descent parameter is a runtime value, so C2's inlining stops at its own limit and the frames
 * below that are genuinely on the stack — but that is a claim about the compiler, so the depth
 * actually achieved is *read back from a real stack trace* and printed rather than assumed.
 */
private class Victim(val depth: Int, val iters: Int, name: String) : Thread(name) {

    @Volatile
    var running = true

    /** One lap is one descent plus the work at the bottom. The victim's throughput, in other words. */
    @Volatile
    var laps = 0L

    private var state = 0x9E3779B97F4A7C15uL.toLong()

    override fun run() {
        while (running) {
            state = descend(depth, state)
            laps++
        }
        Sink.consume(state)
    }

    private fun descend(d: Int, s: Long): Long =
        if (d <= 0) burn(s, iters) else descend(d - 1, s) + 1
}

/** Median and the tail, because the tail is what a handshake with an unlucky thread looks like. */
private class Spread(samples: LongArray) {
    private val sorted = samples.sortedArray()
    val n = sorted.size
    val median = if (n == 0) Double.NaN else sorted[n / 2].toDouble()
    val p99 = if (n == 0) Double.NaN else sorted[(n * 99) / 100].toDouble()
    val max = if (n == 0) Double.NaN else sorted.last().toDouble()
    val mean = if (n == 0) Double.NaN else sorted.average()
}

private fun startVictims(count: Int, depth: Int, iters: Int): List<Victim> =
    (0 until count).map { Victim(depth, iters, "victim-$it") }.onEach { it.isDaemon = true; it.start() }

private fun stop(victims: List<Victim>) {
    for (v in victims) v.running = false
    for (v in victims) v.join(2_000)
}

/**
 * Part one: what the caller pays, against the depth of the stack it is walking.
 *
 * The cheap half of the question. Worth doing first because it settles whether cost scales with
 * depth, which decides whether a deep workload — a compiler, the next trial on the list — is a
 * different proposition from a shallow one.
 */
private fun callerCost(depths: List<Int>, victimCount: Int, iters: Int, samplesPerDepth: Int) {
    println()
    println("CALLER COST — one Thread.getStackTrace against a running victim")
    println("-".repeat(102))
    println(String.format(Locale.ROOT, "%8s %10s %10s %10s %10s %10s %10s", "asked", "reached", "samples", "median", "mean", "p99", "max"))
    for (depth in depths) {
        val victims = startVictims(victimCount, depth, iters)
        try {
            // Let the victims get compiled and settle at depth before anything is timed.
            Thread.sleep(300)
            val reached = victims[0].stackTrace.size
            // Warm the walker itself: the first few calls on any path are the interpreter's.
            repeat(200) { victims[0].stackTrace }

            val samples = LongArray(samplesPerDepth)
            for (i in 0 until samplesPerDepth) {
                val victim = victims[i % victims.size]
                val t0 = System.nanoTime()
                val trace = victim.stackTrace
                samples[i] = System.nanoTime() - t0
                if (trace.isEmpty()) error("empty stack")
            }
            val s = Spread(samples)
            println(
                String.format(
                    Locale.ROOT, "%8d %10d %10d %9.1fus %9.1fus %9.1fus %9.1fus",
                    depth, reached, s.n, s.median / 1e3, s.mean / 1e3, s.p99 / 1e3, s.max / 1e3
                )
            )
        } finally {
            stop(victims)
        }
    }
    println("'reached' is the depth a real trace came back with — recursion is only as deep as C2 leaves it")
}

/**
 * Part two: what the victims pay, which is the question.
 *
 * Throughput of the worker threads against the rate at which their stacks are being taken. Rates
 * are run round-robin with the order reversed every other round, for the reason recorded four times
 * in findings.md: this machine's clock moves by more than the effect being measured, and a
 * sequential sweep charges the drift to whichever configuration was running when it happened.
 */
private fun victimCost(rates: List<Int>, victimCount: Int, depth: Int, iters: Int, rounds: Int, roundMillis: Long) {
    val victims = startVictims(victimCount, depth, iters)
    try {
        Thread.sleep(300)
        val reached = victims[0].stackTrace.size
        repeat(200) { victims[0].stackTrace }

        // -1 is the control and -2 is unthrottled. The control spins in exactly the same loop as
        // every probing configuration and simply does not take the stack, so the only difference
        // between them is the walk itself — a control that parked instead would be comparing "a
        // spinning thread on a spare core" against "a parked one" and calling the difference a
        // handshake. Unthrottled is not a proposal; it is there to find the rate at which the cost
        // becomes visible at all, because a number below the noise floor is not a measurement.
        val configs = listOf(-1) + rates + listOf(-2)

        println()
        println("VICTIM COST — worker throughput while its stack is being taken")
        println("-".repeat(102))
        println("$victimCount victims at stack depth $reached, $rounds rounds of $roundMillis ms each, order reversed every other round")
        println("the control spins in the same loop and takes no stack, so the difference is the walk and nothing else")

        val laps = configs.associateWith { ArrayList<Double>() }
        val achieved = configs.associateWith { ArrayList<Double>() }

        for (round in 0 until rounds) {
            for (config in if (round % 2 == 0) configs else configs.reversed()) {
                val before = victims.sumOf { it.laps }
                val t0 = System.nanoTime()
                val deadline = t0 + roundMillis * 1_000_000L
                // Spin rather than park. LockSupport.parkNanos on this machine has a granularity
                // near a millisecond, which silently capped an earlier version of this measurement
                // at 1,417 stacks/s when it had asked for 10,000 — so the high-rate configurations
                // were never actually run and the answer looked like "free at any rate".
                val periodNanos = if (config > 0) 1_000_000_000L / config else 0L
                var taken = 0L
                var next = t0
                var i = 0
                while (true) {
                    val now = System.nanoTime()
                    if (now >= deadline) break
                    if (config == -1) continue
                    if (config > 0 && now < next) continue
                    val trace = victims[i++ % victims.size].stackTrace
                    if (trace.isEmpty()) error("empty stack")
                    taken++
                    if (config > 0) next = maxOf(next + periodNanos, System.nanoTime() - periodNanos)
                }
                val elapsed = (System.nanoTime() - t0) / 1e9
                laps.getValue(config) += (victims.sumOf { it.laps } - before) / elapsed
                achieved.getValue(config) += taken / elapsed
            }
        }

        fun median(v: List<Double>) = v.sorted().let { it[it.size / 2] }
        val control = median(laps.getValue(-1))
        // The floor: how far the control alone moves between rounds. Anything inside this is not a
        // measurement of the stack walk, whatever sign it carries.
        val controlRounds = laps.getValue(-1)
        val floor = (controlRounds.max() - controlRounds.min()) / control * 100

        println()
        println(
            String.format(
                Locale.ROOT, "control: %,.0f laps/s, and it moves %.2f%% between rounds on its own — that is the floor",
                control, floor
            )
        )
        println()
        println(String.format(Locale.ROOT, "%18s %12s %16s %12s %14s", "stacks/s asked", "achieved", "laps/s (median)", "vs control", "per stack"))
        for (config in configs) {
            val m = median(laps.getValue(config))
            val got = achieved.getValue(config).average()
            val delta = (m / control - 1) * 100
            val lostPerStack = if (got <= 0) Double.NaN else (control - m) / got
            println(
                String.format(
                    Locale.ROOT, "%18s %12.0f %16.0f %11.2f%% %14s",
                    when (config) {
                        -1 -> "none (control)"
                        -2 -> "unthrottled"
                        else -> "%,d".format(Locale.ROOT, config)
                    },
                    got, m, delta,
                    when {
                        config == -1 -> "-"
                        kotlin.math.abs(delta) < floor -> "below the floor"
                        else -> String.format(Locale.ROOT, "%.1f laps", lostPerStack)
                    }
                )
            )
        }
    } finally {
        stop(victims)
    }
}

/**
 * Part three: the obvious wrong way to do it, priced so nobody reaches for it.
 *
 * `Thread.getAllStackTraces` looks like the convenient call — one invocation, every thread — and it
 * is a *global* safepoint rather than a handshake with one thread, so every thread in the process
 * stops whether it was interesting or not.
 */
private fun allStacksCost(victimCount: Int, depth: Int, iters: Int, samples: Int) {
    val victims = startVictims(victimCount, depth, iters)
    try {
        Thread.sleep(300)
        repeat(50) { Thread.getAllStackTraces() }
        val taken = LongArray(samples)
        for (i in 0 until samples) {
            val t0 = System.nanoTime()
            val all = Thread.getAllStackTraces()
            taken[i] = System.nanoTime() - t0
            if (all.isEmpty()) error("no stacks")
        }
        val s = Spread(taken)
        println()
        println("FOR COMPARISON — Thread.getAllStackTraces (a global safepoint, not a handshake)")
        println("-".repeat(102))
        println(
            String.format(
                Locale.ROOT, "%d threads at depth %d: median %.1f us, mean %.1f us, p99 %.1f us, max %.1f us",
                victimCount, depth, s.median / 1e3, s.mean / 1e3, s.p99 / 1e3, s.max / 1e3
            )
        )
    } finally {
        stop(victims)
    }
}

/**
 * The whole measurement. Entered from `--stackcost`, before the bench touches anything.
 *
 * [iters] is chosen so one lap is a few microseconds: short enough that a lap count is a sensitive
 * throughput measure, long enough that the lap itself is not measuring the loop condition.
 */
fun runStackCost(
    victims: Int,
    depths: List<Int> = listOf(16, 64, 256, 1024),
    rates: List<Int> = listOf(10, 100, 1_000, 10_000, 100_000),
    depth: Int = 64,
    iters: Int = 200,
    rounds: Int = 9,
    roundMillis: Long = 500,
) {
    println("=".repeat(102))
    println("WHAT A CROSS-THREAD STACK COSTS")
    println("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, cores: ${Runtime.getRuntime().availableProcessors()}")
    println("victims: $victims, burn per lap: $iters iterations")
    println("=".repeat(102))
    warmUpBurn(300)
    callerCost(depths, victims, iters, samplesPerDepth = 2_000)
    victimCost(rates, victims, depth, iters, rounds, roundMillis)
    allStacksCost(victims, depth, iters, samples = 500)
    println()
    println("=".repeat(102))
}

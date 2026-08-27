package com.minogin.profiler

import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Per-thread CPU time, and what it is worth on this platform.
 *
 * Everything here is checked rather than assumed. `getThreadCpuTime` is optional in the JVM spec,
 * can be supported but switched off, and — on Windows, where it comes from `GetThreadTimes` — is
 * updated on scheduler ticks of the order of 15 ms. A number read at a resolution coarser than the
 * window it is measured over is worse than no number at all, so the resolution is *measured* at
 * startup and the duty cycle reports itself unavailable where the clock cannot support the window.
 */
object ThreadCpuClock {
    private val bean = ManagementFactory.getThreadMXBean()

    val supported: Boolean = bean.isThreadCpuTimeSupported

    /** Supported is not enough: it can be switched off, and it is then a -1 from every call. */
    val enabled: Boolean = supported && run {
        if (!bean.isThreadCpuTimeEnabled) {
            try {
                bean.isThreadCpuTimeEnabled = true
            } catch (_: Exception) {
                // Left to the check below. An unsupported operation here is simply "no".
            }
        }
        bean.isThreadCpuTimeEnabled
    }

    /**
     * CPU nanoseconds this thread has consumed, or -1 if the thread is gone or the clock is off.
     * Thread ids rather than references throughout, so nothing here can pin a dead thread alive.
     */
    fun cpuNanos(threadId: Long): Long = if (enabled) bean.getThreadCpuTime(threadId) else -1L

    /**
     * The smallest step this clock will actually take, measured.
     *
     * Spins on the calling thread — so CPU time advances at wall rate — and watches for the
     * counter to change. What comes back is the granularity we can *use*: on a platform where the
     * counter is fine-grained it is bounded below by the cost of the call itself, which is the
     * honest answer, since a resolution finer than one read is not a resolution anyone can exploit.
     *
     * Returns -1 if the clock did not move twice, which means we know nothing about it and have to
     * say so rather than guess.
     */
    fun probeResolutionNanos(): Long {
        if (!enabled) return -1L
        val deadline = System.nanoTime() + PROBE_LIMIT_NANOS
        var last = bean.currentThreadCpuTime
        var smallest = Long.MAX_VALUE
        var changes = 0
        while (changes < PROBE_CHANGES && System.nanoTime() < deadline) {
            val c = bean.currentThreadCpuTime
            if (c > last) {
                smallest = min(smallest, c - last)
                last = c
                changes++
            }
        }
        // One change tells us only that the clock moves at all: the first interval is truncated by
        // wherever the probe happened to start inside it, so it is not a granularity.
        return if (changes >= 2) smallest else -1L
    }

    private const val PROBE_LIMIT_NANOS = 300_000_000L
    private const val PROBE_CHANGES = 8
}

/**
 * How much of the threads' occupancy was CPU — the bound on every share at once.
 *
 * The sampler counts a slot as inside its operation whether the thread is running, blocked, parked
 * or descheduled. That is occupancy, not CPU. This measures the gap between the two in aggregate:
 * summed CPU delta over summed wall delta across the registered threads. It does not say *which*
 * operation was stalling — it says how much stalling there was in total, and that single number
 * bounds the error on every share, whatever caused it.
 *
 * Sampled at a low rate, never per tick. `getThreadCpuTime` is a native call per thread and its
 * underlying counter moves in scheduler ticks; asking it every millisecond would cost real time
 * and return quantisation noise.
 *
 * **The quantisation telescopes.** Each window's delta is the difference of two readings of one
 * cumulative counter, so a rounding error at a window boundary enters one window positive and the
 * next negative. Across the run they cancel, and the aggregate is accurate to about one granule
 * per thread over the whole span however ragged the per-window figures look.
 */
class DutyCycle(private val windowNanos: Long = DEFAULT_WINDOW_NANOS) {

    /** Previous reading per thread id. Rebuilt each window, so dead threads drop out by themselves. */
    private var prevCpu = HashMap<Long, Long>()
    private var prevWall = 0L
    private var nextAt = Long.MIN_VALUE

    /**
     * The same two sums as [cpuNanos] and [wallNanos], kept per thread rather than only added up.
     *
     * Adding up first is what made the bound vacuous. It is applied to shares that are over
     * *labelled* samples, while it was computed over *every registered thread* — so a pool thread
     * that sat outside every label all run pushed the bound up without appearing in a single share
     * it supposedly bounded. Measured in starvation mode: 18.83% duty and a formally unbounded
     * error, while the three threads the numbers actually came from were on CPU 96% of the time.
     *
     * Indexed by slot index, not by thread id, because that is what the sampler's own per-slot
     * counters use and the two have to be paired thread by thread. A thread that arrived past the
     * [MAX_SLOTS] ceiling has no index and is absent from both sides, which is consistent if not
     * complete — the report says how many those were. A slot handed on to a new thread after the
     * old one died accumulates both, which is wrong in the fourth decimal and not worth a
     * generation counter.
     */
    private val cpuByIndex = LongArray(MAX_SLOTS)
    private val wallByIndex = LongArray(MAX_SLOTS)

    private var windows = 0
    private var cpuNanos = 0L
    private var wallNanos = 0L
    private var minDuty = Double.NaN
    private var maxDuty = Double.NaN
    private var maxThreads = 0
    private var maxSampleNanos = 0L
    private var anomalies = 0

    /** Why there will be no number, or null if there will be one. */
    private val reason: String? = when {
        !ThreadCpuClock.supported -> "this JVM does not support per-thread CPU time"
        !ThreadCpuClock.enabled -> "per-thread CPU time is supported but disabled and would not enable"
        resolution < 0 -> "the per-thread CPU clock did not move during the probe"
        resolution * 10 > windowNanos -> String.format(
            Locale.ROOT, "clock resolution %.3f ms is too coarse for a %.3f s window",
            resolution / 1e6, windowNanos / 1e9
        )

        else -> null
    }

    private val available: Boolean get() = reason == null

    /** The shortest window worth taking: below this the clock's own steps dominate the answer. */
    private val floorNanos = max(windowNanos / 10, max(resolution, 0) * 10)

    /** Called every tick; takes a sample only once the window has elapsed. */
    fun tick(now: Long, slots: List<OpSlot>) {
        if (!available || now < nextAt) return
        sample(now, slots)
        nextAt = now + windowNanos
    }

    /**
     * A final sample, so the last partial window is not silently dropped. Refused when too little
     * time has passed to be worth reading, which is the rule the windows themselves follow.
     */
    fun finish(now: Long, slots: List<OpSlot>) {
        if (!available || prevWall == 0L || now - prevWall < floorNanos) return
        sample(now, slots)
    }

    private fun sample(now: Long, slots: List<OpSlot>) {
        // What this walk costs the sampler, measured rather than assumed: it runs on the sampling
        // thread, and a sampler that holds a 1 ms step is not something to spend without counting.
        val entered = System.nanoTime()
        val next = HashMap<Long, Long>(slots.size * 2)
        var cpu = 0L
        var counted = 0
        for (s in slots) {
            val c = ThreadCpuClock.cpuNanos(s.threadId)
            // -1 means the thread died between the walk and the read. It contributes to neither
            // side: a thread with no CPU baseline would otherwise add wall time and no CPU, which
            // is precisely the direction that would invent a stall that never happened.
            if (c < 0) continue
            next[s.threadId] = c
            val p = prevCpu[s.threadId] ?: continue
            val d = c - p
            if (d < 0) anomalies++ else {
                cpu += d
                counted++
                // Wall time per thread is the same interval for all of them; it is kept per slot
                // anyway so that the two arrays can be divided one by the other without a second
                // count of who had a baseline in which window.
                val idx = s.index
                if (idx >= 0) {
                    cpuByIndex[idx] += d
                    wallByIndex[idx] += now - prevWall
                }
            }
        }
        if (prevWall != 0L && counted > 0) {
            // Wall time counted once per thread that has a baseline, so numerator and denominator
            // cover exactly the same set of threads over exactly the same interval.
            val wall = (now - prevWall) * counted
            cpuNanos += cpu
            wallNanos += wall
            windows++
            val d = cpu.toDouble() / wall
            if (windows == 1) {
                minDuty = d
                maxDuty = d
            } else {
                minDuty = min(minDuty, d)
                maxDuty = max(maxDuty, d)
            }
            if (counted > maxThreads) maxThreads = counted
        }
        prevCpu = next
        prevWall = now
        val cost = System.nanoTime() - entered
        if (cost > maxSampleNanos) maxSampleNanos = cost
    }

    /**
     * How much of *labelled* occupancy was CPU — the number the report's shares are bounded by.
     *
     * A bound and not a measurement, and it has to be: a thread can be parked *inside* a label. The
     * bench's `--lock` mode puts the label outside the acquisition on purpose and 52,422 of
     * `lockedUpdate`'s 71,839 hits caught a thread that was not runnable, so "labelled therefore
     * running" is false and the cheap version of this fix is not available.
     *
     * What is available is an interval. For thread *i* with stall fraction `f` and labelled
     * fraction `l`, the stall that could possibly have been inside labelled work is
     * `min(f, l) × occupancy` — you cannot have more stall inside labels than you have stall, nor
     * more than you have labels. Summed, that is the worst case, and the idle threads that broke
     * the old number fall out on their own: `min(1.0, 0.0) = 0`.
     *
     * @param slotHits every sample taken at that slot, labelled or not.
     * @param slotLabelled those of them that were inside some operation.
     */
    private fun labelledDuty(slotHits: LongArray, slotLabelled: LongArray): Double {
        var labelled = 0.0
        var stall = 0.0
        for (i in 0 until MAX_SLOTS) {
            val hits = slotHits[i]
            val wall = wallByIndex[i]
            if (hits == 0L || wall <= 0L) continue
            val l = slotLabelled[i].toDouble() / hits
            // Clamped: the CPU counter moves in scheduler ticks, so a window can read a hair over
            // wall or a hair under zero, and a fraction outside 0..1 is quantisation and not news.
            val f = min(1.0, max(0.0, 1.0 - cpuByIndex[i].toDouble() / wall))
            labelled += slotLabelled[i]
            stall += min(f, l) * hits
        }
        return if (labelled <= 0.0) Double.NaN else (labelled - stall) / labelled
    }

    fun report(slotHits: LongArray, slotLabelled: LongArray): DutyReport = DutyReport(
        labelledDuty = if (available) labelledDuty(slotHits, slotLabelled) else Double.NaN,
        reason = reason,
        resolutionNanos = resolution,
        windowNanos = windowNanos,
        windows = windows,
        threads = maxThreads,
        cpuNanos = cpuNanos,
        wallNanos = wallNanos,
        minWindowDuty = minDuty,
        maxWindowDuty = maxDuty,
        anomalies = anomalies,
        maxSampleNanos = maxSampleNanos,
    )

    companion object {
        /**
         * Probed once, lazily, on whatever thread asks first. It costs up to 300 ms of spinning on
         * a platform with a coarse clock, so it must not sit on a path that runs per sampler.
         */
        val resolution: Long by lazy { ThreadCpuClock.probeResolutionNanos() }

        /**
         * One second. The window has to be far longer than the clock's granularity — 15.6 ms on
         * Windows — and far shorter than a run, and there is nothing here a finer window would
         * answer: this is an aggregate bound, not a time series.
         */
        const val DEFAULT_WINDOW_NANOS = 1_000_000_000L
    }
}

/** What the duty measurement came to, and the bound it puts on every share in the report. */
class DutyReport(
    /**
     * The fraction of *labelled* occupancy that was CPU, worst case. NaN when it could not be
     * computed, and the aggregate [duty] then stands in — which is the old, pessimistic behaviour
     * rather than a wrong one. See [DutyCycle.labelledDuty].
     */
    val labelledDuty: Double,
    val reason: String?,
    val resolutionNanos: Long,
    val windowNanos: Long,
    val windows: Int,
    val threads: Int,
    val cpuNanos: Long,
    val wallNanos: Long,
    val minWindowDuty: Double,
    val maxWindowDuty: Double,
    val anomalies: Int,
    /** The dearest of the walks, so what this costs the sampling thread is a number and not a hope. */
    val maxSampleNanos: Long,
) {
    /** True when there is a number to read: a measurement was attempted and it succeeded. */
    val available: Boolean get() = reason == null && windows > 0 && wallNanos > 0

    /** The fraction of occupancy that was CPU, over every registered thread. */
    val duty: Double get() = if (wallNanos > 0) cpuNanos.toDouble() / wallNanos else Double.NaN

    /**
     * The duty the shares are actually bounded by: [labelledDuty] where it exists, and the
     * aggregate where it does not.
     *
     * Everything that judges a *share* uses this. Everything that describes what the *machine* was
     * doing to every thread at once — [Report.machineFloor], [Report.offCpuSamples] — keeps using
     * [duty], because there the whole process is the subject and an idle thread belongs in it.
     */
    val shareDuty: Double get() = if (labelledDuty.isNaN()) duty else labelledDuty

    /**
     * The worst a share can be wrong by, in percentage points, if every stall there was landed on
     * one operation.
     *
     * With duty `d`, an operation reported at occupancy share `s` has a true CPU share somewhere in
     * `[(s - (1 - d)) / d, s / d]`, clamped to 0..1 — the low end if all the stalling was its own,
     * the high end if none of it was. The widest either end can sit from `s`, over all `s`, is
     * `(1 - d) / d`, and that is the number worth printing: it holds for every operation at once
     * and needs nothing to be known about where the stalling went.
     */
    val boundPp: Double get() = min(100.0, (1 - shareDuty) / shareDuty * 100)

    /** What a reported occupancy share could really be, as CPU. */
    fun boundsFor(share: Double): Pair<Double, Double> =
        max(0.0, (share - (1 - shareDuty)) / shareDuty) to min(1.0, share / shareDuty)

    fun lines(): List<String> {
        if (reason != null) return listOf(
            "CPU duty cycle: unavailable — $reason",
            "  nothing here bounds how much of the occupancy was not CPU",
        )
        if (!available) return listOf(
            String.format(
                Locale.ROOT,
                "CPU duty cycle: unavailable — no window completed (the run is shorter than %.3f s)",
                windowNanos / 1e9
            )
        )
        val out = ArrayList<String>()
        out += String.format(
            Locale.ROOT,
            "clock: getThreadCpuTime, resolution %.3f ms measured, window %.3f s, dearest walk %.1f us",
            resolutionNanos / 1e6, windowNanos / 1e9, maxSampleNanos / 1e3
        )
        out += String.format(
            Locale.ROOT,
            "threads were on CPU %.2f%% of sampled wall time  (%d windows, %d threads, per window %.2f%%..%.2f%%)",
            duty * 100, windows, threads, minWindowDuty * 100, maxWindowDuty * 100
        )
        // The bound is over labelled occupancy alone, which is what the shares are over. Printed
        // beside the aggregate rather than instead of it: the gap between the two lines is how much
        // of the process was idle, which is worth seeing and used to be silently charged to the
        // shares. Starvation mode is the extreme — 18.83% aggregate against 96% inside the labels.
        if (!labelledDuty.isNaN()) out += String.format(
            Locale.ROOT,
            "  inside labelled work it was %.2f%%, and that is what bounds the shares", labelledDuty * 100
        )
        // Not "is not CPU", which reads as an apology for having failed to measure CPU. Occupancy
        // counts a wait in full and that is the right behaviour for the latency question — the
        // reason the duty cycle exists is not that CPU was the goal, it is that a *sum* over
        // threads is only additive when it is CPU.
        out += String.format(
            Locale.ROOT, "at most %.2f pp of any share is a thread waiting rather than working", boundPp
        )
        out += when {
            shareDuty >= RANKING_SAFE -> "so the ranking is trustworthy"
            shareDuty >= OCCUPANCY_ONLY ->
                "so a share is still roughly time, but small gaps between operations are not resolved"

            else ->
                "so read a share as where threads SIT, not where cycles GO — and beware of adding two " +
                        "of them up, since one wait can be counted once per thread waiting on it"
        }
        if (anomalies > 0) out += "$anomalies thread readings went backwards and were dropped"
        return out
    }

    companion object {
        /**
         * Above this the non-CPU fraction cannot reorder the top of a report: the bound is then
         * under a percentage point, and real gaps between ranked operations are wider than that.
         * A judgement about how to read the number rather than a measured threshold — which is why
         * the bound itself is printed above this line, and this line only names it.
         */
        const val RANKING_SAFE = 0.95
        const val OCCUPANCY_ONLY = 0.75
    }
}

package com.minogin.profiler

import java.util.Locale
import java.util.concurrent.locks.LockSupport

/**
 * How the sampler waits for its next tick.
 *
 * Measured at a 1 ms request, 8 workers on 16 cores: PARK achieved 1.62 ms with 2,534 resyncs out
 * of 6,177 ticks, SLEEP 1.81 ms, SPIN 1.001 ms with a single resync. With all 16 cores loaded
 * PARK degraded to 13.5 ms — the sampler simply could not get scheduled.
 *
 * SPIN is the default and the others are kept as the evidence for that choice, worth re-running
 * on different hardware. PARK also bunches: after falling behind it fires several ticks in quick
 * succession, and bunched samples are correlated samples, which more samples will not fix.
 */
enum class WaitStrategy {
    /** LockSupport.parkNanos — free, but only as punctual as the scheduler is willing to be. */
    PARK,

    /** Thread.sleep(1) in a loop. Tested on the theory that a short sleep makes HotSpot ask
     *  Windows for a finer timer; it made no difference. */
    SLEEP,

    /** Busy-spin on nanoTime — microsecond accuracy, at the price of a whole core. */
    SPIN,
}

/**
 * The sampling thread. Wakes on a fixed step, reads every slot, increments the counter for
 * whatever id it found. Reads other threads' slots without any synchronisation — deliberately;
 * whether that smears the picture is a phase 3 question, answered by whether the numbers agree.
 *
 * Counters are plain longs: this thread is their only writer.
 */
class Sampler(
    private val stepNanos: Long,
    private val wait: WaitStrategy = WaitStrategy.SPIN,
    private val jitterFraction: Double = 0.25,
    dutyWindowNanos: Long = DutyCycle.DEFAULT_WINDOW_NANOS,
    /**
     * Whether a leaked label stops the session. See the severity ladder in plan.md.
     *
     * A label left open at a point the caller declared quiescent does not make a number imprecise,
     * it makes it *someone else's*: every sample after the leak is billed to the leaked operation,
     * and the result looks exactly like a finding. There is no rerun in which that becomes valid,
     * so under strict the session stops at the first one rather than spending twenty minutes
     * producing an answer that was never going to be right.
     *
     * Off for code you do not own and cannot fix — the leak is then still counted and reported.
     */
    val strict: Boolean = true,
    /**
     * Whether each hit also records whether the owning thread was runnable.
     *
     * A switch and not a constant, for the same reason `--labels` and `--sampler` are separate:
     * the cost of this has to be measurable against its own absence, and a thing that is always on
     * can only be priced by argument.
     */
    val sampleState: Boolean = true,
) : Thread("sampler") {

    /**
     * Why the session stopped early, or null if it did not. Set the moment the leak is noticed, on
     * the thread that noticed it, so a run that cannot be right ends there rather than at the end.
     */
    @Volatile
    var failure: String? = null
        private set

    /**
     * The duty cycle rides on this thread's tick loop, but at its own far lower rate — it costs one
     * native call per thread and it is measuring something that moves in scheduler ticks. Carried
     * here rather than on a thread of its own because this thread already walks the slot list, and
     * because the two must cover exactly the same span if the bound is to apply to these shares.
     */
    private val duty = DutyCycle(dutyWindowNanos)

    // Ticking on an exact beat can lock onto a workload that has a rhythm of its own near the
    // same period — the wagon-wheel effect, where more samples do not help because every sample
    // catches the same phase. Scattering the interval makes that impossible. The jitter is
    // symmetric, so the mean interval is still the requested step. Seeded, so runs repeat.
    private val rnd = java.util.Random(20260820L)

    private fun nextInterval(): Long =
        if (jitterFraction <= 0.0) stepNanos
        else stepNanos + ((rnd.nextDouble() * 2 - 1) * jitterFraction * stepNanos).toLong()

    /** Hits per operation; the last entry counts slots that held no operation. */
    val counters = LongArray(MAX_OPERATIONS + 1)

    /**
     * Of those hits, the ones that caught an execution which had already been running at the
     * previous tick — see [stuckHits] and the arrays below.
     */
    val stuckHits = LongArray(MAX_OPERATIONS)

    /** How many distinct executions lasted across a tick: one per unbroken run of stuck samples. */
    val stuckInstances = LongArray(MAX_OPERATIONS)

    /**
     * Of those hits, the ones where the owning thread was **not** runnable — parked, blocked, or
     * waiting. Zero everywhere when [sampleState] is off.
     *
     * `RUNNABLE` is not the same as *on a core*: it also covers a thread the scheduler has
     * preempted — 14–18% of wall time on this machine even on a bench that never blocks — and a
     * thread sitting in a blocking socket read, which the JVM cannot see into. So this counts
     * waiting that some *other thread* caused, which is the kind that does not add up across
     * threads. Separating on-a-core from preempted needs the per-thread duty cycle.
     */
    val waitingHits = LongArray(MAX_OPERATIONS)

    /**
     * Hits that were both stuck and waiting — a long execution caught with its thread parked.
     *
     * The pairing is the point. An operation that waits *somewhere* is not the same as one whose
     * *long* executions are where the waiting happens, and it is the second that decides whether a
     * flagged operation is honest-but-coarse or is occupancy masquerading as CPU. Before this the
     * verdict had to charge the whole run's off-CPU budget against every operation separately and
     * could only ever say "cannot say which".
     */
    val stuckWaitingHits = LongArray(MAX_OPERATIONS)

    /**
     * Of the samples that caught a thread inside no label at all, the ones where it was not
     * runnable.
     *
     * The unlabelled column is the one place a reader has no other instrument for. A large
     * unlabelled share reads as "the labels miss most of the run", which is alarming, and this says
     * whether that time was work nobody labelled or a thread doing nothing at all — two findings
     * with nothing in common.
     */
    var idleWaitingHits: Long = 0; private set

    /**
     * Ticks at which at least one thread was inside the operation — its wall-clock footprint,
     * counted once however many threads were in it.
     *
     * This is the divisor that turns summed occupancy back into elapsed time. Occupancy alone
     * cannot distinguish a hundred threads waiting one second from one thread waiting a hundred
     * seconds; both are a hundred thread-seconds and their real costs are a hundredfold apart.
     * `activeTicks × step` is the first, `hits / activeTicks` is the mean concurrency that
     * separates them, and on a contended lock that is the difference between *break up the convoy*
     * and *design the contention out*. See profiler.md, "Turning occupancy back into wall time".
     */
    val activeTicks = LongArray(MAX_OPERATIONS)

    /**
     * The tick at which each operation was last seen, so [activeTicks] counts ticks and not slots.
     * A stamp rather than a flag array cleared each tick: one write when an operation first appears
     * in a tick, and nothing at all for the operations that did not.
     */
    private val seenAt = LongArray(MAX_OPERATIONS) { -1L }

    /**
     * What the last tick found in each slot, kept on this side of the fence.
     *
     * The test is two words against two words. If a slot holds the same operation as last tick
     * *and* that thread's entry counter for it has not moved, then nobody entered the operation in
     * between, so this is not a new execution — it is the same one, still running, and it has now
     * lasted at least a whole tick. For an operation whose label claims tens of nanoseconds that is
     * four orders of magnitude out.
     *
     * A counter that went *backwards* means the index was recycled to a new thread, which is
     * treated as a fresh execution — the one case where the answer would otherwise be nonsense.
     */
    /**
     * Per thread, every sample taken at its slot and those of them that were inside some operation.
     *
     * Paired with the duty cycle's per-thread CPU fractions to bound the error on the shares — see
     * [labelledDuty]. Indexed by slot index, which is why that index exists: it is
     * immutable for the life of the slot and it is the only thing the sampler and the duty walk
     * can both use to mean *the same thread*.
     */
    val slotHits = LongArray(MAX_SLOTS)
    val slotLabelled = LongArray(MAX_SLOTS)

    /**
     * Of those, the ones that caught the thread not runnable — all of them, and the labelled ones.
     *
     * These are what stop the bound being vacuous on a thread pool. A pool thread parks between
     * tasks and works inside a label, so its stall fraction and its labelled fraction are both
     * large and `min` of the two assumes the parking happened inside the label. On Lucene that
     * turned a true ~98% into a printed 47%. The state read already says where the waiting was;
     * these two counters are the only thing needed to use it. Zero when [sampleState] is off, and
     * the bound falls back to the assumption.
     */
    val slotWaiting = LongArray(MAX_SLOTS)
    val slotLabelledWaiting = LongArray(MAX_SLOTS)

    private val prevOp = IntArray(MAX_SLOTS) { NO_OP }
    private val prevCount = LongArray(MAX_SLOTS)
    private val prevStuck = BooleanArray(MAX_SLOTS)

    var ticks: Long = 0; private set
    var lagged: Long = 0; private set
    var minStep: Long = Long.MAX_VALUE; private set
    var maxStep: Long = 0; private set
    var span: Long = 0; private set

    /** Most slots seen at any one tick. Read after the fact, when the threads may already be gone. */
    var maxSlots: Int = 0; private set

    /**
     * Time spent in the slot walk itself, and how many slot visits that covers, so the walk can be
     * priced per slot with state sampling on and off.
     *
     * Two `nanoTime` calls per tick on a thread that has a core — about 40 ns against a millisecond,
     * and it is the only way this feature's cost can be a number rather than an argument.
     */
    var walkNanos: Long = 0; private set
    var walkVisits: Long = 0; private set

    @Volatile private var running = true

    /**
     * Call counts as they stood when this session began.
     *
     * Every number derived from calls — the implied duration, the floor check — divides *this
     * session's* hits by them, so they have to be this session's calls. The registry's totals are
     * for the life of the process and include calls made by threads that died before sampling
     * started, whose counts are folded into the retired totals and stay there.
     *
     * Found by the floor check accusing a 20 ns operation of being under 7.9 ns — an upper bound
     * below the truth, which is arithmetically impossible and so could only be a mismatch of what
     * the numerator and the denominator were counting. The bench's warm-up is a separate set of
     * worker threads that exit before the measured run, and it was inflating every call count by
     * about a tenth.
     */
    private val callsAtStart = LongArray(MAX_OPERATIONS)

    /** Calls made since this session began. See [callsAtStart]. */
    fun sessionCalls(id: Int): Long = (Profiler.callsOf(id) - callsAtStart[id]).coerceAtLeast(0)

    init {
        isDaemon = true
        priority = MAX_PRIORITY
    }

    override fun run() {
        val slots = Profiler.slots()
        // Before the first tick, so that no call is counted whose sample could not have been taken.
        for (id in 0 until MAX_OPERATIONS) callsAtStart[id] = Profiler.callsOf(id)
        var next = System.nanoTime() + nextInterval()
        var prev = 0L
        var first = 0L

        while (running) {
            waitUntil(next)
            if (!running) break
            val now = System.nanoTime()

            val walkStart = System.nanoTime()
            for (s in slots) {
                val c = s.getOpaque()
                counters[if (c < 0) NO_OP_INDEX else c]++

                // Carried down to the long-execution test below, so that a hit which is both long
                // and waiting is counted as such. Knowing an operation waits somewhere is not the
                // same as knowing its *long* executions are where the waiting is.
                // Asked for every slot, labelled or not. What a thread does *outside* every label is
                // the larger half of some runs — 85.5% of thread-time on the Netty trial — and
                // "waiting or working" is exactly as worth knowing there. A field read on the Thread
                // object: no handshake, no safepoint. The weak reference is null only if the thread
                // died and the slot outlived it, in which case there is nothing to ask.
                var waiting = false
                if (sampleState) {
                    val t = s.thread.get()
                    if (t != null && t.state != State.RUNNABLE) waiting = true
                }
                if (c >= 0) {
                    // Once per operation per tick, not once per slot: this counts the operation's
                    // wall-clock footprint, which is the same whether one thread was inside it or
                    // sixteen.
                    if (seenAt[c] != ticks) {
                        seenAt[c] = ticks
                        activeTicks[c]++
                    }
                    if (waiting) waitingHits[c]++
                } else if (waiting) {
                    idleWaitingHits++
                }

                val idx = s.index
                if (idx < 0) continue           // past the ceiling: sampled, but not tracked
                // The other half of the per-thread bound. The duty walk knows how much of thread
                // i's time was CPU; this is how much of it was inside a label, and the bound is
                // what the two agree could have been stalling *there*. Two increments on the
                // sampling thread, nothing on the hot path.
                slotHits[idx]++
                if (c >= 0) slotLabelled[idx]++
                if (waiting) {
                    slotWaiting[idx]++
                    if (c >= 0) slotLabelledWaiting[idx]++
                }
                if (c < 0) {
                    prevOp[idx] = NO_OP
                    prevStuck[idx] = false
                    continue
                }
                // The second word. One extra cache line per slot per tick, on a thread that has a
                // core to itself, and nothing at all on the hot path — the counter is already
                // maintained by the hook for the calls column.
                val n = s.countOpaque(c)
                if (prevOp[idx] == c && prevCount[idx] == n) {
                    stuckHits[c]++
                    if (waiting) stuckWaitingHits[c]++
                    // Only the first tick of a run counts as an instance, so a single execution
                    // spanning ten ticks is one long execution and not ten.
                    if (!prevStuck[idx]) {
                        stuckInstances[c]++
                        prevStuck[idx] = true
                    }
                } else {
                    prevStuck[idx] = false
                }
                prevOp[idx] = c
                prevCount[idx] = n
            }
            walkNanos += System.nanoTime() - walkStart
            walkVisits += slots.size
            if (slots.size > maxSlots) maxSlots = slots.size
            ticks++
            // Self-throttling: these return immediately on all but one tick in a thousand.
            duty.tick(now, slots)

            if (prev == 0L) {
                first = now
            } else {
                val d = now - prev
                if (d < minStep) minStep = d
                if (d > maxStep) maxStep = d
            }
            prev = now
            span = now - first

            next += nextInterval()
            // Fell behind by more than a whole step: resync rather than fire a burst of catch-up
            // ticks, which would bunch samples and distort the shares.
            if (next <= now) {
                next = now + nextInterval()
                lagged++
            }
        }
        // The tail of the run belongs to the measurement as much as the middle does. Taken here
        // rather than in shutdown() so that it happens on this thread, before the slots go.
        duty.finish(System.nanoTime(), slots)
    }

    /**
     * Stops the session and records why. Called from whatever noticed, on whatever thread.
     *
     * The one condition that reaches here is a leaked label — see [Profiler.expectBalanced]. A
     * below-floor label used to as well, and stopped two correct runs in two consecutive trials
     * before it was demoted to the warning it always should have been; the reasoning is in
     * plan.md § 1a and the list it now produces is [Report.tooSmall].
     *
     * Stops sampling rather than the application: this is somebody else's process.
     */
    fun fail(reason: String) {
        if (failure != null) return
        failure = reason
        running = false
    }

    /** How much of the occupancy this session sampled was CPU. See [DutyCycle]. */
    fun duty(): DutyReport =
        duty.report(slotHits, slotLabelled, slotWaiting, slotLabelledWaiting, sampleState)

    private fun waitUntil(deadline: Long) {
        when (wait) {
            WaitStrategy.PARK -> {
                var left = deadline - System.nanoTime()
                // parkNanos may return early; loop until the deadline has actually passed.
                while (left > 0 && running) {
                    LockSupport.parkNanos(left)
                    left = deadline - System.nanoTime()
                }
            }

            WaitStrategy.SLEEP -> {
                while (running && deadline - System.nanoTime() > 0) {
                    try {
                        sleep(1)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }

            WaitStrategy.SPIN -> {
                while (running && deadline - System.nanoTime() > 0) onSpinWait()
            }
        }
    }

    fun shutdown() {
        running = false
        LockSupport.unpark(this)
        join()
    }

    /** Total samples taken: one per slot per tick. */
    fun totalSamples(): Long = counters.sum()

}

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
internal class Sampler(
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

    /** When to next look for slots whose thread died without releasing. See Profiler.reclaimDeadSlots. */
    private var nextReclaim = Long.MIN_VALUE

    // Ticking on an exact beat can lock onto a workload that has a rhythm of its own near the
    // same period — the wagon-wheel effect, where more samples do not help because every sample
    // catches the same phase. Scattering the interval makes that impossible. The jitter is
    // symmetric, so the mean interval is still the requested step. Seeded, so runs repeat.
    private val rnd = java.util.Random(20260820L)

    private fun nextInterval(): Long =
        if (jitterFraction <= 0.0) stepNanos
        else stepNanos + ((rnd.nextDouble() * 2 - 1) * jitterFraction * stepNanos).toLong()

    /** Hits per operation; the last entry counts slots that held no operation. */
    internal val counters = LongArray(MAX_OPERATIONS + 1)

    /**
     * Of those hits, the ones that caught an execution which had already been running at the
     * previous tick — see [stuckHits] and the arrays below.
     */
    internal val stuckHits = LongArray(MAX_OPERATIONS)

    /** How many distinct executions lasted across a tick: one per unbroken run of stuck samples. */
    internal val stuckInstances = LongArray(MAX_OPERATIONS)

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
    internal val waitingHits = LongArray(MAX_OPERATIONS)

    /**
     * Hits that were both stuck and waiting — a long execution caught with its thread parked.
     *
     * The pairing is the point. An operation that waits *somewhere* is not the same as one whose
     * *long* executions are where the waiting happens, and it is the second that decides whether a
     * flagged operation is honest-but-coarse or is occupancy masquerading as CPU. Before this the
     * verdict had to charge the whole run's off-CPU budget against every operation separately and
     * could only ever say "cannot say which".
     */
    internal val stuckWaitingHits = LongArray(MAX_OPERATIONS)

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
    internal val activeTicks = LongArray(MAX_OPERATIONS)

    /**
     * The tick at which each operation was last seen, so [activeTicks] counts ticks and not slots.
     * A stamp rather than a flag array cleared each tick: one write when an operation first appears
     * in a tick, and nothing at all for the operations that did not.
     */
    private val seenAt = LongArray(MAX_OPERATIONS) { -1L }

    /** Samples that caught a thread inside an execution of this coarse type, innermost only. */
    internal val coarseHits = LongArray(MAX_COARSE_TYPES)

    /** Of those, the ones whose thread was runnable. `hits - running` is the waiting, sampled. */
    internal val coarseRunningHits = LongArray(MAX_COARSE_TYPES)

    /**
     * Samples caught anywhere **under** this coarse type, itself included.
     *
     * The self/inclusive distinction the fine tier never needed: a fine operation is atomic, so its
     * self time is its inclusive time, while a coarse operation exists precisely to contain others.
     * Counted by walking the parent chain, deduplicated by type so that a type nested inside itself
     * is credited once for a sample rather than once per level.
     */
    internal val coarseInclusiveHits = LongArray(MAX_COARSE_TYPES)

    /** Of [coarseInclusiveHits], the ones whose thread was runnable. `span - this` is the waiting. */
    internal val coarseRunningInclusiveHits = LongArray(MAX_COARSE_TYPES)

    /**
     * Distinct executions of this type occupied at each tick, summed over ticks.
     *
     * The denominator of the second factor. `inclusiveHits / instanceTicks` is threads per
     * execution — **parallelism** — and `inclusiveHits / activeTicks` is threads per tick, so the
     * two divided give executions in flight. Counted by stamping the context object rather than a
     * table, because the thing being counted is an instance and only the instance can identify
     * itself; three threads inside one execution stamp it once.
     */
    internal val coarseInstanceTicks = LongArray(MAX_COARSE_TYPES)

    /** Ticks at which at least one thread was anywhere under this coarse type. */
    internal val coarseActiveTicks = LongArray(MAX_COARSE_TYPES)

    /** As [seenAt], for coarse types, so [coarseActiveTicks] counts ticks and not slots. */
    private val coarseSeenAt = LongArray(MAX_COARSE_TYPES) { -1L }

    /**
     * The cross-tabulation: `[coarse type][fine operation]`, the last column being *no fine label*.
     *
     * Every sample records the pair, which is the one thing neither tier can produce alone — fine
     * sampling says what is hot but not what it was for, coarse spans say where the time went but
     * not why. 131 KB, flat and indexed rather than nested so the tick loop does one bounds check.
     */
    internal val pairHits = LongArray(MAX_COARSE_TYPES * (MAX_OPERATIONS + 1))

    /** Types already credited while walking one parent chain. Reused; never escapes the tick loop. */
    private val chain = IntArray(MAX_COARSE_DEPTH)

    /**
     * Samples that caught a thread inside a fine label and inside **no** coarse context.
     *
     * The one detector for the failure mode neither the floor check nor the balance check can see:
     * **work escaping its context onto another thread.** A context lives on the thread that made it,
     * so when an operation hands work to a pool, the pool threads run the callers' fine labels with
     * no context at all — and the caller's span reports that time as *waiting*. Silent, and in the
     * contaminating direction, which is the class of error this project spends its effort on.
     *
     * Measured on Lucene, whose search fans across a pool: at one thread the search's own busy time
     * is 14.87 ms of a 14.88 ms span, and at eight it is 3.10 ms of 4.10 ms — 11.8 ms of work gone
     * somewhere the context could not follow. This counter is where it went.
     *
     * **A measurement and not an accusation.** It also reads high for a perfectly good reason: you
     * bracketed part of your program coarsely and not the rest. The report states the number and
     * names both readings rather than picking one, because it cannot tell them apart and pretending
     * otherwise is how a tool spends its credibility.
     *
     * One branch, in a loop that has both values in registers already.
     */
    internal var labelledOutsideCoarse: Long = 0; private set

    /**
     * Per thread, every sample taken at its slot and those of them that were inside some operation.
     *
     * Paired with the duty cycle's per-thread CPU fractions to bound the error on the shares — see
     * [labelledDuty]. Indexed by slot index, which is why that index exists: it is
     * immutable for the life of the slot and it is the only thing the sampler and the duty walk
     * can both use to mean *the same thread*.
     */
    internal val slotHits = LongArray(MAX_SLOTS)
    internal val slotLabelled = LongArray(MAX_SLOTS)

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
    internal val slotWaiting = LongArray(MAX_SLOTS)
    internal val slotLabelledWaiting = LongArray(MAX_SLOTS)

    /**
     * What the last tick found in each slot, kept on this side of the fence.
     *
     * The test is two words against two words. If a slot holds the same operation as last tick
     * *and* that thread's entry counter for it has not moved, then nobody entered the operation in
     * between, so this is not a new execution — it is the same one, still running, and it has now
     * lasted at least a whole tick. For an operation whose label claims tens of nanoseconds that is
     * four orders of magnitude out.
     */
    private val prevOp = IntArray(MAX_SLOTS) { NO_OP }
    private val prevCount = LongArray(MAX_SLOTS)
    private val prevStuck = BooleanArray(MAX_SLOTS)

    /**
     * Which thread each index belonged to last tick, so a recycled one starts clean.
     *
     * An index outlives the thread that held it — `release()` hands it to the next arrival — and
     * the three arrays above would otherwise carry a dead thread's last tick into a live thread's
     * first. The documentation used to claim a counter going backwards caught that. It does not:
     * the test is inequality, and the new thread's counter for the old operation only has to *pass
     * through* the old value at a tick boundary to be read as one execution still running. Rare,
     * and worst on exactly the operations where a single false long execution is most visible —
     * the ones entered a handful of times per pass.
     *
     * One long compare per slot per tick, on a thread that has a core to itself.
     */
    private val prevThreadId = LongArray(MAX_SLOTS) { -1L }

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
    internal fun sessionCalls(id: Int): Long = (Profiler.callsOf(id) - callsAtStart[id]).coerceAtLeast(0)

    init {
        isDaemon = true
        priority = MAX_PRIORITY
    }

    override fun run() {
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
            var live = 0
            // A plain indexed loop and not forEachSlot: this body is large, and inlining it into an
            // already-large run() costs more than the lambda saves - measured at 2.7x the walk.
            val ceiling = Profiler.slotCeiling()
            for (i in 0 until ceiling) {
                val s = Profiler.slotAt(i) ?: continue
                live++
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

                // The coarse half of the same photograph. One reference read, and it costs nothing
                // at all when nobody has placed a coarse label: the field is null and the branch is
                // perfectly predicted.
                val ctx = s.contextOpaque()
                if (ctx != null) {
                    val t = ctx.type
                    coarseHits[t]++
                    if (!waiting) coarseRunningHits[t]++
                    val fine = if (c < 0) NO_OP_INDEX else c
                    // Everything else is credited up the chain, each type at most once per sample.
                    // Inclusive and not self, because the question a coarse label is asked is *what
                    // was this request made of* — and an answer scoped to the operation's own frame
                    // would leave out everything it delegated, which is most of it.
                    var p: CoarseContext? = ctx
                    var d = 0
                    var n = 0
                    while (p != null && d < MAX_COARSE_DEPTH) {
                        val pt = p.type
                        var seen = false
                        var j = 0
                        while (j < n) {
                            if (chain[j] == pt) { seen = true; break }
                            j++
                        }
                        if (!seen) {
                            chain[n++] = pt
                            coarseInclusiveHits[pt]++
                            if (!waiting) coarseRunningInclusiveHits[pt]++
                            // The pair, and the whole reason both halves are read in one visit.
                            pairHits[pt * (MAX_OPERATIONS + 1) + fine]++
                            if (coarseSeenAt[pt] != ticks) {
                                coarseSeenAt[pt] = ticks
                                coarseActiveTicks[pt]++
                            }
                            // Stamped on the instance, so an execution occupied by four threads at
                            // one tick counts once here and four times in the hits above. That
                            // ratio is the parallelism.
                            if (p.tickStamp != ticks) {
                                p.tickStamp = ticks
                                coarseInstanceTicks[pt]++
                            }
                        }
                        p = p.parent
                        d++
                    }
                } else if (c >= 0) {
                    // Labelled work, under no coarse span at all. See labelledOutsideCoarse.
                    labelledOutsideCoarse++
                }

                val idx = s.index
                if (idx < 0) continue           // cannot happen: only indexed slots are in the walk
                // A recycled index starts clean. See [prevThreadId].
                if (prevThreadId[idx] != s.threadId) {
                    prevThreadId[idx] = s.threadId
                    prevOp[idx] = NO_OP
                    prevCount[idx] = 0
                    prevStuck[idx] = false
                }
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
            walkVisits += live
            if (live > maxSlots) maxSlots = live
            ticks++
            // Self-throttling: these return immediately on all but one tick in a thousand.
            duty.tick(now)
            if (now >= nextReclaim) {
                Profiler.reclaimDeadSlots()
                nextReclaim = now + RECLAIM_NANOS
            }

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
        duty.finish(System.nanoTime())
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
    internal fun fail(reason: String) {
        if (failure != null) return
        failure = reason
        running = false
    }

    /** How much of the occupancy this session sampled was CPU. See [DutyCycle]. */
    internal fun duty(): DutyReport =
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

    /**
     * Clears the span statistics **on the caller's thread**, then starts sampling.
     *
     * Synchronously, and that is the whole point. Doing it inside `run()` puts it on the sampling
     * thread, which is racing the caller: the caller goes on to release the workers while this
     * thread is still starting, so the first few milliseconds of spans get recorded and then wiped.
     * Measured, that lost 0.21% of every coarse count — small, systematic, and in the direction that
     * makes an execution count look *lower* than the call graph says, which reads exactly like a
     * dropped span.
     *
     * The fine tier's `callsAtStart` snapshot is left where it is: it has the same shape of race and
     * it subtracts rather than resets, so a call counted on both sides of the boundary cancels.
     */
    override fun start() {
        Profiler.resetCoarse()
        super.start()
    }

    internal fun shutdown() {
        running = false
        LockSupport.unpark(this)
        join()
    }

    /** Total samples taken: one per slot per tick. */
    internal fun totalSamples(): Long = counters.sum()

    private companion object {
        /** Once a second: this walks the whole ceiling, so it is not a per-tick cost. */
        const val RECLAIM_NANOS = 1_000_000_000L
    }

}

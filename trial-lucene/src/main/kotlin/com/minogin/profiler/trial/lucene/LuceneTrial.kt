package com.minogin.profiler.trial.lucene

import com.minogin.profiler.NO_OP
import com.minogin.profiler.propagating
import com.minogin.profiler.Profiler
import com.minogin.profiler.op
import com.minogin.profiler.getOpaque
import com.minogin.profiler.trial.analyzeJfr
import com.minogin.profiler.trial.recordExecutionSamples
import org.apache.lucene.document.IntPoint
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.TopDocs
import org.apache.lucene.store.FSDirectory
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The second trial: the fine tier pointed at Apache Lucene.
 *
 * Why this candidate, in one sentence: **several clauses of one query are the same class**. Calcite's
 * identity gap was many classes hidden behind one inherited method; Lucene's is many *instances*
 * hidden behind one class, which is the commoner shape and the one a stack profiler cannot answer
 * even in principle - there is no frame that differs between two `TermQuery` clauses on two
 * different terms.
 *
 * And it is concurrent, which nothing on foreign code has been so far: the duty cycle, the bound it
 * puts on every share, and the sampler's walk over live thread slots have only ever met the bench.
 */

/** The clauses, and the reason each one is here. */
class TrialQuery(corpus: Corpus, mode: Placement) {

    /**
     * Term ranks, chosen so the same class covers three orders of magnitude of cost.
     *
     * These four clauses are `TermQuery` on `body`, differing only in which term. To the JVM they
     * are one `TermScorer` running one `ImpactsDISI.advance`; to anybody tuning the query they are
     * four completely different propositions. That gap is the entire trial.
     */
    private val ranks = listOf(2, 20, 400, 8_000)

    val clauses: ClauseSet

    val query: Query

    init {
        val declared = ArrayList<Clause>()

        fun wrap(name: String, q: Query): Query {
            if (mode == Placement.NONE) return q
            val clause = Clause(name, mode)
            declared += clause
            return LabelledQuery(q, clause)
        }

        val b = BooleanQuery.Builder()
        for (rank in ranks) {
            val term = corpus.term(rank)
            b.add(wrap("term#$rank", TermQuery(Term("body", term))), BooleanClause.Occur.SHOULD)
        }
        // A phrase over two common terms. Its cost lives somewhere no term clause's does: in the
        // two-phase confirmation, where positions are read and compared. It is also the clause the
        // stack CAN see, since PhraseScorer has frames of its own - a deliberate control, so the
        // trial can be seen to agree with a flame graph where a flame graph works.
        b.add(
            wrap(
                "phrase",
                PhraseQuery.Builder()
                    .add(Term("body", corpus.term(3)), 0)
                    .add(Term("body", corpus.term(5)), 1)
                    .build()
            ),
            BooleanClause.Occur.SHOULD
        )
        // A prefix, which rewrites into a union of a hundred terms. One clause by the user's
        // reckoning, a hundred posting lists by the index's - and the wrapper has to survive the
        // rewrite to say so.
        b.add(wrap("prefix", PrefixQuery(Term("body", "w000"))), BooleanClause.Occur.SHOULD)
        // A low-cardinality string field: an enormous posting list, trivial work per document. The
        // opposite cost shape to the rare term, and the pair is what makes the counts column earn
        // its keep.
        b.add(wrap("cat", TermQuery(Term("cat", "c7"))), BooleanClause.Occur.SHOULD)
        // Not a postings scan at all: a BKD tree walk that materialises a bitset up front. Its cost
        // is nearly all in one call, which is a third cost shape again.
        b.add(wrap("point", IntPoint.newRangeQuery("price", 2_000, 2_600)), BooleanClause.Occur.SHOULD)

        query = b.build()
        clauses = ClauseSet(declared)
    }
}

/**
 * One segment per slice, stated rather than inherited.
 *
 * Lucene's default slicing groups small segments together, and the trial's whole interest in this
 * candidate is that it is the first concurrent workload on foreign code. How many threads actually
 * run is therefore an experimental parameter and not something to discover from a default.
 */
private class SlicedSearcher(reader: IndexReader, executor: Executor) : IndexSearcher(reader, executor) {
    override fun slices(leaves: List<LeafReaderContext>): Array<LeafSlice> = slices(leaves, 1, 1, false)
}

/** Everything a run needs: an open index, a pool, a searcher, and a query built for one placement. */
class Bed(
    val corpus: Corpus,
    val threads: Int,
    val mode: Placement,
    val topK: Int = 100,
    /**
     * Whether the search pool carries the coarse execution the calling thread was inside.
     *
     * A switch and not a fact, so the before and the after are one binary. Without it a search
     * fanned across eight threads put 88.5% of its labelled thread-time outside every span and read
     * 24.5% waiting while those threads worked; the point of the phase is that the same run with
     * this on does not. Comparing against a build that no longer exists would prove nothing.
     */
    val propagate: Boolean = true,
) : AutoCloseable {

    private val directory = FSDirectory.open(corpus.dir)
    val reader: DirectoryReader = DirectoryReader.open(directory)

    /**
     * The search threads, kept so their stacks can be taken.
     *
     * Only used by `--stacks`, which prices the idea of walking a stack on the ticks that found no
     * label — the one thing this design cannot otherwise do is say *where* its unlabelled time went.
     * The bench measures that in the abstract; this measures it on threads doing real work with
     * memory-mapped I/O in them, which is a different proposition and might be a cheaper one.
     */
    val workers = java.util.concurrent.CopyOnWriteArrayList<Thread>()

    private val pool: ExecutorService? =
        if (threads > 1) Executors.newFixedThreadPool(threads) { r ->
            Thread(r).also { it.isDaemon = true; workers += it }
        } else null

    /**
     * What the searcher is handed: the pool, wrapped so a slice runs inside the search that forked
     * it. The whole of the change, and it is one call — which is the claim the library makes and
     * this is where it is tested on code we did not write.
     *
     * The wrapper delegates to the pool underneath, so the thread factory above still runs and
     * [workers] is still populated. `--stacks` depends on that.
     */
    private val searchPool: ExecutorService? =
        if (pool != null && propagate) pool.propagating() else pool

    val searcher: IndexSearcher =
        if (searchPool != null) SlicedSearcher(reader, searchPool) else IndexSearcher(reader)

    val trialQuery = TrialQuery(corpus, mode)

    init {
        // The query cache would turn the second search of a clause into a bitset lookup and the
        // label would then honestly report almost no time in it. Off, so that every search does the
        // work every other search did.
        searcher.setQueryCache(null)
    }

    fun searchOnce(): TopDocs = searcher.search(trialQuery.query, topK)

    override fun close() {
        pool?.shutdown()
        reader.close()
        directory.close()
    }
}

private fun millis(nanos: Long) = nanos / 1e6

/** Holds a reference so nothing about the result can be optimised away. */
object Sink {
    @Volatile
    var last: Any? = null
}

private fun repeatSearch(bed: Bed, times: Int): LongArray {
    val out = LongArray(times)
    for (i in 0 until times) {
        val t0 = System.nanoTime()
        val hits = bed.searchOnce()
        out[i] = System.nanoTime() - t0
        Sink.last = hits
    }
    return out
}

/**
 * Step one of the recipe: **qualify the candidate before instrumenting it.**
 *
 * A workload nobody would want profiled proves nothing. What has to be true here is that the search
 * costs real time, that the clause costs are wildly unequal, and that the inequality is invisible
 * from outside. The first two are printed; the third is what the JFR run is for.
 */
private fun qualify(corpus: Corpus, threads: Int) {
    println()
    println("term frequencies actually in the index")
    println("-".repeat(52))
    println(String.format(Locale.ROOT, "%-12s %14s %12s", "term", "docFreq", "share"))
    for ((rank, df) in corpus.frequencies(listOf(2, 3, 5, 20, 400, 8_000))) {
        println(
            String.format(
                Locale.ROOT, "%-12s %14d %11.3f%%",
                corpus.term(rank), df, df * 100.0 / corpus.docCount
            )
        )
    }

    Bed(corpus, threads, Placement.NONE).use { bed ->
        println()
        println("segments: ${bed.reader.leaves().size}, docs: ${bed.reader.numDocs()}, slices: ${bed.searcher.slices.size}, threads: $threads")
        println("query: " + bed.trialQuery.query)
        val warm = repeatSearch(bed, 20)
        val timed = repeatSearch(bed, 50)
        println(
            String.format(
                Locale.ROOT,
                "search: first %.1f ms, warm mean %.2f ms (min %.2f, max %.2f) over %d runs",
                millis(warm[0]), timed.map { millis(it) }.average(),
                millis(timed.min()), millis(timed.max()), timed.size
            )
        )
        println(String.format(Locale.ROOT, "throughput: %.1f searches/s", 1e9 / timed.average()))
        println("top hit: " + (Sink.last as TopDocs).let { "${it.totalHits} hits, top score ${it.scoreDocs.firstOrNull()?.score}" })
    }
}

/**
 * Takes the search threads' stacks at a fixed rate, one second on and one second off.
 *
 * Toggling inside a single run rather than comparing two runs is the whole point. This machine's
 * throughput falls by more than a factor of two over a twenty-second run, so two separate runs would
 * charge the throttle curve to whichever configuration went second — the trap this project has
 * fallen into four times. Alternating every second interleaves the two configurations against a
 * clock that moves on a scale of seconds, which is the only honest way to see an effect this small.
 */
private class Prober(val bed: Bed, val ratePerSecond: Int) : Thread("stack-prober") {

    @Volatile
    var probing = false

    @Volatile
    var running = true

    val taken = java.util.concurrent.atomic.LongAdder()
    private val costs = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val depths = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    init {
        isDaemon = true
    }

    override fun run() {
        val period = 1_000_000_000L / ratePerSecond.coerceAtLeast(1)
        var next = System.nanoTime()
        var phase = System.nanoTime() + 1_000_000_000L
        var block = 0
        var i = 0
        while (running) {
            val now = System.nanoTime()
            if (now >= phase) {
                // ABBA, not ABAB. Flipping every second would put every probing phase at an even
                // second and every control phase at an odd one, and this machine throttles
                // monotonically through a run - so the probing phases would all be systematically
                // later, and the drift would be charged to the stack walk. The same position
                // artifact that once reported an instrumented configuration as 6.6% faster.
                // off, on, on, off, off, on, on, off ... puts the two symmetrically in time.
                block++
                probing = (block % 4 == 1 || block % 4 == 2)
                phase = now + 1_000_000_000L
                next = now
            }
            if (!probing) continue
            if (now < next) continue
            val threads = bed.workers
            if (threads.isEmpty()) continue
            val t0 = System.nanoTime()
            val trace = threads[i++ % threads.size].stackTrace
            val cost = System.nanoTime() - t0
            if (trace.isNotEmpty()) {
                taken.increment()
                // Sampled rather than all of them, so the recording is not itself a cost.
                if (i % 32 == 0) {
                    costs.add(cost)
                    // The depth of the traces actually taken, not of a thread probed afterwards - by then
                    // the workers are parked in the pool and read 11 frames deep instead of forty.
                    depths.add(trace.size)
                }
            }
            next = maxOf(next + period, System.nanoTime() - period)
        }
    }

    /** Median caller cost, and the depth the real workload actually reaches. */
    fun report(): String {
        val c = costs.toLongArray().sortedArray()
        val d = depths.toIntArray().sortedArray()
        return if (c.isEmpty()) "no stacks taken" else String.format(
            Locale.ROOT, "%,d stacks taken, caller cost median %.1f us (p90 %.1f us); depth of the traces taken: median %d, p90 %d, max %d",
            taken.sum(), c[c.size / 2] / 1e3, c[(c.size * 9) / 10] / 1e3, d[d.size / 2], d[(d.size * 9) / 10], d.last()
        )
    }
}

/**
 * Does an unlabelled gap have a shape a trigger could find, and would a stack name it?
 *
 * The proposal is to walk a stack not at a fixed rate but *on demand* — when a thread has been
 * outside every label for longer than a tick, which is the same threshold the long-instance detector
 * already uses for labelled work. The argument for it is that the trigger doubles as a filter: a
 * long unlabelled window is usually a label somebody forgot, while unlabelled time that is
 * fine-grained and pervasive is the host's own coordination, which no label could ever have covered.
 *
 * That argument rests on a claim about *shape*, and the claim was derived rather than measured. This
 * measures it, three ways:
 *
 * 1. **The run-length distribution of unlabelled observations**, against what pure chance would give.
 *    If unlabelled time were scattered uniformly, consecutive unlabelled ticks would follow a
 *    geometric distribution with the observed unlabelled fraction. Runs longer than that are real
 *    windows, and they are what a trigger can see.
 * 2. **Where the triggered stacks land.** Only threads whose slot is genuinely empty are walked, so
 *    this is the feature's own view and not a general profile.
 * 3. **Against a control.** Run it on the good placement as well as the broken one: the frames that
 *    appear for the broken placement and not for the good one are exactly what the feature exists to
 *    surface.
 */
private class GapProbe(val bed: Bed, val triggerTicks: Int, val stepNanos: Long = 1_000_000L) : Thread("gap-probe") {

    @Volatile
    var running = true

    private val leaf = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val searchFrame = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val runLengths = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    var observations = 0L
    var unlabelled = 0L
    var stacks = 0L

    /** Triggers that turned out to be a parked thread rather than unlabelled work. */
    var parked = 0L

    /** Unlabelled observations where the thread was not even runnable. */
    var unlabelledParked = 0L

    init {
        isDaemon = true
    }

    override fun run() {
        // Consecutive unlabelled ticks per thread, and whether this window has been sampled already.
        // One stack per window, not one per tick: a thread stuck outside every label for a second
        // would otherwise hand back a thousand copies of the same answer and pay for all of them.
        val consecutive = HashMap<Long, Int>()
        val sampled = HashSet<Long>()
        var next = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            if (now < next) continue
            next = maxOf(next + stepNanos, now - stepNanos)
            val slots = Profiler.slots().associateBy { it.threadId }
            for (t in bed.workers) {
                val slot = slots[t.threadId()] ?: continue
                observations++
                if (slot.getOpaque() == NO_OP) {
                    unlabelled++
                    // Every unlabelled observation, not only the triggered ones: this is what settles
                    // whether unlabelled occupancy is work nobody labelled or a pool thread with
                    // nothing to do, and the duty cycle has been an open question for want of it.
                    if (t.state != Thread.State.RUNNABLE) unlabelledParked++
                    val n = (consecutive[t.threadId()] ?: 0) + 1
                    consecutive[t.threadId()] = n
                    if (n >= triggerTicks && sampled.add(t.threadId())) {
                        // A slot is empty for two quite different reasons, and only one of them is a
                        // missing label: the thread is running code nobody labelled, or it is parked
                        // waiting for work. In a thread pool the second dominates completely, so
                        // without this the answer is a screen of Unsafe.park. Thread.getState reads
                        // a field and needs no handshake, so the cheap check comes first and the
                        // expensive one is never paid for an idle thread.
                        if (t.state != Thread.State.RUNNABLE) { parked++; continue }
                        val trace = t.stackTrace
                        if (trace.isNotEmpty()) {
                            stacks++
                            record(trace)
                        }
                    }
                } else {
                    consecutive.remove(t.threadId())?.let { runLengths.merge(it, 1L, Long::plus) }
                    sampled.remove(t.threadId())
                }
            }
        }
    }

    private fun record(trace: Array<StackTraceElement>) {
        leaf.merge(short(trace[0]), 1L, Long::plus)
        // The first frame from the leaf upward that belongs to Lucene's search package, which is
        // where a clause's identity lives. Below it is codec and JDK plumbing shared by everything.
        val f = trace.firstOrNull { it.className.startsWith("org.apache.lucene.search") }
        searchFrame.merge(if (f == null) "<none>" else short(f), 1L, Long::plus)
    }

    private fun short(f: StackTraceElement) = "${f.className.substringAfterLast('.')}.${f.methodName}"

    fun report(): String = buildString {
        val p = unlabelled.toDouble() / observations.coerceAtLeast(1)
        appendLine(
            String.format(
                Locale.ROOT,
                "GAP PROBE — %,d slot observations, %,d unlabelled (%.1f%%); %,d windows triggered, of which %,d were a parked thread (%.1f%%) and %,d were walked",
                observations, unlabelled, p * 100, stacks + parked, parked, parked * 100.0 / (stacks + parked).coerceAtLeast(1), stacks
            )
        )
        appendLine(
            String.format(
                Locale.ROOT,
                "  of the %,d unlabelled observations, %,d were a thread that was not runnable at all (%.1f%%)",
                unlabelled, unlabelledParked, unlabelledParked * 100.0 / unlabelled.coerceAtLeast(1)
            )
        )
        appendLine()
        appendLine("  unlabelled run length, against what scattering alone would give")
        appendLine("  " + "-".repeat(72))
        appendLine(String.format(Locale.ROOT, "  %8s %12s %12s %12s", "ticks", "windows", "expected", "observed/exp"))
        val total = runLengths.values.sum()
        for (k in runLengths.keys.sorted().take(12)) {
            val observed = runLengths.getValue(k)
            // Geometric: a window of exactly k unlabelled ticks, if each observation were an
            // independent coin toss at the marginal unlabelled rate.
            val expected = total * Math.pow(p, (k - 1).toDouble()) * (1 - p)
            appendLine(
                String.format(
                    Locale.ROOT, "  %8d %12d %12.0f %11.2fx",
                    k, observed, expected, if (expected <= 0) Double.NaN else observed / expected
                )
            )
        }
        val long = runLengths.filterKeys { it >= triggerTicks }.values.sum()
        appendLine(String.format(Locale.ROOT, "  windows of >= %d ticks: %,d of %,d (%.1f%%)", triggerTicks, long, total, long * 100.0 / total.coerceAtLeast(1)))

        fun table(title: String, m: Map<String, Long>) {
            appendLine()
            appendLine("  $title")
            appendLine("  " + "-".repeat(72))
            val n = m.values.sum().coerceAtLeast(1)
            for ((name, c) in m.entries.sortedByDescending { it.value }.take(10)) {
                appendLine(String.format(Locale.ROOT, "  %-52s %8d %7.1f%%", name, c, c * 100.0 / n))
            }
        }
        table("where the triggered stacks landed — leaf frame", leaf)
        table("where the triggered stacks landed — deepest frame in lucene.search", searchFrame)
    }
}

/**
 * The profiling run: search in a loop for a fixed wall-clock span, with whatever instrumentation
 * the mode asks for, and optionally with JFR recording the same window.
 */
private fun load(
    corpus: Corpus,
    threads: Int,
    seconds: Int,
    mode: Placement,
    sampler: Boolean,
    step: Double,
    jfr: String?,
    warmups: Int,
    stacks: Int,
    gapTrigger: Int,
    /**
     * Whether a search also carries a coarse label. Off by default: at threads>1 it measures a
     * quantity the tier cannot yet represent, and that is a demonstration rather than a setting.
     */
    coarse: Boolean = false,
    /** Whether the search pool carries the caller's coarse execution. See [Bed.propagate]. */
    propagate: Boolean = true,
) {
    val searchCoarse = if (coarse) Profiler.registerCoarse("search") else null
    Bed(corpus, threads, mode, propagate = propagate).use { bed ->
        println(
            "threads=$threads placement=$mode sampler=$sampler" +
                    (if (coarse) ", coarse label on, propagation ${if (propagate) "ON" else "OFF"}" else "") +
                    "; warm-up $warmups searches, then searching for $seconds s"
        )
        val warm = repeatSearch(bed, warmups)
        println(String.format(Locale.ROOT, "warm-up: first %.1f ms, last %.2f ms", millis(warm[0]), millis(warm.last())))
        bed.trialQuery.clauses.reset()

        val recording = if (jfr != null) recordExecutionSamples(1) else null
        if (sampler) Profiler.start(stepMillis = step)

        val prober = if (stacks > 0) Prober(bed, stacks).also { it.start() } else null
        val gaps = if (gapTrigger > 0) GapProbe(bed, gapTrigger).also { it.start() } else null

        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        var searches = 0L
        // Two buckets, filled by the same loop in the same run, according to which second-long phase
        // the prober happened to be in. Each search is charged to the state that was true when it
        // started, so a search straddling a toggle lands in one bucket rather than being split.
        var withStacks = 0L
        var withStacksNanos = 0L
        var without = 0L
        var withoutNanos = 0L
        val started = System.nanoTime()
        while (System.nanoTime() < deadline) {
            val probing = prober?.probing == true
            val t0 = System.nanoTime()
            // A search is the obvious coarse operation here, and it is the workload phase 5 was
            // justified on. Lucene hands one slice per segment to a pool; without propagation the
            // context stays on the calling thread while most of the work happens on threads that
            // never see it, so `busy/exec` counts only the caller and `span - busy` reads as waiting
            // when it is really other threads working. Measured that way: 88.5% of labelled
            // thread-time outside every span, and busy/exec falling 14.87 ms to 3.10 ms between one
            // thread and eight.
            //
            // With `--propagate` on — the default now — the pool is wrapped and the slices run inside
            // the search that forked them. `--propagate=off` still runs the other way, because the
            // before and the after have to be the same binary.
            Sink.last = searchCoarse?.let { c -> op(c) { bed.searchOnce() } } ?: bed.searchOnce()
            val took = System.nanoTime() - t0
            searches++
            if (probing) {
                withStacks++; withStacksNanos += took
            } else {
                without++; withoutNanos += took
            }
        }
        val elapsed = System.nanoTime() - started
        prober?.running = false
        gaps?.running = false

        val report = if (sampler) Profiler.stop() else null
        if (recording != null) {
            recording.stop()
            recording.dump(Path.of(jfr))
            recording.close()
            println("JFR written to $jfr")
        }

        println(
            String.format(
                Locale.ROOT, "%,d searches in %.1f s - %.2f ms each, %.1f searches/s",
                searches, elapsed / 1e9, millis(elapsed) / searches, searches / (elapsed / 1e9)
            )
        )
        if (prober != null) {
            println()
            println("STACK PROBE — " + prober.report())
            println(
                String.format(
                    Locale.ROOT,
                    "  seconds with stacks being taken: %,d searches, %.3f ms each",
                    withStacks, millis(withStacksNanos) / withStacks.coerceAtLeast(1)
                )
            )
            println(
                String.format(
                    Locale.ROOT,
                    "  seconds without:                 %,d searches, %.3f ms each  (%+.2f%%)",
                    without, millis(withoutNanos) / without.coerceAtLeast(1),
                    ((withStacksNanos.toDouble() / withStacks.coerceAtLeast(1)) /
                            (withoutNanos.toDouble() / without.coerceAtLeast(1)) - 1) * 100
                )
            )
        }
        if (gaps != null) {
            println()
            print(gaps.report())
        }
        if (mode == Placement.TIME) {
            println()
            print(bed.trialQuery.clauses.render())
        }
        if (report != null) println("\n" + report.render())
    }
}

/**
 * Step three of the recipe: **measure the mechanism separately from the hook**, interleaved.
 *
 * Three-way at least, and here four, because Lucene's placement mechanism can do something
 * Calcite's could not - it can change which code path the library takes. NONE against INERT prices
 * the wrapper; INERT against LABEL prices our hook; and TIME prices the alternative that a
 * hand-rolled clause profiler would use.
 *
 * The order is swapped every round for the reason the first trial learned the hard way: with a
 * fixed order, anything that depends on position - a collection that always lands in the first
 * slot, a clock coming up from idle - is charged entirely to whichever configuration goes first. A
 * fixed order once reported the instrumented configuration as 6.6% faster than the clean one.
 */
private fun compare(corpus: Corpus, threads: Int, modes: List<Placement>, rounds: Int, perRound: Int) {
    val beds = modes.map { it to Bed(corpus, threads, it) }
    try {
        println("interleaved: ${modes.joinToString(" vs ")}, $rounds rounds of $perRound searches each")
        for ((_, bed) in beds) repeatSearch(bed, 20)

        val times = modes.associateWith { ArrayList<Long>() }
        for (r in 0 until rounds) {
            val order = if (r % 2 == 0) beds else beds.reversed()
            for ((mode, bed) in order) {
                val t = repeatSearch(bed, perRound)
                times.getValue(mode) += t.toList()
            }
            println("  round ${r + 1}: " + modes.joinToString(", ") { m ->
                String.format(Locale.ROOT, "%s %.2f ms", m, times.getValue(m).takeLast(perRound).map { millis(it) }.average())
            })
        }

        println()
        val baseline = times.getValue(modes.first()).map { millis(it) }
        for (m in modes) {
            val t = times.getValue(m).map { millis(it) }.sorted()
            println(
                String.format(
                    Locale.ROOT, "%-8s n=%d  mean %8.3f ms   median %8.3f ms   min %8.3f ms   vs %s: %+.2f%%",
                    m, t.size, t.average(), t[t.size / 2], t.first(), modes.first(),
                    (t.average() / baseline.average() - 1) * 100
                )
            )
        }
    } finally {
        for ((_, bed) in beds) bed.close()
    }
}

fun main(args: Array<String>) {
    val opt = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val value = if (i + 1 < args.size && !args[i + 1].startsWith("--")) args[++i] else "true"
            opt[key] = value
        }
        i++
    }

    val corpus = Corpus(
        dir = Path.of(opt["index"] ?: "trial-lucene/index"),
        docCount = opt["docs"]?.toInt() ?: 1_000_000,
        segments = opt["segments"]?.toInt() ?: 8,
    )
    val threads = opt["threads"]?.toInt() ?: 8

    when {
        opt["build"] != null -> corpus.ensure()

        opt["qualify"] != null -> {
            corpus.ensure()
            qualify(corpus, threads)
        }

        opt["analyze"] != null -> analyzeJfr(
            Path.of(opt["analyze"]!!), opt["top"]?.toInt() ?: 15,
            opt["collapsed"]?.let { Path.of(it) }
        )

        opt["ab"] != null -> {
            corpus.ensure()
            compare(
                corpus, threads,
                opt["modes"]?.split(",")?.map { Placement.valueOf(it.uppercase(Locale.ROOT)) }
                    ?: listOf(Placement.NONE, Placement.INERT, Placement.LABEL),
                opt["rounds"]?.toInt() ?: 6,
                opt["per"]?.toInt() ?: 20,
            )
        }

        else -> {
            corpus.ensure()
            load(
                corpus, threads,
                seconds = opt["seconds"]?.toInt() ?: 20,
                mode = Placement.valueOf((opt["placement"] ?: "LABEL").uppercase(Locale.ROOT)),
                sampler = opt["sampler"] != "false",
                step = opt["step"]?.toDouble() ?: 1.0,
                jfr = opt["jfr"],
                warmups = opt["warmups"]?.toInt() ?: 30,
                stacks = opt["stacks"]?.toInt() ?: 0,
                gapTrigger = opt["gaps"]?.toInt() ?: 0,
                coarse = opt["coarse"] != null,
                propagate = opt["propagate"] != "off",
            )
        }
    }
}

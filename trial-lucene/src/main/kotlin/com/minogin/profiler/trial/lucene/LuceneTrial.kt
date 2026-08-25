package com.minogin.profiler.trial.lucene

import com.minogin.profiler.Profiler
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

    val searcher: IndexSearcher =
        if (pool != null) SlicedSearcher(reader, pool) else IndexSearcher(reader)

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
 * The profiling run: search in a loop for a fixed wall-clock span, with whatever instrumentation
 * the mode asks for, and optionally with JFR recording the same window.
 */
private fun load(
    corpus: Corpus,
    threads: Int,
    seconds: Int,
    mode: Placement,
    sampler: Boolean,
    strict: Boolean,
    step: Double,
    jfr: String?,
    warmups: Int,
    stacks: Int,
) {
    Bed(corpus, threads, mode).use { bed ->
        println("threads=$threads placement=$mode sampler=$sampler strict=$strict; warm-up $warmups searches, then searching for $seconds s")
        val warm = repeatSearch(bed, warmups)
        println(String.format(Locale.ROOT, "warm-up: first %.1f ms, last %.2f ms", millis(warm[0]), millis(warm.last())))
        bed.trialQuery.clauses.reset()

        val recording = if (jfr != null) recordExecutionSamples(1) else null
        if (sampler) Profiler.start(stepMillis = step, strict = strict)

        val prober = if (stacks > 0) Prober(bed, stacks).also { it.start() } else null

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
            Sink.last = bed.searchOnce()
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
                strict = opt["strict"] == "true",
                step = opt["step"]?.toDouble() ?: 1.0,
                jfr = opt["jfr"],
                warmups = opt["warmups"]?.toInt() ?: 30,
                stacks = opt["stacks"]?.toInt() ?: 0,
            )
        }
    }
}

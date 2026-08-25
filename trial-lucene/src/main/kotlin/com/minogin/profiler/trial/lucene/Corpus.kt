package com.minogin.profiler.trial.lucene

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.NoMergePolicy
import org.apache.lucene.index.Term
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.SplittableRandom

/**
 * The corpus: a million documents that exist only to make search cost something.
 *
 * Two properties are deliberate and everything else is incidental.
 *
 * **The term distribution is Zipfian**, because that is what makes the trial's question sharp. A
 * uniform vocabulary would give every clause the same cost and the question "which clause cost the
 * time" would have a boring answer. With Zipf, term rank 3 appears in a third of the documents and
 * term rank 3,000 in a few hundred, so a query can hold several `TermQuery` clauses whose costs
 * differ by three orders of magnitude — and which are, to a stack profiler, the same `TermScorer`
 * running the same `ImpactsDISI.advance`.
 *
 * **The segment count is fixed rather than left to the merge policy.** Concurrency in Lucene is
 * per segment: `IndexSearcher` slices the segments across its executor. A force-merged index would
 * search on one thread and the whole reason this candidate was chosen — the first concurrent
 * foreign workload — would evaporate. Merging is switched off and a commit is forced every
 * `docs / segments` documents, so the shape of the index is a stated property of the experiment.
 */
class Corpus(
    val dir: Path,
    val docCount: Int = 1_000_000,
    val segments: Int = 8,
    val vocabulary: Int = 50_000,
    val bodyTokens: Int = 24,
    val seed: Long = 20260825L,
) {

    /**
     * Zipf by inverse-CDF lookup rather than binary search.
     *
     * 24 million tokens are drawn to build the index. A binary search over 50,000 cumulative
     * weights is sixteen dependent loads per token and would dominate the build; a table indexed by
     * the top bits of a random int is one. The table is 4 MB and built once.
     */
    private val lookup: IntArray = buildZipfLookup(vocabulary)

    val analyzer = StandardAnalyzer()

    /**
     * Term text for a rank. Prefixed so that a `PrefixQuery` has something predictable to expand,
     * and zero-padded so that lexicographic and numeric order agree, which makes the index's own
     * term ordering easy to reason about when a result looks wrong.
     */
    fun term(rank: Int): String = String.format(Locale.ROOT, "w%05d", rank)

    /** Builds the index if it is not already there. Reports what it found or what it wrote. */
    fun ensure(): Boolean {
        if (Files.isDirectory(dir) && Files.list(dir).use { s -> s.anyMatch { it.fileName.toString().startsWith("segments_") } }) {
            println("index: reusing $dir")
            return false
        }
        build()
        return true
    }

    private fun build() {
        println("index: building $docCount docs, $segments segments, vocabulary $vocabulary into $dir")
        Files.createDirectories(dir)
        val t0 = System.nanoTime()
        FSDirectory.open(dir).use { d ->
            val config = IndexWriterConfig(analyzer)
                // Merging off, and a commit every docs/segments documents. A merge policy would
                // decide the segment count for us, and the segment count is what decides how much
                // of this workload is concurrent.
                .setMergePolicy(NoMergePolicy.INSTANCE)
                .setRAMBufferSizeMB(512.0)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
            IndexWriter(d, config).use { w ->
                val random = SplittableRandom(seed)
                val perSegment = docCount / segments
                val body = StringBuilder(bodyTokens * 7)
                for (i in 0 until docCount) {
                    val doc = Document()
                    doc.add(StringField("id", i.toString(), Field.Store.NO))
                    body.setLength(0)
                    for (t in 0 until bodyTokens) {
                        if (t > 0) body.append(' ')
                        body.append(term(zipf(random)))
                    }
                    doc.add(TextField("body", body.toString(), Field.Store.NO))
                    // A low-cardinality field, so there is a clause in the query whose cost shape
                    // is completely different from a term clause: cheap per document, enormous
                    // posting list.
                    doc.add(StringField("cat", "c" + (i % 20), Field.Store.NO))
                    // A point field, so one clause of the query is not a postings scan at all.
                    doc.add(IntPoint("price", random.nextInt(0, 10_000)))
                    w.addDocument(doc)
                    if ((i + 1) % perSegment == 0) {
                        w.commit()
                        print(".")
                    }
                }
                w.commit()
            }
        }
        println()
        println(String.format(Locale.ROOT, "index: built in %.1f s", (System.nanoTime() - t0) / 1e9))
    }

    private fun zipf(random: SplittableRandom): Int = lookup[random.nextInt(LOOKUP_SIZE)]

    /**
     * Document frequencies, straight out of the index.
     *
     * The query is chosen from these rather than from the theoretical Zipf weights: what matters
     * is what the analyzer and the writer actually produced, and the two have disagreed before —
     * the standard analyzer's stop-word handling and tokenisation are between the generator and
     * the postings.
     */
    fun frequencies(ranks: List<Int>): List<Pair<Int, Int>> =
        FSDirectory.open(dir).use { d ->
            DirectoryReader.open(d).use { r ->
                ranks.map { it to r.docFreq(Term("body", term(it))) }
            }
        }

    companion object {
        const val LOOKUP_SIZE = 1 shl 20

        /**
         * A table of [LOOKUP_SIZE] entries where entry *i* is the rank a uniform draw of *i* should
         * produce, under a Zipf distribution with exponent 1 over [n] ranks.
         */
        fun buildZipfLookup(n: Int): IntArray {
            val weights = DoubleArray(n) { 1.0 / (it + 1) }
            val total = weights.sum()
            val table = IntArray(LOOKUP_SIZE)
            var rank = 0
            var cumulative = 0.0
            for (i in 0 until LOOKUP_SIZE) {
                val target = (i + 0.5) / LOOKUP_SIZE
                while (rank < n - 1 && cumulative + weights[rank] / total < target) {
                    cumulative += weights[rank] / total
                    rank++
                }
                table[i] = rank
            }
            return table
        }
    }
}

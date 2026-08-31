package com.ticksnick.trial.lucene

import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.search.BulkScorer
import org.apache.lucene.search.DocAndFloatFeatureBuffer
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.LeafCollector
import org.apache.lucene.search.Matches
import org.apache.lucene.search.Query
import org.apache.lucene.search.QueryVisitor
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.Scorable
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.ScorerSupplier
import org.apache.lucene.search.TwoPhaseIterator
import org.apache.lucene.search.Weight
import org.apache.lucene.util.Bits
import org.apache.lucene.util.FixedBitSet

/**
 * Placing a label in code you do not own, second shape: **wrapping**.
 *
 * Calcite handed the trial a listener - two callbacks, before and after, and nothing else in the
 * planner reachable without forking. Lucene hands it a chain of factories instead: a `Query` makes
 * a `Weight`, a `Weight` makes a `ScorerSupplier`, a supplier makes a `Scorer`, and a scorer
 * exposes a `DocIdSetIterator`. Every one of those is a class a third party is invited to extend,
 * so the label can go exactly where the work is - and it goes there as a *block*, which is the
 * form the documentation puts first and which the first trial could not use at all.
 *
 * That is the good news. The rest of this file is the bad news, and it is the reason a second
 * trial was worth more than any feature on the list:
 *
 * - **A wrapper is not transparent.** Lucene decides what to do partly by asking the objects it
 *   was given what they can do. Every bulk escape hatch below - `intoBitSet`, `docIDRunEnd`,
 *   `nextDocsAndScores`, `ScorerSupplier.bulkScorer` - has a default implementation on the base
 *   class that falls back to a doc-at-a-time loop. Override none of them and the wrapper silently
 *   *changes the workload*: the profile would then be accurate about a query Lucene would never
 *   have run that way. All of them are delegated here, and the label goes around the delegated
 *   call.
 * - **The query cache would delete the work being measured.** An `LRUQueryCache` in front of a
 *   clause turns the second search into a bitset lookup, and the label would then correctly report
 *   almost nothing. The trial switches the cache off rather than explain a zero.
 * - **Dynamic pruning is a contract, not an optimisation.** `setMinCompetitiveScore`,
 *   `getMaxScore` and `advanceShallow` are how block-max pruning skips whole blocks of documents.
 *   A wrapper that swallows any of them does not merely lose speed - it profiles a different query.
 *
 * None of this is asserted. It is what the three-way comparison in `--ab` exists to catch.
 */

/** The wrapper the searcher is handed instead of the real clause. */
class LabelledQuery(val inner: Query, val clause: Clause) : Query() {

    override fun createWeight(searcher: IndexSearcher, scoreMode: ScoreMode, boost: Float): Weight =
        LabelledWeight(this, inner.createWeight(searcher, scoreMode, boost), clause)

    /**
     * Rewriting is where a wrapper is most easily lost.
     *
     * `IndexSearcher.rewrite` loops until the query stops changing. A `PrefixQuery` rewrites into a
     * union of terms and a `BooleanQuery` rewrites each of its clauses, so if this returned the
     * rewritten inner query the wrapper would simply disappear and the clause would go unlabelled -
     * with no error and no missing row, just a share that quietly went somewhere else. Re-wrapping
     * keeps it; returning `this` once the inner query has reached its fixpoint is what stops the
     * loop.
     */
    override fun rewrite(searcher: IndexSearcher): Query {
        val rewritten = inner.rewrite(searcher)
        return if (rewritten === inner) this else LabelledQuery(rewritten, clause)
    }

    override fun visit(visitor: QueryVisitor) = inner.visit(visitor)

    override fun toString(field: String?): String = inner.toString(field)

    override fun equals(other: Any?): Boolean =
        sameClassAs(other) && clause === (other as LabelledQuery).clause && inner == other.inner

    override fun hashCode(): Int = 31 * classHash() + inner.hashCode()
}

class LabelledWeight(query: Query, val inner: Weight, val clause: Clause) : Weight(query) {

    override fun explain(context: LeafReaderContext, doc: Int): Explanation = inner.explain(context, doc)

    override fun matches(context: LeafReaderContext, doc: Int): Matches? = inner.matches(context, doc)

    override fun isCacheable(ctx: LeafReaderContext): Boolean = inner.isCacheable(ctx)

    /** Delegated: it is how a clause answers a count without scoring anything. */
    override fun count(context: LeafReaderContext): Int = inner.count(context)

    /**
     * Labelled, and it took a disagreement with JFR to find out why it has to be.
     *
     * The first version of this file labelled only the *product* - the scorer and its iterator -
     * and left every factory call bare, on the reasoning that constructing a scorer is setup and
     * the work is in the scan. That is true of a term clause and badly false of a prefix clause,
     * which rewrites into a hundred terms and unions their postings into a bitset before a single
     * document is scored. Measured: the single hottest stack in the whole baseline recording,
     * 9.05% of all samples, is that bitset being built inside `ScorerSupplier.get`. With the
     * factories unlabelled, the prefix clause's share came out about a third low and nothing in the
     * report suggested anything was missing.
     */
    override fun scorerSupplier(context: LeafReaderContext): ScorerSupplier? =
        (if (clause.factories) clause.probe { inner.scorerSupplier(context) } else inner.scorerSupplier(context))
            ?.let { LabelledSupplier(it, clause) }
}

class LabelledSupplier(val inner: ScorerSupplier, val clause: Clause) : ScorerSupplier() {

    /** Labelled: for a rewriting clause this is where most of the clause's time is. See above. */
    override fun get(leadCost: Long): Scorer =
        LabelledScorer(if (clause.factories) clause.probe { inner.get(leadCost) } else inner.get(leadCost), clause)

    override fun cost(): Long = inner.cost()

    /**
     * Delegated on purpose. The base class would otherwise build a `DefaultBulkScorer` over [get],
     * which is a correct bulk scorer and the wrong one: it throws away whatever specialised scorer
     * this clause has for scoring a whole window at a time. The label goes around the delegate
     * instead, which keeps both the code path and the attribution - and around the construction as
     * well as the scoring, for the reason given on `scorerSupplier`.
     */
    override fun bulkScorer(): BulkScorer =
        if (clause.bulk) LabelledBulkScorer(if (clause.factories) clause.probe { inner.bulkScorer() } else inner.bulkScorer(), clause)
        else super.bulkScorer()

    override fun setTopLevelScoringClause() = inner.setTopLevelScoringClause()
}

class LabelledBulkScorer(val inner: BulkScorer, val clause: Clause) : BulkScorer() {

    override fun score(collector: LeafCollector, acceptDocs: Bits?, min: Int, max: Int): Int =
        clause.probe { inner.score(collector, acceptDocs, min, max) }

    override fun cost(): Long = inner.cost()
}

class LabelledScorer(val inner: Scorer, val clause: Clause) : Scorer() {

    /**
     * The two-phase contract, kept.
     *
     * A scorer with an expensive match test - a phrase clause checking positions - splits itself
     * into a cheap approximation and a `matches()` confirmation, and the conjunctions above it
     * schedule the two separately. Handing back a plain iterator here would collapse that into one
     * step and change how much work the clause does. So the approximation and the confirmation are
     * wrapped separately and both are labelled: a phrase clause's cost is mostly in `matches()`,
     * and that is precisely the number the trial is after.
     */
    private val innerTwoPhase: TwoPhaseIterator? = inner.twoPhaseIterator()

    private val twoPhase: TwoPhaseIterator? = innerTwoPhase?.let { tp ->
        object : TwoPhaseIterator(LabelledIterator(tp.approximation(), clause)) {
            override fun matches(): Boolean = clause.probe { tp.matches() }
            override fun matchCost(): Float = tp.matchCost()
        }
    }

    private val labelledIterator: DocIdSetIterator =
        if (twoPhase != null) TwoPhaseIterator.asDocIdSetIterator(twoPhase)
        else LabelledIterator(inner.iterator(), clause)

    override fun docID(): Int = inner.docID()

    override fun iterator(): DocIdSetIterator = labelledIterator

    override fun twoPhaseIterator(): TwoPhaseIterator? = twoPhase

    override fun score(): Float = clause.probe { inner.score() }

    override fun getMaxScore(upTo: Int): Float = inner.getMaxScore(upTo)

    /** Block-max pruning. Delegated, or the clause being profiled is not the clause Lucene runs. */
    override fun advanceShallow(target: Int): Int = inner.advanceShallow(target)

    override fun setMinCompetitiveScore(minScore: Float) = inner.setMinCompetitiveScore(minScore)

    override fun smoothingScore(docId: Int): Float = inner.smoothingScore(docId)

    override fun getChildren(): MutableCollection<Scorable.ChildScorable> = inner.children

    /**
     * The bulk scoring path added in Lucene 10. The default on `Scorer` walks the iterator one
     * document at a time; the specialised implementations fill the buffer straight out of the
     * postings. Delegating keeps the fast one, and one label then covers a whole buffer rather than
     * one document - which is the right granularity anyway, since a single `nextDoc` on a common
     * term is well under the floor.
     */
    override fun nextDocsAndScores(upTo: Int, liveDocs: Bits?, buffer: DocAndFloatFeatureBuffer) =
        if (clause.bulk) clause.probe { inner.nextDocsAndScores(upTo, liveDocs, buffer) }
        else super.nextDocsAndScores(upTo, liveDocs, buffer)
}

/**
 * The iterator, which is where nearly all of a clause's time is.
 *
 * `nextDoc` on a common term is tens of nanoseconds - under the floor the profiler refuses to
 * describe - while `advance` on a rare term with a skip list, or a phrase confirmation, is hundreds
 * of nanoseconds to microseconds. One query holds both, which is exactly why this candidate is
 * worth the trouble: the report has to say, per clause, which of its own numbers it can stand
 * behind.
 */
class LabelledIterator(val inner: DocIdSetIterator, val clause: Clause) : DocIdSetIterator() {

    override fun docID(): Int = inner.docID()

    override fun nextDoc(): Int = clause.probe { inner.nextDoc() }

    override fun advance(target: Int): Int = clause.probe { inner.advance(target) }

    override fun cost(): Long = inner.cost()

    /** Bulk. Delegated for the same reason as everything else bulk here. */
    override fun intoBitSet(upTo: Int, bitSet: FixedBitSet, offset: Int) =
        if (clause.bulk) clause.probe { inner.intoBitSet(upTo, bitSet, offset) }
        else super.intoBitSet(upTo, bitSet, offset)

    override fun docIDRunEnd(): Int =
        if (clause.bulk) clause.probe { inner.docIDRunEnd() } else super.docIDRunEnd()
}

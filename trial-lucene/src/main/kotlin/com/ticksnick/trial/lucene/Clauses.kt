package com.ticksnick.trial.lucene

import com.ticksnick.FineOp
import com.ticksnick.Profiler
import com.ticksnick.op
import java.util.Locale
import java.util.concurrent.atomic.LongAdder

/**
 * How a clause is instrumented, if at all.
 *
 * Four configurations, and the reason there are four is the third step of the trial recipe:
 * *measure the mechanism separately from the hook*. In Calcite, attaching any listener made the
 * planner allocate two objects per firing whether the listener did anything or not, and pricing
 * that together with our hook would have blamed us for somebody else's cost. Lucene's placement
 * mechanism is wrapping, and wrapping is a virtual call the JIT cannot devirtualise plus — the
 * part that is new and that Calcite never had — a chance that the library takes a different code
 * path because our wrapper is not the class it was expecting.
 */
enum class Placement {
    /** No wrapper anywhere. The baseline. */
    NONE,

    /** Wrapped, delegating everything, measuring nothing. This is what wrapping alone costs. */
    INERT,

    /** Wrapped, with a profiler label on every clause. */
    LABEL,

    /**
     * Wrapped, with `System.nanoTime()` on both sides of every clause call, accumulated per clause.
     *
     * This is how Elasticsearch's query profiler works — `ProfileWeight` and `ProfileScorer` wrap
     * every scorer and time it — and it exists here as the independent check on our labels' *relative*
     * shares, and as an honest measurement of what the alternative approach costs. Two calls to
     * `nanoTime` around an operation of a few tens of nanoseconds is not a small correction.
     */
    TIME,

    /**
     * Labelled, and wrapped the way anybody would wrap it the first time: overriding the methods
     * that obviously matter and letting the base class supply the rest.
     *
     * `DocIdSetIterator.intoBitSet`, `DocIdSetIterator.docIDRunEnd`, `Scorer.nextDocsAndScores` and
     * `ScorerSupplier.bulkScorer` all have working default implementations that fall back to a
     * doc-at-a-time loop. A wrapper that does not delegate them still *works*, still returns the
     * right documents, and still produces a report — of a query Lucene would never have run that
     * way. This configuration exists to measure how far wrong that goes, because the alternative is
     * to assert it.
     */
    NAIVE,

    /**
     * Labelled on the *product* only — the scorer and its iterator — with every factory call left
     * bare. The first version of these wrappers, kept because it is the mistake worth reproducing.
     *
     * It is a reasonable-sounding rule: building a scorer is setup, the work is in the scan. True
     * of a term clause; false of a prefix clause, which rewrites into a hundred terms and unions
     * their postings into a bitset before a single document is scored. The report it produces looks
     * completely healthy — shares sum, every number is plausible, the ranking is sensible — and it
     * is a third low on the clause that matters. This configuration exists so that the difference
     * between a good placement and a bad one can be *measured* rather than recalled.
     */
    PRODUCT,
}

/**
 * One labelled clause of the query.
 *
 * `labelled` and `timed` are final fields read by a branch inside [probe], rather than three
 * different wrapper class families. That is deliberate: the A/B runs all configurations in one
 * JVM, and three implementations of one interface would make every call site in the wrappers
 * megamorphic — so the inert configuration would be charged for the presence of the others and
 * the comparison would measure the harness. A branch on a final field is the same shape of code
 * in all four.
 */
class Clause(val name: String, mode: Placement) {

    @JvmField
    val labelled = mode == Placement.LABEL || mode == Placement.NAIVE || mode == Placement.PRODUCT

    @JvmField
    val timed = mode == Placement.TIME

    /** Whether Lucene's bulk escape hatches are delegated. False only for [Placement.NAIVE]. */
    @JvmField
    val bulk = mode != Placement.NAIVE

    /** Whether the factory calls carry the label too. False only for [Placement.PRODUCT]. */
    @JvmField
    val factories = mode != Placement.PRODUCT

    /**
     * Registered once, at construction. A name lookup per `nextDoc` would cost many times the hook
     * it feeds — the same point the Calcite trial had to make, and the one the documentation should
     * make before anybody discovers it.
     */
    // No @JvmField: Kotlin forbids it on a value-class property, and a null here is the honest way
    // to say "this configuration places no label" — which used to be -1 and a test at every use.
    val id: FineOp? = if (labelled) Profiler.registerFine("clause:$name") else null

    /** Only used by [Placement.TIME]. Contended across the search threads, which is part of its cost. */
    @JvmField
    val nanos = LongAdder()

    @JvmField
    val calls = LongAdder()
}

/**
 * Runs one call of the clause's iterator or scorer under whatever instrumentation is configured.
 *
 * **The label here is lexical, and that is the first thing this trial says about placement.**
 * Calcite's only boundary was a pair of callbacks, which is what `Profiler.enter` / `exit` were
 * built for. Lucene's extension point is a *wrapper*, and a wrapper method body is a block we own —
 * so `op(id) { }`, the form the documentation puts first, is back in play, with its `finally`
 * written by the compiler and no way to leak. Two libraries, two shapes, and the library needs
 * both forms for a reason that is now observed twice rather than argued once.
 */
inline fun <T> Clause.probe(body: () -> T): T {
    id?.let { return op(it, body) }
    if (timed) {
        val t0 = System.nanoTime()
        try {
            return body()
        } finally {
            nanos.add(System.nanoTime() - t0)
            calls.increment()
        }
    }
    return body()
}

/** The clauses of one query, in the order they were declared. */
class ClauseSet(val clauses: List<Clause>) {

    fun reset() {
        for (c in clauses) {
            c.nanos.reset()
            c.calls.reset()
        }
    }

    /**
     * What the timing approach reports, in the same shape as our own report so the two can be laid
     * side by side.
     *
     * The wall-clock total here is the sum over threads of time spent inside clause calls, so it
     * exceeds the elapsed time of the search by roughly the thread count. Shares of that total are
     * the comparable quantity, not the absolute nanoseconds.
     */
    fun render(): String = buildString {
        val total = clauses.sumOf { it.nanos.sum() }
        appendLine("TIMED CLAUSES - System.nanoTime on both sides of every call, the Elasticsearch approach")
        appendLine("-".repeat(78))
        appendLine(String.format(Locale.ROOT, "%-28s %12s %14s %12s", "clause", "share", "calls", "ns/call"))
        for (c in clauses.sortedByDescending { it.nanos.sum() }) {
            val n = c.nanos.sum()
            val k = c.calls.sum()
            appendLine(
                String.format(
                    Locale.ROOT, "%-28s %11.2f%% %14d %12.1f",
                    c.name, if (total == 0L) 0.0 else n * 100.0 / total, k,
                    if (k == 0L) 0.0 else n.toDouble() / k
                )
            )
        }
        appendLine(String.format(Locale.ROOT, "total across threads: %.3f s", total / 1e9))
    }
}

package com.ticksnick.trial.calcite

import com.ticksnick.FineOp
import com.ticksnick.Profiler
import org.apache.calcite.plan.RelOptListener
import org.apache.calcite.plan.RelOptNode
import org.apache.calcite.plan.RelOptPlanner
import org.apache.calcite.plan.RelOptRule
import org.apache.calcite.plan.RelTraitSet
import org.apache.calcite.rel.RelNode
import org.apache.calcite.tools.Program

/**
 * Placing labels in code that is not yours.
 *
 * The profiler's documented surface is `op(id) { }` — a lexical block. Nothing in Calcite is ours
 * to wrap in a block: the interesting boundary is inside `VolcanoRuleCall.onMatch`, in a jar. What
 * Calcite does offer is a listener that is notified immediately before and immediately after
 * `rule.onMatch(call)` runs, which is the same boundary expressed as two callbacks.
 *
 * That was the trial's first friction finding, and it cost fifteen lines of helper here — a span
 * stack written against `Profiler.slot()` and the opaque accessors, which are public but were never
 * presented as the way to place a label. **Those fifteen lines are now `Profiler.enter` /
 * `Profiler.exit` and this file no longer has them**, which is the point of phase 3.75: the second
 * trial should not have to rediscover the same thing.
 */

/**
 * Labels every rule firing with the identity of the *rule instance*.
 *
 * This is the axis the stack cannot express. Every enumerable conversion rule —
 * EnumerableJoinRule, EnumerableProjectRule, EnumerableFilterRule and a dozen more — inherits one
 * `ConverterRule.onMatch`, so a sampling profiler files all of them under a single frame. They are
 * different rules with different costs, and telling them apart is a question about the domain, not
 * about the call stack.
 *
 * Ids are resolved once, into an identity map keyed by the rule object, because the alternative —
 * a name lookup per rule attempt — would cost many times the hook it feeds.
 */
/**
 * A listener that does nothing at all.
 *
 * Attaching *any* listener changes the workload: Calcite allocates two RuleAttemptedEvent objects
 * per rule firing and only when a listener is present. That cost belongs to the placement
 * mechanism, not to the profiler's hook, and the two have to be measured apart or the hook will be
 * blamed for somebody else's allocation.
 */
class NullListener : RelOptListener {
    override fun ruleAttempted(event: RelOptListener.RuleAttemptedEvent) {}
    override fun relEquivalenceFound(event: RelOptListener.RelEquivalenceEvent) {}
    override fun ruleProductionSucceeded(event: RelOptListener.RuleProductionEvent) {}
    override fun relDiscarded(event: RelOptListener.RelDiscardedEvent) {}
    override fun relChosen(event: RelOptListener.RelChosenEvent) {}
}

class RuleLabeller(rules: List<RelOptRule>) : RelOptListener {

    private val ids = java.util.IdentityHashMap<RelOptRule, FineOp>().apply {
        for (r in rules) put(r, Profiler.registerFine(shortName(r)))
    }

    /** Rules Calcite created for itself, which are not in the set we handed it. */
    private val unregistered = Profiler.registerFine("rule:<other>")

    override fun ruleAttempted(event: RelOptListener.RuleAttemptedEvent) {
        // The close now names what it is closing, and Calcite happens to hand us the rule on
        // both callbacks — so a crossed enter/exit here would be caught rather than reported as
        // a plausible share. The old no-argument exit() could not have known.
        val op = ids[event.ruleCall.rule] ?: unregistered
        if (event.isBefore) Profiler.enter(op) else Profiler.exit(op)
    }

    override fun relEquivalenceFound(event: RelOptListener.RelEquivalenceEvent) {}
    override fun ruleProductionSucceeded(event: RelOptListener.RuleProductionEvent) {}
    override fun relDiscarded(event: RelOptListener.RelDiscardedEvent) {}
    override fun relChosen(event: RelOptListener.RelChosenEvent) {}

    companion object {
        /**
         * Rule descriptions carry their configuration —
         * "EnumerableProjectRule(in:LOGICAL,out:ENUMERABLE)" — which is more than a label needs and
         * long enough to wreck a report column.
         */
        fun shortName(r: RelOptRule): String {
            val d = r.toString().substringBefore('(')
            return "rule:" + d.substringAfterLast('.')
        }
    }
}

/**
 * Wraps a [Program] so the listener is attached to the planner the framework built for us.
 *
 * A second piece of friction, and a smaller one: the planner is created inside `PlannerImpl` and
 * never handed to the caller. The program is the one place a third party is given a reference to
 * it, so that is where the listener has to be installed.
 */
fun listeningProgram(inner: Program, listener: RelOptListener): Program =
    Program { planner: RelOptPlanner, rel: RelNode, traits: RelTraitSet, materializations, lattices ->
        planner.addListener(listener)
        inner.run(planner, rel, traits, materializations, lattices)
    }

@Suppress("unused")
private fun unusedRelOptNode(n: RelOptNode) = n

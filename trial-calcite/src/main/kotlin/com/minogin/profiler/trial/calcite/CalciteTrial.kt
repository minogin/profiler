package com.minogin.profiler.trial.calcite

import com.minogin.profiler.trial.analyzeJfr
import com.minogin.profiler.trial.recordExecutionSamples
import com.minogin.profiler.Profiler
import com.minogin.profiler.Report
import com.minogin.profiler.SpanHistogram
import com.minogin.profiler.coarse
import com.minogin.profiler.duration
import com.minogin.profiler.op
import org.apache.calcite.DataContext
import org.apache.calcite.adapter.enumerable.EnumerableConvention
import org.apache.calcite.adapter.enumerable.EnumerableRules
import org.apache.calcite.linq4j.Enumerable
import org.apache.calcite.linq4j.Linq4j
import org.apache.calcite.plan.RelOptRule
import org.apache.calcite.plan.volcano.AbstractConverter
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.logical.LogicalJoin
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rel.rules.JoinAssociateRule
import org.apache.calcite.rel.rules.JoinCommuteRule
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.ScannableTable
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.schema.Statistic
import org.apache.calcite.schema.Statistics
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.tools.Frameworks
import org.apache.calcite.tools.Planner
import org.apache.calcite.tools.Programs
import org.apache.calcite.tools.RuleSets
import java.nio.file.Path
import java.util.Locale

/**
 * The trial workload: Apache Calcite planning a hard query, with no data anywhere.
 *
 * The tables are declarations — a row type and a row count, nothing more. Planning never touches a
 * row, so the whole thing is CPU and memory and reproduces from a single command, which is exactly
 * why this candidate was picked over anything needing a database underneath it.
 */

/**
 * A table that exists only as a shape and a size.
 *
 * [ScannableTable] is implemented because the enumerable convention refuses to produce a physical
 * scan for a table it cannot see a way to read — a plain [AbstractTable] left every scan subset
 * empty and planning failed. The scan method is never called: nothing here executes a plan.
 */
private class DeclaredTable(
    private val columns: List<Pair<String, SqlTypeName>>,
    private val rows: Double,
) : AbstractTable(), ScannableTable {

    override fun scan(root: DataContext): Enumerable<Array<Any?>> = Linq4j.emptyEnumerable()

    override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
        val b = typeFactory.builder()
        for ((name, type) in columns) b.add(name, type)
        return b.build()
    }

    // Row counts differ per table on purpose. With every table the same size the cost model cannot
    // tell one join order from another, and the search degenerates into something a real planner
    // never does.
    override fun getStatistic(): Statistic = Statistics.of(rows, emptyList())
}

/**
 * A chain of tables joined key to key, which is the shape that makes join enumeration expensive:
 * with commute and associate enabled, the number of orderings the planner can reach grows far
 * faster than the number of tables.
 */
class ChainSchema(val tableCount: Int) {

    val schema: SchemaPlus = Frameworks.createRootSchema(true).also { root ->
        for (i in 0 until tableCount) {
            root.add(
                "T$i",
                DeclaredTable(
                    listOf(
                        "ID" to SqlTypeName.INTEGER,
                        "FK" to SqlTypeName.INTEGER,
                        "A" to SqlTypeName.INTEGER,
                        "B" to SqlTypeName.VARCHAR,
                        "C" to SqlTypeName.DOUBLE,
                    ),
                    // Sizes spread over an order of magnitude, deterministic, no two alike.
                    rows = 1000.0 * (1 + (i * 7) % 13) + i,
                )
            )
        }
    }

    /** A chain join over every table, with a few predicates so the filter rules have work to do. */
    fun sql(): String = buildString {
        append("SELECT ")
        append((0 until tableCount).joinToString(", ") { "T$it.ID AS ID$it, T$it.C AS C$it" })
        append(" FROM T0")
        for (i in 1 until tableCount) append(" JOIN T$i ON T${i - 1}.ID = T$i.FK")
        append(" WHERE T0.A > 10")
        for (i in 1 until tableCount step 3) append(" AND T$i.A < 900")
        append(" AND T${tableCount - 1}.C > 0.5")
    }
}

/**
 * The rule set. Given explicitly rather than taken from Programs.standard() so that the search
 * space is a stated property of the experiment: commute and associate are what turn a chain join
 * into a planning problem, and their presence should not be an accident of a default.
 */
fun rules(associate: Boolean, mergeJoin: Boolean = true): List<RelOptRule> = listOfNotNull(
    // Without this the planner cannot satisfy a trait it asked for itself: the merge-join rule
    // requests sorted inputs and nothing else in the set knows how to produce a sort on demand.
    // Leaving it out failed immediately at three tables, with 29 empty subsets all of them sorted.
    AbstractConverter.ExpandConversionRule.INSTANCE,
    // Bound to LogicalJoin rather than Join. The stock CoreRules constants match any Join, and
    // once the enumerable rules have produced an EnumerableHashJoin the associate rule matches
    // that too and Calcite throws: "is a PhysicalNode, which is not allowed in JoinAssociateRule".
    JoinCommuteRule.Config.DEFAULT.withOperandFor(LogicalJoin::class.java).toRule(),
    // The knob that decides whether this is a hard planning problem or a trivial one. With
    // associate on, four tables already cost 20 s; with only commute, the curve is flat.
    if (associate) JoinAssociateRule.Config.DEFAULT.withOperandFor(LogicalJoin::class.java).toRule() else null,
    CoreRules.FILTER_INTO_JOIN,
    CoreRules.JOIN_PUSH_EXPRESSIONS,
    CoreRules.FILTER_MERGE,
    CoreRules.PROJECT_MERGE,
    CoreRules.PROJECT_REMOVE,
    CoreRules.FILTER_PROJECT_TRANSPOSE,
    CoreRules.PROJECT_JOIN_TRANSPOSE,
) + EnumerableRules.ENUMERABLE_RULES.filter {
    // The knob the profile suggested. Dropping the merge-join rule is the experiment that tests
    // what the labels claimed; it is not otherwise part of the workload's definition.
    mergeJoin || it !== EnumerableRules.ENUMERABLE_MERGE_JOIN_RULE
}

/**
 * The four phases of planning, as labels.
 *
 * Registered once at class initialisation, which is what the profiler asks for: registration takes
 * a map lookup and has no business on a hot path.
 */
object Phases {
    val parse = Profiler.register("phase:parse")
    val validate = Profiler.register("phase:validate")
    val sqlToRel = Profiler.register("phase:sqlToRel")
    val optimise = Profiler.register("phase:optimise")
}

/**
 * The same two boundaries again, in the coarse tier.
 *
 * A plan is what this trial has always been about — `plan.md` records *"one plan is a coarse
 * operation"* as where the tier's shape came from — and until now nothing measured one. The fine
 * labels above say which *phase* held the thread-time; they cannot say how long a plan took, and a
 * planner's user cares about exactly that.
 *
 * `optimise` carries both labels on purpose. Fine, it is a share of thread-time; coarse, it is a
 * duration with a distribution, nested inside `plan`. Having one operation under both is the
 * cross-check the two tiers otherwise cannot give each other.
 */
object CoarsePhases {
    val plan = Profiler.registerCoarse("plan")
    val optimise = Profiler.registerCoarse("optimise")
}

/**
 * One planning cycle, optionally labelled.
 *
 * The labelled and unlabelled paths are the same method rather than two, so that a throughput
 * comparison between them is not really a comparison of two different inlining trees.
 */
class PlanRunner(
    val tables: Int,
    val associate: Boolean,
    val labels: Boolean,
    val mergeJoin: Boolean = true,
    /** Attach a listener that does nothing, to price Calcite's half of the placement mechanism. */
    val emptyListener: Boolean = false,
    /** Whether a plan and its optimise phase also carry coarse labels. See [CoarsePhases]. */
    val coarse: Boolean = false,
) {

    private val chain = ChainSchema(tables)
    val sql: String = chain.sql()
    private val ruleList = rules(associate, mergeJoin)
    private val labeller = when {
        labels -> RuleLabeller(ruleList)
        emptyListener -> NullListener()
        else -> null
    }

    private fun newPlanner(): Planner {
        val program = Programs.of(RuleSets.ofList(ruleList))
        return Frameworks.getPlanner(
            Frameworks.newConfigBuilder()
                .defaultSchema(chain.schema)
                .programs(if (labeller != null) listeningProgram(program, labeller) else program)
                .build()
        )
    }

    fun planOnce(): RelNode {
        val p = newPlanner()
        try {
            if (!labels) {
                val parsed = p.parse(sql)
                val validated = p.validate(parsed)
                val rel = p.rel(validated).rel
                return p.transform(0, p.emptyTraitSet.replace(EnumerableConvention.INSTANCE), rel)
            }
            val parsed = op(Phases.parse) { p.parse(sql) }
            val validated = op(Phases.validate) { p.validate(parsed) }
            val rel = op(Phases.sqlToRel) { p.rel(validated).rel }
            // The coarse label goes outside the fine one, so its span is the whole of the phase
            // including everything the rules did — which is the quantity a fine label cannot report
            // and the loop's own stopwatch can check.
            return if (!coarse) op(Phases.optimise) {
                p.transform(0, p.emptyTraitSet.replace(EnumerableConvention.INSTANCE), rel)
            } else coarse(CoarsePhases.optimise) {
                op(Phases.optimise) {
                    p.transform(0, p.emptyTraitSet.replace(EnumerableConvention.INSTANCE), rel)
                }
            }
        } finally {
            p.close()
        }
    }
}

private fun millis(nanos: Long) = nanos / 1e6

/** Holds a reference so nothing about the plan can be optimised away. */
object Sink {
    @Volatile
    var last: Any? = null

    fun keep(x: Any?) {
        last = x
    }
}

/** Plans [times] times and returns each duration in nanoseconds. */
private fun repeatPlan(runner: PlanRunner, times: Int): LongArray {
    val out = LongArray(times)
    for (i in 0 until times) {
        val t0 = System.nanoTime()
        val plan = runner.planOnce()
        out[i] = System.nanoTime() - t0
        Sink.keep(plan)
    }
    return out
}

/**
 * How planning time grows with the number of joined tables. The interesting claim about this
 * candidate — planning time grows badly with query complexity — is a claim about a curve, so the
 * first thing to produce is the curve.
 */
private fun scale(from: Int, to: Int, capMillis: Long, associate: Boolean) {
    println(String.format(Locale.ROOT, "%8s %14s %14s %12s", "tables", "first (ms)", "warm (ms)", "vs previous"))
    println("-".repeat(52))
    var previous = Double.NaN
    for (n in from..to) {
        val runner = PlanRunner(n, associate, labels = false)
        val t0 = System.nanoTime()
        runner.planOnce()
        val first = System.nanoTime() - t0
        // A handful of repeats, so the number reported is not dominated by class loading and the
        // interpreter. The minimum is the honest one for a single-shot cost.
        val warm = if (millis(first) < capMillis) repeatPlan(runner, 5).min() else first
        val w = millis(warm)
        println(
            String.format(
                Locale.ROOT, "%8d %14.1f %14.1f %12s",
                n, millis(first), w,
                if (previous.isNaN()) "-" else String.format(Locale.ROOT, "%.2fx", w / previous)
            )
        )
        previous = w
        if (millis(first) > capMillis) {
            println("stopping: a single plan passed $capMillis ms")
            return
        }
    }
}

/**
 * Plans in a loop for a fixed wall-clock span. This is what a profiler gets pointed at.
 *
 * The warm-up is not optional. Planning is deeply polymorphic — megamorphic call sites everywhere,
 * thousands of classes loaded on first use — so a profile of the first plan describes the
 * interpreter and the class loader rather than the planner.
 */
private fun load(
    tables: Int,
    seconds: Int,
    associate: Boolean,
    warmups: Int,
    jfr: String?,
    labels: Boolean,
    sampler: Boolean,
    mergeJoin: Boolean,
    step: Double,
    coarse: Boolean = false,
) {
    val runner = PlanRunner(tables, associate, labels, mergeJoin, coarse = coarse)
    println(
        "$tables tables, associate=$associate, labels=$labels, sampler=$sampler; " +
                "warm-up $warmups plans, then planning for $seconds s"
    )

    val warm = repeatPlan(runner, warmups)
    println("warm-up: " + warm.joinToString(", ") { String.format(Locale.ROOT, "%.1f ms", millis(it)) })

    val recording = if (jfr != null) recordExecutionSamples(1) else null
    if (sampler) Profiler.start(stepMillis = step)

    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    val times = ArrayList<Long>()
    val started = System.nanoTime()
    // Calcite's "after" notification is not in a finally block: a rule that throws leaves the
    // label set and every later sample is billed to it. Silent misattribution, which is exactly
    // the failure mode this design keeps having to guard against, so it is checked rather than
    // assumed. The span stack must be empty between plans.
    var leaked = 0
    while (System.nanoTime() < deadline) {
        val t0 = System.nanoTime()
        // The coarse label brackets exactly what the stopwatch above brackets, which is what makes
        // `times` a truth for it rather than a rough comparison: the two measure the same interval
        // with the same clock, so what they disagree by is the profiler and nothing else.
        Sink.keep(if (runner.coarse) coarse(CoarsePhases.plan) { runner.planOnce() } else runner.planOnce())
        times += System.nanoTime() - t0
        // The library's check now, not the trial's own. A plan boundary is the point at which this
        // thread cannot legitimately be inside a rule, which is exactly what expectBalanced wants.
        if (labels && !Profiler.expectBalanced()) leaked++
    }
    val elapsed = System.nanoTime() - started
    if (labels) println(if (leaked == 0) "span stack balanced after every plan" else "WARNING: label leaked after $leaked of ${times.size} plans")

    val report = if (sampler) Profiler.stop() else null
    if (recording != null) {
        recording.stop()
        recording.dump(Path.of(jfr))
        recording.close()
        println("JFR written to $jfr")
    }

    println(
        String.format(
            Locale.ROOT, "%,d plans in %.1f s - %.1f ms each (min %.1f, max %.1f)",
            times.size, elapsed / 1e9, millis(elapsed) / times.size,
            millis(times.min()), millis(times.max())
        )
    )
    println(String.format(Locale.ROOT, "throughput: %.4f plans/s", times.size / (elapsed / 1e9)))
    if (report != null) println("\n" + report.render())
    if (report != null && runner.coarse) checkPlanSpans(report, times)
}

/**
 * The coarse tier against a stopwatch this harness was already keeping.
 *
 * The bench can do this because we wrote the bench. Here the *target* is Calcite and the timing is
 * the trial's own loop, which brackets `planOnce()` with two `nanoTime` calls and has done since
 * before the coarse tier existed. So the check is not "does the distribution look plausible" but
 * "does the profiler's p90 equal the p90 of the very same intervals" — on foreign code.
 *
 * Compared through the profiler's own histogram on both sides. Comparing a quantised percentile
 * against an exact one measures the quantiser, which is specified; the exact figure is printed
 * beside it so a reader can see the quantisation, and it is not what is being checked.
 */
private fun checkPlanSpans(report: Report, times: List<Long>) {
    val plan = report.coarse.firstOrNull { it.name == "plan" } ?: return
    if (plan.count == 0L || times.isEmpty()) return
    val sorted = times.sorted()
    val ref = LongArray(SpanHistogram.BUCKETS)
    for (v in sorted) ref[SpanHistogram.bucketOf(v)]++
    val n = sorted.size.toLong()
    fun exact(p: Double) = sorted[minOf(sorted.size - 1, maxOf(0, Math.ceil(p * sorted.size).toInt() - 1))].toDouble()

    println("\n--- the coarse tier against the trial's own stopwatch ---")
    println(
        String.format(
            Locale.ROOT, "  %-8s %11s %11s %8s %12s", "plan", "profiler", "harness", "diff", "harness,exact"
        )
    )
    println(
        String.format(
            Locale.ROOT, "  %-8s %11d %11d %+7.2f%%",
            "count", plan.count, n, (plan.count.toDouble() / n - 1) * 100
        )
    )
    val rows = listOf(
        Triple("mean", plan.meanSpanNanos, sorted.sumOf { it } .toDouble() / n),
        Triple("p50", plan.percentileNanos(0.50), SpanHistogram.percentile(ref, n, 0.50)),
        Triple("p90", plan.percentileNanos(0.90), SpanHistogram.percentile(ref, n, 0.90)),
        Triple("p99", plan.percentileNanos(0.99), SpanHistogram.percentile(ref, n, 0.99)),
    )
    val exacts = listOf(sorted.sumOf { it }.toDouble() / n, exact(0.50), exact(0.90), exact(0.99))
    var worst = 0.0
    for ((i, row) in rows.withIndex()) {
        val (name, got, want) = row
        val rel = if (want == 0.0) 0.0 else got / want - 1
        if (kotlin.math.abs(rel) > worst) worst = kotlin.math.abs(rel)
        println(
            String.format(
                Locale.ROOT, "  %-8s %11s %11s %+7.2f%% %12s",
                name, duration(got), duration(want), rel * 100, duration(exacts[i])
            )
        )
    }
    println(
        String.format(
            Locale.ROOT, "  worst %.2f%%, tolerance %.1f%% (one histogram bucket)%s",
            worst * 100, SpanHistogram.PRECISION * 100,
            if (worst > SpanHistogram.PRECISION) "   OUT OF TOLERANCE" else "   OK"
        )
    )
}

/**
 * Two configurations, interleaved.
 *
 * Not one after the other. This machine's clock swings by 2x inside a run, tracking load and the
 * turbo budget, and the same mistake has already been made three times in this project: measure
 * sequentially and the drift lands on the comparison as if it were the effect. Alternating gives
 * both configurations the same conditions, on average.
 *
 * Each plan here costs seconds, so there are few repeats and the spread matters more than the
 * mean. Both are printed.
 */
private fun compare(nameA: String, a: PlanRunner, nameB: String, b: PlanRunner, rounds: Int) {
    println("interleaved: $nameA vs $nameB, $rounds rounds each")
    // Warm both, so neither pays for the other's class loading.
    repeatPlan(a, 1)
    repeatPlan(b, 1)

    val ta = ArrayList<Long>()
    val tb = ArrayList<Long>()
    for (r in 0 until rounds) {
        // Order swapped every round. Interleaving alone is not enough: with a fixed A-then-B order
        // every round, anything that depends on *position* — a collection that always lands in the
        // first slot, a clock that has just come up from idle — is charged entirely to A. Measured:
        // a fixed order reported the instrumented configuration as 6.6% faster than the clean one,
        // in all four rounds.
        if (r % 2 == 0) {
            ta += repeatPlan(a, 1)[0]
            tb += repeatPlan(b, 1)[0]
        } else {
            tb += repeatPlan(b, 1)[0]
            ta += repeatPlan(a, 1)[0]
        }
        println(String.format(Locale.ROOT, "  round %d: %s %.1f ms, %s %.1f ms", r + 1, nameA, millis(ta.last()), nameB, millis(tb.last())))
    }

    fun line(name: String, t: List<Long>) = String.format(
        Locale.ROOT, "%-28s n=%d  mean %8.1f ms   min %8.1f ms   max %8.1f ms",
        name, t.size, t.map { millis(it) }.average(), millis(t.min()), millis(t.max())
    )

    println()
    println(line(nameA, ta))
    println(line(nameB, tb))
    val meanRatio = tb.map { millis(it) }.average() / ta.map { millis(it) }.average()
    val minRatio = millis(tb.min()) / millis(ta.min())
    println(String.format(Locale.ROOT, "%s / %s: %.3fx by mean, %.3fx by minimum", nameB, nameA, meanRatio, minRatio))
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

    when {
        opt["scale"] != null -> scale(
            opt["from"]?.toInt() ?: 3,
            opt["to"]?.toInt() ?: 14,
            opt["cap"]?.toLong() ?: 20_000L,
            opt["associate"] == "true",
        )

        opt["ab"] == "labels" -> {
            val t = opt["tables"]?.toInt() ?: 4
            val assoc = opt["associate"] == "true"
            val mj = opt["mergejoin"] != "false"
            compare(
                "listener-only", PlanRunner(t, assoc, labels = false, mergeJoin = mj, emptyListener = true),
                "labelled", PlanRunner(t, assoc, labels = true, mergeJoin = mj),
                opt["rounds"]?.toInt() ?: 5,
            )
        }

        opt["ab"] == "tool" -> {
            val t = opt["tables"]?.toInt() ?: 4
            val assoc = opt["associate"] == "true"
            val mj = opt["mergejoin"] != "false"
            compare(
                "plain", PlanRunner(t, assoc, labels = false, mergeJoin = mj),
                "labelled", PlanRunner(t, assoc, labels = true, mergeJoin = mj),
                opt["rounds"]?.toInt() ?: 5,
            )
        }

        opt["ab"] == "listener" -> {
            val t = opt["tables"]?.toInt() ?: 4
            val assoc = opt["associate"] == "true"
            val mj = opt["mergejoin"] != "false"
            compare(
                "plain", PlanRunner(t, assoc, labels = false, mergeJoin = mj),
                "listener-only", PlanRunner(t, assoc, labels = false, mergeJoin = mj, emptyListener = true),
                opt["rounds"]?.toInt() ?: 5,
            )
        }

        opt["ab"] == "merge" -> {
            val t = opt["tables"]?.toInt() ?: 4
            val assoc = opt["associate"] == "true"
            compare(
                "with-mergejoin", PlanRunner(t, assoc, labels = false, mergeJoin = true),
                "no-mergejoin", PlanRunner(t, assoc, labels = false, mergeJoin = false),
                opt["rounds"]?.toInt() ?: 5,
            )
        }

        opt["plan"] != null -> {
            val t = opt["tables"]?.toInt() ?: 4
            val assoc = opt["associate"] == "true"
            for (mj in listOf(true, false)) {
                val runner = PlanRunner(t, assoc, labels = false, mergeJoin = mj)
                val t0 = System.nanoTime()
                val plan = runner.planOnce()
                val took = System.nanoTime() - t0
                println("=".repeat(78))
                println(String.format(Locale.ROOT, "mergeJoin=%s, planned in %.1f ms", mj, millis(took)))
                println("=".repeat(78))
                print(
                    org.apache.calcite.plan.RelOptUtil.dumpPlan(
                        "", plan,
                        org.apache.calcite.sql.SqlExplainFormat.TEXT,
                        org.apache.calcite.sql.SqlExplainLevel.ALL_ATTRIBUTES,
                    )
                )
            }
        }

        opt["analyze"] != null -> analyzeJfr(
            Path.of(opt["analyze"]!!),
            opt["top"]?.toInt() ?: 25,
            opt["collapsed"]?.let { Path.of(it) },
        )

        else -> load(
            tables = opt["tables"]?.toInt() ?: 10,
            seconds = opt["seconds"]?.toInt() ?: 20,
            associate = opt["associate"] == "true",
            warmups = opt["warmups"]?.toInt() ?: 3,
            jfr = opt["jfr"],
            labels = opt["labels"] == "true",
            sampler = opt["sampler"] == "true",
            mergeJoin = opt["mergejoin"] != "false",
            step = opt["step"]?.toDouble() ?: 1.0,
            coarse = opt["coarse"] == "true",
        )
    }
}

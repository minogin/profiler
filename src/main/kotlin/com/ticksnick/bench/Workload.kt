package com.ticksnick.bench

import com.ticksnick.*
import java.util.Random

const val OP_COUNT = 20

/**
 * A bench operation: its own work of [selfNanos] plus the nested calls in [children].
 * The own work spins in the busy loop, the children run after it.
 */
class OpSpec(val name: String, val selfNanos: Double, vararg val children: Int)

/**
 * Catalogue of 20 operations. Self durations are skewed by two orders of magnitude: 20 ns for
 * nodeLookup against 2000 ns for serialize. Nesting goes four levels deep:
 * traverse -> frontierStep -> expandNode -> markVisited.
 */
val OPS = arrayOf(
    /*  0 */ OpSpec("nodeLookup", 20.0),
    /*  1 */ OpSpec("hashProbe", 25.0),
    /*  2 */ OpSpec("edgeScan", 45.0),
    /*  3 */ OpSpec("degreeCheck", 70.0),
    /*  4 */ OpSpec("markVisited", 110.0),
    /*  5 */ OpSpec("pushFrontier", 170.0),
    /*  6 */ OpSpec("popFrontier", 260.0),
    /*  7 */ OpSpec("filterNode", 400.0),
    /*  8 */ OpSpec("scoreNode", 620.0),
    /*  9 */ OpSpec("compact", 950.0),
    /* 10 */ OpSpec("rehash", 1400.0),
    /* 11 */ OpSpec("serialize", 2000.0),
    /* 12 */ OpSpec("expandNode", 35.0, 0, 2, 4),
    /* 13 */ OpSpec("visitNeighbor", 30.0, 1, 3),
    /* 14 */ OpSpec("frontierStep", 80.0, 5, 6, 12),
    /* 15 */ OpSpec("rankBatch", 120.0, 7, 8),
    /* 16 */ OpSpec("maintain", 210.0, 9, 10),
    /* 17 */ OpSpec("checkpoint", 150.0, 11, 16),
    /* 18 */ OpSpec("tinyStep", 20.0),
    /* 19 */ OpSpec("traverse", 60.0, 13, 14),
)

/**
 * Registers the catalogue with the profiler, checking that ids come back matching array indices.
 *
 * The bench indexes [OPS] by operation id throughout, which is only valid because it registers its
 * operations first and in order. A library user has no such luxury and must keep the id `register`
 * hands back.
 */
fun registerOperations() {
    for (id in 0 until OP_COUNT) {
        val assigned = Profiler.registerFine(OPS[id].name).id
        check(assigned == id) { "${OPS[id].name} got id $assigned, expected $id" }
    }
}

/** How many times the worker loop calls an operation over one pass of the schedule. */
val ROOT_WEIGHTS = IntArray(OP_COUNT).also {
    it[18] = 2000  // very frequent and very short
    it[19] = 900
    it[0] = 600
    it[13] = 300
    it[15] = 180
    it[7] = 80
    it[16] = 24
    it[17] = 8
    it[11] = 4     // very rare and very long
}

const val SCHEDULE_SIZE = 4096

/**
 * The bench operations promoted to the coarse tier, checked against the boundary rather than picked.
 *
 * The rule in [profiler.md](../../../../../../../docs/profiler.md) is `d >= max(800 ns, 4 us x share)`,
 * which comes from what the instrumentation costs per execution and not from any opinion about size.
 * Against this catalogue:
 *
 * | operation | inclusive | share | threshold | |
 * |---|---|---|---|---|
 * | `checkpoint` | 4710 ns | 3.0% | 800 ns | in — and it contains `maintain`, so nesting is exercised |
 * | `maintain` | 2560 ns | 6.6% | 800 ns | in — 24 root calls and 8 nested per pass |
 * | `rankBatch` | 1140 ns | 16.4% | 656 ns | in — the frequent one |
 * | `traverse` | 905 ns | 65.2% | **2610 ns** | **out**, and deliberately so |
 *
 * `traverse` is the negative control and the reason the fine tier exists: it is long enough to look
 * promotable and holds so much of the run that a ~40 ns context per execution would cost more than
 * the whole coarse tier is allowed to. It keeps its fine label and nothing else.
 */
val COARSE_OPS = intArrayOf(15, 16, 17)

/** Coarse type per operation id, or -1. Filled by [registerCoarseOperations]. */
val coarseTypeOf = IntArray(OP_COUNT) { -1 }

/** The coarse type of a whole request. See [REQUEST_CHUNKS]. */
var requestType: Int = -1
    private set

/**
 * How many chunks of [SCHEDULE_SIZE]-driven work one `request` contains — 1 to 16, seeded.
 *
 * A request is the bench's only millisecond-scale coarse operation, and it exists because
 * percentiles of a distribution with no spread describe nothing. One chunk is 256 root calls, about
 * 78 us, so a request runs from roughly 78 us to 1.25 ms and p50, p90 and p99 are genuinely
 * different numbers. The truth stays exact: the worker times every request itself.
 */
val REQUEST_CHUNKS = IntArray(64).also {
    val rnd = Random(20260828L)
    for (i in it.indices) it[i] = 1 + rnd.nextInt(16)
}

/**
 * Registers the coarse catalogue. Separate from [registerOperations] because the coarse tier is a
 * separate id space, and because the bench can run entirely without it.
 */
/**
 * The operation the boundary rule rejects, labelled on purpose. `--coarse=violate`.
 *
 * `traverse` is 905 ns and holds 65.2% of the run, so condition 1 demands 2610 ns and it misses by
 * a factor of three. Labelling it is how the floor check gets exercised end to end rather than only
 * in a unit test: the report must name it, say what a context costs as a fraction of it, and say
 * what to do instead. A check nobody has watched fire is a check nobody knows is wired up.
 */
const val COARSE_VIOLATOR = 19

fun registerCoarseOperations(violate: Boolean = false) {
    for (id in COARSE_OPS) coarseTypeOf[id] = Profiler.registerCoarse(OPS[id].name).id
    if (violate) coarseTypeOf[COARSE_VIOLATOR] = Profiler.registerCoarse(OPS[COARSE_VIOLATOR].name).id
    requestType = Profiler.registerCoarse("request").id
}

/**
 * subtree[root][op] — how many times op runs during one call of root.
 * Also catches a cycle in the graph: a bench with a cycle has no finite truth.
 */
fun subtreeCounts(): Array<LongArray> {
    val memo = arrayOfNulls<LongArray>(OP_COUNT)
    val color = IntArray(OP_COUNT)

    fun visit(id: Int): LongArray {
        memo[id]?.let { return it }
        check(color[id] == 0) { "cycle in the operation graph at ${OPS[id].name}" }
        color[id] = 1
        val c = LongArray(OP_COUNT)
        c[id] = 1
        for (ch in OPS[id].children) {
            val sub = visit(ch)
            for (k in 0 until OP_COUNT) c[k] += sub[k]
        }
        color[id] = 2
        memo[id] = c
        return c
    }

    return Array(OP_COUNT) { visit(it) }
}

/**
 * Expands root call counts into total calls per operation through the graph. Counters sit on
 * root calls only; everything nested is derived here, exactly, with no counter in the hot path.
 */
fun expandCalls(rootCalls: LongArray, subtree: Array<LongArray>): LongArray {
    val total = LongArray(OP_COUNT)
    for (root in 0 until OP_COUNT) {
        val n = rootCalls[root]
        if (n == 0L) continue
        val sub = subtree[root]
        for (op in 0 until OP_COUNT) total[op] += n * sub[op]
    }
    return total
}

/**
 * Mean self duration of an operation call, weighted by how often each one is actually called.
 *
 * Weighted, not a plain average over the catalogue: the long operations are rare — tinyStep alone
 * is 2000 calls in every 4096 — so the unweighted mean is 339 ns while the operation a hook
 * actually lands on averages 88 ns. Using the unweighted figure makes any per-call overhead look
 * four times cheaper than it is.
 */
fun meanOperationNanos(): Double {
    val subtree = subtreeCounts()
    var calls = 0L
    var nanos = 0.0
    for (root in 0 until OP_COUNT) {
        val weight = ROOT_WEIGHTS[root]
        if (weight == 0) continue
        for (op in 0 until OP_COUNT) {
            calls += weight.toLong() * subtree[root][op]
            nanos += weight * subtree[root][op] * OPS[op].selfNanos
        }
    }
    return nanos / calls
}

/** Total time of one call of an operation together with its children, per configuration. */
fun inclusiveNanos(subtree: Array<LongArray>): DoubleArray = DoubleArray(OP_COUNT) { id ->
    var sum = 0.0
    for (k in 0 until OP_COUNT) sum += subtree[id][k] * OPS[k].selfNanos
    sum
}

/**
 * The workload. The busy-loop iteration count per operation is recomputed on every calibration,
 * in place in the array; the threads read it after a barrier.
 */
class Workload(
    /**
     * Whether the coarse labels are placed as well as the fine ones.
     *
     * A switch of its own, for the same reason `--labels` and `--sampler` are separate: the coarse
     * tier costs tens of nanoseconds per execution where the fine hook costs two, and a cost that is
     * always on can only be priced by argument. It also keeps the observer-effect A/B measuring what
     * it has always measured — the fine hook against its own absence — rather than silently becoming
     * a measurement of both tiers at once.
     */
    val coarseLabels: Boolean = false,
) {
    val iters = IntArray(OP_COUNT)
    val children = Array(OP_COUNT) { OPS[it].children }
    val schedule = buildSchedule()

    fun applyCalibration(cal: Calibration) {
        for (i in 0 until OP_COUNT) iters[i] = cal.itersFor(OPS[i].selfNanos)
    }

    /** State flows in and out: the sink at the end of the chain keeps the loop alive. */
    fun exec(id: Int, state: Long): Long {
        var s = burn(state, iters[id])
        val ch = children[id]
        var i = 0
        while (i < ch.size) {
            s = exec(ch[i], s)
            i++
        }
        return s
    }

    /** The same thing with the profiler hook around it. The uninstrumented [exec] stays for the
     *  observer-effect comparison: the two differ by the hook and by nothing else. */
    fun execLabeled(id: Int, state: Long): Long {
        val ct = if (coarseLabels) coarseTypeOf[id] else -1
        // The coarse label goes *outside* the fine one, so the span it measures is the operation's
        // inclusive duration — which is exactly the quantity the truth knows, and exactly what a
        // fine label can never report.
        // FineOp/CoarseOp erase to an int, so wrapping here costs nothing: the bench keeps its
        // ids in IntArrays because it indexes the catalogue by them, and hands the handle over
        // at the call. Measured in the generated code — no allocation, no extra load.
        return if (ct < 0) execFine(id, state) else op(CoarseOp(ct)) { execFine(id, state) }
    }

    private fun execFine(id: Int, state: Long): Long = op(FineOp(id)) {
        var s = burn(state, iters[id])
        val ch = children[id]
        var i = 0
        while (i < ch.size) {
            s = execLabeled(ch[i], s)
            i++
        }
        s
    }

    private fun buildSchedule(): IntArray {
        val sch = IntArray(SCHEDULE_SIZE)
        var p = 0
        for (id in 0 until OP_COUNT) repeat(ROOT_WEIGHTS[id]) { sch[p++] = id }
        require(p == SCHEDULE_SIZE) { "weights sum to $p, expected $SCHEDULE_SIZE" }
        val rnd = Random(20260819L)
        for (i in sch.indices.reversed()) {
            val j = rnd.nextInt(i + 1)
            val t = sch[i]; sch[i] = sch[j]; sch[j] = t
        }
        return sch
    }
}

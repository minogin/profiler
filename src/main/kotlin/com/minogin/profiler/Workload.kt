package com.minogin.profiler

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
class Workload {
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
    fun execLabeled(id: Int, state: Long): Long = op(id) {
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

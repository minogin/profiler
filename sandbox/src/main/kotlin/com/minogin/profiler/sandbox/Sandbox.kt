package com.minogin.profiler.sandbox

import com.minogin.profiler.Profiler
import com.minogin.profiler.coarse
import com.minogin.profiler.op

/**
 * A place to **use** the profiler rather than to test it.
 *
 * **This is not a trial, and nothing measured here is evidence.** The trials — Calcite, Lucene,
 * Netty, PostgreSQL — are somebody else's codebase, and that is exactly what makes their numbers
 * mean something: the code was not written with this tool in mind, so it cannot have been shaped to
 * suit it. Anything in this file was written by someone who already knows how the profiler works,
 * which is the same objection that kept the graph-traversal bench and the coroutines module out of
 * the plan ([ideas.md](../../../../../../docs/ideas.md) items 23 and 25).
 *
 * **What it is for is the other half of the question.** The trials answer *are the numbers right*.
 * This answers *is the thing usable* — whether a label goes where you want it, whether the report
 * reads without the documentation open, whether a warning lands or looks like noise. That question
 * needs a person using it, not a workload proving it.
 *
 * Friction found here goes in [docs/sandbox.md](../../../../../../docs/sandbox.md), and graduates
 * into `ideas.md` when it becomes something to do.
 *
 * Run with `gradlew :sandbox:run` — with the colon, or Gradle matches `run` in every module and
 * launches the bench and all four trials as well.
 */
fun main() {
    val request = Profiler.registerCoarse("request")
    val work = Profiler.register("work")

    Profiler.start(stepMillis = 1.0)

    val deadline = System.nanoTime() + 3_000_000_000L
    var sink = 0L
    while (System.nanoTime() < deadline) {
        coarse(request) {
            sink += op(work) { placeholder() }
        }
    }

    println(Profiler.stop().render())
    println("(sink: $sink)")
}

/**
 * Replace this with whatever you actually want to look at.
 *
 * It is deliberately trivial and deliberately not a benchmark: the point of this module is your
 * workload, not one of mine, and anything more elaborate here would quietly become the thing being
 * measured.
 */
private fun placeholder(): Long {
    var s = 0L
    for (i in 0 until 20_000) s = s * 31 + i
    return s
}

package com.minogin.profiler.trial

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/**
 * The conventional profile, for comparison.
 *
 * JFR rather than async-profiler because async-profiler has no Windows build. The mechanism is the
 * same one a flame graph is made of — periodic stack sampling of running threads — so the question
 * the trial asks ("does the flame graph disappoint?") gets a fair hearing.
 *
 * The recording is driven from inside the process rather than by a `-XX:StartFlightRecording` flag
 * so that it covers exactly the window we measure, and exactly the window our own sampler will
 * later cover. A recording started at JVM launch would include class loading, the interpreter and
 * the warm-up, and those would dominate the answer.
 */
fun recordExecutionSamples(periodMillis: Long): Recording {
    val r = Recording()
    // Nothing is enabled by default in a bare Recording, which is what we want: only the event
    // that makes a flame graph, so the recording measures the profile and not the profiler.
    r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(periodMillis))
    // To disk, with a cap far above anything a trial produces. Without this the recording keeps its
    // events in a wrapping in-memory buffer, so a long run silently discards its early samples and
    // the count stops growing with the run — which reads exactly like the sampler throttling and is
    // not. Caught on the Netty trial: 1,071 samples at twenty seconds and 1,750 at sixty, against
    // 3,887 and 12,051 once written to disk. The delivered *rate* is flat either way (194/s and
    // 201/s), so the shares an earlier trial computed are still over a fair sample of a steady
    // workload — but any sample *count* recorded before this fix is a floor, not the number JFR
    // produced.
    r.isToDisk = true
    r.maxSize = 512L * 1024 * 1024
    r.start()
    return r
}

/** One frame as the analysis needs it: a method, and the line it was on. */
private fun frameName(e: RecordedEvent, index: Int): String {
    val frames = e.stackTrace?.frames ?: return "<no stack>"
    if (index >= frames.size) return "<truncated>"
    val f = frames[index]
    val m = f.method
    val type = m.type.name
    return "${type.substringAfterLast('.')}.${m.name}"
}

private fun fullFrame(e: RecordedEvent, index: Int): String {
    val frames = e.stackTrace?.frames ?: return "<no stack>"
    val f = frames[index]
    return "${f.method.type.name}.${f.method.name}"
}

/**
 * What a flame graph would tell you, in text.
 *
 * Two views, because they are the two things anyone actually looks at: self time, which is the
 * width of the leaves, and the hottest complete stacks, which is what you see when you zoom.
 */
fun analyzeJfr(path: Path, top: Int, collapsedOut: Path?) {
    val self = HashMap<String, Long>()
    val collapsed = HashMap<String, Long>()
    // Inclusive counts: every distinct method appearing anywhere in a stack, counted once per
    // sample. This is the "width of the box" a flame graph shows for a method.
    val inclusive = HashMap<String, Long>()
    var total = 0L
    // A stack sampler has a depth limit; JFR's default is 64 frames. When a stack is deeper than
    // that the *root* end is what gets dropped, so every method that was merely on the way in
    // loses the sample. A label in a thread-local slot has no depth, so the two disagree exactly
    // where recursion is deepest.
    var truncated = 0L
    val depths = ArrayList<Int>()
    val truncatedRoot = HashMap<String, Long>()

    RecordingFile(path).use { f ->
        while (f.hasMoreEvents()) {
            val e = f.readEvent()
            if (e.eventType.name != "jdk.ExecutionSample") continue
            val frames = e.stackTrace?.frames ?: continue
            if (frames.isEmpty()) continue
            total++
            depths += frames.size
            if (e.stackTrace!!.isTruncated) {
                truncated++
                truncatedRoot.merge(frameName(e, frames.size - 1), 1L, Long::plus)
            }

            self.merge(frameName(e, 0), 1L, Long::plus)

            val seen = HashSet<String>()
            for (i in frames.indices) {
                val n = frameName(e, i)
                if (seen.add(n)) inclusive.merge(n, 1L, Long::plus)
            }

            if (collapsedOut != null) {
                val stack = (frames.indices.reversed()).joinToString(";") { fullFrame(e, it) }
                collapsed.merge(stack, 1L, Long::plus)
            }
        }
    }

    println()
    println("=".repeat(78))
    println(String.format(Locale.ROOT, "JFR execution samples: %,d", total))
    depths.sort()
    println(
        String.format(
            Locale.ROOT, "stack depth: median %d, p90 %d, max %d; truncated %,d (%.1f%%)",
            depths[depths.size / 2], depths[(depths.size * 9) / 10], depths.last(),
            truncated, truncated * 100.0 / total
        )
    )
    if (truncated > 0) {
        println("deepest frame surviving in truncated stacks (the root end is what was lost):")
        for ((n, c) in truncatedRoot.entries.sortedByDescending { it.value }.take(5)) {
            println(String.format(Locale.ROOT, "    %-46s %6d", n, c))
        }
    }
    println("=".repeat(78))

    fun table(title: String, m: Map<String, Long>) {
        println()
        println(title)
        println("-".repeat(78))
        println(String.format(Locale.ROOT, "%-52s %10s %10s", "method", "samples", "share"))
        for ((name, n) in m.entries.sortedByDescending { it.value }.take(top)) {
            println(String.format(Locale.ROOT, "%-52s %10d %9.2f%%", name, n, n * 100.0 / total))
        }
    }

    table("SELF TIME - the leaves, which is what a flame graph's width at the top shows", self)
    table("INCLUSIVE - every method anywhere on the stack, once per sample", inclusive)

    if (collapsedOut != null) {
        collapsedOut.toFile().bufferedWriter().use { w ->
            for ((stack, n) in collapsed.entries.sortedByDescending { it.value }) {
                w.write(stack); w.write(" "); w.write(n.toString()); w.newLine()
            }
        }
        println()
        println("collapsed stacks written to $collapsedOut (${collapsed.size} distinct)")

        println()
        println("HOTTEST COMPLETE STACKS")
        println("-".repeat(78))
        for ((stack, n) in collapsed.entries.sortedByDescending { it.value }.take(3)) {
            println(String.format(Locale.ROOT, "%d samples (%.2f%%)", n, n * 100.0 / total))
            for (frame in stack.split(";").reversed().take(18)) println("    $frame")
            println()
        }
    }
}

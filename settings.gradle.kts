plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "profiler"

// The trial: the fine tier pointed at somebody else's code. Separate module so that nothing it
// needs — Calcite, a logging binding, JFR plumbing — can leak into the profiler itself, which
// still takes no dependencies at all.
include("trial")

// Shared by every trial and depending on nothing but the JDK: the JFR recording and the
// collapsed-stack analysis that answers "what would a flame graph have told you".
include("trial-common")

// The second trial: Lucene, which is concurrent and where the identity question is not "which
// class" but "which instance" — several clauses of one query, all of them TermScorer with
// different terms behind them.
include("trial-lucene")

// The third trial: Netty, whose concurrency shape is the opposite of Lucene's — few long-lived
// event-loop threads carrying many short tasks, and time that is mostly not CPU. It is the first
// workload where the thread-state column has anything to say, and the first that can test whether
// it says the wrong thing: a selector wait is native, and a thread in native code reads RUNNABLE.
include("trial-netty")

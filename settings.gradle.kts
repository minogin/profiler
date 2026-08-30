plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "profiler"

// The first trial: the fine tier pointed at somebody else's code. Separate module so that nothing it
// needs — Calcite, a logging binding, JFR plumbing — can leak into the profiler itself, which
// still takes no dependencies at all.
include("trial-calcite")

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

// The fourth trial, and the first with any waiting in it. Calcite is single-threaded and pure CPU,
// Lucene's index is page-cached, and Netty's loopback request never blocks — so `mean - busy/exec`,
// the quantity the coarse tier exists to produce, has never been checked on foreign code against an
// answer that is not zero. A database over a socket is waiting that is real, parallel and somebody
// else's, and it is the one workload that can say whether `working` counts a blocked thread as
// working: Java thread state is blind to native waits, and a socket read is a native wait.
include("trial-jdbc")

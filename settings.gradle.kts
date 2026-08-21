plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "profiler"

// The trial: the fine tier pointed at somebody else's code. Separate module so that nothing it
// needs — Calcite, a logging binding, JFR plumbing — can leak into the profiler itself, which
// still takes no dependencies at all.
include("trial")

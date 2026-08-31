plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The profiler, and nothing else by default. A sibling module sees only the public API —
    // Kotlin's `internal` is scoped to a compilation — so this sits exactly where a stranger sits.
    // That is what makes friction found here worth recording.
    implementation(project(":"))
}

// 21, like the library. The sandbox is meant to sit exactly where a stranger sits, and a stranger
// runs what we publish for - so a toolchain the library does not ship for is the one thing this
// module must not have. It was 26 until 2026-08-31, which compiled it to class file 70 and made
// `:sandbox:run` fail with a LinkageError on the JDK 21 this machine actually has on PATH.
kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.ticksnick.sandbox.SandboxKt")
}

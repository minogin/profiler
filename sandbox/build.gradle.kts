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

kotlin {
    jvmToolchain(26)
}

application {
    mainClass.set("com.minogin.profiler.sandbox.SandboxKt")
}

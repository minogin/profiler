plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The profiler under trial. Source dependency on the root project — the point of the exercise
    // is that a third party needs nothing but Profiler.register and a place to put the label.
    implementation(project(":"))
    implementation(project(":trial-common"))

    // Netty 4.2. `netty-all` would drag in every transport including the native ones; the three
    // below are what a real server on this platform actually loads, and keeping the list explicit
    // means the flame graph has no frames in it that our own build put there.
    implementation("io.netty:netty-transport:4.2.9.Final")
    implementation("io.netty:netty-codec-http:4.2.9.Final")
    implementation("io.netty:netty-handler:4.2.9.Final")
}

kotlin {
    jvmToolchain(26)
}

application {
    mainClass.set("com.ticksnick.trial.netty.NettyTrialKt")
}

/** Writes the runtime classpath to a file, so the trial can be launched by `java` directly with
 *  whatever JVM flags a given experiment needs — JFR in particular — without Gradle in the way. */
tasks.register("classpathFile") {
    val cp = sourceSets["main"].runtimeClasspath
    val out = layout.buildDirectory.file("classpath.txt")
    inputs.files(cp)
    outputs.file(out)
    doLast {
        out.get().asFile.writeText(cp.joinToString(File.pathSeparator))
    }
}

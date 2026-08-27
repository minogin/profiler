plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The profiler under trial. Source dependency on the root project — the point of the exercise
    // is that a third party needs nothing but Profiler.register / op(id) { }.
    implementation(project(":"))

    implementation(project(":trial-common"))

    implementation("org.apache.calcite:calcite-core:1.42.0")
    // Calcite logs through slf4j. Without a binding every run opens with a warning; nop keeps the
    // console clean and, more to the point, keeps a logging framework out of the profile.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

kotlin {
    jvmToolchain(26)
}

application {
    mainClass.set("com.minogin.profiler.trial.CalciteTrialKt")
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

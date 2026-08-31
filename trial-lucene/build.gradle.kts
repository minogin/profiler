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

    implementation("org.apache.lucene:lucene-core:10.4.0")
    // The analyzer the corpus is written and queried with. Kept explicit: which analyzer is used
    // decides the term distribution, and the term distribution is the whole experiment.
    implementation("org.apache.lucene:lucene-analysis-common:10.4.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.ticksnick.trial.lucene.LuceneTrialKt")
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

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

    // The stack being profiled, and all of it somebody else's: a driver that blocks on a socket,
    // and the connection pool nearly every JVM service in production is using.
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")

    // HikariCP logs through SLF4J and complains to stderr without a binding. Simple, not none: a
    // pool that cannot say it timed out would be the one thing worth knowing about it.
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

kotlin {
    jvmToolchain(26)
}

application {
    mainClass.set("com.ticksnick.trial.jdbc.JdbcTrialKt")
}

/** As the other trials: the runtime classpath on disk, so a run can be launched by `java` directly
 *  with whatever JVM flags an experiment needs, without Gradle in the way. */
tasks.register("classpathFile") {
    val cp = sourceSets["main"].runtimeClasspath
    val out = layout.buildDirectory.file("classpath.txt")
    inputs.files(cp)
    outputs.file(out)
    doLast {
        out.get().asFile.writeText(cp.joinToString(File.pathSeparator))
    }
}

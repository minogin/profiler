plugins {
    kotlin("jvm") version "2.4.10"
    application
    `maven-publish`
}

group = "com.minogin"

// 0.x on purpose. The fine tier is measured and reviewed, but the public surface has not yet had a
// release's worth of other people's use, and a 0.x says the API may still move. See README.
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(26)
}

// The library takes no dependencies at all, and the published POM has to keep saying so. The bench
// and the trials live in this repository but never in the artifact: the bench is in a package of
// its own inside this module, and every trial is a separate module.
java {
    withSourcesJar()
}

// The bench lives in this module so that `internal` reaches the sampler's counters, which is what
// lets the library keep them out of its public API. That is right for the source tree and wrong for
// the artifact: nobody depending on this wants Bench, Workload and StackCost on their classpath.
// `run` is unaffected — it builds from the classes directory, not from the jar.
tasks.named<Jar>("jar") { exclude("com/minogin/profiler/bench/**") }
tasks.named<Jar>("sourcesJar") { exclude("com/minogin/profiler/bench/**") }

application {
    mainClass.set("com.minogin.profiler.bench.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "profiler"
            from(components["java"])
            pom {
                name.set("profiler")
                description.set(
                    "A sampling profiler for operations too short for a stack profiler to see: " +
                            "labels written to a thread-local slot, read by a sampling thread, with a " +
                            "measured bound on the error of every share."
                )
                url.set("https://github.com/minogin/profiler")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("minogin")
                        name.set("Andrey Minogin")
                    }
                }
                scm {
                    url.set("https://github.com/minogin/profiler")
                    connection.set("scm:git:https://github.com/minogin/profiler.git")
                }
            }
        }
    }
}

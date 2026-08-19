plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "com.minogin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(26)
}

application {
    mainClass.set("com.minogin.profiler.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

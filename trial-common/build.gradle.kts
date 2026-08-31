plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

/**
 * What every trial needs and no trial should own: the conventional profile, for comparison.
 *
 * It lives here rather than in the Calcite module because the second trial needs exactly the same
 * JFR recording and the same collapsed-stack analysis, and a copy of it would drift. It takes
 * nothing but the JDK — in particular it does not depend on the profiler, which is the property
 * the separate trial modules exist to protect.
 */
kotlin {
    jvmToolchain(21)
}

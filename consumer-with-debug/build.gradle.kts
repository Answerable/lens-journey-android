// Not published. This module is a staging app: it depends on the core artifact AND the opt-in debug attestation artifact.
plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    testImplementation(project(":"))
    testImplementation(project(":debug-attestation"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }

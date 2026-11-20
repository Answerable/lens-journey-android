import org.gradle.api.attributes.Usage

// Not published. This module is an app that depends on the CORE artifact only. It compiles snippets with the real Kotlin compiler
// against the core artifact (and, for the control, against core plus the debug artifact) and asserts what the compiler says.
plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

fun runtimeClasspath(name: String) = configurations.create(name) {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
}

val coreOnly = runtimeClasspath("coreOnlyClasspath")
val coreAndDebug = runtimeClasspath("coreAndDebugClasspath")

dependencies {
    coreOnly(project(":"))
    coreAndDebug(project(":"))
    coreAndDebug(project(":debug-attestation"))

    // The test code itself is an app that sees the core artifact only.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
}

kotlin { jvmToolchain(17) }

tasks.test {
    useJUnitPlatform()
    // The jars must be built before the snippets compile against them.
    inputs.files(coreOnly, coreAndDebug)
    // The classpaths the snippets compile against. The first has the core artifact and its runtime dependencies, nothing else.
    systemProperty("boundary.coreOnlyClasspath", coreOnly.incoming.files.asPath)
    systemProperty("boundary.coreAndDebugClasspath", coreAndDebug.incoming.files.asPath)
}

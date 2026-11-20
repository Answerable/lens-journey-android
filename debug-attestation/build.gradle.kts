import java.net.URI

// ai.varai:lens-journey-debug-attestation: the opt-in artifact that holds the whole debug attestation flow (the core artifact has no
// debug code at all). Apps add it to the staging/debug variant only.
plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "ai.varai"
// One version for both artifacts: the VERSION file at the repository root.
version = rootProject.file("VERSION").readText().trim()

repositories { mavenCentral() }

dependencies {
    // The entry points return the core's Transport and LensJourney, so the core is part of this artifact's API (a compile-scope pom dependency).
    api(project(":"))
    // The debug install-token body is built here, with the JSON library the core already uses.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin { jvmToolchain(17) }

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.named<Jar>("javadocJar") {
    from(rootProject.layout.projectDirectory.file("README.md"))
    from(rootProject.layout.projectDirectory.file("LICENSE"))
    from(rootProject.layout.projectDirectory.file("NOTICE"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "lens-journey-debug-attestation"
            pom {
                name.set("Lens Journey Android SDK, debug attestation (staging only)")
                description.set("Opt-in debug attestation provider for the Lens Journey Android SDK. Staging and test builds only: a production VARAi server refuses it. Never add it to a release build.")
                url.set("https://github.com/Answerable/lens-journey-android")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                organization { name.set("VARAI Inc.") }
                developers {
                    developer {
                        id.set("varai")
                        name.set("VARAI Inc.")
                        organization.set("VARAI Inc.")
                        url.set("https://github.com/Answerable")
                    }
                }
                scm {
                    url.set("https://github.com/Answerable/lens-journey-android")
                    connection.set("scm:git:https://github.com/Answerable/lens-journey-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Answerable/lens-journey-android.git")
                    tag.set("v${project.version}")
                }
            }
        }
    }
    repositories {
        // The SAME local Maven repository as the core artifact (<root>/build/central-repo), so the release script zips ONE bundle that
        // holds both artifacts.
        maven {
            name = "releaseBundle"
            url = URI(rootProject.layout.buildDirectory.dir("central-repo").get().asFile.toURI().toString())
        }
    }
}

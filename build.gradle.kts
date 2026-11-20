import java.net.URI

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    `maven-publish`
    // The committed API dumps (api/*.api) are the proof of what each artifact exposes. `apiCheck` (part of `check`) fails when the
    // public API of either published artifact changes without the dump being regenerated and reviewed (`./gradlew apiDump`).
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
}

// The consumer modules are test apps, never published: no API to validate.
apiValidation {
    ignoredProjects.addAll(listOf("boundary-tests", "consumer-with-debug"))
}

group = "ai.varai"
// The single source of truth is the VERSION file. The release version check compares it with LensJourney.VERSION,
// the newest CHANGELOG.md heading and the README. This build never signs anything: the release script signs the
// published artifacts itself, so Gradle (dependencies, plugins, tests) never receives a key.
version = file("VERSION").readText().trim()

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

java {
    withSourcesJar()
    withJavadocJar()
}

// Maven Central requires a javadoc jar but accepts one without generated API docs (a README is enough).
// Publishing the Kotlin sources with Dokka is a possible follow-up; until then the jar carries a pointer.
tasks.named<Jar>("javadocJar") {
    from(layout.projectDirectory.file("README.md"))
    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.file("NOTICE"))
}

// Byte-identical jars for identical sources, so a re-run of the release script rebuilds the same artifacts.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// The test fixtures live in <package-root>/fixtures in the public mirror and in ../fixtures in the monorepo.
val fixturesDir = projectDir.resolve("fixtures").takeIf { it.isDirectory } ?: projectDir.resolve("../fixtures")

tasks.test {
    useJUnitPlatform()
    systemProperty("lensJourney.fixtures", fixturesDir.canonicalPath)
    systemProperty("lensJourney.packageRoot", projectDir.canonicalPath)
    // The tests read these at run time; declaring them keeps Gradle from reporting the task up to date when they change.
    inputs.files("VERSION", "CHANGELOG.md")
    inputs.dir(fixturesDir)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "lens-journey"
            pom {
                name.set("Lens Journey Android SDK")
                description.set("Pure Kotlin/JVM client for VARAi's server-driven lens journey (Commerce Studio). Contains no API key.")
                url.set("https://github.com/Answerable/lens-journey-android")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                organization {
                    name.set("VARAI Inc.")
                }
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
        // A local Maven-layout repository that the release script zips into the Central bundle.
        maven {
            name = "releaseBundle"
            url = URI(layout.buildDirectory.dir("central-repo").get().asFile.toURI().toString())
        }
    }
}

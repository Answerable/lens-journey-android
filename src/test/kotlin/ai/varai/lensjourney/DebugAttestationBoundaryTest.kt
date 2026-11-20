package ai.varai.lensjourney

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-369 D3/D4: a release build cannot use debug attestation. The committed binary-compatibility-validator dump is the public API of
 * the core artifact; `apiCheck` fails when the built artifact differs from it. These tests read the dump, so a dump that someone
 * regenerated with a debug path in it is caught too, and not just a drift between code and dump.
 */
class DebugAttestationBoundaryTest {
    private val root = Path.of(System.getProperty("lensJourney.packageRoot"))
    private val coreApi = Files.readAllLines(root.resolve("api/lens-journey-android.api"))
    private val debugApi = Files.readAllLines(root.resolve("debug-attestation/api/debug-attestation.api"))

    @Test
    fun `the core public API has nothing about debug attestation`() {
        val hits = coreApi.filter { it.contains("debug", ignoreCase = true) }
        assertEquals(emptyList<String>(), hits, "the core artifact must not expose a debug payload, provider or serialiser")
    }

    @Test
    fun `the core public API does not list the old Debug payload or provider`() {
        assertFalse(coreApi.any { it.contains("AttestationPayload\$Debug") })
        assertFalse(coreApi.any { it.contains("DebugAttestation") })
        assertFalse(coreApi.any { it.contains("Restricted") })
    }

    @Test
    fun `the payload kinds an app can name are exactly the two platform proofs`() {
        val kinds = coreApi.filter { it.contains("class ai/varai/lensjourney/AttestationPayload\$") }.map { it.substringAfter("AttestationPayload\$").substringBefore(" ") }
        assertEquals(listOf("AppAttest", "PlayIntegrity"), kinds.sorted())
    }

    @Test
    fun `the debug artifact exposes its entry points and nothing else`() {
        val classes = debugApi.filter { it.contains(" class ") || it.contains(" interface ") || it.contains(" object ") }.map { it.substringAfter(" class ").substringBefore(" ") }
        assertEquals(listOf("ai/varai/lensjourney/debug/DebugAttestation", "ai/varai/lensjourney/debug/DebugAttestationKt"), classes.sorted())
    }

    @Test
    fun `the core build declares no dependency on the debug artifact`() {
        val build = Files.readString(root.resolve("build.gradle.kts"))
        assertFalse(Regex("""debug-attestation""").containsMatchIn(build.replace("ignoredProjects", "")), "core must not depend on the debug artifact")
    }
}

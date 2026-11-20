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
        assertFalse(coreApi.any { it.contains("DebugAttestationProvider") })
    }

    @Test
    fun `the restricted payload has no public constructor and no public accessor`() {
        val start = coreApi.indexOfFirst { it.startsWith("public final class ai/varai/lensjourney/AttestationPayload\$Restricted ") }
        assertTrue(start >= 0, "Restricted must be part of the public API as a type")
        val members = coreApi.drop(start + 1).takeWhile { it != "}" }.map { it.trim() }
        assertEquals(listOf("public fun toString ()Ljava/lang/String;"), members, "Restricted exposes nothing an app could build or read from")
    }

    @Test
    fun `the payload kinds an app can name are exactly the platform proofs and the opaque restricted type`() {
        val kinds = coreApi.filter { it.contains("class ai/varai/lensjourney/AttestationPayload\$") }.map { it.substringAfter("AttestationPayload\$").substringBefore(" ") }
        assertEquals(listOf("AppAttest", "PlayIntegrity", "Restricted"), kinds.sorted())
    }

    @Test
    fun `the debug artifact exposes the provider and only the provider`() {
        val classes = debugApi.filter { it.contains(" class ") || it.contains(" interface ") || it.contains(" object ") }
        assertEquals(1, classes.size, classes.toString())
        assertTrue(classes.single().contains("ai/varai/lensjourney/debug/DebugAttestationProvider"), classes.single())
    }

    @Test
    fun `the core build declares no dependency on the debug artifact`() {
        val build = Files.readString(root.resolve("build.gradle.kts"))
        assertFalse(Regex("""debug-attestation""").containsMatchIn(build.replace("ignoredProjects", "")), "core must not depend on the debug artifact")
    }
}

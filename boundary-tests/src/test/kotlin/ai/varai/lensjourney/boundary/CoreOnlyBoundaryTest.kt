package ai.varai.lensjourney.boundary

import ai.varai.lensjourney.AttestationPayload
import ai.varai.lensjourney.AttestationProvider
import ai.varai.lensjourney.InMemoryInstallIdStore
import ai.varai.lensjourney.MobileKeyConfig
import ai.varai.lensjourney.MobileKeyTransport
import ai.varai.lensjourney.Transport
import ai.varai.lensjourney.TransportRequest
import ai.varai.lensjourney.TransportResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * An app that depends on the core artifact (`ai.varai:lens-journey`) and nothing else, which is what a release build looks like.
 * ADR-369 D3/D4: a release build cannot use debug attestation. The core artifact holds no code that writes a `debug` wire field and
 * no type that represents a debug payload, so there is nothing to construct, name, subclass or reflect on. Each "refused" test
 * compiles app code with the real Kotlin compiler.
 */
class CoreOnlyBoundaryTest {
    private fun core(source: String) = CompilerHarness.compile(source.trimIndent(), CompilerHarness.coreOnlyClasspath)

    private fun assertRefused(result: CompileResult, vararg expected: String) {
        assertFalse(result.compiled, "the compiler accepted app code that must be refused:\n${result.output}")
        for (text in expected) assertTrue(result.output.contains(text, ignoreCase = true), "expected the compiler to say '$text', it said:\n${result.output}")
    }

    @Test
    fun `control - ordinary app code against the core artifact compiles`() {
        val result = core(
            """
            import ai.varai.lensjourney.*

            class AppProvider : AttestationProvider {
                override suspend fun attest(challenge: String, installId: String): AttestationPayload = AttestationPayload.PlayIntegrity("token")
            }

            fun describe(payload: AttestationPayload): String = when (payload) {
                is AttestationPayload.AppAttest -> "app-attest"
                is AttestationPayload.PlayIntegrity -> "play-integrity"
            }

            fun config() = MobileKeyConfig("cspk_live_abc123", "https://shop.example.com", AppProvider())
            """,
        )
        assertTrue(result.compiled, "the control must compile, otherwise the refusals below prove nothing:\n${result.output}")
    }

    @Test
    fun `the core artifact has no Debug payload`() {
        assertRefused(
            core("""import ai.varai.lensjourney.*
                fun forge(): AttestationPayload = AttestationPayload.Debug("challenge")"""),
            "Unresolved reference", "Debug",
        )
    }

    @Test
    fun `the core artifact has no restricted or other opaque payload type`() {
        assertRefused(
            core("""import ai.varai.lensjourney.*
                fun forge(): AttestationPayload = AttestationPayload.Restricted("o", "debug", mapOf("challenge" to "c"))"""),
            "Unresolved reference", "Restricted",
        )
    }

    @Test
    fun `the core artifact has no debug provider under the old package`() {
        assertRefused(
            core("""import ai.varai.lensjourney.DebugAttestationProvider
                fun provider() = DebugAttestationProvider()"""),
            "Unresolved reference", "DebugAttestationProvider",
        )
    }

    @Test
    fun `the core artifact has no debug provider under the new package either`() {
        assertRefused(
            core("""import ai.varai.lensjourney.debug.DebugAttestationProvider
                fun provider() = DebugAttestationProvider()"""),
            "Unresolved reference", "debug",
        )
    }

    @Test
    fun `the core artifact has no debug entry point`() {
        assertRefused(
            core("""import ai.varai.lensjourney.debug.DebugAttestation
                fun transport() = DebugAttestation.transport("cspk_live_abc123", "https://staging.example.com")"""),
            "Unresolved reference", "debug",
        )
    }

    @Test
    fun `an app cannot add its own payload kind`() {
        assertRefused(
            core("""import ai.varai.lensjourney.*
                class Mine : AttestationPayload()"""),
            "AttestationPayload",
        )
    }

    // The same app code, with the debug artifact on the classpath: its entry point compiles, and core still has no debug payload type.

    @Test
    fun `control - with the debug artifact on the classpath its entry point compiles`() {
        val result = CompilerHarness.compile(
            """
            import ai.varai.lensjourney.*
            import ai.varai.lensjourney.debug.DebugAttestation
            import ai.varai.lensjourney.debug.mobileDebug

            fun transport(): Transport = DebugAttestation.transport("cspk_live_abc123", "https://staging.example.com")
            fun journey(): LensJourney = LensJourney.mobileDebug(JourneyConfig(), "cspk_live_abc123", "https://staging.example.com")
            """.trimIndent(),
            CompilerHarness.coreAndDebugClasspath,
        )
        assertTrue(result.compiled, "the debug artifact must be usable by an app that opts in:\n${result.output}")
    }

    @Test
    fun `even with the debug artifact linked the core has no debug payload type`() {
        for (name in listOf("Debug", "Restricted")) {
            val result = CompilerHarness.compile(
                """
                import ai.varai.lensjourney.*
                fun forge(): AttestationPayload = AttestationPayload.$name("c")
                """.trimIndent(),
                CompilerHarness.coreAndDebugClasspath,
            )
            assertRefused(result, "Unresolved reference", name)
        }
    }

    @Test
    fun `the core classpath does not contain the debug artifact at run time`() {
        for (name in listOf(
            "ai.varai.lensjourney.debug.DebugAttestation", "ai.varai.lensjourney.debug.DebugAttestationProvider",
            "ai.varai.lensjourney.DebugAttestationProvider", "ai.varai.lensjourney.AttestationPayload\$Debug",
            "ai.varai.lensjourney.AttestationPayload\$Restricted",
        )) {
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }

    // Behaviour, not just names: whatever payload the core can represent, the core's install-token body never carries a debug field.

    @Test
    fun `the core transport emits only platform bindings on install-token`() = runBlocking {
        val payloads = listOf(
            AttestationPayload.PlayIntegrity("pi-token") to "android",
            AttestationPayload.AppAttest("key-1", attestationObject = byteArrayOf(1, 2, 3)) to "ios",
        )
        for ((payload, field) in payloads) {
            val bodies = mutableListOf<String>()
            val server = Transport { request ->
                when {
                    request.path.endsWith("/install-nonce") -> TransportResponse(200, emptyMap(), """{"nonce":"n1","challenge":"challenge-1"}""".encodeToByteArray())
                    request.path.endsWith("/install-token") -> {
                        bodies += request.body!!.decodeToString()
                        TransportResponse(200, emptyMap(), """{"install_token":"t","expires_at":"2027-01-15T08:00:00Z"}""".encodeToByteArray())
                    }
                    else -> TransportResponse(200, emptyMap(), "{}".encodeToByteArray())
                }
            }
            val provider = object : AttestationProvider {
                override suspend fun attest(challenge: String, installId: String) = payload
            }
            MobileKeyTransport(
                MobileKeyConfig("cspk_live_abc123", "https://shop.example.com", provider, InMemoryInstallIdStore()),
                server,
                { 1_800_000_000_000L },
            ).send(TransportRequest("POST", "/api/v1/lens-journey/evaluate", emptyMap()))
            assertEquals(1, bodies.size)
            assertEquals(setOf("install_id", "platform", "nonce", field), Json.parseToJsonElement(bodies[0]).jsonObject.keys, bodies[0])
            assertFalse(bodies[0].contains("debug", ignoreCase = true), bodies[0])
        }
    }
}

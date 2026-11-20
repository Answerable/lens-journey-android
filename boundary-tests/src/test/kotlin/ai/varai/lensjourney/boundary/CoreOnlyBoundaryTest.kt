package ai.varai.lensjourney.boundary

import ai.varai.lensjourney.AttestationException
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * An app that depends on the core artifact (`ai.varai:lens-journey`) and nothing else, which is what a release build looks like.
 * ADR-369 D3/D4: a release build cannot use debug attestation. Each "refused" test compiles app code with the real Kotlin compiler.
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
                is AttestationPayload.Restricted -> "restricted"
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
    fun `an app cannot construct a restricted payload`() {
        assertRefused(
            core("""import ai.varai.lensjourney.*
                fun forge(): AttestationPayload = AttestationPayload.Restricted("ai.varai:lens-journey-debug-attestation", "debug", mapOf("challenge" to "c"))"""),
            "Cannot access", "internal",
        )
    }

    @Test
    fun `an app cannot read what a restricted payload carries`() {
        assertRefused(
            core("""import ai.varai.lensjourney.*
                fun peek(payload: AttestationPayload.Restricted) = payload.values"""),
            "Cannot access", "internal",
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

    // The same app code, with the debug artifact on the classpath: it compiles, and the payload is still unconstructible.

    @Test
    fun `control - with the debug artifact on the classpath the provider compiles`() {
        val result = CompilerHarness.compile(
            """
            import ai.varai.lensjourney.*
            import ai.varai.lensjourney.debug.DebugAttestationProvider

            fun config() = MobileKeyConfig("cspk_live_abc123", "https://staging.example.com", DebugAttestationProvider())
            """.trimIndent(),
            CompilerHarness.coreAndDebugClasspath,
        )
        assertTrue(result.compiled, "the debug artifact must be usable by an app that opts in:\n${result.output}")
    }

    @Test
    fun `even with the debug artifact linked an app cannot construct a restricted payload`() {
        val result = CompilerHarness.compile(
            """
            import ai.varai.lensjourney.*
            fun forge(): AttestationPayload = AttestationPayload.Restricted("ai.varai:lens-journey-debug-attestation", "debug", mapOf("challenge" to "c"))
            """.trimIndent(),
            CompilerHarness.coreAndDebugClasspath,
        )
        assertRefused(result, "Cannot access", "internal")
    }

    @Test
    fun `the core classpath does not contain the debug artifact at run time`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("ai.varai.lensjourney.debug.DebugAttestationProvider") }
        assertFailsWith<ClassNotFoundException> { Class.forName("ai.varai.lensjourney.DebugAttestationProvider") }
        assertFailsWith<ClassNotFoundException> { Class.forName("ai.varai.lensjourney.AttestationPayload\$Debug") }
    }

    // Second line of defence. The compiler is the boundary. `internal` is public in the bytecode, so a Java caller or reflection can
    // reach the constructor. The transport still refuses to send a restricted payload that did not come from the debug artifact.

    @Test
    fun `a restricted payload forged by reflection is refused before install-token is called`() = runBlocking {
        // Named by string: the type is part of the public API, but its constructor is not, so a plain call does not compile (see above).
        val constructor = Class.forName("ai.varai.lensjourney.AttestationPayload\$Restricted").declaredConstructors.single()
        constructor.isAccessible = true
        val forged = constructor.newInstance("an-app", "debug", mapOf("challenge" to "c")) as AttestationPayload

        val server = RecordingServer()
        val transport = MobileKeyTransport(
            MobileKeyConfig("cspk_live_abc123", "https://shop.example.com", OneShotProvider(forged), InMemoryInstallIdStore()),
            server,
            { 1_800_000_000_000L },
        )
        val error = assertFailsWith<AttestationException> { transport.send(TransportRequest("POST", "/api/v1/lens-journey/evaluate", emptyMap())) }
        assertEquals("ATTESTATION_PAYLOAD_NOT_ALLOWED", error.code)
        assertTrue(server.paths.none { it.endsWith("/install-token") }, "install-token must never be called: ${server.paths}")
        assertNotEquals(emptyList<String>(), server.paths, "the nonce call is made first")
    }
}

private class OneShotProvider(private val payload: AttestationPayload) : AttestationProvider {
    override suspend fun attest(challenge: String, installId: String) = payload
}

private class RecordingServer : Transport {
    val paths = mutableListOf<String>()
    override suspend fun send(request: TransportRequest): TransportResponse {
        paths += request.path
        val body = if (request.path.endsWith("/install-nonce")) """{"nonce":"n1","challenge":"challenge-1"}""" else "{}"
        return TransportResponse(200, emptyMap(), body.encodeToByteArray())
    }
}

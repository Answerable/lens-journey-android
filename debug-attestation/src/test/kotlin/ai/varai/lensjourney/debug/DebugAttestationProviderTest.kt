package ai.varai.lensjourney.debug

import ai.varai.lensjourney.AttestationPayload
import ai.varai.lensjourney.InMemoryInstallIdStore
import ai.varai.lensjourney.MobileKeyConfig
import ai.varai.lensjourney.MobileKeyTransport
import ai.varai.lensjourney.Transport
import ai.varai.lensjourney.TransportRequest
import ai.varai.lensjourney.TransportResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class DebugAttestationProviderTest {
    @Test
    fun `the provider makes a restricted payload that never prints the challenge`() = runBlocking {
        val payload = DebugAttestationProvider().attest("chal-secret", "install-id-000001")
        assertIs<AttestationPayload.Restricted>(payload)
        assertFalse(payload.toString().contains("chal-secret"))
    }

    @Test
    fun `the transport sends the challenge in the debug field and nothing else`() = runBlocking {
        val bodies = mutableListOf<String>()
        val server = Transport { request ->
            when {
                request.path.endsWith("/install-nonce") -> TransportResponse(200, emptyMap(), """{"nonce":"n","challenge":"c-from-server"}""".encodeToByteArray())
                request.path.endsWith("/install-token") -> {
                    bodies += request.body!!.decodeToString()
                    TransportResponse(200, emptyMap(), """{"install_token":"t","expires_at":"2027-01-15T08:00:00Z"}""".encodeToByteArray())
                }
                else -> TransportResponse(200, emptyMap(), "{}".encodeToByteArray())
            }
        }
        MobileKeyTransport(
            MobileKeyConfig("cspk_live_abc123", "https://staging.example.com", DebugAttestationProvider(), InMemoryInstallIdStore()),
            server,
            { 1_800_000_000_000L },
        ).send(TransportRequest("POST", "/api/v1/lens-journey/evaluate", emptyMap()))
        assertEquals(1, bodies.size)
        assertTrue(bodies[0].contains(""""debug":{"challenge":"c-from-server"}"""), bodies[0])
        val keys = Json.parseToJsonElement(bodies[0]).jsonObject.keys
        assertTrue("ios" !in keys && "android" !in keys, "only the debug binding is sent: $keys")
    }
}

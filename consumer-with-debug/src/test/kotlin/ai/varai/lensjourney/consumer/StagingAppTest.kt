package ai.varai.lensjourney.consumer

import ai.varai.lensjourney.InMemoryInstallIdStore
import ai.varai.lensjourney.JourneyConfig
import ai.varai.lensjourney.LensJourney
import ai.varai.lensjourney.Transport
import ai.varai.lensjourney.TransportRequest
import ai.varai.lensjourney.TransportResponse
import ai.varai.lensjourney.debug.DebugAttestation
import ai.varai.lensjourney.debug.mobileDebug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A staging app: it depends on the core artifact AND `ai.varai:lens-journey-debug-attestation`. It can send debug attestation. The
 * counterpart, an app with the core artifact only, is in the `boundary-tests` module and cannot.
 */
class StagingAppTest {
    private val nonceRoute = "/api/v1/lens-journey/install-nonce"
    private val tokenRoute = "/api/v1/lens-journey/install-token"
    private val evaluate = "/api/v1/lens-journey/evaluate"

    @Test
    fun `an app that links the debug artifact sends the debug attestation to install-token`() = runBlocking {
        val calls = mutableListOf<TransportRequest>()
        val server = Transport { request ->
            calls += request
            when (request.path) {
                nonceRoute -> TransportResponse(200, emptyMap(), """{"nonce":"n-1","challenge":"challenge-from-server"}""".encodeToByteArray())
                tokenRoute -> TransportResponse(200, emptyMap(), """{"install_token":"token-1","expires_at":"2027-01-15T08:00:00Z"}""".encodeToByteArray())
                else -> TransportResponse(200, emptyMap(), "{}".encodeToByteArray())
            }
        }
        val transport = DebugAttestation.transport(
            "cspk_live_abc123", "https://shop.example.com", InMemoryInstallIdStore(), http = server, clockMillis = { 1_800_000_000_000L },
        )

        val response = transport.send(TransportRequest("POST", evaluate, emptyMap()))

        assertEquals(200, response.status)
        assertEquals(listOf(nonceRoute, tokenRoute, evaluate), calls.map { it.path })
        val tokenBody = Json.parseToJsonElement(calls[1].body!!.decodeToString()).jsonObject
        assertEquals("challenge-from-server", tokenBody["debug"]!!.jsonObject["challenge"]!!.jsonPrimitive.content)
        assertTrue("ios" !in tokenBody && "android" !in tokenBody, "only the debug binding is sent: $tokenBody")
        assertEquals("token-1", calls[2].headers["X-CS-Install-Token"])
    }

    @Test
    fun `the staging journey factory is available next to the release one`() {
        // Construction only: it proves the entry point an app calls exists and refuses a secret key like the release path.
        LensJourney.mobileDebug(JourneyConfig(), "cspk_live_abc123", "https://shop.example.com")
        assertTrue(runCatching { LensJourney.mobileDebug(JourneyConfig(), "cs_live_abcdefghijklmnop", "https://shop.example.com") }.isFailure)
    }
}

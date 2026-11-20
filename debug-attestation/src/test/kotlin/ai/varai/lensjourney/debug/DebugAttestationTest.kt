package ai.varai.lensjourney.debug

import ai.varai.lensjourney.AttestationException
import ai.varai.lensjourney.AttestationPayload
import ai.varai.lensjourney.InMemoryInstallIdStore
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
import kotlinx.serialization.json.jsonPrimitive

class DebugAttestationTest {
    private val nonceRoute = "/api/v1/lens-journey/install-nonce"
    private val tokenRoute = "/api/v1/lens-journey/install-token"
    private val evaluate = "/api/v1/lens-journey/evaluate"

    /** A scripted staging server. [rejectFirstJourneyCall] answers the first journey call with 401 INSTALL_TOKEN_INVALID. */
    private class Server(private val rejectFirstJourneyCall: Boolean = false) : Transport {
        val calls = mutableListOf<TransportRequest>()
        private var minted = 0
        private var rejected = false
        override suspend fun send(request: TransportRequest): TransportResponse {
            calls += request
            return when {
                request.path.endsWith("/install-nonce") -> TransportResponse(200, emptyMap(), """{"nonce":"nonce-${++minted}","challenge":"challenge-$minted"}""".encodeToByteArray())
                request.path.endsWith("/install-token") -> TransportResponse(200, emptyMap(), """{"install_token":"token-$minted","expires_at":"2027-01-15T08:00:00Z"}""".encodeToByteArray())
                rejectFirstJourneyCall && !rejected -> { rejected = true; TransportResponse(401, emptyMap(), """{"code":"INSTALL_TOKEN_INVALID"}""".encodeToByteArray()) }
                else -> TransportResponse(200, emptyMap(), "{}".encodeToByteArray())
            }
        }
        fun tokenBodies() = calls.filter { it.path.endsWith("/install-token") }.map { Json.parseToJsonElement(it.body!!.decodeToString()).jsonObject }
    }

    private fun transport(server: Server, store: InMemoryInstallIdStore = InMemoryInstallIdStore()) =
        DebugAttestation.transport("cspk_live_abc123", "https://shop.example.com", store, http = server, clockMillis = { 1_800_000_000_000L })

    private fun journey(body: String = """{"a":1}""") = TransportRequest("POST", evaluate, mapOf("X-Client-Id" to "client_123"), body.encodeToByteArray(), "application/json")

    @Test
    fun `install-token carries the server challenge in the debug binding and no platform binding`() = runBlocking {
        val server = Server()
        val store = InMemoryInstallIdStore()
        assertEquals(200, transport(server, store).send(journey()).status)

        assertEquals(listOf(nonceRoute, tokenRoute, evaluate), server.calls.map { it.path })
        val body = server.tokenBodies().single()
        assertEquals(setOf("install_id", "platform", "nonce", "debug"), body.keys, body.toString())
        assertEquals("challenge-1", body["debug"]!!.jsonObject["challenge"]!!.jsonPrimitive.content)
        assertEquals(setOf("challenge"), body["debug"]!!.jsonObject.keys)
        assertEquals(store.load(), body["install_id"]!!.jsonPrimitive.content)
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("nonce-1", body["nonce"]!!.jsonPrimitive.content)
        assertEquals("cspk_live_abc123", server.calls[1].headers["X-API-Key"], "the mint call keeps the mobile key")
    }

    @Test
    fun `journey calls pass through untouched and carry the install token`() = runBlocking {
        val server = Server()
        transport(server).send(journey("""{"a":1}"""))
        val sent = server.calls.last()
        assertEquals(evaluate, sent.path)
        assertEquals("""{"a":1}""", sent.body!!.decodeToString())
        assertEquals("token-1", sent.headers["X-CS-Install-Token"])
        assertEquals("client_123", sent.headers["X-Client-Id"])
    }

    @Test
    fun `the core lifecycle still applies, a rejected token is minted again with the debug binding`() = runBlocking {
        val server = Server(rejectFirstJourneyCall = true)
        assertEquals(200, transport(server).send(journey()).status)
        val bodies = server.tokenBodies()
        assertEquals(2, bodies.size, "mint, 401 INSTALL_TOKEN_INVALID, mint again")
        assertTrue(bodies.all { "debug" in it && "android" !in it && "ios" !in it }, bodies.toString())
        assertEquals("challenge-2", bodies[1]["debug"]!!.jsonObject["challenge"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the provider only carries the challenge and never makes a platform proof`() = runBlocking {
        val payload = ChallengeProvider().attest("chal-secret", "install-id-000001")
        assertEquals(AttestationPayload.PlayIntegrity("chal-secret"), payload)
        assertFalse(payload.toString().contains("chal-secret"), "payloads never print their contents")
    }

    @Test
    fun `an install-token body without the carrier binding is refused, not sent`() = runBlocking {
        val server = Server()
        val seam = DebugBindingTransport(server)
        for (body in listOf("""{"install_id":"i","platform":"android","nonce":"n"}""", """{"install_id":"i","ios":{"key_id":"k"}}""", "not json", "")) {
            assertFailsWith<AttestationException>(body) { seam.send(TransportRequest("POST", tokenRoute, emptyMap(), body.encodeToByteArray())) }
        }
        assertEquals(emptyList(), server.calls, "nothing may reach the server")
    }

    @Test
    fun `only POST install-token is rewritten`() = runBlocking {
        val server = Server()
        val seam = DebugBindingTransport(server)
        val body = """{"install_id":"i","android":{"integrity_token":"t"}}"""
        seam.send(TransportRequest("POST", nonceRoute, emptyMap(), body.encodeToByteArray()))
        seam.send(TransportRequest("GET", tokenRoute, emptyMap(), body.encodeToByteArray()))
        assertEquals(listOf(body, body), server.calls.map { it.body!!.decodeToString() })
    }
}

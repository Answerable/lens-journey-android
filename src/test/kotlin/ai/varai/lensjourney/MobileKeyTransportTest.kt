package ai.varai.lensjourney

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileKeyTransportTest {
    private val start = 1_800_000_000_000L
    private val evaluate = "/api/v1/lens-journey/evaluate"
    private val nonceRoute = "/api/v1/lens-journey/install-nonce"
    private val tokenRoute = "/api/v1/lens-journey/install-token"

    // key refusal

    @Test
    fun `a secret key is refused before anything else happens`() {
        for (key in listOf("cs_live_abcdefghijklmnop", "cs_", "CS_Live_abc", "cs_test_x")) {
            val error = assertFailsWith<InvalidConfigurationException>(key) { config(key) }
            assertEquals("SECRET_KEY_IN_APP", error.code, key)
            assertTrue(error.message!!.contains("must never ship in an app"), key)
        }
    }

    @Test
    fun `anything that is not a mobile publishable key is refused`() {
        for (key in listOf("", "cspk_", "pk_live_abcdef", "cspk-live-abc", "xcspk_abc", " cspk_abc", "cspk_ab cd", "cspk_abç")) {
            val error = assertFailsWith<InvalidConfigurationException>("'$key'") { config(key) }
            assertEquals("INVALID_MOBILE_KEY", error.code, key)
        }
        config("cspk_live_abc123")
    }

    @Test
    fun `the key is never printed`() {
        assertFalse(config("cspk_live_supersecretvalue").toString().contains("supersecretvalue"))
    }

    @Test
    fun `the LensJourney factory refuses a secret key too`() {
        assertFailsWith<InvalidConfigurationException> { LensJourney.mobile(JourneyConfig(), config("cs_live_abcdefghijklmnop")) }
    }

    @Test
    fun `a non https base url is refused unless allowed for debug`() {
        val plain = MobileKeyConfig("cspk_live_abc123", "http://localhost:3000", TestProvider())
        assertEquals("baseUrl must use https (allowInsecureHttp = true is for local debugging only)", assertFailsWith<InvalidRequestException> { MobileKeyTransport(plain) }.message)
        MobileKeyTransport(MobileKeyConfig("cspk_live_abc123", "http://localhost:3000", TestProvider(), allowInsecureHttp = true))
    }

    // nonce -> attest -> token -> journey header

    @Test
    fun `first journey call runs nonce attest token and sends the install token header`() = runBlocking {
        val stub = StubServer(start)
        val provider = RecordingProvider(AttestationPayload.PlayIntegrity("echo"))
        val store = InMemoryInstallIdStore()
        val transport = transport(stub, provider, store)

        val response = transport.send(journeyRequest())
        assertEquals(200, response.status)

        val calls = stub.calls
        assertEquals(listOf(nonceRoute, tokenRoute, evaluate), calls.map { it.path })
        val installId = assertNotNull(store.load())
        assertTrue(Regex("^[A-Za-z0-9_-]{16,64}$").matches(installId), "the install id must match the server's rule")

        val nonce = calls[0]
        assertEquals("POST", nonce.method)
        assertEquals("cspk_live_abc123", nonce.headers["X-API-Key"])
        assertEquals("application/json", nonce.contentType)
        assertEquals(installId, body(nonce)["install_id"]!!.jsonPrimitive.content)
        assertEquals("android", body(nonce)["platform"]!!.jsonPrimitive.content)

        assertEquals(listOf("challenge-1" to installId), provider.calls, "the provider attests over the server's challenge")

        val token = calls[1]
        assertEquals("cspk_live_abc123", token.headers["X-API-Key"])
        val tokenBody = body(token)
        assertEquals(installId, tokenBody["install_id"]!!.jsonPrimitive.content)
        assertEquals("android", tokenBody["platform"]!!.jsonPrimitive.content)
        assertEquals("nonce-1", tokenBody["nonce"]!!.jsonPrimitive.content)
        assertEquals("echo", tokenBody["android"]!!.jsonObject["integrity_token"]!!.jsonPrimitive.content)
        assertNull(tokenBody["ios"]); assertNull(tokenBody["debug"])

        val journey = calls[2]
        assertEquals("token-1", journey.headers["X-CS-Install-Token"])
        assertEquals("client_123", journey.headers["X-Client-Id"], "the caller's own headers survive")
        assertNull(journey.headers["X-API-Key"], "the mobile key is only sent on the mint calls")
        assertEquals("{}", journey.body!!.decodeToString())
        assertEquals(listOf(installId), provider.accepted)
    }

    @Test
    fun `a play integrity payload maps to the android wire field`() = runBlocking {
        val stub = StubServer(start)
        transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("pi-token"))).send(journeyRequest())
        assertEquals("pi-token", body(stub.calls[1])["android"]!!.jsonObject["integrity_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `app attest payloads map to the ios wire fields`() = runBlocking {
        val first = StubServer(start)
        transport(first, RecordingProvider(AttestationPayload.AppAttest("key-1", attestationObject = byteArrayOf(1, 2, 3)))).send(journeyRequest())
        val ios = body(first.calls[1])["ios"]!!.jsonObject
        assertEquals("key-1", ios["key_id"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), ios["attestation_object"]!!.jsonPrimitive.content)
        assertNull(ios["assertion"])

        val refresh = StubServer(start)
        transport(refresh, RecordingProvider(AttestationPayload.AppAttest("key-1", assertion = byteArrayOf(9, 9)))).send(journeyRequest())
        val ios2 = body(refresh.calls[1])["ios"]!!.jsonObject
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(9, 9)), ios2["assertion"]!!.jsonPrimitive.content)
        assertNull(ios2["attestation_object"])
    }

    @Test
    fun `the token and install id are reused across calls`() = runBlocking {
        val stub = StubServer(start)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")))
        repeat(3) { transport.send(journeyRequest()) }
        assertEquals(1, stub.calls.count { it.path == nonceRoute })
        assertEquals(1, stub.calls.count { it.path == tokenRoute })
        assertEquals(listOf("token-1", "token-1", "token-1"), stub.calls.filter { it.path == evaluate }.map { it.headers["X-CS-Install-Token"] })
    }

    @Test
    fun `a stored install id is reused and an invalid one is replaced`() = runBlocking {
        val stub = StubServer(start)
        transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")), InMemoryInstallIdStore("stored-install-id-0001")).send(journeyRequest())
        assertEquals("stored-install-id-0001", body(stub.calls[0])["install_id"]!!.jsonPrimitive.content)

        val junk = InMemoryInstallIdStore("short")
        val stub2 = StubServer(start)
        transport(stub2, RecordingProvider(AttestationPayload.PlayIntegrity("c")), junk).send(journeyRequest())
        val replaced = assertNotNull(junk.load())
        assertNotEquals("short", replaced)
        assertEquals(replaced, body(stub2.calls[0])["install_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `concurrent first calls mint once`() = runBlocking {
        val stub = StubServer(start, mintDelayMillis = 30)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")))
        val statuses = (1..5).map { async { transport.send(journeyRequest()).status } }.awaitAll()
        assertEquals(List(5) { 200 }, statuses)
        assertEquals(1, stub.calls.count { it.path == nonceRoute })
    }

    // refresh

    @Test
    fun `the token is refreshed five minutes before it expires`() = runBlocking {
        val stub = StubServer(start, lifetimeMillis = 3_600_000)
        val clock = AtomicLong(start)
        val provider = RecordingProvider(AttestationPayload.PlayIntegrity("c"))
        val transport = transport(stub, provider, clock = clock)
        transport.send(journeyRequest())

        clock.set(start + 3_600_000 - 301_000)
        transport.send(journeyRequest())
        assertEquals(1, stub.mintCount, "301 s before expiry the token is still used")

        clock.set(start + 3_600_000 - 299_000)
        transport.send(journeyRequest())
        assertEquals(2, stub.mintCount, "within 5 minutes of expiry the SDK mints a new token")
        assertEquals("token-2", stub.calls.last().headers["X-CS-Install-Token"])
        assertEquals(2, provider.accepted.size)
    }

    @Test
    fun `a short lived token is not minted again on every call`() = runBlocking {
        val stub = StubServer(start, lifetimeMillis = 120_000)
        val clock = AtomicLong(start)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")), clock = clock)
        transport.send(journeyRequest())
        clock.set(start + 30_000)
        transport.send(journeyRequest())
        assertEquals(1, stub.mintCount)
    }

    @Test
    fun `a failed refresh falls back to the token that is still valid`() = runBlocking {
        val stub = StubServer(start, lifetimeMillis = 3_600_000)
        val clock = AtomicLong(start)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")), clock = clock)
        transport.send(journeyRequest())
        stub.failNextNonce(503)
        clock.set(start + 3_600_000 - 200_000)
        assertEquals(200, transport.send(journeyRequest()).status)
        assertEquals("token-1", stub.calls.last().headers["X-CS-Install-Token"])
    }

    @Test
    fun `an expired token whose refresh fails surfaces the error`() = runBlocking {
        val stub = StubServer(start, lifetimeMillis = 3_600_000)
        val clock = AtomicLong(start)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")), clock = clock)
        transport.send(journeyRequest())
        stub.failNextNonce(503)
        clock.set(start + 3_601_000)
        val error = assertFailsWith<ServiceUnavailableException> { transport.send(journeyRequest()) }
        assertEquals(503, error.status)
    }

    // re-mint on 401 INSTALL_TOKEN_INVALID

    @Test
    fun `a 401 install token invalid mints once and retries the call`() = runBlocking {
        val stub = StubServer(start)
        stub.rejectJourneyToken("token-1", "INSTALL_TOKEN_INVALID")
        val response = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest("{\"a\":1}"))
        assertEquals(200, response.status)
        assertEquals(listOf(nonceRoute, tokenRoute, evaluate, nonceRoute, tokenRoute, evaluate), stub.calls.map { it.path })
        assertEquals("token-1", stub.calls[2].headers["X-CS-Install-Token"])
        assertEquals("token-2", stub.calls[5].headers["X-CS-Install-Token"])
        assertEquals(stub.calls[2].body!!.decodeToString(), stub.calls[5].body!!.decodeToString(), "the retry resends the same body")
    }

    @Test
    fun `the re-mint happens only once per call`() = runBlocking {
        val stub = StubServer(start)
        stub.rejectAllJourneyTokens("INSTALL_TOKEN_INVALID")
        val response = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest())
        assertEquals(401, response.status, "the second 401 is returned, not retried again")
        assertEquals(2, stub.calls.count { it.path == evaluate })
        assertEquals(2, stub.mintCount)
    }

    @Test
    fun `other unauthorized answers are not re-minted`() = runBlocking {
        val stub = StubServer(start)
        stub.rejectAllJourneyTokens("SOMETHING_ELSE")
        assertEquals(401, transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest()).status)
        assertEquals(1, stub.mintCount)
        assertEquals(1, stub.calls.count { it.path == evaluate })
    }

    @Test
    fun `two calls rejected together share one re-mint`() = runBlocking {
        val stub = StubServer(start, mintDelayMillis = 30)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")))
        transport.send(journeyRequest())
        stub.rejectJourneyToken("token-1", "INSTALL_TOKEN_INVALID")
        val statuses = (1..2).map { async { transport.send(journeyRequest()).status } }.awaitAll()
        assertEquals(listOf(200, 200), statuses)
        assertEquals(2, stub.mintCount, "one initial mint plus one shared re-mint")
    }

    @Test
    fun `a call rejected after another call already replaced the token reuses the new one`() = runBlocking {
        val stub = StubServer(start)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")))
        transport.send(journeyRequest())
        stub.rejectJourneyToken("token-1", "INSTALL_TOKEN_INVALID")
        val slow = async { transport.send(journeyRequest(headers = mapOf("X-Slow" to "1"))) }
        delay(30)
        val fast = transport.send(journeyRequest())
        val late = slow.await()
        assertEquals(200, fast.status); assertEquals(200, late.status)
        assertEquals(2, stub.mintCount, "the late 401 must reuse token-2, not mint token-3")
    }

    // typed attestation errors

    @Test
    fun `a provider failure is a typed attestation error`() = runBlocking {
        val stub = StubServer(start)
        val transport = transport(stub, ThrowingProvider(IllegalStateException("no play services")))
        val error = assertFailsWith<AttestationException> { transport.send(journeyRequest()) }
        assertEquals("ATTESTATION_FAILED", error.code)
        assertEquals(0, stub.calls.count { it.path == evaluate }, "no journey call is sent without a token")
    }

    @Test
    fun `a providers own typed error passes through`() = runBlocking {
        val own = AttestationException("This device cannot attest", "ATTESTATION_UNAVAILABLE")
        val error = assertFailsWith<AttestationException> { transport(StubServer(start), ThrowingProvider(own)).send(journeyRequest()) }
        assertTrue(error === own)
    }

    @Test
    fun `a server attestation refusal is typed and tells the provider`() = runBlocking {
        val stub = StubServer(start)
        stub.failNextToken(401, "ATTESTATION_FAILED")
        val provider = RecordingProvider(AttestationPayload.PlayIntegrity("c"))
        val transport = transport(stub, provider)
        val error = assertFailsWith<AttestationException> { transport.send(journeyRequest()) }
        assertEquals("ATTESTATION_FAILED", error.code); assertEquals(401, error.status); assertEquals("req-1", error.requestId)
        assertEquals(1, provider.rejected.size); assertEquals(0, provider.accepted.size)
        assertEquals(200, transport.send(journeyRequest()).status, "the next call tries again from scratch")
    }

    @Test
    fun `a disabled mobile channel is typed`() = runBlocking {
        val stub = StubServer(start)
        stub.failNextNonce(403, "MOBILE_CHANNEL_DISABLED")
        assertFailsWith<MobileChannelDisabledException> { transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest()) }
        Unit
    }

    @Test
    fun `a mint response the sdk cannot read is malformed`() = runBlocking {
        val stub = StubServer(start)
        stub.garbleNextToken()
        assertFailsWith<MalformedResponseException> { transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest()) }
        Unit
    }

    // mint failures the server really sends

    @Test
    fun `mint errors map to typed exceptions and never loop or send a journey call`() = runBlocking {
        data class Case(val route: String, val status: Int, val code: String, val type: kotlin.reflect.KClass<out LensJourneyException>)
        val cases = listOf(
            Case("nonce", 429, "RATE_LIMITED", RateLimitedException::class),
            Case("token", 429, "INSTALL_CAP_EXCEEDED", RateLimitedException::class),
            Case("token", 403, "INSTALL_REVOKED", InsufficientScopeException::class),
            Case("nonce", 403, "MOBILE_BINDING_NOT_ACTIVE", InsufficientScopeException::class),
            Case("nonce", 401, "INVALID_API_KEY", AuthenticationException::class),
            Case("token", 503, "MOBILE_CHANNEL_UNAVAILABLE", ServiceUnavailableException::class),
            Case("token", 400, "INVALID_REQUEST", InvalidRequestException::class),
        )
        for (c in cases) {
            val stub = StubServer(start)
            if (c.route == "nonce") stub.failNextNonce(c.status, c.code) else stub.failNextToken(c.status, c.code)
            val error = try { transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest()); null } catch (e: LensJourneyException) { e }
            assertNotNull(error, c.code)
            assertTrue(c.type.isInstance(error), "${c.code}: ${error::class}")
            assertEquals(c.code, error.code)
            assertEquals(0, stub.calls.count { it.path == evaluate }, "${c.code}: no journey call without a token")
            assertTrue(stub.calls.count { it.path == tokenRoute || it.path == nonceRoute } <= 2, "${c.code}: the mint is not retried")
        }
    }

    @Test
    fun `an expires_at with milliseconds is read`() = runBlocking {
        val stub = StubServer(start, lifetimeMillis = 3_600_000)
        val clock = AtomicLong(start)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")), clock = clock)
        transport.send(journeyRequest())
        clock.set(start + 3_600_000 - 299_000)
        transport.send(journeyRequest())
        assertEquals(2, stub.mintCount, "a .000Z expiry decodes, so the refresh window is honoured")
    }

    @Test
    fun `a token reply with an unreadable expiry or no token is malformed`() = runBlocking {
        val replies = listOf(
            buildJsonObject { put("install_token", "t"); put("expires_at", "tomorrow") },
            buildJsonObject { put("install_token", ""); put("expires_at", "2099-01-01T00:00:00.000Z") },
            buildJsonObject { put("expires_at", "2099-01-01T00:00:00.000Z") },
        )
        for (reply in replies) {
            val stub = StubServer(start)
            stub.replyToNextToken(reply)
            assertFailsWith<MalformedResponseException>(reply.toString()) { transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest()) }
        }
        Unit
    }

    @Test
    fun `a legacy envelope code is recognised for the re-mint`() = runBlocking {
        val stub = StubServer(start)
        stub.useLegacyEnvelope()
        stub.rejectJourneyToken("token-1", "INSTALL_TOKEN_INVALID")
        assertEquals(200, transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest()).status)
        assertEquals(2, stub.mintCount)
    }

    @Test
    fun `the mobile key is never sent on journey calls and the token never on mint calls`() = runBlocking {
        val stub = StubServer(start)
        stub.rejectJourneyToken("token-1", "INSTALL_TOKEN_INVALID")
        transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c"))).send(journeyRequest())   // mint, 401, re-mint, 200
        assertEquals(6, stub.calls.size)
        for (call in stub.calls) {
            if (call.path == nonceRoute || call.path == tokenRoute) {
                assertEquals("cspk_live_abc123", call.headers["X-API-Key"]); assertNull(call.headers["X-CS-Install-Token"])
            } else {
                assertNull(call.headers["X-API-Key"], "no journey call carries the mobile key, including the retry"); assertNotNull(call.headers["X-CS-Install-Token"])
            }
        }
    }

    // a failed refresh is not retried on every call

    @Test
    fun `a failed refresh is not retried on every call for thirty seconds`() = runBlocking {
        val stub = StubServer(start, lifetimeMillis = 3_600_000)
        val clock = AtomicLong(start)
        val transport = transport(stub, RecordingProvider(AttestationPayload.PlayIntegrity("c")), clock = clock)
        transport.send(journeyRequest())
        clock.set(start + 3_600_000 - 200_000)
        stub.failNextNonce(503)
        transport.send(journeyRequest())                                  // refresh fails, old token used
        val before = stub.calls.count { it.path == nonceRoute }
        repeat(5) { transport.send(journeyRequest()) }
        assertEquals(before, stub.calls.count { it.path == nonceRoute }, "no new mint attempts inside the back-off")
        clock.set(start + 3_600_000 - 200_000 + MobileKeyTransport.REFRESH_RETRY_DELAY_MILLIS + 1_000)
        transport.send(journeyRequest())
        assertEquals(2, stub.mintCount, "after the back-off the refresh succeeds")
    }

    // App Attest recovery (the server accepts a new attestation only for an install without an accepted key)

    private val firstAttestation = AttestationPayload.AppAttest("key-1", attestationObject = byteArrayOf(1))
    private val refreshAssertion = AttestationPayload.AppAttest("key-1", assertion = byteArrayOf(9))

    @Test
    fun `a refused first attestation starts a new install and mints once more in the same call`() = runBlocking {
        val stub = StubServer(start)
        stub.failNextToken(401, "ATTESTATION_FAILED")
        val provider = RecordingProvider(firstAttestation)
        val store = InMemoryInstallIdStore("restored-install-id-1")
        assertEquals(200, transport(stub, provider, store).send(journeyRequest()).status)
        val ids = stub.calls.filter { it.path == nonceRoute }.map { body(it)["install_id"]!!.jsonPrimitive.content }
        assertEquals(2, ids.size); assertEquals("restored-install-id-1", ids[0]); assertNotEquals("restored-install-id-1", ids[1])
        assertEquals(ids[1], store.load(), "the new install id is persisted")
        assertTrue(Regex("^[A-Za-z0-9_-]{16,64}$").matches(ids[1]), "the new id matches the server's rule")
        val tokenBodies = stub.calls.filter { it.path == tokenRoute }.map { body(it) }
        assertEquals(listOf("nonce-1", "nonce-2"), tokenBodies.map { it["nonce"]!!.jsonPrimitive.content }, "the retry uses the nonce issued for the new id")
        assertEquals(ids, tokenBodies.map { it["install_id"]!!.jsonPrimitive.content }, "each token call carries the id its nonce was issued for")
        assertEquals(listOf("restored-install-id-1"), provider.replaced)
    }

    @Test
    fun `a refused first attestation retries only once per call`() = runBlocking {
        val stub = StubServer(start)
        stub.failEveryToken(401, "ATTESTATION_FAILED")
        val provider = RecordingProvider(firstAttestation)
        assertFailsWith<AttestationException> { transport(stub, provider).send(journeyRequest()) }
        assertEquals(2, stub.calls.count { it.path == tokenRoute }, "one try and one retry as a new install, never a loop")
        assertEquals(1, provider.replaced.size, "the retry does not start a further install")
    }

    @Test
    fun `one refused assertion is tolerated and the second in a row starts a new install`() = runBlocking {
        val stub = StubServer(start)
        val provider = RecordingProvider(refreshAssertion)
        val store = InMemoryInstallIdStore("established-install-1")
        val transport = transport(stub, provider, store)
        stub.failNextToken(401, "ATTESTATION_FAILED")
        assertFailsWith<AttestationException> { transport.send(journeyRequest()) }
        assertEquals(emptyList(), provider.replaced, "a single refusal keeps the install and its key")
        assertEquals("established-install-1", store.load())
        stub.failNextToken(401, "ATTESTATION_FAILED")
        assertEquals(200, transport.send(journeyRequest()).status)         // second refusal in a row: new install, retried in the call
        assertEquals(listOf("established-install-1"), provider.replaced)
        assertNotEquals("established-install-1", store.load())
    }

    @Test
    fun `a success resets the refused refresh count`() = runBlocking {
        val stub = StubServer(start, lifetimeMillis = 3_600_000)
        val clock = AtomicLong(start)
        val provider = RecordingProvider(refreshAssertion)
        val transport = transport(stub, provider, InMemoryInstallIdStore("established-install-1"), clock)
        stub.failNextToken(401, "ATTESTATION_FAILED")
        assertFailsWith<AttestationException> { transport.send(journeyRequest()) }
        transport.send(journeyRequest())                                    // mints fine
        clock.set(start + 3_600_000 - 200_000)
        stub.failNextToken(401, "ATTESTATION_FAILED")
        transport.send(journeyRequest())                                    // refresh refused, old token used
        assertEquals(emptyList(), provider.replaced, "two refusals with a success between them do not end the install")
    }

    @Test
    fun `a play integrity refusal never starts a new install`() = runBlocking {
        val stub = StubServer(start)
        stub.failNextToken(401, "ATTESTATION_FAILED")
        val provider = RecordingProvider(AttestationPayload.PlayIntegrity("t"))
        val store = InMemoryInstallIdStore("established-install-1")
        assertFailsWith<AttestationException> { transport(stub, provider, store).send(journeyRequest()) }
        assertEquals(emptyList(), provider.replaced); assertEquals("established-install-1", store.load())
    }

    // nothing secret is printed

    @Test
    fun `payloads and requests do not print their secrets`() {
        val request = TransportRequest("POST", "/x", mapOf("X-API-Key" to "cspk_live_abc123", "X-CS-Install-Token" to "tok-secret"), "body".encodeToByteArray())
        val text = listOf(request, AttestationPayload.PlayIntegrity("chal-secret"), AttestationPayload.PlayIntegrity("pi-secret"), AttestationPayload.AppAttest("key-secret", byteArrayOf(1))).joinToString(" ") { it.toString() }
        for (secret in listOf("cspk_live_abc123", "tok-secret", "chal-secret", "pi-secret", "key-secret")) assertFalse(text.contains(secret), secret)
    }

    // through the real LensJourney

    @Test
    fun `LensJourney start runs through the mobile transport`() = runBlocking {
        val stub = StubServer(start)
        val journey = LensJourney(JourneyConfig(), MobileKeyTransport(config("cspk_live_abc123"), stub, { start }))
        val state = journey.start("single-vision", FrameRef(sku = "S-1"))
        assertEquals(Terminal.CHOICE, state.terminal)
        val last = stub.calls.last()
        assertEquals(evaluate, last.path)
        assertEquals("token-1", last.headers["X-CS-Install-Token"])
    }

    // helpers

    private fun config(key: String, provider: AttestationProvider = TestProvider(), store: InstallIdStore = InMemoryInstallIdStore()) =
        MobileKeyConfig(key, "https://shop.example.com", provider, store)

    private fun transport(stub: StubServer, provider: AttestationProvider, store: InstallIdStore = InMemoryInstallIdStore(), clock: AtomicLong = AtomicLong(start)) =
        MobileKeyTransport(config("cspk_live_abc123", provider, store), stub, { clock.get() })

    private fun journeyRequest(body: String = "{}", headers: Map<String, String> = emptyMap()) =
        TransportRequest("POST", evaluate, mapOf("X-Client-Id" to "client_123") + headers, body.encodeToByteArray(), "application/json")

    private fun body(request: TransportRequest): JsonObject = LensJson.instance.parseToJsonElement(request.body!!.decodeToString()).jsonObject
}

private class RecordingProvider(private val payload: AttestationPayload) : AttestationProvider {
    val calls = mutableListOf<Pair<String, String>>()
    val accepted = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    val replaced = mutableListOf<String>()
    override suspend fun attest(challenge: String, installId: String): AttestationPayload { calls += challenge to installId; return payload }
    override suspend fun attestationAccepted(installId: String) { accepted += installId }
    override suspend fun attestationRejected(installId: String) { rejected += installId }
    override suspend fun installIdReplaced(oldInstallId: String) { replaced += oldInstallId }
}

/** An ordinary app provider: a Play Integrity token. */
private class TestProvider : AttestationProvider {
    override suspend fun attest(challenge: String, installId: String): AttestationPayload = AttestationPayload.PlayIntegrity("test-integrity-token")
}

private class ThrowingProvider(private val error: Exception) : AttestationProvider {
    override suspend fun attest(challenge: String, installId: String): AttestationPayload = throw error
}

/** A scripted VARAi server: nonce and token routes mint "nonce-N" / "token-N", journey routes answer a choice step. */
private class StubServer(
    private val nowMillis: Long,
    private val lifetimeMillis: Long = 3_600_000,
    private val mintDelayMillis: Long = 0,
) : Transport {
    private val lock = Any()
    val calls: List<TransportRequest> get() = synchronized(lock) { recorded.toList() }
    var mintCount = 0
        private set
    private val recorded = mutableListOf<TransportRequest>()
    private var nonceCount = 0
    private var nonceFailure: Pair<Int, String?>? = null
    private var tokenFailure: Pair<Int, String?>? = null
    private var garble = false
    private val rejectedTokens = mutableMapOf<String, String>()
    private var rejectAll: String? = null
    private var nextTokenReply: JsonObject? = null
    private var everyTokenFailure: Pair<Int, String>? = null
    private var legacyEnvelope = false

    fun failNextNonce(status: Int, code: String? = null) = synchronized(lock) { nonceFailure = status to code }
    fun failNextToken(status: Int, code: String) = synchronized(lock) { tokenFailure = status to code }
    fun garbleNextToken() = synchronized(lock) { garble = true }
    fun replyToNextToken(reply: JsonObject) = synchronized(lock) { nextTokenReply = reply }
    fun failEveryToken(status: Int, code: String) = synchronized(lock) { everyTokenFailure = status to code }
    fun useLegacyEnvelope() = synchronized(lock) { legacyEnvelope = true }
    fun rejectJourneyToken(token: String, code: String) = synchronized(lock) { rejectedTokens[token] = code }
    fun rejectAllJourneyTokens(code: String) = synchronized(lock) { rejectAll = code }

    override suspend fun send(request: TransportRequest): TransportResponse {
        synchronized(lock) { recorded += request }
        if (request.path.endsWith("/install-nonce")) {
            if (mintDelayMillis > 0) delay(mintDelayMillis)
            synchronized(lock) {
                nonceFailure?.let { nonceFailure = null; return error(it.first, it.second) }
                nonceCount += 1
                return ok(buildJsonObject { put("nonce", "nonce-$nonceCount"); put("challenge", "challenge-$nonceCount"); put("expires_at", iso(nowMillis + 300_000)) })
            }
        }
        if (request.path.endsWith("/install-token")) {
            synchronized(lock) {
                tokenFailure?.let { tokenFailure = null; return error(it.first, it.second) }
                everyTokenFailure?.let { return error(it.first, it.second) }
                nextTokenReply?.let { nextTokenReply = null; return ok(it) }
                if (garble) { garble = false; return TransportResponse(200, emptyMap(), "{\"install_token\":5}".encodeToByteArray()) }
                mintCount += 1
                return ok(buildJsonObject { put("install_token", "token-$mintCount"); put("expires_at", iso(nowMillis + lifetimeMillis)) })
            }
        }
        if (request.headers["X-Slow"] != null) delay(150)
        val token = request.headers["X-CS-Install-Token"].orEmpty()
        synchronized(lock) {
            (rejectAll ?: rejectedTokens[token])?.let { return error(401, it) }
        }
        return TransportResponse(
            200, emptyMap(),
            "{\"workflow_version\":\"1\",\"category\":\"single-vision\",\"terminal\":\"choice\",\"step\":{\"id\":\"n\",\"type\":\"choice\",\"label\":\"L\",\"selection_mode\":\"single\",\"options\":[{\"id\":\"o\",\"label\":\"o\",\"enabled\":true,\"recommended\":false}]},\"selections\":{},\"auto_selected\":[],\"violations\":[],\"settings_warnings\":[]}".encodeToByteArray(),
        )
    }

    private fun ok(body: JsonObject) = TransportResponse(200, emptyMap(), body.toString().encodeToByteArray())
    private fun error(status: Int, code: String?): TransportResponse {
        // The real mint and token errors are Surface C: the code is in `error`, there is no `code` field. Journey routes may still answer
        // the legacy `{ success: false, error, code }` envelope (useLegacyEnvelope).
        val body = if (legacyEnvelope && code != null) buildJsonObject { put("success", false); put("error", "Unauthorized"); put("code", code) }
        else buildJsonObject { put("error", code ?: "ERR"); put("message", "stub"); put("request_id", "req-1") }
        return TransportResponse(status, emptyMap(), body.toString().encodeToByteArray())
    }
    /** Like the server's `toISOString()`: always three fraction digits (`2026-11-13T10:00:00.000Z`). */
    private fun iso(millis: Long) = java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(millis))
}

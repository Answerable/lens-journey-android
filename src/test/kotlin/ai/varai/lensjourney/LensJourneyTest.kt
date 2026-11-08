package ai.varai.lensjourney

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LensJourneyTest {
    private val json = LensJson.instance

    @Test
    fun `decodes every successful evaluate fixture and the captured configuration and ocr responses`() {
        fixtureFiles("evaluate-").forEach { path ->
            val fixture = fixture(path)
            val response = fixture["response"]!!.jsonObject
            if (response["status"]!!.jsonPrimitive.int == 200) {
                json.decodeFromJsonElement(JourneyState.serializer(), response["body"]!!)
            }
        }
        // The captured server output: `subtotal` is the decimal STRING "45000.00", `confidence` is the string "high".
        val configuration = json.decodeFromJsonElement(LensConfiguration.serializer(), responseBody("configurations-201-captured.json"))
        assertEquals("INR", configuration.currency)
        assertEquals(0, BigDecimal("45000.00").compareTo(configuration.subtotal))
        assertEquals(4_500_000L, configuration.subtotalPaise)
        val draft = json.decodeFromJsonElement(OcrResponse.serializer(), responseBody("rx-ocr-200-captured.json"))
        assertFalse(draft.prescription.confirmed)
        assertEquals(OcrConfidence.HIGH, draft.prescription.confidence)
        val low = json.decodeFromJsonElement(OcrResponse.serializer(), responseBody("rx-ocr-200-low-confidence-captured.json"))
        assertEquals(OcrConfidence.LOW, low.prescription.confidence)
    }

    @Test
    fun `ocr confidence tolerates values the server may add later and a number`() {
        fun confidenceOf(value: String) = json.decodeFromString(
            OcrResponse.serializer(),
            """{"prescription":{"od":{},"os":{},"confidence":$value}}""",
        ).prescription.confidence
        assertEquals(OcrConfidence.UNKNOWN, confidenceOf("\"medium\""))
        assertEquals(OcrConfidence.UNKNOWN, confidenceOf("0.9"))
        assertEquals(null, json.decodeFromString(OcrResponse.serializer(), """{"prescription":{"od":{},"os":{}}}""").prescription.confidence)
    }

    @Test
    fun `subtotal decodes from a decimal string or a number and rejects garbage`() {
        fun subtotalOf(value: String) = json.decodeFromString(LensConfiguration.serializer(), """{"id":"x","subtotal":$value}""").subtotal
        assertEquals(0, BigDecimal("1234.50").compareTo(subtotalOf("\"1234.50\"")))
        assertEquals(0, BigDecimal("120").compareTo(subtotalOf("120")))
        assertEquals(null, subtotalOf("null"))
        assertEquals(null, json.decodeFromString(LensConfiguration.serializer(), """{"id":"x"}""").subtotal)
        assertFailsWith<SerializationException> { subtotalOf("\"free\"") }
    }

    @Test
    fun `whole journey sends cumulative picks back is local and finishes with stable retry key`() = runBlocking<Unit> {
        val transport = FixtureTransport(
            queued("evaluate-first-choice.json"),
            queued("evaluate-second-choice.json"),
            queued("evaluate-summary.json"),
            queued("evaluate-summary.json"),
            TransportResponse(503, emptyMap(), errorBody("EVALUATION_UNAVAILABLE")),
            queued("configurations-201-captured.json"),
        )
        val journey = LensJourney(JourneyConfig(sessionId = "session-12345678", clientId = "client-12345678"), transport)
        assertEquals("vision-option", journey.start("single-vision", FrameRef(sku = "FIXTURE-SKU")).step?.id)
        assertEquals("ar-index", journey.select("anti-reflective").step?.id)
        assertEquals(Terminal.SUMMARY, journey.select("opt-anti-reflective-150-stock").terminal)

        val callsBeforeBack = transport.requests.size
        assertEquals("ar-index", journey.back().step?.id)
        assertEquals(callsBeforeBack, transport.requests.size)
        assertEquals(Terminal.SUMMARY, journey.select("opt-anti-reflective-150-stock").terminal)
        val configuration = journey.finish()
        assertEquals("00000000-0000-4000-8000-000000000001", configuration.id)
        assertEquals(0, BigDecimal("45000.00").compareTo(configuration.subtotal))

        val evaluateBodies = transport.requests.filter { it.path.endsWith("/evaluate") }.map { parseBody(it) }
        assertEquals(setOf("vision-option", "ar-index"), evaluateBodies.last()["selections"]!!.jsonObject.keys)
        val finishBodies = transport.requests.filter { it.path.endsWith("/configurations") }.map { parseBody(it) }
        assertEquals(2, finishBodies.size)
        // The frame SKU goes where POST /configurations reads it (product_sku), not only in a `frame` object it ignores.
        assertEquals("FIXTURE-SKU", finishBodies.last()["product_sku"]!!.jsonPrimitive.content)
        assertEquals(null, finishBodies.last()["product_id"])
        assertEquals(finishBodies[0]["idempotency_key"], finishBodies[1]["idempotency_key"])
        assertEquals("session-12345678", finishBodies[0]["session_id"]!!.jsonPrimitive.content)
        assertTrue(finishBodies[0]["selections"] is JsonArray)
    }

    @Test
    fun `violations and prescription errors are surfaced`() {
        val violation = decodeState("evaluate-violation-off-path.json")
        assertEquals("SELECTION_OFF_PATH", violation.violations.single().code)
        val prescription = decodeState("evaluate-prescription-out-of-range.json").prescription
        assertEquals(listOf("SPH_OUT_OF_RANGE", "RX_INCOMPLETE"), prescription?.errors?.map { it.code })
        assertEquals(-30.0, prescription?.ranges?.sph?.min)
    }

    @Test
    fun `scan returns an unconfirmed draft without changing routing`() = runBlocking<Unit> {
        val transport = FixtureTransport(queued("rx-ocr-200-captured.json"), queued("evaluate-first-choice.json"))
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        val draft = journey.scanPrescription(ImageData(byteArrayOf(1, 2, 3), "image/jpeg")) {
            PreparedImage(it.bytes, it.mimeType)
        }
        assertFalse(draft.confirmed)
        journey.start("single-vision", null)
        assertFalse(parseBody(transport.requests.last()).containsKey("prescription"))
    }

    @Test
    fun `client id validates and generated ids satisfy the contract`() {
        assertFailsWith<InvalidClientIdException> { JourneyConfig(clientId = "bad id!") }
        val first = JourneyConfig()
        val second = JourneyConfig()
        assertTrue(first.clientId.matches(Regex("^[A-Za-z0-9_-]{8,64}$")))
        assertTrue(first.sessionId.isNotBlank())
        assertNotEquals(first.clientId, second.clientId)
    }

    @Test
    fun `evaluate guards body selection counts and identifier lengths before transport`() = runBlocking<Unit> {
        val transport = FixtureTransport()
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678", currency = "x".repeat(17_000)), transport)
        assertFailsWith<RequestTooLargeException> { journey.start("single-vision", null) }
        assertFailsWith<InvalidRequestException> { journey.start("x".repeat(101), null) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `unknown fields and terminal enum values are tolerated`() {
        val original = responseBody("evaluate-first-choice.json").jsonObject
        val changed = JsonObject(original + ("future_field" to JsonPrimitive(true)) + ("terminal" to JsonPrimitive("future-terminal")))
        assertEquals(Terminal.UNKNOWN, json.decodeFromJsonElement(JourneyState.serializer(), changed).terminal)
    }

    @Test
    fun `all status mappings and one retry rule are enforced`() = runBlocking<Unit> {
        val mappings = listOf(
            400 to InvalidRequestException::class,
            401 to AuthenticationException::class,
            403 to InsufficientScopeException::class,
            404 to WorkflowNotPublishedException::class,
            413 to PayloadTooLargeException::class,
            415 to UnsupportedMediaTypeException::class,
            422 to ConfigurationRejectedException::class,
        )
        for ((status, type) in mappings) {
            val transport = FixtureTransport(TransportResponse(status, emptyMap(), errorBody(if (status == 403) "INSUFFICIENT_SCOPE" else "TEST")))
            val error = assertFailsWith<LensJourneyException> {
                LensJourney(JourneyConfig(clientId = "client-12345678"), transport).start("single-vision", null)
            }
            assertTrue(type.isInstance(error), "status $status mapped to ${error::class}")
            assertEquals(1, transport.requests.size)
        }
        for (status in listOf(429, 503)) {
            val transport = FixtureTransport(
                TransportResponse(status, mapOf("Retry-After" to "0"), errorBody("RETRY")),
                TransportResponse(status, emptyMap(), errorBody("RETRY")),
            )
            assertFailsWith<LensJourneyException> {
                LensJourney(JourneyConfig(clientId = "client-12345678"), transport).start("single-vision", null)
            }
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun `busy rejects an overlapping stateful call`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val transport = BlockingTransport(gate, queued("evaluate-first-choice.json"))
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        val first = async { journey.start("single-vision", null) }
        transport.entered.await()
        assertFailsWith<BusyException> { journey.start("other", null) }
        gate.complete(Unit)
        first.await()
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `single selection replaces while invalid and failed selections preserve state`() = runBlocking<Unit> {
        val choice = stateResponse("node", "single", listOf("one", "two", "disabled"))
        val transport = FixtureTransport(choice, choice, TransportResponse(503, emptyMap(), errorBody("DOWN")), TransportResponse(503, emptyMap(), errorBody("DOWN")), choice)
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport, sleep = { _ -> })
        journey.start("same", null)
        journey.select("one")
        assertFailsWith<InvalidSelectionException> { journey.select("disabled") }
        assertEquals(2, transport.requests.size)
        assertFailsWith<ServiceUnavailableException> { journey.select("two") }
        journey.select("two")
        val selections = parseBody(transport.requests.last())["selections"]!!.jsonObject["node"] as JsonArray
        assertEquals(listOf("two"), selections.map { it.jsonPrimitive.content })
    }

    @Test
    fun `finish requires summary locally`() = runBlocking<Unit> {
        val transport = FixtureTransport(stateResponse("node", "single", listOf("one")), stateResponse("node", "single", listOf("one")))
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        journey.start("same", null); journey.select("one")
        assertFailsWith<JourneyIncompleteException> { journey.finish() }
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `start keeps prescription for same category and clears it for a new category`() = runBlocking<Unit> {
        val transport = FixtureTransport(stateResponse("node", "single", listOf("one")), stateResponse("node", "single", listOf("one")), stateResponse(), stateResponse())
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        val rx = Prescription(EyePrescription(sph = 1.0), EyePrescription(sph = 1.0))
        journey.setPrescription(rx)
        journey.start("same", null); journey.select("one"); journey.start("same", null); journey.start("new", null)
        assertTrue(parseBody(transport.requests[0]).containsKey("prescription"))
        assertTrue(parseBody(transport.requests[2]).containsKey("prescription"))
        assertEquals(emptySet(), parseBody(transport.requests[2])["selections"]!!.jsonObject.keys)
        assertFalse(parseBody(transport.requests[3]).containsKey("prescription"))
    }

    @Test
    fun `retry after supports clamped seconds dates and safe defaults`() = runBlocking<Unit> {
        val now = 1_700_000_000_000L
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(now + 5_000).atZone(java.time.ZoneOffset.UTC))
        val cases = listOf("5" to 5_000L, "999999999999999999999" to 1_000L, "garbage" to 1_000L, "-3" to 1_000L, date to 5_000L)
        for ((header, expected) in cases) {
            val delays = mutableListOf<Long>()
            val transport = FixtureTransport(TransportResponse(429, mapOf("Retry-After" to header), errorBody("RATE")), stateResponse())
            LensJourney(JourneyConfig(clientId = "client-12345678"), transport, sleep = { delays += it }, clockMillis = { now }).start("same", null)
            assertEquals(listOf(expected), delays, header)
        }
    }

    @Test
    fun `image guards reject non images and oversized prepared bytes before upload`() = runBlocking<Unit> {
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), FixtureTransport())
        assertFailsWith<UnsupportedMediaTypeException> {
            journey.scanPrescription(ImageData(byteArrayOf(1), "text/plain")) { PreparedImage(it.bytes, it.mimeType) }
        }
        assertFailsWith<PayloadTooLargeException> {
            journey.scanPrescription(ImageData(byteArrayOf(1), "image/jpeg")) { PreparedImage(ByteArray(10 * 1024 * 1024 + 1), "image/jpeg") }
        }
    }

    @Test
    fun `fixture top level response keys cannot drift silently`() {
        val allowed = setOf(
            "success", "workflow_version", "category", "terminal", "step", "selections", "auto_selected",
            "violations", "prescription", "settings_warnings", "gate", "id", "lens_summary", "subtotal",
            "subtotal_paise", "subtotal_minor", "currency_decimals", "price_basis", "price_basis_note", "currency", "breakdown", "inherited_from_partner", "prescription_on_file",
            "photo_attached", "photo_attach_error", "photos", "error", "message", "request_id",
            "documentation_url", "code", "details", "provider", "prescription_id", "upload_capability",
        )
        fixtureFiles().forEach { path ->
            val keys = fixture(path)["response"]!!.jsonObject["body"]!!.jsonObject.keys
            assertEquals(emptySet(), keys - allowed, "Unmapped response keys in ${path.fileName}")
        }
    }

    @Test
    fun `evaluate requests match the shared fixture requests path body and client id header`() = runBlocking<Unit> {
        val first = fixtureRequest("evaluate-first-choice.json")["body"]!!.jsonObject
        val transport = FixtureTransport(
            queued("evaluate-first-choice.json"),
            queued("evaluate-second-choice.json"),
            queued("evaluate-summary.json"),
        )
        val journey = LensJourney(JourneyConfig(sessionId = "session-12345678", clientId = "client-12345678"), transport)
        // The fixture prescription has os.axis "" (unset); canonical() drops empty strings so the Double model can decode it.
        journey.setPrescription(json.decodeFromJsonElement(Prescription.serializer(), canonical(first["prescription"]!!)))
        journey.start(first["category"]!!.jsonPrimitive.content, json.decodeFromJsonElement(FrameRef.serializer(), first["frame"]!!))
        journey.select("anti-reflective")
        journey.select("opt-anti-reflective-150-stock")

        assertEquals(3, transport.requests.size)
        listOf("evaluate-first-choice.json", "evaluate-second-choice.json", "evaluate-summary.json").forEachIndexed { index, name ->
            val expected = fixtureRequest(name)
            val sent = transport.requests[index]
            assertEquals("POST", sent.method, name)
            assertEquals("/api/v1/lens-journey" + expected["path"]!!.jsonPrimitive.content, sent.path, name)
            assertEquals("application/json", sent.contentType, name)
            assertEquals("client-12345678", sent.headers["X-Client-Id"], name)
            val sentBody = parseBody(sent)
            // The one documented difference: the SDK always states vision_type, the captured fixtures predate it.
            assertEquals("distance", sentBody["vision_type"]!!.jsonPrimitive.content, name)
            assertEquals(canonical(expected["body"]!!), canonical(JsonObject(sentBody - "vision_type")), name)
        }
    }

    @Test
    fun `finish reuses one idempotency key across repeated calls until the selections change`() = runBlocking<Unit> {
        val transport = ScriptedTransport(
            stateResponse("node", "single", listOf("one", "two")),
            stateResponse(),
            IOException("offline"),
            TransportResponse(503, emptyMap(), errorBody("EVALUATION_UNAVAILABLE")),
            TransportResponse(503, emptyMap(), errorBody("EVALUATION_UNAVAILABLE")),
            queued("configurations-201-captured.json"),
            stateResponse(),
            queued("configurations-201-captured.json"),
        )
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport, sleep = { _ -> })
        journey.start("same", null)
        journey.select("one")
        assertFailsWith<NetworkException> { journey.finish() }
        assertFailsWith<ServiceUnavailableException> { journey.finish() }
        journey.finish()
        journey.back()
        journey.select("two")
        journey.finish()

        val keys = idempotencyKeys(transport)
        assertEquals(5, keys.size)
        assertEquals(1, keys.take(4).distinct().size, "the key must survive failed finish calls: $keys")
        assertNotEquals(keys[0], keys[4])
    }

    @Test
    fun `finish after setPrescription uses a new idempotency key`() = runBlocking<Unit> {
        val transport = ScriptedTransport(
            stateResponse("node", "single", listOf("one")),
            stateResponse(),
            IOException("offline"),
            stateResponse(),
            queued("configurations-201-captured.json"),
        )
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        journey.start("same", null)
        journey.select("one")
        assertFailsWith<NetworkException> { journey.finish() }
        journey.setPrescription(Prescription(EyePrescription(sph = 1.0), EyePrescription(sph = 1.0)))
        journey.finish()

        val keys = idempotencyKeys(transport)
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `finish after a fresh start and selection uses a new idempotency key`() = runBlocking<Unit> {
        val transport = ScriptedTransport(
            stateResponse("node", "single", listOf("one")),
            stateResponse(),
            IOException("offline"),
            stateResponse("node", "single", listOf("one")),
            stateResponse(),
            queued("configurations-201-captured.json"),
        )
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        journey.start("same", null)
        journey.select("one")
        assertFailsWith<NetworkException> { journey.finish() }
        journey.start("same", null)
        journey.select("one")
        journey.finish()

        val keys = idempotencyKeys(transport)
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `back after setPrescription restores the state that matches the remaining selections`() = runBlocking<Unit> {
        val transport = FixtureTransport(
            stateResponse("node", "single", listOf("one", "two")),
            stateResponse(),
            stateResponse(rxComplete = true),
            stateResponse(),
            queued("configurations-201-captured.json"),
        )
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        journey.start("same", null)
        journey.select("one")
        journey.setPrescription(Prescription(EyePrescription(sph = 1.0), EyePrescription(sph = 1.0)))

        val calls = transport.requests.size
        val back = journey.back()
        assertEquals(calls, transport.requests.size)
        assertEquals(Terminal.CHOICE, back.terminal)
        assertEquals("node", back.step?.id)
        assertEquals(true, back.prescription?.complete, "the prescription check follows the current prescription")
        // Nothing is selected any more, so there is nothing to finish.
        assertFailsWith<JourneyIncompleteException> { journey.finish() }

        journey.select("two")
        journey.finish()
        val evaluate = parseBody(transport.requests[3])
        assertEquals(listOf("two"), (evaluate["selections"]!!.jsonObject["node"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(setOf("node"), evaluate["selections"]!!.jsonObject.keys)
        assertTrue(evaluate.containsKey("prescription"))
        val finishBody = parseBody(transport.requests[4])
        assertEquals(
            listOf("node" to "two"),
            (finishBody["selections"] as JsonArray).map { it.jsonObject["node_id"]!!.jsonPrimitive.content to it.jsonObject["option_id"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `back restores exactly the picks that matched the earlier state`() = runBlocking<Unit> {
        val transport = FixtureTransport(
            stateResponse("a", "single", listOf("a1")),
            stateResponse("b", "multiple", listOf("b1", "b2")),
            stateResponse(),
            stateResponse(),
        )
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        journey.start("same", null)
        journey.select("a1")
        journey.select("b1")
        assertEquals("b", journey.back().step?.id)
        journey.select("b2")
        val selections = parseBody(transport.requests[3])["selections"]!!.jsonObject
        assertEquals(setOf("a", "b"), selections.keys)
        assertEquals(listOf("a1"), (selections["a"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("b2"), (selections["b"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun `back with nothing to undo is an invalid request before start right after start and after undoing everything`() = runBlocking<Unit> {
        val transport = FixtureTransport(stateResponse("node", "single", listOf("one")), stateResponse())
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        assertEquals("There is no previous selection", assertFailsWith<InvalidRequestException> { journey.back() }.message)
        journey.start("same", null)
        assertEquals("There is no previous selection", assertFailsWith<InvalidRequestException> { journey.back() }.message)
        journey.select("one")
        assertEquals("node", journey.back().step?.id)
        assertEquals("There is no previous selection", assertFailsWith<InvalidRequestException> { journey.back() }.message)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `a 400 INVALID_CLIENT_ID maps to the typed client id error and is never retried`() = runBlocking<Unit> {
        val transport = FixtureTransport(queued("evaluate-400-bad-client-id.json"))
        val error = assertFailsWith<InvalidClientIdException> {
            LensJourney(JourneyConfig(clientId = "client-12345678"), transport).start("single-vision", null)
        }
        assertEquals("INVALID_CLIENT_ID", error.code)
        assertEquals(400, error.status)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `cancelling during a request surfaces cancellation sends no retry and frees the journey`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val transport = BlockingTransport(gate, queued("evaluate-first-choice.json"))
        val delays = mutableListOf<Long>()
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport, sleep = { delays += it })
        var captured: Throwable? = null
        val job = launch {
            try { journey.start("single-vision", null) } catch (error: Throwable) { captured = error; throw error }
        }
        transport.entered.await()
        job.cancelAndJoin()

        assertTrue(captured is CancellationException, "cancellation was surfaced as $captured")
        assertEquals(1, transport.requests.size)
        assertTrue(delays.isEmpty())
        gate.complete(Unit)
        assertEquals("vision-option", journey.start("single-vision", null).step?.id)
    }

    @Test
    fun `transport refuses a non https base url unless insecure http is explicitly allowed`() {
        for (url in listOf("http://shop.example.com/lens-proxy", "HTTP://shop.example.com", "ftp://shop.example.com", "shop.example.com/lens-proxy", "")) {
            assertFailsWith<InvalidRequestException>("accepted '$url'") { HttpUrlConnectionTransport(url) }
        }
        HttpUrlConnectionTransport("https://shop.example.com/lens-proxy")
        HttpUrlConnectionTransport("HTTPS://shop.example.com")
        // The README call shape: trailing header lambda after the defaulted allowInsecureHttp parameter.
        HttpUrlConnectionTransport("https://customer.example/lens-proxy") { mapOf("Authorization" to "Bearer token") }
        HttpUrlConnectionTransport("http://10.0.2.2:3000", allowInsecureHttp = true)
        assertFailsWith<InvalidRequestException> { HttpUrlConnectionTransport("ftp://shop.example.com", allowInsecureHttp = true) }
    }

    @Test
    fun `the insecure flag really lets cleartext through to a local server`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "ok".encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val transport = HttpUrlConnectionTransport("http://127.0.0.1:${server.address.port}", allowInsecureHttp = true)
            val response = transport.send(TransportRequest("GET", "/ping"))
            assertEquals(200, response.status)
            assertEquals("ok", response.body.decodeToString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `scan takes the operation lock in both directions and releases it on failure`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val transport = BlockingTransport(gate, queued("rx-ocr-200-captured.json"))
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
        val image = ImageData(byteArrayOf(1, 2, 3), "image/jpeg")
        val scan = async { journey.scanPrescription(image) { PreparedImage(it.bytes, it.mimeType) } }
        transport.entered.await()
        assertFailsWith<BusyException> { journey.start("same", null) }
        assertFailsWith<BusyException> { journey.scanPrescription(image) { PreparedImage(it.bytes, it.mimeType) } }
        gate.complete(Unit)
        scan.await()
        assertEquals(1, transport.requests.size)

        // A scan rejected before any request still frees the lock.
        val next = FixtureTransport(stateResponse("node", "single", listOf("one")))
        val second = LensJourney(JourneyConfig(clientId = "client-12345678"), next)
        assertFailsWith<UnsupportedMediaTypeException> { second.scanPrescription(ImageData(byteArrayOf(1), "text/plain")) { PreparedImage(it.bytes, it.mimeType) } }
        assertEquals("node", second.start("same", null).step?.id)

        // A start in flight rejects a scan.
        val startGate = CompletableDeferred<Unit>()
        val blocked = BlockingTransport(startGate, queued("evaluate-first-choice.json"))
        val third = LensJourney(JourneyConfig(clientId = "client-12345678"), blocked)
        val start = async { third.start("single-vision", null) }
        blocked.entered.await()
        assertFailsWith<BusyException> { third.scanPrescription(image) { PreparedImage(it.bytes, it.mimeType) } }
        startGate.complete(Unit)
        start.await()
    }

    @Test
    fun `malformed success bodies are a typed error on evaluate finish and scan and leave state unchanged`() = runBlocking<Unit> {
        for (body in listOf("not json", "{\"terminal\": 5}", "")) {
            val transport = FixtureTransport(TransportResponse(200, emptyMap(), body.encodeToByteArray()), stateResponse("node", "single", listOf("one")))
            val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), transport)
            val error = assertFailsWith<MalformedResponseException> { journey.start("same", null) }
            assertEquals("MALFORMED_RESPONSE", error.code)
            assertEquals(200, error.status)
            // Nothing was committed and the lock is free: the next start works.
            assertEquals("node", journey.start("same", null).step?.id)
        }

        val finishing = FixtureTransport(stateResponse("node", "single", listOf("one")), stateResponse(), TransportResponse(201, emptyMap(), "[]".encodeToByteArray()))
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), finishing)
        journey.start("same", null); journey.select("one")
        assertEquals("MALFORMED_RESPONSE", assertFailsWith<MalformedResponseException> { journey.finish() }.code)

        val scanning = FixtureTransport(TransportResponse(200, emptyMap(), "{\"prescription\": 1}".encodeToByteArray()))
        val scanner = LensJourney(JourneyConfig(clientId = "client-12345678"), scanning)
        assertFailsWith<MalformedResponseException> { scanner.scanPrescription(ImageData(byteArrayOf(1, 2, 3), "image/jpeg")) { PreparedImage(it.bytes, it.mimeType) } }
    }

    /** Canonical mapping shared with iOS: unlisted status is "unavailable" (not retried), finish() without a completed journey is
     *  "journey incomplete", and a call with no current step is "invalid state". Each carries a code. */
    @Test
    fun `canonical error mapping for an unlisted status finish with no picks and select with no step`() = runBlocking<Unit> {
        for (status in listOf(418, 500, 502, 504)) {
            val transport = FixtureTransport(TransportResponse(status, emptyMap(), errorBody("ODD")))
            val error = assertFailsWith<ServiceUnavailableException>("status $status") {
                LensJourney(JourneyConfig(clientId = "client-12345678"), transport).start("single-vision", null)
            }
            assertEquals("ODD", error.code)
            assertEquals(status, error.status)
            assertEquals(1, transport.requests.size, "$status is not retried")
        }

        val empty = FixtureTransport()
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), empty)
        assertEquals("JOURNEY_INCOMPLETE", assertFailsWith<JourneyIncompleteException> { journey.finish() }.code)
        assertEquals("INVALID_STATE", assertFailsWith<InvalidStateException> { journey.select("one") }.code)
        assertEquals(0, empty.requests.size)

        // A started journey whose state has no step (summary) is also "invalid state" for select.
        val summary = FixtureTransport(stateResponse())
        val atSummary = LensJourney(JourneyConfig(clientId = "client-12345678"), summary)
        atSummary.start("same", null)
        assertEquals("INVALID_STATE", assertFailsWith<InvalidStateException> { atSummary.select("one") }.code)
        // Nothing selected at summary: finish is incomplete, not a generic invalid request.
        assertFailsWith<JourneyIncompleteException> { atSummary.finish() }
    }

    /** Every error the SDK raises itself (client-side validation, state, lock, transport, decode) carries a code. Errors mapped from a
     *  server response carry the server's own code, which is null when the server sent none, so they are not in this list. */
    @Test
    fun `errors the SDK raises itself carry a code`() = runBlocking<Unit> {
        fun journey(transport: Transport = FixtureTransport(), config: JourneyConfig = JourneyConfig(clientId = "client-12345678")) = LensJourney(config, transport)
        val image = ImageData(byteArrayOf(1), "image/jpeg")
        val raised = listOf<Pair<String, LensJourneyException>>(
            "INVALID_REQUEST" to assertFailsWith<InvalidRequestException> { journey().start("x".repeat(101), null) },
            "REQUEST_TOO_LARGE" to assertFailsWith<RequestTooLargeException> { journey(config = JourneyConfig(clientId = "client-12345678", currency = "x".repeat(17_000))).start("same", null) },
            "NETWORK_ERROR" to assertFailsWith<NetworkException> { journey(Transport { throw IOException("down") }).start("same", null) },
            "INVALID_STATE" to assertFailsWith<InvalidStateException> { journey().select("one") },
            "JOURNEY_INCOMPLETE" to assertFailsWith<JourneyIncompleteException> { journey().finish() },
            "INVALID_REQUEST" to assertFailsWith<InvalidRequestException> { journey().back() },
            "PAYLOAD_TOO_LARGE" to assertFailsWith<PayloadTooLargeException> { journey().scanPrescription(image) { PreparedImage(ByteArray(10 * 1024 * 1024 + 1), "image/jpeg") } },
            "UNSUPPORTED_MEDIA_TYPE" to assertFailsWith<UnsupportedMediaTypeException> { journey().scanPrescription(ImageData(byteArrayOf(1), "text/plain")) { PreparedImage(it.bytes, it.mimeType) } },
            "MALFORMED_RESPONSE" to assertFailsWith<MalformedResponseException> { journey(FixtureTransport(TransportResponse(200, emptyMap(), "nope".encodeToByteArray()))).start("same", null) },
        )
        for ((expected, error) in raised) assertEquals(expected, error.code, "${error::class.simpleName}: ${error.message}")

        val gate = CompletableDeferred<Unit>()
        val blocking = BlockingTransport(gate, queued("evaluate-first-choice.json"))
        val busy = journey(blocking)
        val first = async { busy.start("single-vision", null) }
        blocking.entered.await()
        assertEquals("BUSY", assertFailsWith<BusyException> { busy.start("other", null) }.code)
        gate.complete(Unit)
        first.await()

        val choice = journey(FixtureTransport(stateResponse("node", "single", listOf("one", "disabled"))))
        choice.start("same", null)
        assertEquals("INVALID_SELECTION", assertFailsWith<InvalidSelectionException> { choice.select("disabled") }.code)
    }

    /** The default sleeper is `delay`. Cancelling while it waits out Retry-After must not send the retry. runBlocking is single-threaded, so
     *  by the time the test resumes after the first request the call is already suspended inside `delay`. */
    @Test
    fun `cancelling the default delay sleeper during a 429 wait never sends the retry and frees the journey`() = runBlocking<Unit> {
        val transport = FixtureTransport(
            TransportResponse(429, mapOf("Retry-After" to "30"), errorBody("RATE_LIMITED")),
            stateResponse("node", "single", listOf("one")),
        )
        val entered = CompletableDeferred<Unit>()
        val observing = Transport { request -> transport.send(request).also { entered.complete(Unit) } }
        val journey = LensJourney(JourneyConfig(clientId = "client-12345678"), observing)
        var captured: Throwable? = null
        val job = launch {
            try { journey.start("single-vision", null) } catch (error: Throwable) { captured = error; throw error }
        }
        entered.await()
        val started = System.nanoTime()
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "the 30 second Retry-After was waited out")
        assertTrue(captured is CancellationException, "cancellation was surfaced as $captured")
        assertEquals(1, transport.requests.size, "a cancelled call must not send the retry")
        assertEquals("node", journey.start("single-vision", null).step?.id)
        assertEquals(2, transport.requests.size)
    }

    /** `instanceFollowRedirects = false`: a 3xx comes back as a status and its Location is never requested (a followed https-to-http hop
     *  would send the prescription and the credential in clear). GET, because HttpURLConnection only follows redirects without a streamed body. */
    @Test
    fun `a redirect is returned as a status and the location is never followed`() = runBlocking<Unit> {
        var targetHits = 0
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/") { exchange -> targetHits++; exchange.sendResponseHeaders(200, -1); exchange.close() }
        target.start()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/stolen")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        origin.start()
        try {
            val transport = HttpUrlConnectionTransport("http://127.0.0.1:${origin.address.port}", allowInsecureHttp = true)
            val response = transport.send(TransportRequest("GET", "/api/v1/lens-journey/anything", mapOf("Authorization" to "Bearer secret")))
            assertEquals(302, response.status)
            assertEquals(0, targetHits, "the redirect target must never be contacted")
        } finally {
            origin.stop(0); target.stop(0)
        }
    }

    @Test
    fun `cancelling the caller aborts a blocking socket read instead of waiting out the read timeout`() = runBlocking<Unit> {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = CompletableDeferred<Unit>()
        val held = mutableListOf<java.net.Socket>()
        val acceptor = Thread {
            runCatching { val socket = server.accept(); held += socket; accepted.complete(Unit); socket.getInputStream().read(ByteArray(4096)) }
        }.apply { isDaemon = true; start() }
        try {
            val transport = HttpUrlConnectionTransport("http://127.0.0.1:${server.localPort}", allowInsecureHttp = true)
            var captured: Throwable? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try { transport.send(TransportRequest("GET", "/never-answers")) } catch (error: Throwable) { captured = error; throw error }
            }
            withTimeout(5_000) { accepted.await() }
            val started = System.nanoTime()
            withTimeout(5_000) { job.cancelAndJoin() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 3_000, "cancel took ${elapsedMs}ms; the 15s read timeout was waited out")
            assertTrue(captured is CancellationException, "cancellation was surfaced as $captured")
        } finally {
            held.forEach { runCatching { it.close() } }
            server.close()
            acceptor.join(1_000)
        }
    }

    private fun fixtureRequest(name: String) = fixture(fixtures.resolve(name))["request"]!!.jsonObject
    private fun idempotencyKeys(transport: ScriptedTransport) =
        transport.requests.filter { it.path.endsWith("/configurations") }.map { parseBody(it)["idempotency_key"]!!.jsonPrimitive.content }
    // Numbers compare by value and empty strings (an unset field) are dropped, so 90 equals 90.0 and axis "" equals absent.
    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterValues { !(it is JsonPrimitive && it.isString && it.content.isEmpty()) }.mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map { canonical(it) })
        is JsonPrimitive -> if (!element.isString && element.content.toDoubleOrNull() != null) JsonPrimitive(element.content.toDouble()) else element
    }
    private fun decodeState(name: String) = json.decodeFromJsonElement(JourneyState.serializer(), responseBody(name))
    private fun responseBody(name: String) = fixture(fixtures.resolve(name))["response"]!!.jsonObject["body"]!!
    private fun queued(name: String): TransportResponse {
        val response = fixture(fixtures.resolve(name))["response"]!!.jsonObject
        val headers = response["headers"]!!.jsonObject.mapValues { (_, value) -> if (value is JsonPrimitive && value.isString) value.content else "" }
        return TransportResponse(response["status"]!!.jsonPrimitive.int, headers, response["body"]!!.toString().encodeToByteArray())
    }
    private fun fixture(path: Path) = json.parseToJsonElement(Files.readString(path)).jsonObject
    private fun fixtureFiles(prefix: String = "") = Files.list(fixtures).use { paths ->
        paths.filter { it.fileName.toString().startsWith(prefix) && it.fileName.toString().endsWith(".json") }.sorted().toList()
    }
    private fun parseBody(request: TransportRequest) = json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
    private fun errorBody(code: String) = "{\"success\":false,\"error\":\"failure\",\"code\":\"$code\",\"details\":null}".encodeToByteArray()
    private fun stateResponse(node: String? = null, mode: String = "single", options: List<String> = emptyList(), rxComplete: Boolean? = null): TransportResponse {
        val rx = rxComplete?.let { "\"prescription\":{\"complete\":$it,\"errors\":[]}," } ?: ""
        val step = node?.let { "\"step\":{\"id\":\"$it\",\"type\":\"choice\",\"label\":\"Choice\",\"selection_mode\":\"$mode\",\"options\":[${options.joinToString { id -> "{\"id\":\"$id\",\"label\":\"$id\",\"enabled\":${id != "disabled"}}" }}]}," } ?: ""
        return TransportResponse(200, emptyMap(), "{\"terminal\":\"${if (node == null) "summary" else "choice"}\",\"category\":\"same\",$step$rx\"selections\":{},\"auto_selected\":[],\"violations\":[],\"settings_warnings\":[]}".encodeToByteArray())
    }

    companion object {
        private val fixtures: Path = Path.of(checkNotNull(System.getProperty("lensJourney.fixtures")))
    }
}

private class BlockingTransport(private val gate: CompletableDeferred<Unit>, private val response: TransportResponse) : Transport {
    val entered = CompletableDeferred<Unit>()
    val requests = mutableListOf<TransportRequest>()
    override suspend fun send(request: TransportRequest): TransportResponse {
        requests += request
        // Only the first call blocks, so a regression that lets a second call through fails an assertion instead of hanging.
        if (!entered.isCompleted) { entered.complete(Unit); gate.await() }
        return response
    }
}

private class ScriptedTransport(vararg steps: Any) : Transport {
    private val steps = ArrayDeque(steps.toList())
    val requests = mutableListOf<TransportRequest>()

    override suspend fun send(request: TransportRequest): TransportResponse {
        requests += request
        return when (val step = steps.removeFirst()) {
            is TransportResponse -> step
            is Throwable -> throw step
            else -> error("Unsupported scripted step $step")
        }
    }
}

private class FixtureTransport(vararg responses: TransportResponse) : Transport {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<TransportRequest>()

    override suspend fun send(request: TransportRequest): TransportResponse {
        requests += request
        return responses.removeFirst()
    }
}

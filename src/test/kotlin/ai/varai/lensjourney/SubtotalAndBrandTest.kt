package ai.varai.lensjourney

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * POST /configurations answers `subtotal` as a STRING ((paise / 100) written with two decimals).
 * These tests pin that real shape, the exactness of the decimal, the typed malformed-response error, and the optional `brand`.
 */
class SubtotalAndBrandTest {
    private val json = LensJson.instance

    @Test
    fun `decodes the India shaped string subtotal and keeps its exact text`() {
        val configuration = decodeFixture("configurations-201-captured.json")
        assertEquals(0, BigDecimal("45000.00").compareTo(configuration.subtotal))
        assertEquals("45000.00", configuration.subtotalString)
        assertEquals("INR", configuration.currency)
        assertEquals(4_500_000L, configuration.subtotalPaise)
        // India sends none of the catalogue-market money fields: the ISO table is the fallback.
        assertNull(configuration.subtotalMinor); assertNull(configuration.currencyDecimals)
        assertNull(configuration.priceBasis); assertNull(configuration.priceBasisNote)
        assertFalse(configuration.isQuote)
        assertEquals(2, configuration.currencyMinorUnitDigits)
        assertEquals(4_500_000L, configuration.subtotalMinorUnits)
    }

    @Test
    fun `decodes a numeric subtotal to the same decimal type`() {
        val whole = decode(configurationJson("120"))
        assertEquals(BigDecimal("120"), whole.subtotal)
        assertEquals("120", whole.subtotalString)
        val fractional = decode(configurationJson("12.345", "KWD"))
        assertEquals(BigDecimal("12.345"), fractional.subtotal)
        assertEquals("12.345", fractional.subtotalString)
        assertEquals(12_345L, fractional.subtotalMinorUnits)
    }

    @Test
    fun `KWD three decimal subtotal round trips exactly`() {
        val configuration = decodeFixture("configurations-201-kwd-captured.json")
        assertEquals(BigDecimal("22.345"), configuration.subtotal)
        assertEquals("22.345", configuration.subtotalString)
        assertEquals(3, configuration.currencyMinorUnitDigits)
        assertEquals(22_345L, configuration.subtotalMinorUnits)
        // Beyond a Double's 15-16 significant digits the string must still be exact.
        val wide = decode(configurationJson("\"123456789012345.678\"", "KWD"))
        assertEquals(BigDecimal("123456789012345.678"), wide.subtotal)
        assertEquals("123456789012345.678", wide.subtotalString)
        // Encode then decode: the exact text and every money field survive, and the wire shape is the server's.
        val encoded = json.encodeToString(LensConfiguration.serializer(), configuration)
        val wire = json.parseToJsonElement(encoded).jsonObject
        assertEquals("22.345", wire["subtotal"]!!.jsonPrimitive.content)
        assertTrue(wire["subtotal"]!!.jsonPrimitive.isString)
        assertEquals(22_345L, wire["subtotal_minor"]!!.jsonPrimitive.content.toLong())
        assertEquals(3, wire["currency_decimals"]!!.jsonPrimitive.int)
        assertEquals("quote", wire["price_basis"]!!.jsonPrimitive.content)
        assertEquals(configuration, json.decodeFromString(LensConfiguration.serializer(), encoded))
    }

    @Test
    fun `EUR subtotal keeps two decimals and minor units follow the currency`() {
        val configuration = decodeFixture("configurations-201-eur-captured.json")
        assertEquals("59.95", configuration.subtotalString)
        assertEquals(0, BigDecimal("59.95").compareTo(configuration.subtotal))
        assertEquals(2, configuration.currencyMinorUnitDigits)
        assertEquals(5_995L, configuration.subtotalMinorUnits)
        assertEquals(1_500L, decode(configurationJson("\"1500\"", "JPY")).subtotalMinorUnits)
        assertNull(decode(configurationJson("\"12.3456\"", "KWD")).subtotalMinorUnits, "more digits than the currency has is not a minor-unit amount")
        val unknown = decode(configurationJson("\"5.00\"", null))
        assertNull(unknown.currencyMinorUnitDigits)
        assertNull(unknown.subtotalMinorUnits)
    }

    @Test
    fun `server currency decimals win when they disagree with the ISO table`() {
        // The table says KWD has 3; the server says 2. The server is authoritative.
        val configuration = decode(configurationJson("\"12.30\"", "KWD", "\"currency_decimals\":2"))
        assertEquals(2, configuration.currencyDecimals)
        assertEquals(2, configuration.currencyMinorUnitDigits)
        assertEquals(1_230L, configuration.subtotalMinorUnits)
        // ...and the other way: JPY is 0 in the table, the server says 2.
        val jpy = decode(configurationJson("\"15.50\"", "JPY", "\"currency_decimals\":2"))
        assertEquals(2, jpy.currencyMinorUnitDigits)
        assertEquals(1_550L, jpy.subtotalMinorUnits)
        // Decimals with no currency still count.
        assertEquals(1, decode(configurationJson("\"1.5\"", null, "\"currency_decimals\":1")).currencyMinorUnitDigits)
    }

    @Test
    fun `server subtotal_minor is preferred over a value derived from the string`() {
        val configuration = decode(configurationJson("\"1.234\"", "KWD", "\"subtotal_minor\":999,\"currency_decimals\":3"))
        assertEquals(999L, configuration.subtotalMinor)
        assertEquals(999L, configuration.subtotalMinorUnits)
    }

    @Test
    fun `missing currency decimals falls back to the table and a non integer one is rejected`() {
        assertNull(decode(configurationJson("\"1.000\"", "KWD")).currencyDecimals)
        assertEquals(3, decode(configurationJson("\"1.000\"", "KWD")).currencyMinorUnitDigits)
        assertNull(decode(configurationJson("\"1.000\"", "KWD", "\"currency_decimals\":null")).currencyDecimals)
        // A whole-valued JSON number is an integer (3.0 == 3), a fractional or non-number one is not. iOS agrees.
        for (whole in listOf("3", "3.0", "3.00", "3e0", "30e-1")) {
            assertEquals(3, decode(configurationJson("\"1.000\"", "KWD", "\"currency_decimals\":$whole")).currencyDecimals, "currency_decimals $whole")
        }
        for (whole in listOf("22345", "22345.0", "2.2345e4")) {
            assertEquals(22_345L, decode(configurationJson("\"22.345\"", "KWD", "\"subtotal_minor\":$whole")).subtotalMinor, "subtotal_minor $whole")
        }
        for (bad in listOf("3.5", "2.5", "0.5", "\"3\"", "true", "-1", "7", "{}", "[3]")) {
            assertFailsWith<SerializationException>("currency_decimals $bad") { decode(configurationJson("\"1.000\"", "KWD", "\"currency_decimals\":$bad")) }
        }
        for (bad in listOf("22345.5", "22345.0001", "\"22345\"", "true", "[1]")) {
            assertFailsWith<SerializationException>("subtotal_minor $bad") { decode(configurationJson("\"1.000\"", "KWD", "\"subtotal_minor\":$bad")) }
        }
    }

    @Test
    fun `non integer currency decimals fails finish with the typed error`() = runBlocking {
        val (journey, _) = summaryJourney(configurationJson("\"1.000\"", "KWD", "\"currency_decimals\":3.5"))
        assertFailsWith<MalformedResponseException> { journey.finish() }
        Unit
    }

    @Test
    fun `decodes the captured KWD and EUR responses field for field`() = runBlocking {
        val note = "Indicative quote. The merchant cart re-prices every line and is the charging authority; this configuration is never charged."
        data class Expected(val file: String, val subtotal: String, val minor: Long, val decimals: Int, val currency: String)
        for (e in listOf(
            Expected("configurations-201-kwd-captured.json", "22.345", 22_345L, 3, "KWD"),
            Expected("configurations-201-eur-captured.json", "59.95", 5_995L, 2, "EUR"),
        )) {
            val root = json.parseToJsonElement(Files.readString(fixtures.resolve(e.file))).jsonObject
            val post = root["response"]!!.jsonObject["body"]!!
            val get = root["follow_up_get"]!!.jsonObject["response"]!!.jsonObject["body"]!!
            // The id is whatever the captured fixture holds (it changes on every re-capture), read from the raw JSON, not hard-coded.
            val fixtureId = post.jsonObject["id"]!!.jsonPrimitive.content
            assertTrue(fixtureId.isNotEmpty(), "${e.currency} fixture POST id is empty")
            assertEquals(fixtureId, get.jsonObject["id"]!!.jsonPrimitive.content, "${e.currency}: the follow-up GET returns the configuration the POST created")
            for ((shape, body) in listOf("POST 201" to post, "follow-up GET" to get)) {
                val label = "${e.currency} $shape"
                val c = json.decodeFromJsonElement(LensConfiguration.serializer(), body)
                assertEquals(fixtureId, c.id, label)
                assertEquals(e.subtotal, c.subtotalString, label)
                assertEquals(BigDecimal(e.subtotal), c.subtotal, label)
                assertEquals(e.minor, c.subtotalMinor, label)
                assertEquals(e.decimals, c.currencyDecimals, label)
                assertEquals(e.currency, c.currency, label)
                assertEquals("quote", c.priceBasis, label)
                assertEquals(note, c.priceBasisNote, label)
                assertTrue(c.isQuote, label)
                assertNull(c.subtotalPaise, "$label: catalogue markets send no subtotal_paise")
                assertEquals(e.decimals, c.currencyMinorUnitDigits, label)
                assertEquals(e.minor, c.subtotalMinorUnits, label)
                assertEquals(3, (c.breakdown as JsonArray).size, label)
            }
            val postConfiguration = json.decodeFromJsonElement(LensConfiguration.serializer(), post)
            val getConfiguration = json.decodeFromJsonElement(LensConfiguration.serializer(), get)
            assertFalse(postConfiguration.inheritedFromPartner); assertFalse(postConfiguration.prescriptionOnFile)
            assertNull(postConfiguration.status); assertEquals("draft", getConfiguration.status)
            // The POST body also survives a whole finish() call.
            val (journey, _) = summaryJourney(post.toString())
            val finished = journey.finish()
            assertEquals(e.minor, finished.subtotalMinorUnits); assertEquals("quote", finished.priceBasis)
        }
    }

    @Test
    fun `absent or null subtotal is null`() {
        assertNull(decode(configurationJson("null")).subtotal)
        assertNull(decode(configurationJson(null)).subtotal)
        assertNull(decode(configurationJson("null")).subtotalString)
    }

    @Test
    fun `malformed subtotal fails finish with the typed MalformedResponseException`() = runBlocking {
        for (bad in listOf("\"twelve\"", "\"\"", "\"1,5\"", "\"NaN\"", "\"Infinity\"", "\"12.\"", "\" 12\"", "\"1e3\"", "\"0x10\"", "true", "{}", "[1]")) {
            val (journey, _) = summaryJourney(configurationJson(bad))
            val error = assertFailsWith<MalformedResponseException>("subtotal $bad") { journey.finish() }
            assertEquals("MALFORMED_RESPONSE", error.code)
        }
    }

    @Test
    fun `an exponent form numeric subtotal is a malformed response and never becomes a BigDecimal`() = runBlocking {
        // 1e999999999 used to decode, and subtotalString (toPlainString) or subtotalMinorUnits (movePointRight) then exhausted memory.
        for (bad in listOf("1e999999999", "1E999999999", "1e-999999999", "-1e999999999", "1e400", "1.5e2", "1e3", "1e0")) {
            assertFailsWith<SerializationException>("direct decode of subtotal $bad") { decode(configurationJson(bad)) }
            val (journey, _) = summaryJourney(configurationJson(bad))
            val error = assertFailsWith<MalformedResponseException>("finish with subtotal $bad") { journey.finish() }
            assertEquals("MALFORMED_RESPONSE", error.code)
        }
        // A plain number is still fine.
        assertEquals(0, BigDecimal("12.5").compareTo(decode(configurationJson("12.5")).subtotal))
    }

    @Test
    fun `the subtotal text is bounded to 40 characters on both the string and the number path`() = runBlocking {
        val atBound = "123456789012345678901234567890.123456789"
        assertEquals(40, atBound.length)
        for (good in listOf("\"$atBound\"", atBound)) {
            val configuration = decode(configurationJson(good))
            assertEquals(atBound, configuration.subtotalString)
            assertNull(configuration.subtotalMinorUnits, "nine decimals is not a KWD minor-unit amount, and asking must not throw")
        }
        for (bad in listOf("\"${atBound}1\"", "${atBound}1", "\"${"9".repeat(41)}\"", "9".repeat(41), "\"-${"9".repeat(40)}\"")) {
            assertFailsWith<SerializationException>("direct decode of ${bad.length} characters") { decode(configurationJson(bad)) }
            val (journey, _) = summaryJourney(configurationJson(bad))
            assertFailsWith<MalformedResponseException>("finish with ${bad.length} characters") { journey.finish() }
        }
    }

    @Test
    fun `a good string subtotal survives the whole finish call`() = runBlocking {
        val (journey, _) = summaryJourney(configurationJson("\"12.345\"", "KWD"))
        val configuration = journey.finish()
        assertEquals("12.345", configuration.subtotalString)
        assertEquals(BigDecimal("12.345"), configuration.subtotal)
    }

    @Test
    fun `brand is sent on evaluate and configurations when set`() = runBlocking {
        val (journey, transport) = summaryJourney(configurationJson("\"1.00\""), brand = "BRANDB")
        journey.finish()
        assertEquals(4, transport.requests.size)
        transport.requests.forEach { assertEquals("BRANDB", body(it)["brand"]!!.jsonPrimitive.content, it.path) }
        assertEquals(3, transport.requests.count { it.path.endsWith("/evaluate") })
        assertTrue(transport.requests.last().path.endsWith("/configurations"))
    }

    @Test
    fun `brand is absent when not set`() = runBlocking {
        val (journey, transport) = summaryJourney(configurationJson("\"1.00\""))
        journey.finish()
        assertEquals(4, transport.requests.size)
        transport.requests.forEach { assertFalse(body(it).containsKey("brand"), it.path) }
    }

    @Test
    fun `brand matches the servers rule and is trimmed`() {
        for (good in listOf("ACME", "BRANDB", "acme", "Z", "A1", "a".repeat(32))) {
            assertEquals(good, JourneyConfig(clientId = "client-12345678", brand = good).brand, good)
        }
        assertEquals("ACME", JourneyConfig(clientId = "client-12345678", brand = "  ACME\n").wireBrand, "trimmed before it is checked and sent")
        for (bad in listOf("", "   ", "1ACME", "AC ME", "AC-ME", "ACME_", "AC.ME", "ACME!", "ACMÉ", "ACME😀", "٣ACME", "a".repeat(33))) {
            assertFailsWith<InvalidRequestException>("brand ${bad.length}:$bad") { JourneyConfig(clientId = "client-12345678", brand = bad) }
        }
        assertNull(JourneyConfig(clientId = "client-12345678").brand)
    }

    @Test
    fun `the trimmed brand is what is sent`() = runBlocking {
        val (journey, transport) = summaryJourney(configurationJson("\"1.00\""), brand = " brandb ")
        journey.finish()
        transport.requests.forEach { assertEquals("brandb", body(it)["brand"]!!.jsonPrimitive.content) }
    }

    @Test
    fun `version is 0 point 2 point 0 and matches gradle, the VERSION file and the CHANGELOG`() {
        assertEquals("0.2.0", LensJourney.VERSION)
        val root = packageRoot
        assertTrue(Files.readString(root.resolve("build.gradle.kts")).contains("version = file(\"VERSION\").readText().trim()"), "Gradle must read its version from VERSION")
        assertEquals(LensJourney.VERSION, Files.readString(root.resolve("VERSION")).trim())
        val newest = Files.readAllLines(root.resolve("CHANGELOG.md")).first { it.startsWith("## ") }.removePrefix("## ").substringBefore(' ')
        assertEquals(LensJourney.VERSION, newest, "the newest CHANGELOG.md heading must carry the current version")
    }

    private fun decode(text: String) = json.decodeFromString(LensConfiguration.serializer(), text)
    private fun decodeFixture(name: String) = json.decodeFromJsonElement(LensConfiguration.serializer(), fixtureResponse(name)["body"]!!)
    private fun body(request: TransportRequest): JsonObject = json.parseToJsonElement(request.body!!.decodeToString()).jsonObject

    /** Raw JSON text so a number stays a JSON number and a string stays a string. `subtotal` is spliced in verbatim; null omits the key. */
    private fun configurationJson(subtotal: String?, currency: String? = "KWD", extra: String? = null): String {
        val fields = mutableListOf("\"success\":true", "\"id\":\"11111111-2222-4333-8444-555555555555\"", "\"lens_summary\":null", "\"breakdown\":[]")
        subtotal?.let { fields += "\"subtotal\":$it" }
        currency?.let { fields += "\"currency\":\"$it\"" }
        extra?.let { fields += it }
        return "{${fields.joinToString(",")}}"
    }

    /** start + two selects reach the summary terminal; the next transport response is the /configurations body. */
    private suspend fun summaryJourney(configurationBody: String, brand: String? = null): Pair<LensJourney, RecordingTransport> {
        val transport = RecordingTransport(
            queued("evaluate-first-choice.json"), queued("evaluate-second-choice.json"), queued("evaluate-summary.json"),
            TransportResponse(201, emptyMap(), configurationBody.encodeToByteArray()),
        )
        val journey = LensJourney(JourneyConfig(sessionId = "session-12345678", clientId = "client-12345678", brand = brand), transport)
        journey.start("single-vision", FrameRef(sku = "FIXTURE-SKU"))
        journey.select("anti-reflective")
        journey.select("opt-anti-reflective-150-stock")
        return journey to transport
    }

    private fun fixtureResponse(name: String) = json.parseToJsonElement(Files.readString(fixtures.resolve(name))).jsonObject["response"]!!.jsonObject
    private fun queued(name: String): TransportResponse {
        val response = fixtureResponse(name)
        return TransportResponse(response["status"]!!.jsonPrimitive.int, emptyMap(), response["body"]!!.toString().encodeToByteArray())
    }

    companion object {
        private val fixtures: Path = Path.of(checkNotNull(System.getProperty("lensJourney.fixtures")))
        private val packageRoot: Path = Path.of(checkNotNull(System.getProperty("lensJourney.packageRoot")))
    }
}

private class RecordingTransport(vararg responses: TransportResponse) : Transport {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<TransportRequest>()
    override suspend fun send(request: TransportRequest): TransportResponse { requests += request; return responses.removeFirst() }
}

package ai.varai.lensjourney

import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal object LensJson {
    val instance = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
}

data class JourneyConfig(
    val sessionId: String = UUID.randomUUID().toString(),
    val clientId: String = UUID.randomUUID().toString(),
    val currency: String? = null,
    val store: String? = null,
    /**
     * Optional lens brand (for example "ACME") the server uses to pick the brand's workflow. Must match the server's rule, a letter then up to
     * 31 letters or digits after trimming (anything else throws [InvalidRequestException]). The trimmed value is what is sent, on `/evaluate`
     * and on `finish()`'s `/configurations` call, and only when set; null keeps the server's default workflow selection.
     */
    val brand: String? = null,
) {
    /** [brand] trimmed, as sent to the server. */
    internal val wireBrand: String? get() = brand?.trim()

    init {
        if (!CLIENT_ID.matches(clientId)) throw InvalidClientIdException("clientId must match ${CLIENT_ID.pattern}")
        if (sessionId.isBlank() || sessionId.length > 200) throw InvalidRequestException("sessionId must be 1 to 200 characters")
        if (wireBrand != null && !BRAND.matches(wireBrand!!)) throw InvalidRequestException("brand must be a letter followed by up to 31 letters or digits")
    }

    private companion object {
        val CLIENT_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
        val BRAND = Regex("^[A-Za-z][A-Za-z0-9]{0,31}$")
    }
}

@Serializable
data class FrameRef(val sku: String? = null, @SerialName("product_id") val productId: String? = null) {
    init { require(sku != null || productId != null) { "FrameRef requires sku or productId" } }
}

@Serializable
enum class VisionType {
    @SerialName("distance") DISTANCE,
    @SerialName("near") NEAR,
}

@Serializable(with = TerminalSerializer::class)
enum class Terminal { CHOICE, SUMMARY, BLOCK, DEAD_END, MAX_STEPS, UNKNOWN }

object TerminalSerializer : KSerializer<Terminal> {
    override val descriptor = PrimitiveSerialDescriptor("Terminal", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Terminal = when (decoder.decodeString()) {
        "choice" -> Terminal.CHOICE
        "summary" -> Terminal.SUMMARY
        "block" -> Terminal.BLOCK
        "dead-end" -> Terminal.DEAD_END
        "max-steps" -> Terminal.MAX_STEPS
        else -> Terminal.UNKNOWN
    }
    override fun serialize(encoder: Encoder, value: Terminal) = encoder.encodeString(value.name.lowercase().replace('_', '-'))
}

@Serializable
data class JourneyState(
    val terminal: Terminal,
    val category: String? = null,
    val step: Step? = null,
    val selections: Map<String, List<Selection>> = emptyMap(),
    @SerialName("auto_selected") val autoSelected: List<JsonElement> = emptyList(),
    val violations: List<Violation> = emptyList(),
    val prescription: PrescriptionCheck? = null,
    @SerialName("settings_warnings") val settingsWarnings: List<JsonElement> = emptyList(),
    val gate: Gate? = null,
    @SerialName("workflow_version") val workflowVersion: String? = null,
)

@Serializable
data class Step(
    val id: String,
    val type: String,
    val label: String,
    @SerialName("selection_mode") val selectionMode: String? = null,
    val options: List<Option> = emptyList(),
)

@Serializable
data class Option(
    val id: String,
    val label: String,
    val price: Double? = null,
    val msrp: Double? = null,
    @SerialName("platform_sku") val platformSku: String? = null,
    val enabled: Boolean = true,
    val recommended: Boolean = false,
    val metadata: Map<String, JsonPrimitive> = emptyMap(),
    @SerialName("addon_skus") val addonSkus: List<String>? = null,
)

@Serializable
data class Selection(val optionId: String, val optionLabel: String? = null)

@Serializable
data class Violation(val code: String, val nodeId: String? = null)

@Serializable
data class PrescriptionCheck(
    val complete: Boolean,
    val errors: List<PrescriptionError> = emptyList(),
    val normalized: NormalizedPrescription? = null,
    val ranges: PrescriptionRanges? = null,
)

@Serializable
data class PrescriptionError(val field: String? = null, val code: String)

@Serializable
data class PrescriptionRanges(
    val sph: NumericRange? = null,
    val cyl: NumericRange? = null,
    val add: NumericRange? = null,
    val pd: PdRanges? = null,
    val axis: NumericRange? = null,
)

@Serializable data class NumericRange(val min: Double, val max: Double, val step: Double? = null)
@Serializable data class PdRanges(val binocular: NumericRange, val monocular: NumericRange)
@Serializable data class Gate(val decision: String? = null, val degraded: Boolean = false, val reason: String? = null)

@Serializable
data class Prescription(
    val od: EyePrescription,
    val os: EyePrescription,
    val pd: Double? = null,
    val pdOd: Double? = null,
    val pdOs: Double? = null,
)

@Serializable
data class EyePrescription(val sph: Double? = null, val cyl: Double? = null, val axis: Double? = null, val add: Double? = null)

/**
 * How sure the server's reader is of a photographed prescription. The server answers the string "high" (both spheres read)
 * or "low", never a number. Any other value, or a non-string, decodes to [UNKNOWN] instead of failing the whole scan.
 */
@Serializable(with = OcrConfidenceSerializer::class)
enum class OcrConfidence { HIGH, LOW, UNKNOWN }

object OcrConfidenceSerializer : KSerializer<OcrConfidence> {
    override val descriptor = PrimitiveSerialDescriptor("OcrConfidence", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): OcrConfidence {
        val text = (decoder as JsonDecoder).decodeJsonElement().let { if (it is JsonPrimitive && it.isString) it.content else null }
        return when (text) {
            "high" -> OcrConfidence.HIGH
            "low" -> OcrConfidence.LOW
            else -> OcrConfidence.UNKNOWN
        }
    }
    override fun serialize(encoder: Encoder, value: OcrConfidence) = encoder.encodeString(value.name.lowercase())
}

@Serializable
data class NormalizedPrescription(
    val od: NormalizedEye,
    val os: NormalizedEye,
    val pd: String = "",
    val pdOd: String = "",
    val pdOs: String = "",
    val confidence: OcrConfidence? = null,
)

@Serializable data class NormalizedEye(val sph: String = "", val cyl: String = "", val axis: String = "", val add: String = "")

data class PrescriptionDraft(
    val od: NormalizedEye,
    val os: NormalizedEye,
    val pd: String,
    val pdOd: String,
    val pdOs: String,
    val confidence: OcrConfidence?,
    val confirmed: Boolean = false,
)

@Serializable
internal data class OcrResponse(val prescription: OcrPrescription) {
    @Serializable
    data class OcrPrescription(
        val od: NormalizedEye,
        val os: NormalizedEye,
        val pd: String = "",
        val pdOd: String = "",
        val pdOs: String = "",
        val confidence: OcrConfidence? = null,
    ) {
        val confirmed: Boolean get() = false
        fun toDraft() = PrescriptionDraft(od, os, pd, pdOd, pdOs, confidence)
    }
}

@Serializable
data class LensConfiguration(
    val id: String,
    @SerialName("lens_summary") val lensSummary: JsonElement? = null,
    /**
     * The subtotal as an exact decimal. The server sends a JSON string such as "120.00" or "12.345" (a number is also accepted);
     * it is never read through a Double, so a 3-decimal currency such as KWD keeps its precision. The scale is the server's own:
     * "120.00" has scale 2, so compare with compareTo, not equals.
     */
    @Serializable(with = ExactDecimalSerializer::class) val subtotal: BigDecimal? = null,
    /** The server's integer `subtotal_paise`, passed through unchanged. Its name is historical; use [subtotalMinorUnits] for an amount scaled by [currency]. */
    @SerialName("subtotal_paise") val subtotalPaise: Long? = null,
    /** Catalogue markets only: [subtotal] in the currency's minor units (22345 for "22.345" KWD). Authoritative when present. India sends none. */
    @Serializable(with = StrictLongSerializer::class) @SerialName("subtotal_minor") val subtotalMinor: Long? = null,
    /** Catalogue markets only: the currency's decimal places as the server applied them. Authoritative over the ISO table. India sends none. */
    @Serializable(with = StrictIntSerializer::class) @SerialName("currency_decimals") val currencyDecimals: Int? = null,
    /** Catalogue markets only: "quote" means [subtotal] is an indicative lens-only quote and the merchant cart is the charging authority. */
    @SerialName("price_basis") val priceBasis: String? = null,
    /** Catalogue markets only: the server's human-readable explanation of [priceBasis]. */
    @SerialName("price_basis_note") val priceBasisNote: String? = null,
    /** Only on the follow-up `GET /configurations/:id` body ("draft"); the POST 201 does not carry it. */
    val status: String? = null,
    val currency: String? = null,
    val breakdown: JsonElement? = null,
    @SerialName("inherited_from_partner") val inheritedFromPartner: Boolean = false,
    @SerialName("prescription_on_file") val prescriptionOnFile: Boolean = false,
    @SerialName("photo_attached") val photoAttached: Boolean? = null,
    @SerialName("photo_attach_error") val photoAttachError: JsonElement? = null,
    val photos: JsonElement? = null,
) {
    init {
        currencyDecimals?.let { if (it !in 0..6) throw SerializationException("currency_decimals must be 0 to 6") }
    }

    /** True when the server says [subtotal] is an indicative quote (`priceBasis == "quote"`): the merchant cart, not this configuration, charges. */
    val isQuote: Boolean get() = priceBasis == "quote"

    /** The subtotal exactly as written, trailing zeros kept ("120.00"). */
    val subtotalString: String? get() = subtotal?.toPlainString()

    /**
     * Minor-unit digits for [currency]. The server's `currency_decimals` is authoritative; only when it is absent (India) does this fall back to the
     * ISO 4217 table (KWD 3, JPY 0, EUR 2). Null when the response has neither.
     */
    val currencyMinorUnitDigits: Int? get() = currencyDecimals ?: currency?.let { CurrencyMinorUnits.digits(it) }

    /**
     * [subtotal] in the currency's minor units (22.345 KWD is 22345). The server's `subtotal_minor` wins when present; otherwise it is derived from
     * [subtotal] and [currencyMinorUnitDigits]. Null when neither is possible, or when the subtotal has more digits than the currency has.
     */
    val subtotalMinorUnits: Long? get() {
        subtotalMinor?.let { return it }
        val amount = subtotal ?: return null
        val digits = currencyMinorUnitDigits ?: return null
        return try { amount.movePointRight(digits).toBigIntegerExact().longValueExact() } catch (_: ArithmeticException) { null }
    }
}

internal object CurrencyMinorUnits {
    private val ZERO = setOf("BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "UYI", "VND", "VUV", "XAF", "XOF", "XPF")
    private val THREE = setOf("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND")
    private val FOUR = setOf("CLF", "UYW")

    /** Keep this table identical to the iOS SDK's `CurrencyMinorUnits`; both are tested against the same codes. */
    fun digits(code: String): Int {
        val upper = code.uppercase()
        return when {
            upper in ZERO -> 0
            upper in THREE -> 3
            upper in FOUR -> 4
            else -> 2
        }
    }
}

/**
 * An integer field that must be a whole JSON number: 3, 3.0 and 3e0 are accepted (the same as iOS), a quoted "3", 3.5 or true is a malformed
 * response, not a coerced value.
 */
internal object StrictLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("StrictLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long {
        val primitive = (decoder as? JsonDecoder ?: throw SerializationException("StrictLong reads JSON only")).decodeJsonElement() as? JsonPrimitive
        if (primitive == null || primitive.isString) throw SerializationException("Expected a JSON integer")
        return try { BigDecimal(primitive.content).longValueExact() } catch (_: ArithmeticException) { throw SerializationException("Expected a JSON integer") } catch (_: NumberFormatException) { throw SerializationException("Expected a JSON integer") }
    }
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

internal object StrictIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("StrictInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int =
        StrictLongSerializer.deserialize(decoder).let { if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw SerializationException("Expected a JSON integer") else it.toInt() }
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/**
 * Reads a JSON decimal that arrives as a string or a number, as a [BigDecimal] built from the token text, never from a Double.
 * Both forms must be a plain decimal ("120.00", "-0.5") of at most [MAX_LENGTH] characters. An exponent form ("1e999999999") is refused for a
 * number as well as a string: a [BigDecimal] built from it is tiny to hold but exhausts memory when printed or scaled. The iOS SDK applies the
 * same length bound to the text it ends up with; it cannot see a number's token (see its README).
 */
internal object ExactDecimalSerializer : KSerializer<BigDecimal> {
    const val MAX_LENGTH = 40
    private val PLAIN = Regex("^-?[0-9]+(\\.[0-9]+)?$")
    override val descriptor = PrimitiveSerialDescriptor("ExactDecimal", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): BigDecimal {
        val element = (decoder as? JsonDecoder ?: throw SerializationException("ExactDecimal reads JSON only")).decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: throw SerializationException("Expected a decimal string or number")
        val text = primitive.content
        if (text.length > MAX_LENGTH) throw SerializationException("A decimal may be at most $MAX_LENGTH characters")
        if (!PLAIN.matches(text)) throw SerializationException("Expected a plain decimal such as 120.00, without an exponent")
        return BigDecimal(text)
    }
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
}

data class ImageData(val bytes: ByteArray, val mimeType: String)
data class PreparedImage(val bytes: ByteArray, val mimeType: String)
fun interface ImagePreparer { fun prepare(image: ImageData): PreparedImage }

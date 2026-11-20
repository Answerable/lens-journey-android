package ai.varai.lensjourney

import java.util.UUID
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

class LensJourney(
    private val config: JourneyConfig,
    private val transport: Transport,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private var category: String? = null
    private var frame: FrameRef? = null
    private var vision: VisionType = VisionType.DISTANCE
    private var prescription: Prescription? = null
    private var state: JourneyState? = null
    private val picks = linkedMapOf<String, MutableList<String>>()
    private val history = mutableListOf<Snapshot>()
    private var finishKey: String? = null
    private val operationMutex = Mutex()

    suspend fun start(category: String, frame: FrameRef?, vision: VisionType = VisionType.DISTANCE): JourneyState {
        beginOperation()
        try {
        validateId(category, "category", 100)
        frame?.sku?.let { validateId(it, "frame.sku") }
        frame?.productId?.let { validateId(it, "frame.productId") }
        val nextPrescription = if (this.category == null || this.category == category) prescription else null
        val next = evaluate(category, frame, vision, nextPrescription, emptyMap())
        this.category = category; this.frame = frame; this.vision = vision; prescription = nextPrescription
        picks.clear(); history.clear(); state = next; finishKey = null
        return next
        } finally { operationMutex.unlock() }
    }

    suspend fun select(optionId: String): JourneyState {
        beginOperation()
        try {
        validateId(optionId, "optionId")
        val current = state ?: throw InvalidStateException("No choice is available")
        val step = current.step ?: throw InvalidStateException("No choice is available")
        val nodeId = step.id
        validateId(nodeId, "nodeId")
        if (step.options.none { it.id == optionId && it.enabled }) throw InvalidSelectionException()
        val staged = picks.mapValuesTo(linkedMapOf()) { it.value.toMutableList() }
        val nodePicks = staged.getOrPut(nodeId) { mutableListOf() }
        if (step.selectionMode != "multiple") nodePicks.clear()
        if (optionId in nodePicks) return current
        if (nodePicks.size >= MAX_IDS_PER_NODE) throw InvalidRequestException("A node may have at most $MAX_IDS_PER_NODE selections")
        nodePicks += optionId
        val next = evaluate(currentCategory(), frame, vision, prescription, staged.mapValues { it.value.toList() })
        history += Snapshot(current, picks.mapValues { it.value.toList() }, prescription)
        picks.clear(); staged.forEach { (node, ids) -> picks[node] = ids }; state = next; finishKey = null
        return next
        } finally { operationMutex.unlock() }
    }

    /**
     * Local undo of the last select (native SDK contract section 4: "local, drops the last pick").
     * Throws [InvalidRequestException] when there is nothing to undo: before start, right after start, and once every pick is undone.
     * After a [setPrescription], the restored step's prescription check follows the current prescription, but its options, `enabled`
     * flags and gate are as they were when that step was evaluated. This is display only: the server re-evaluates on the next [select].
     */
    fun back(): JourneyState {
        beginOperation()
        try {
        val previous = history.lastOrNull() ?: throw InvalidRequestException("There is no previous selection")
        history.removeAt(history.lastIndex)
        picks.clear(); previous.picks.forEach { (node, ids) -> picks[node] = ids.toMutableList() }
        val current = state
        // The prescription check describes the current prescription, not the picks, so it follows a later setPrescription.
        val restored = if (current != null && previous.prescription != prescription) previous.state.copy(prescription = current.prescription) else previous.state
        state = restored; finishKey = null
        return restored
        } finally { operationMutex.unlock() }
    }

    suspend fun setPrescription(rx: Prescription): JourneyState {
        beginOperation()
        try {
            val currentCategory = category
            if (currentCategory == null) { prescription = rx; finishKey = null; return emptyState() }
            val next = evaluate(currentCategory, frame, vision, rx, picks.mapValues { it.value.toList() })
            // No history entry: back() undoes picks only, so the history stays one entry per pick.
            prescription = rx; state = next; finishKey = null
            return next
        } finally { operationMutex.unlock() }
    }

    suspend fun scanPrescription(photo: ImageData, preparer: ImagePreparer): PrescriptionDraft {
        beginOperation()
        try {
        requireImageMime(photo.mimeType)
        val prepared = preparer.prepare(photo)
        requireImageMime(prepared.mimeType)
        if (prepared.bytes.size > MAX_IMAGE_BYTES) throw PayloadTooLargeException("Prepared image exceeds 10 MB", code = "PAYLOAD_TOO_LARGE")
        val boundary = "LensJourney-${UUID.randomUUID()}"
        val body = multipart(boundary, prepared)
        val response = sendWithRetry(
            TransportRequest("POST", "$API_ROOT/rx-ocr", clientHeaders(), body, "multipart/form-data; boundary=$boundary"),
        )
        ensureSuccess(response)
        return decodeSuccess(OcrResponse.serializer(), response).prescription.toDraft()
        } finally { operationMutex.unlock() }
    }

    suspend fun finish(): LensConfiguration {
        beginOperation()
        try {
        val currentCategory = category ?: throw JourneyIncompleteException()
        if (picks.isEmpty() || state?.terminal != Terminal.SUMMARY) throw JourneyIncompleteException()
        // One key per journey + selection set: repeated finish() calls (after a surfaced 503 or a network error) reuse it so the
        // server can dedupe; start, select, back and setPrescription clear it. The in-call 429/503 retry resends the same body.
        val idempotencyKey = finishKey ?: UUID.randomUUID().toString().also { finishKey = it }
        val selections = picks.flatMap { (nodeId, ids) -> ids.map { SelectionRequest(nodeId, it) } }
        val requestBody = ConfigurationRequest(
            category = currentCategory,
            selections = selections,
            sessionId = config.sessionId,
            prescription = prescription,
            frame = frame,
            productId = frame?.productId,
            productSku = frame?.sku,
            visionType = vision,
            brand = config.wireBrand,
            idempotencyKey = idempotencyKey,
        )
        val body = LensJson.instance.encodeToString(requestBody).encodeToByteArray()
        val response = sendWithRetry(TransportRequest("POST", "$API_ROOT/configurations", clientHeaders(), body, JSON_CONTENT_TYPE))
        ensureSuccess(response)
        return decodeSuccess(LensConfiguration.serializer(), response)
        } finally { operationMutex.unlock() }
    }

    private suspend fun evaluate(category: String, frame: FrameRef?, vision: VisionType, prescription: Prescription?, selections: Map<String, List<String>>): JourneyState {
        if (selections.size > MAX_NODES) throw InvalidRequestException("Selections may contain at most $MAX_NODES nodes")
        selections.forEach { (nodeId, ids) ->
            validateId(nodeId, "nodeId")
            if (ids.size > MAX_IDS_PER_NODE) throw InvalidRequestException("A node may have at most $MAX_IDS_PER_NODE selections")
            ids.forEach { validateId(it, "optionId") }
        }
        val body = LensJson.instance.encodeToString(
            EvaluateRequest(
                category = category,
                frame = frame,
                visionType = vision,
                prescription = prescription,
                selections = selections,
                currency = config.currency,
                store = config.store,
                brand = config.wireBrand,
            ),
        ).encodeToByteArray()
        if (body.size > MAX_EVALUATE_BYTES) throw RequestTooLargeException("Evaluate request exceeds 16 KB")
        val response = sendWithRetry(TransportRequest("POST", "$API_ROOT/evaluate", clientHeaders(), body, JSON_CONTENT_TYPE))
        ensureSuccess(response)
        return decodeSuccess(JourneyState.serializer(), response)
    }

    private suspend fun sendWithRetry(request: TransportRequest): TransportResponse {
        var response = sendOnce(request)
        if (response.status == 429 || response.status == 503) {
            val waitMillis = if (response.status == 429) retryAfterMillis(response.headers) else SERVICE_RETRY_MS
            sleep(waitMillis)
            response = sendOnce(request)
        }
        return response
    }

    private suspend fun sendOnce(request: TransportRequest): TransportResponse = try {
        transport.send(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: LensJourneyException) {
        throw error
    } catch (error: Exception) {
        throw NetworkException("Transport failed", error)
    }

    /** A 2xx whose body does not decode is a [MalformedResponseException], not a raw SerializationException. */
    private fun <T> decodeSuccess(serializer: KSerializer<T>, response: TransportResponse): T = try {
        LensJson.instance.decodeFromString(serializer, response.body.decodeToString())
    } catch (error: IllegalArgumentException) { // SerializationException (malformed JSON, wrong types, missing fields) is an IllegalArgumentException
        throw MalformedResponseException("The server answered ${response.status} with a body the SDK could not read", response.status, error)
    }

    private fun ensureSuccess(response: TransportResponse) {
        if (response.status in 200..299) return
        val envelope = runCatching { LensJson.instance.decodeFromString(ErrorEnvelope.serializer(), response.body.decodeToString()) }.getOrNull()
        val code = envelope?.code ?: envelope?.error
        val message = envelope?.message ?: envelope?.error ?: "Request failed with HTTP ${response.status}"
        val exception = when (response.status) {
            400 -> if (code == "INVALID_CLIENT_ID") InvalidClientIdException(message) else InvalidRequestException(message, code = code)
            401 -> AuthenticationException(message, code)
            403 -> InsufficientScopeException(message, code)
            404 -> WorkflowNotPublishedException(message, code)
            413 -> PayloadTooLargeException(message, code = code)
            415 -> UnsupportedMediaTypeException(message, code)
            422 -> ConfigurationRejectedException(message, code, envelope?.details)
            429 -> RateLimitedException(message, code)
            503 -> ServiceUnavailableException(message, code)
            else -> ServiceUnavailableException(message, code, response.status)
        }
        exception.requestId = envelope?.requestId
        exception.documentationUrl = envelope?.documentationUrl
        throw exception
    }

    private fun clientHeaders() = mapOf("X-Client-Id" to config.clientId)

    private fun retryAfterMillis(headers: Map<String, String>): Long {
        val value = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.trim() ?: return DEFAULT_RETRY_MS
        value.toLongOrNull()?.let { return if (it >= 0) it.coerceAtMost(MAX_RETRY_SECONDS) * 1_000L else DEFAULT_RETRY_MS }
        val target = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return DEFAULT_RETRY_MS
        val delta = runCatching { Math.subtractExact(target, clockMillis()) }.getOrNull() ?: return DEFAULT_RETRY_MS
        return if (delta >= 0) delta.coerceAtMost(MAX_RETRY_SECONDS * 1_000L) else DEFAULT_RETRY_MS
    }

    private fun beginOperation() { if (!operationMutex.tryLock()) throw BusyException() }
    private fun currentCategory() = category ?: throw InvalidStateException("Start the journey first")
    private fun emptyState() = JourneyState(category = category.orEmpty(), terminal = Terminal.UNKNOWN)

    private fun validateId(value: String, name: String, max: Int = MAX_ID_LENGTH) {
        if (value.isBlank() || value.length > max) throw InvalidRequestException("$name must be 1 to $max characters")
    }

    private fun requireImageMime(mimeType: String) {
        if (!mimeType.lowercase().startsWith("image/")) throw UnsupportedMediaTypeException("Prepared content must have an image MIME type", "UNSUPPORTED_MEDIA_TYPE")
    }

    private fun multipart(boundary: String, image: PreparedImage): ByteArray {
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"prescription\"\r\nContent-Type: ${image.mimeType}\r\n\r\n".encodeToByteArray()
        val suffix = "\r\n--$boundary--\r\n".encodeToByteArray()
        return prefix + image.bytes + suffix
    }

    companion object {
        /** The SDK version. Keep it equal to `version` in build.gradle.kts, `VERSION` and the newest `##` heading in `CHANGELOG.md` (the release version check compares all four). */
        const val VERSION = "0.4.0"
        private const val API_ROOT = "/api/v1/lens-journey"
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val MAX_EVALUATE_BYTES = 16 * 1024
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        private const val MAX_NODES = 64
        private const val MAX_IDS_PER_NODE = 16
        private const val MAX_ID_LENGTH = 128
        private const val SERVICE_RETRY_MS = 100L
        private const val DEFAULT_RETRY_MS = 1_000L
        private const val MAX_RETRY_SECONDS = 30L
    }
}

/** What back() restores: the state shown before a pick, the picks at that time, and the prescription it was evaluated with. */
private data class Snapshot(val state: JourneyState, val picks: Map<String, List<String>>, val prescription: Prescription?)

@Serializable
private data class EvaluateRequest(
    val category: String,
    val frame: FrameRef? = null,
    @SerialName("vision_type") val visionType: VisionType,
    val prescription: Prescription? = null,
    val selections: Map<String, List<String>>,
    val currency: String? = null,
    val store: String? = null,
    val brand: String? = null,
)

@Serializable private data class SelectionRequest(@SerialName("node_id") val nodeId: String, @SerialName("option_id") val optionId: String)

@Serializable
private data class ConfigurationRequest(
    val category: String,
    val selections: List<SelectionRequest>,
    @SerialName("session_id") val sessionId: String,
    val prescription: Prescription? = null,
    val frame: FrameRef? = null,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("product_sku") val productSku: String? = null,
    @SerialName("vision_type") val visionType: VisionType,
    val brand: String? = null,
    val context: JsonObject? = null,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

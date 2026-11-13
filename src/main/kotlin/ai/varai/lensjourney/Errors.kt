package ai.varai.lensjourney

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

sealed class LensJourneyException(
    message: String,
    val status: Int? = null,
    val code: String? = null,
    val details: JsonElement? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    var requestId: String? = null
        internal set
    var documentationUrl: String? = null
        internal set
}

class InvalidRequestException(message: String, status: Int = 400, code: String? = "INVALID_REQUEST") : LensJourneyException(message, status, code)
class InvalidClientIdException(message: String) : LensJourneyException(message, 400, "INVALID_CLIENT_ID")
class AuthenticationException(message: String, code: String? = null) : LensJourneyException(message, 401, code)
class InsufficientScopeException(message: String, code: String? = "INSUFFICIENT_SCOPE") : LensJourneyException(message, 403, code)
class WorkflowNotPublishedException(message: String, code: String? = "WORKFLOW_NOT_PUBLISHED") : LensJourneyException(message, 404, code)
class PayloadTooLargeException(message: String, status: Int = 413, code: String? = null) : LensJourneyException(message, status, code)
class UnsupportedMediaTypeException(message: String, code: String? = null) : LensJourneyException(message, 415, code)
class ConfigurationRejectedException(message: String, code: String?, details: JsonElement?) : LensJourneyException(message, 422, code, details)
class RateLimitedException(message: String, code: String? = null) : LensJourneyException(message, 429, code)
/**
 * HTTP 503, and also the canonical mapping (shared with iOS `.unavailable`) for ANY status the SDK does not list, including an
 * unlisted 4xx such as 418. Read [status] for the real HTTP status; iOS exposes no status, only the server code. The name says
 * "unavailable" because that is the one cross-platform kind for "the service did not give an answer the SDK understands".
 */
class ServiceUnavailableException(message: String, code: String? = null, status: Int = 503) : LensJourneyException(message, status, code)
class NetworkException(message: String, cause: Throwable) : LensJourneyException(message, code = "NETWORK_ERROR", cause = cause)
class RequestTooLargeException(message: String) : LensJourneyException(message, code = "REQUEST_TOO_LARGE")
/** A call needs state the journey does not have: no current step to select from, or the journey was not started. */
class InvalidStateException(message: String) : LensJourneyException(message, code = "INVALID_STATE")
/** A 2xx response whose body the SDK could not decode. Nothing was committed to the journey when this is thrown. */
class MalformedResponseException(message: String, status: Int, cause: Throwable) : LensJourneyException(message, status, "MALFORMED_RESPONSE", cause = cause)
class BusyException(message: String = "Another journey operation is in progress") : LensJourneyException(message, code = "BUSY")
class InvalidSelectionException(message: String = "The selection is not enabled for the current step") : LensJourneyException(message, code = "INVALID_SELECTION")
class JourneyIncompleteException(message: String = "Journey must reach summary before finish") : LensJourneyException(message, code = "JOURNEY_INCOMPLETE")
/** The mobile key or base URL is unusable, for example a secret `cs_` key (code `SECRET_KEY_IN_APP`) or a key that is not `cspk_` (`INVALID_MOBILE_KEY`). */
class InvalidConfigurationException(message: String, code: String? = "INVALID_CONFIGURATION") : LensJourneyException(message, code = code)
/**
 * The install could not be attested: the provider failed (code `ATTESTATION_FAILED`, or `ATTESTATION_UNAVAILABLE` from a provider that
 * knows the device cannot attest), or the server refused the attestation (HTTP 401, code `ATTESTATION_FAILED`).
 */
class AttestationException(message: String, code: String? = "ATTESTATION_FAILED", status: Int? = null, cause: Throwable? = null) : LensJourneyException(message, status, code, cause = cause)
/** HTTP 403 `MOBILE_CHANNEL_DISABLED`: VARAi has switched the mobile channel off. */
class MobileChannelDisabledException(message: String, code: String? = "MOBILE_CHANNEL_DISABLED") : LensJourneyException(message, 403, code)

@Serializable
internal data class ErrorEnvelope(
    val error: String? = null,
    val message: String? = null,
    val code: String? = null,
    val details: JsonElement? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("documentation_url") val documentationUrl: String? = null,
)

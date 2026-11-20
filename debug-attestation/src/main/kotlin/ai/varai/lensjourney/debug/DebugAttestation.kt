package ai.varai.lensjourney.debug

import ai.varai.lensjourney.AttestationException
import ai.varai.lensjourney.AttestationPayload
import ai.varai.lensjourney.AttestationProvider
import ai.varai.lensjourney.HttpUrlConnectionTransport
import ai.varai.lensjourney.InMemoryInstallIdStore
import ai.varai.lensjourney.InstallIdStore
import ai.varai.lensjourney.JourneyConfig
import ai.varai.lensjourney.LensJourney
import ai.varai.lensjourney.MobileKeyConfig
import ai.varai.lensjourney.MobileKeyTransport
import ai.varai.lensjourney.Transport
import ai.varai.lensjourney.TransportRequest
import ai.varai.lensjourney.TransportResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put

/**
 * Staging-only debug attestation. It sends the server's challenge back unsigned. A production VARAi server refuses a `debug` binding
 * at issue time and at use time, so this can only ever work against staging, test or development servers.
 *
 * This is a separate artifact, `ai.varai:lens-journey-debug-attestation`, so that a release build does not contain it. Depend on it
 * from the staging or debug variant only (`stagingDebugImplementation(...)`), never from `implementation` or `releaseImplementation`.
 *
 * **The core artifact has no debug code at all**: no payload type, no provider, no `debug` wire field, no constant. The whole debug
 * flow lives here, and it is built from the core's ordinary public pieces. [transport] runs the core's [MobileKeyTransport] (nonce,
 * install token, caching, refresh and retry are the core's own code) over an HTTP seam that changes ONE thing, the body of
 * `install-token`: the platform binding the core wrote (which carries only the server's challenge, see [ChallengeProvider]) becomes
 * the `debug` binding. An app that depends on the core alone cannot do this with anything the core offers.
 *
 * Anyone can wrap a [Transport] and write any body: that is no different from hand-writing the HTTP request, and a production
 * server refuses a debug binding either way. What the core guarantees is that it never offers the path itself.
 */
object DebugAttestation {
    /**
     * A [Transport] for a staging app: the core's mobile-key transport with the debug install-token binding.
     *
     * @param http sends the requests. The default is the core's [HttpUrlConnectionTransport] against [baseUrl].
     */
    fun transport(
        mobileKey: String,
        baseUrl: String,
        installIdStore: InstallIdStore = InMemoryInstallIdStore(),
        allowInsecureHttp: Boolean = false,
        http: Transport = HttpUrlConnectionTransport(baseUrl, allowInsecureHttp),
        clockMillis: () -> Long = System::currentTimeMillis,
    ): Transport = MobileKeyTransport(
        MobileKeyConfig(mobileKey, baseUrl, ChallengeProvider(), installIdStore, allowInsecureHttp),
        DebugBindingTransport(http),
        clockMillis,
    )
}

/** A journey for a staging app that attests with the debug binding. The release counterpart is `LensJourney.mobile`. */
fun LensJourney.Companion.mobileDebug(
    config: JourneyConfig,
    mobileKey: String,
    baseUrl: String,
    installIdStore: InstallIdStore = InMemoryInstallIdStore(),
    allowInsecureHttp: Boolean = false,
    sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    clockMillis: () -> Long = System::currentTimeMillis,
): LensJourney = LensJourney(
    config,
    DebugAttestation.transport(mobileKey, baseUrl, installIdStore, allowInsecureHttp, clockMillis = clockMillis),
    sleep,
    clockMillis,
)

/**
 * Hands the server's challenge to [DebugBindingTransport] through the core's ordinary Play Integrity payload. The token field is
 * only a carrier here: the transport below replaces the binding before anything leaves the process, so no Play Integrity token is
 * ever sent, and the challenge is never signed.
 */
internal class ChallengeProvider : AttestationProvider {
    override suspend fun attest(challenge: String, installId: String): AttestationPayload = AttestationPayload.PlayIntegrity(challenge)
}

/** Rewrites the body of `POST .../install-token` from the carrier binding to `{"debug":{"challenge":...}}`. Every other request passes through. */
internal class DebugBindingTransport(private val inner: Transport) : Transport {
    override suspend fun send(request: TransportRequest): TransportResponse =
        if (request.method == "POST" && request.path.endsWith("/install-token")) inner.send(request.copy(body = debugBody(request.body))) else inner.send(request)

    private fun debugBody(body: ByteArray?): ByteArray {
        val original = try {
            Json.parseToJsonElement(requireNotNull(body).decodeToString()).jsonObject
        } catch (error: Exception) {
            throw AttestationException("The install-token request could not be read for debug attestation", cause = error)
        }
        // Fail closed: if the core ever stops writing the carrier binding, send nothing rather than a half-built request.
        val challenge = original["android"]?.jsonObject?.get(CARRIER_FIELD)?.jsonPrimitive?.content
            ?: throw AttestationException("The install-token request has no challenge for debug attestation")
        val rewritten: JsonObject = buildJsonObject {
            original.forEach { (name, value) -> if (name != "android" && name != "ios") put(name, value) }
            put("debug", buildJsonObject { put("challenge", challenge) })
        }
        return rewritten.toString().encodeToByteArray()
    }

    private companion object {
        const val CARRIER_FIELD = "integrity_token"
    }
}

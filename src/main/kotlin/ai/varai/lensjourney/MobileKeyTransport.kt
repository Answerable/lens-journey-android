package ai.varai.lensjourney

import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A [Transport] that talks to VARAi directly with an attested mobile publishable key.
 *
 * On the first journey call (and whenever the token is about to expire) it runs `install-nonce`, asks the [AttestationProvider] to
 * attest over the server's challenge, and exchanges that for a short-lived install token via `install-token`. Journey calls carry
 * the token in `X-CS-Install-Token`; the mobile key itself is only sent on the two mint calls.
 *
 * - The token is kept in memory and replaced about 5 minutes before it expires (or half-way through a token that lives under
 *   10 minutes). If that refresh fails while the old token is still valid, the old token is used.
 * - A `401` with code `INSTALL_TOKEN_INVALID` (revoked, or expired early) mints a new token once and retries the call once.
 * - A failed refresh is not retried on every call: while the old token is still valid the transport waits [REFRESH_RETRY_DELAY_MILLIS].
 * - App Attest recovery (an iOS payload type, handled here so both SDKs behave the same): the server accepts a new attestation only for
 *   an install without an accepted key. If it refuses a first attestation, or two refresh assertions in a row, the transport starts a new
 *   install (new install id) and mints once more. One refused assertion alone is tolerated.
 * - Concurrent calls share one mint.
 *
 * @param transport sends the requests. The default is [HttpUrlConnectionTransport] against [MobileKeyConfig.baseUrl], which refuses
 *   a non-https URL unless [MobileKeyConfig.allowInsecureHttp] is set. Tests inject a stub.
 */
class MobileKeyTransport(
    private val config: MobileKeyConfig,
    private val transport: Transport = HttpUrlConnectionTransport(config.baseUrl, config.allowInsecureHttp),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : Transport {
    private class CachedToken(val value: String, val refreshAtMillis: Long, val expiresAtMillis: Long)

    private val tokenMutex = Mutex()
    @Volatile private var cached: CachedToken? = null
    @Volatile private var installId: String? = null
    @Volatile private var refreshBackoffUntilMillis = 0L
    private var refusedRefreshes = 0
    private var installRotatedDuringMint = false

    override suspend fun send(request: TransportRequest): TransportResponse {
        val token = currentToken()
        val first = transport.send(authorized(request, token))
        if (first.status != 401 || errorCode(first) != INSTALL_TOKEN_INVALID) return first
        return transport.send(authorized(request, tokenAfterRejecting(token)))
    }

    // token cache

    private suspend fun currentToken(): String {
        usableWithoutMint()?.let { return it }
        return tokenMutex.withLock {
            usableWithoutMint()?.let { return@withLock it }
            try {
                mint().value.also { refreshBackoffUntilMillis = 0L }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A failed refresh is not a failure while the old token still works.
                cached?.let {
                    if (clockMillis() < it.expiresAtMillis) {
                        refreshBackoffUntilMillis = clockMillis() + REFRESH_RETRY_DELAY_MILLIS
                        return@withLock it.value
                    }
                }
                throw error
            }
        }
    }

    /** The cached token while it is outside the refresh window, or inside it but a recent refresh failed (back-off). */
    private fun usableWithoutMint(): String? {
        val token = cached ?: return null
        val now = clockMillis()
        if (now < token.refreshAtMillis) return token.value
        if (now < refreshBackoffUntilMillis && now < token.expiresAtMillis) return token.value
        return null
    }

    private suspend fun tokenAfterRejecting(stale: String): String = tokenMutex.withLock {
        // Another call may already have replaced the rejected token.
        cached?.let { if (it.value != stale && clockMillis() < it.expiresAtMillis) return@withLock it.value }
        if (cached?.value == stale) cached = null
        mint().value
    }

    // mint

    private suspend fun mint(): CachedToken {
        installRotatedDuringMint = false
        try {
            return mintOnce(allowNewInstall = true)
        } catch (error: Exception) {
            // The server refused an attestation that a new install can fix: try once more as a new install. That retry never starts a
            // further install, so a server that keeps refusing costs one new install id per call, not a chain of them.
            if (!installRotatedDuringMint || error is CancellationException) throw error
            installRotatedDuringMint = false
            return mintOnce(allowNewInstall = false)
        }
    }

    private suspend fun mintOnce(allowNewInstall: Boolean): CachedToken {
        val installId = resolveInstallId()
        val provider = config.attestationProvider

        val nonce = decode(NonceResponse.serializer(), post("install-nonce", buildJsonObject { put("install_id", installId); put("platform", PLATFORM) }))

        val payload = try {
            provider.attest(nonce.challenge, installId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: LensJourneyException) {
            throw error
        } catch (error: Exception) {
            throw AttestationException("The device could not attest this install: ${error.message}", cause = error)
        }

        val body = buildJsonObject {
            put("install_id", installId); put("platform", PLATFORM); put("nonce", nonce.nonce)
            when (payload) {
                is AttestationPayload.AppAttest -> put("ios", buildJsonObject {
                    put("key_id", payload.keyId)
                    payload.attestationObject?.let { put("attestation_object", Base64.getEncoder().encodeToString(it)) }
                    payload.assertion?.let { put("assertion", Base64.getEncoder().encodeToString(it)) }
                })
                is AttestationPayload.PlayIntegrity -> put("android", buildJsonObject { put("integrity_token", payload.token) })
            }
        }
        val tokenResponse = try {
            post("install-token", body)
        } catch (error: AttestationException) {
            provider.attestationRejected(installId)
            if (allowNewInstall && payload is AttestationPayload.AppAttest && shouldStartNewInstall(afterRefusedAssertion = payload.assertion != null)) {
                startNewInstall(installId)
            }
            throw error
        }
        val issued = decode(TokenResponse.serializer(), tokenResponse)
        val expiresAt = runCatching { OffsetDateTime.parse(issued.expiresAt).toInstant().toEpochMilli() }.getOrNull()
        if (expiresAt == null || issued.installToken.isEmpty()) {
            throw MalformedResponseException("The server issued an install token the SDK could not read", tokenResponse.status, IllegalArgumentException("install token"))
        }
        val lifetime = (expiresAt - clockMillis()).coerceAtLeast(0)
        val token = CachedToken(issued.installToken, expiresAt - minOf(REFRESH_LEAD_MILLIS, lifetime / 2), expiresAt)
        cached = token
        refusedRefreshes = 0
        provider.attestationAccepted(installId)
        return token
    }

    private suspend fun post(route: String, body: JsonObject): TransportResponse {
        val response = transport.send(
            TransportRequest("POST", "$API_ROOT/$route", mapOf("X-API-Key" to config.mobileKey), body.toString().encodeToByteArray(), "application/json"),
        )
        if (response.status !in 200..299) throw failure(response)
        return response
    }

    private fun resolveInstallId(): String {
        installId?.let { return it }
        val stored = config.installIdStore.load()
        if (stored != null && INSTALL_ID.matches(stored)) { installId = stored; return stored }
        return newInstallId()
    }

    private fun newInstallId(): String {
        val fresh = UUID.randomUUID().toString().replace("-", "")
        config.installIdStore.save(fresh)
        installId = fresh
        return fresh
    }

    /**
     * A refused first attestation is treated as an install id that cannot be attested again (the server refuses a new attestation for an
     * install with an accepted key), and a new install is the only recovery. A refused assertion may be transient, so the second in a row
     * ends the install.
     */
    private fun shouldStartNewInstall(afterRefusedAssertion: Boolean): Boolean {
        if (!afterRefusedAssertion) return true
        refusedRefreshes += 1
        return refusedRefreshes >= 2
    }

    private suspend fun startNewInstall(old: String) {
        refusedRefreshes = 0
        newInstallId()
        installRotatedDuringMint = true
        config.attestationProvider.installIdReplaced(old)
    }

    // wire

    private fun authorized(request: TransportRequest, token: String) = request.copy(headers = request.headers + (INSTALL_TOKEN_HEADER to token))

    private fun envelope(response: TransportResponse): ErrorEnvelope? =
        runCatching { LensJson.instance.decodeFromString(ErrorEnvelope.serializer(), response.body.decodeToString()) }.getOrNull()

    private fun errorCode(response: TransportResponse): String? = envelope(response)?.let { it.code ?: it.error }

    private fun failure(response: TransportResponse): LensJourneyException {
        val envelope = envelope(response)
        val code = envelope?.code ?: envelope?.error
        val message = envelope?.message ?: envelope?.error ?: "Request failed with HTTP ${response.status}"
        val exception = when (response.status) {
            400 -> InvalidRequestException(message, code = code)
            401 -> if (code == "ATTESTATION_FAILED") AttestationException(message, code, 401) else AuthenticationException(message, code)
            403 -> if (code == "MOBILE_CHANNEL_DISABLED") MobileChannelDisabledException(message, code) else InsufficientScopeException(message, code)
            429 -> RateLimitedException(message, code)
            503 -> ServiceUnavailableException(message, code)
            else -> ServiceUnavailableException(message, code, response.status)
        }
        exception.requestId = envelope?.requestId
        exception.documentationUrl = envelope?.documentationUrl
        return exception
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, response: TransportResponse): T = try {
        LensJson.instance.decodeFromString(serializer, response.body.decodeToString())
    } catch (error: IllegalArgumentException) {
        throw MalformedResponseException("The server answered ${response.status} with a body the SDK could not read", response.status, error)
    }

    @Serializable private class NonceResponse(val nonce: String, val challenge: String)
    @Serializable private class TokenResponse(@SerialName("install_token") val installToken: String, @SerialName("expires_at") val expiresAt: String)

    companion object {
        /** How long before expiry the token is replaced. */
        const val REFRESH_LEAD_MILLIS = 300_000L

        /** After a failed refresh (with the old token still valid) the next attempt waits this long. */
        const val REFRESH_RETRY_DELAY_MILLIS = 30_000L
        private const val PLATFORM = "android"
        private const val API_ROOT = "/api/v1/lens-journey"
        private const val INSTALL_TOKEN_HEADER = "X-CS-Install-Token"
        private const val INSTALL_TOKEN_INVALID = "INSTALL_TOKEN_INVALID"
        private val INSTALL_ID = Regex("^[A-Za-z0-9_-]{16,64}$")
    }
}

/** A journey that talks to VARAi directly with an attested mobile key. */
fun LensJourney.Companion.mobile(
    config: JourneyConfig,
    mobileKey: MobileKeyConfig,
    sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    clockMillis: () -> Long = System::currentTimeMillis,
): LensJourney = LensJourney(config, MobileKeyTransport(mobileKey), sleep, clockMillis)

package ai.varai.lensjourney

/**
 * Configuration for talking to VARAi directly with an attested mobile publishable key.
 *
 * The key must be a publishable mobile key (`cspk_...`). A secret key (`cs_...`) must never ship in an app: the constructor refuses
 * it, and refuses anything that is not a `cspk_` key, with [InvalidConfigurationException].
 *
 * @param baseUrl the VARAi API base URL for the environment this key belongs to (https). There is deliberately no default.
 * @param installIdStore where the install id lives. The default is in memory, so a new id (and a new attestation) is made on every
 *   launch: give the app a persistent store (see the README for a SharedPreferences sample).
 * @param allowInsecureHttp debug opt-in (an emulator reaching http://10.0.2.2). Never set it in a release build.
 */
class MobileKeyConfig(
    val mobileKey: String,
    val baseUrl: String,
    val attestationProvider: AttestationProvider,
    val installIdStore: InstallIdStore = InMemoryInstallIdStore(),
    val allowInsecureHttp: Boolean = false,
) {
    init {
        if (mobileKey.lowercase().startsWith("cs_")) {
            throw InvalidConfigurationException(
                "A secret key (cs_...) must never ship in an app. Use a mobile publishable key (cspk_...) and let VARAi attest the install.",
                "SECRET_KEY_IN_APP",
            )
        }
        if (!mobileKey.startsWith("cspk_") || mobileKey.length <= 5 || mobileKey.any { it.code <= 32 || it.code >= 127 }) {
            throw InvalidConfigurationException("mobileKey must be a mobile publishable key starting with cspk_", "INVALID_MOBILE_KEY")
        }
    }

    /** The key is never printed. */
    override fun toString() = "MobileKeyConfig(baseUrl=$baseUrl, mobileKey=<redacted>)"
}

/**
 * Where the SDK keeps the install id (a random id for one app installation). Persist it across launches and drop it when the app is
 * uninstalled (a private `SharedPreferences` file does both). The in-memory default forgets it on every launch.
 */
interface InstallIdStore {
    fun load(): String?
    fun save(installId: String)
}

class InMemoryInstallIdStore(installId: String? = null) : InstallIdStore {
    @Volatile private var value: String? = installId
    override fun load(): String? = value
    override fun save(installId: String) { value = installId }
}

/** The platform proof the server checks. The case decides which field of `POST /install-token` carries it. */
sealed class AttestationPayload {
    /** App Attest (iOS). Send [attestationObject] on the first issue for a key, or [assertion] on a refresh. Android hosts do not use it. */
    class AppAttest(val keyId: String, val attestationObject: ByteArray? = null, val assertion: ByteArray? = null) : AttestationPayload() {
        override fun equals(other: Any?) = other is AppAttest && keyId == other.keyId &&
            attestationObject.contentEqualsNullable(other.attestationObject) && assertion.contentEqualsNullable(other.assertion)
        override fun hashCode() = keyId.hashCode()
        override fun toString() = "AttestationPayload.AppAttest(<redacted>)"
    }

    /** A Play Integrity token (Android) made over the server's challenge. */
    data class PlayIntegrity(val token: String) : AttestationPayload() {
        override fun toString() = "AttestationPayload.PlayIntegrity(<redacted>)"
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?) = if (this == null || other == null) this == other else contentEquals(other)

/**
 * Produces the proof that this is a genuine install of the app. [challenge] comes from the server (bound to the install id); the proof
 * must be made over it. On Android that is a Play Integrity token whose nonce is the challenge (see the README for a sample).
 *
 * Throw [AttestationException] when the device cannot attest; any other exception is wrapped as one.
 */
interface AttestationProvider {
    suspend fun attest(challenge: String, installId: String): AttestationPayload

    /** The server accepted the attestation and issued a token. Providers that stage state commit it here. */
    suspend fun attestationAccepted(installId: String) {}

    /**
     * The server refused the attestation. Providers drop state staged for this attempt. Keep anything the server already accepted: the
     * refusal may be transient, and the transport ends the install by itself when refusals repeat ([installIdReplaced]).
     */
    suspend fun attestationRejected(installId: String) {}

    /** The transport started a new install (a new install id) because the server will not attest this one again. Drop everything kept for [oldInstallId]. */
    suspend fun installIdReplaced(oldInstallId: String) {}
}

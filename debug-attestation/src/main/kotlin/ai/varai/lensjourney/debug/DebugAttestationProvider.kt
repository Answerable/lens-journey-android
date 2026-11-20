package ai.varai.lensjourney.debug

import ai.varai.lensjourney.AttestationPayload
import ai.varai.lensjourney.AttestationProvider
import ai.varai.lensjourney.DEBUG_ATTESTATION_FIELD
import ai.varai.lensjourney.DEBUG_ATTESTATION_ORIGIN

/**
 * Staging-only. It sends the server's challenge back unsigned. A production VARAi server refuses a `debug` binding at issue time and
 * at use time, so this provider can only ever work against staging, test or development servers.
 *
 * This lives in its own artifact, `ai.varai:lens-journey-debug-attestation`, so that a release build does not contain it. Depend on it
 * from the staging or debug variant only (`stagingDebugImplementation(...)`), never from `implementation` or `releaseImplementation`.
 * The core artifact has no debug payload and no public way to make one: this class is the only maker.
 */
class DebugAttestationProvider : AttestationProvider {
    override suspend fun attest(challenge: String, installId: String): AttestationPayload =
        // The constructor is `internal` to the core artifact. The Gradle build of this module is the only one granted access to it.
        AttestationPayload.Restricted(DEBUG_ATTESTATION_ORIGIN, DEBUG_ATTESTATION_FIELD, mapOf("challenge" to challenge))
}

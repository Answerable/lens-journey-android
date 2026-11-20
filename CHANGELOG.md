# Changelog

All notable changes to the Lens Journey Android SDK. Versions are `0.x`: the API can change in a minor release (see the README).

## 0.4.0 (pre-release 0.x, unpublished)

- **Breaking, source:** debug attestation moves to a separate, opt-in artifact, `ai.varai:lens-journey-debug-attestation`. ADR-369 D3/D4: a release build must not be able to use debug attestation, and 0.3.0 could. `AttestationPayload.Debug` was a public subclass of the sealed class, `DebugAttestationProvider` was public in the single artifact, and `MobileKeyTransport` serialised a `Debug` payload without any condition, so a release app could name the provider or return `Debug` from its own provider. The production server refuses debug bindings, so this was defence in depth, but the SDK must not offer the path (issue #1; iOS fixed the same thing in 0.4.0).
- **Removed from `ai.varai:lens-journey`:** `AttestationPayload.Debug` and `DebugAttestationProvider`. The core artifact has no public type, constructor or serialiser for a debug payload. The `binary-compatibility-validator` API dump (`api/lens-journey-android.api`) is committed and checked by `apiCheck`, so adding one again fails the build.
- **New artifact `ai.varai:lens-journey-debug-attestation`:** holds `ai.varai.lensjourney.debug.DebugAttestationProvider` (note the new package) and is the only code that can make a debug payload. Add it to the staging/debug variant only (`stagingDebugImplementation("ai.varai:lens-journey-debug-attestation:0.4.0")`), never to `implementation` or a release variant. It carries the same version as the core.
- **New: `AttestationPayload.Restricted`:** a payload kind with no public constructor and no public fields (both are `internal`), printed as `<redacted>`. Only the debug artifact can make one: its build is named a Kotlin friend module of the core (`-Xfriend-paths`), and an app's build is not, so an app's compiler refuses `AttestationPayload.Restricted(...)`. Behaviour of an existing `when` over `AttestationPayload`: add a `Restricted` branch (or an `else`) where you had a `Debug` branch.
- **Second line of defence:** `internal` is public in the bytecode, so Java code or reflection could still call the constructor. The transport sends a `Restricted` payload only if it carries the debug artifact's origin and the `debug` wire field; anything else throws `AttestationException` (code `ATTESTATION_PAYLOAD_NOT_ALLOWED`) before `install-token` is called. This is defence in depth, not the boundary: the compiler is the boundary, and an app that passes `-Xfriend-paths` by hand or vendors the sources has made a source-level fork.
- **Migration:** a staging or debug app swaps `import ai.varai.lensjourney.DebugAttestationProvider` for `import ai.varai.lensjourney.debug.DebugAttestationProvider` and adds the new artifact to that variant. A production app needs nothing, and its release build should be scanned to make sure the debug artifact is not in it (see the README).
- Tests: the `boundary-tests` module compiles app code with the real Kotlin compiler against the core artifact only and asserts that naming `Debug`, `DebugAttestationProvider` or the `Restricted` constructor fails to compile, with a control that compiles; `consumer-with-debug` is a staging app that can send debug attestation.
- `LensJourney.VERSION` and the Gradle `version` are `0.4.0`.

## 0.3.0 (pre-release 0.x, unpublished)

- **New, the headline:** the app can talk to VARAi directly with an attested mobile publishable key (`cspk_...`) instead of through a merchant proxy. The merchant's platform prices the cart; the app only passes the configuration id and the session id (ADR-369).
- New: `MobileKeyConfig(mobileKey, baseUrl, attestationProvider, installIdStore, allowInsecureHttp)`. It throws `InvalidConfigurationException` for a secret `cs_` key (code `SECRET_KEY_IN_APP`, the message says secret keys must never ship in an app) and for anything that is not `cspk_` (`INVALID_MOBILE_KEY`). There is no default base URL. Its `toString` never prints the key.
- New: `MobileKeyTransport`, a `Transport` that runs `install-nonce`, asks the provider to attest over the challenge, exchanges the proof at `install-token`, keeps the token in memory, replaces it about 5 minutes before it expires, sends it as `X-CS-Install-Token`, and mints once and retries once on `401 INSTALL_TOKEN_INVALID`. Concurrent calls share one mint, and a failed refresh falls back to a token that is still valid. `LensJourney.mobile(config, mobileKey)` (an extension on `LensJourney.Companion`) builds a journey on it.
- New: `AttestationProvider` (`attest(challenge, installId)`, plus optional `attestationAccepted` and `attestationRejected`), `AttestationPayload` (`AppAttest`, `PlayIntegrity`, `Debug`), `InstallIdStore` with `InMemoryInstallIdStore` (the default). The module stays pure Kotlin/JVM: the README shows a Play Integrity provider and a `SharedPreferences` store for the host app.
- New: `DebugAttestationProvider`, **staging only**: a production server refuses a debug binding when it is issued and when it is used.
- New: App Attest recovery in the transport (shared with iOS): a refused first attestation, or two refused assertions in a row, starts a new install (a new install id) and mints once more in the same call. One refused assertion keeps the install. `AttestationProvider` gained `installIdReplaced(oldInstallId)` (default no-op). A Play Integrity refusal never starts a new install.
- New: a failed refresh is not retried on every call; the transport waits `MobileKeyTransport.REFRESH_RETRY_DELAY_MILLIS` (30 s) while the old token is valid.
- New: `TransportRequest` and `AttestationPayload` print header names only / `<redacted>`, so a log line cannot carry a key, token, challenge or proof.
- New exceptions: `InvalidConfigurationException`, `AttestationException` (code `ATTESTATION_FAILED` or `ATTESTATION_UNAVAILABLE`), `MobileChannelDisabledException`.
- **Deprecated:** the merchant-proxy model (`HttpUrlConnectionTransport` against a proxy that holds a secret key). It keeps working unchanged. The README now leads with the mobile key.
- Docs: how the app hands `final.id` and `sessionId` to the merchant's cart without pricing it, and the Magento cart handoff (`POST /rest/V1/commerce-studio/lens-journey/cart` and the guest variant).
- `LensJourney.VERSION` and the Gradle `version` are `0.3.0`.

## 0.2.0 (pre-release 0.x)

- **Breaking, source:** `LensConfiguration.subtotal` is `BigDecimal?`, no longer `Double?`. The old type could not decode the server's string subtotal at all, so `finish()` failed on the first real success. 0.1.0 still has `Double?`; 0.2.0 is the first version that carries `BigDecimal?`.
- **Breaking, source:** the public `DecimalSerializer` object that an earlier unreleased change added is removed. The `internal` `ExactDecimalSerializer` replaces it, so code that named `DecimalSerializer` (for example in its own `@Serializable(with = ...)`) no longer compiles. `LensConfiguration.subtotalString` is new.
- **Breaking, behaviour:** `subtotal` must be a plain decimal of at most 40 characters, as a string or a number. A string with surrounding whitespace is now rejected (the earlier serializer trimmed it), and so are an exponent form (`1e3`, `1e999999999`, which could exhaust memory) and anything longer than 40 characters. All of these throw `MalformedResponseException` from `finish()`.
- **Breaking, source:** `LensJourney`'s companion object is now public (it carries `VERSION`).
- **Breaking, behaviour:** `JourneyConfig` validates `brand` and throws `InvalidRequestException` for an invalid one.
- `MalformedResponseException` is new: a 2xx body that cannot be decoded throws it instead of a raw `SerializationException`; this includes a malformed `subtotal`, `subtotal_minor` or `currency_decimals`.
- New on `LensConfiguration`: `subtotalString`, `subtotalMinor`, `currencyDecimals`, `priceBasis`, `priceBasisNote`, `status`, `isQuote`, `currencyMinorUnitDigits`, `subtotalMinorUnits`. `inheritedFromPartner` and `prescriptionOnFile` already defaulted to `false`.
- New: `JourneyConfig.brand` (last parameter, so positional callers still compile), sent on `/evaluate` and `/configurations` only when set; `LensJourney.VERSION`; Gradle `version` is `0.2.0`.
- **Breaking, source:** `UnexpectedResponseException` is removed. An unlisted HTTP status is now `ServiceUnavailableException` (with the real `status`), matching iOS `.unavailable`.
- **Breaking, source:** `finish()` with no `start`, no picks or no summary throws `JourneyIncompleteException` (it was `InvalidRequestException`). `select()` with no current step throws `InvalidStateException` (it was `InvalidRequestException`).
- `scanPrescription` is exclusive with the other stateful calls (`BusyException`).
- `HttpUrlConnectionTransport.send` aborts the socket on coroutine cancel instead of blocking until the read timeout.
- Not new here: the iOS `URLSessionTransport` initializer already throws for a non-https base URL (an earlier unpublished change), as `HttpUrlConnectionTransport` always has with `InvalidRequestException`.
- New: `InvalidStateException`; `NetworkException` has code `NETWORK_ERROR`; client-side `InvalidRequestException` has code `INVALID_REQUEST`; `RequestTooLargeException` code is `REQUEST_TOO_LARGE` (was `CLIENT_REQUEST_TOO_LARGE`) to match iOS; the client-side image guards carry `PAYLOAD_TOO_LARGE` and `UNSUPPORTED_MEDIA_TYPE`.

## 0.1.0

- First pre-release.

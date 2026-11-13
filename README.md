# Lens Journey Android core

> **Pre-release (0.x), unpublished.** The API can still change without notice. Pin an exact version.

Pure Kotlin/JVM client for the server-driven Commerce Studio lens journey. It has no Android framework dependency and contains no lens rules and no API secret.

**How the app is authenticated (0.3.0).** The app talks to VARAi directly, with an attested mobile publishable key (`cspk_...`). The merchant's platform prices the cart: the app hands the journey result (the configuration id and the `sessionId`) to the merchant's cart, and the merchant's platform prices it from its own catalogue (ADR-369). The app never prices anything and never holds a secret key. A secret key (`cs_...`) must never ship in an app, and the SDK refuses one.

```kotlin
// MobileKeyConfig throws InvalidConfigurationException for a secret cs_ key and for anything that is not cspk_.
val mobile = MobileKeyConfig(
    mobileKey = appMobileKey,                      // "cspk_..." from your VARAi dashboard
    baseUrl = apiBaseUrl,                          // the VARAi API URL for your environment (https)
    attestationProvider = PlayIntegrityProvider(context, cloudProjectNumber), // DebugAttestationProvider() only against staging
    installIdStore = PrefsInstallIdStore(context), // keep the install id across launches
)
val journey = LensJourney.mobile(JourneyConfig(currency = "USD", store = "us"), mobile)

val first = journey.start("single-vision", FrameRef(sku = "FRAME-1"))
val next = journey.select(first.step!!.options.first().id)
val final = journey.finish() // final.id is the configuration id the app gives to the merchant's cart
```

(`LensJourney.mobile` is an extension on `LensJourney.Companion`: `import ai.varai.lensjourney.mobile`.)

## Mobile key, attestation and install tokens

> **Needs the server side.** The mobile channel is off by default on the VARAi API (`403 MOBILE_CHANNEL_DISABLED`, typed as ``MobileChannelDisabledException``) until VARAi enables it for your tenant and binds your app to a mobile key. Until then use the proxy transport below.

A mobile key alone authorises nothing. `MobileKeyTransport` (what `LensJourney.mobile` uses) exchanges it for a short-lived **install token**:

1. `POST /api/v1/lens-journey/install-nonce` with the install id gives a single-use nonce and a challenge that is bound to this install.
2. The `AttestationProvider` proves the install is a genuine copy of your app over that challenge (Play Integrity on Android).
3. `POST /api/v1/lens-journey/install-token` exchanges the proof for an install token (valid for about an hour).
4. Every journey call carries the token in `X-CS-Install-Token`. The mobile key is sent only on the two mint calls.

The token is kept in memory and replaced about 5 minutes before it expires (half-way through a token that lives under 10 minutes). If that refresh fails while the old token still works, the old token is used. If the server answers `401` with code `INSTALL_TOKEN_INVALID` (revoked, or expired early) the SDK mints a new token once and retries that call once. Concurrent calls share one mint. If the refresh keeps failing, the SDK does not try again on every call: it waits 30 seconds (`MobileKeyTransport.REFRESH_RETRY_DELAY_MILLIS`) while the old token is still valid.

**Install id.** A random id for one app installation, made on first use and kept in an `InstallIdStore`. The default `InMemoryInstallIdStore` forgets it on every launch, which means a new attestation on every launch (and a per-device cap you will hit). Persist it in a private file that is removed with the app:

```kotlin
class PrefsInstallIdStore(context: Context) : InstallIdStore {
    private val prefs = context.getSharedPreferences("lens_journey", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString("install_id", null)
    override fun save(installId: String) { prefs.edit().putString("install_id", installId).apply() }
}
```

Exclude that file from Auto Backup and device-transfer (`android:fullBackupContent` / `android:dataExtractionRules`), so a restored id never lands on another device. (An App Attest install recovers by itself with a new install id; Play Integrity has no per-install key, so there is nothing to recover and the restored id is simply reused.)

**Play Integrity.** This module is pure Kotlin/JVM, so the provider lives in your app, where the Play Integrity API is available (`com.google.android.play:integrity` and `kotlinx-coroutines-play-services`). Pass the server's challenge as the request nonce, unchanged; VARAi checks that the verdict's nonce equals the challenge, that the app is recognised and that the device meets device integrity. This sample is intentionally not compiled by this module:

```kotlin
class PlayIntegrityProvider(private val context: Context, private val cloudProjectNumber: Long) : AttestationProvider {
    override suspend fun attest(challenge: String, installId: String): AttestationPayload {
        val manager = IntegrityManagerFactory.create(context)
        val response = manager.requestIntegrityToken(
            IntegrityTokenRequest.builder().setNonce(challenge).setCloudProjectNumber(cloudProjectNumber).build(),
        ).await()
        return AttestationPayload.PlayIntegrity(response.token())
    }
}
```

Throw `AttestationException` (or let the Play error propagate: any other exception is wrapped as one) when the device cannot attest. A device that fails attestation cannot start a journey: emulators and rooted devices are refused by design.

**`DebugAttestationProvider` is for staging only.** It sends the challenge back unsigned. A production VARAi server refuses a debug binding when it is issued and again when it is used, so it works only against staging, test or development servers. Use it for emulator runs and CI against staging, and never ship it in a release build (for example put it in `debug/`).

The optional `attestationAccepted(installId)`, `attestationRejected(installId)` and `installIdReplaced(oldInstallId)` let a provider commit or drop state around the server's verdict: drop what you staged for a refused attempt, keep what the server already accepted, and drop everything for the old id when the SDK starts a new install. The SDK starts a new install (a new install id, one more mint in the same call) when a first App Attest attestation is refused or two assertions are refused in a row; a Play Integrity refusal never does.

Failures are typed: `AttestationException` (code `ATTESTATION_FAILED`, from the provider or from a server `401`), `MobileChannelDisabledException` (`403 MOBILE_CHANNEL_DISABLED`) and `InvalidConfigurationException` (`SECRET_KEY_IN_APP`, `INVALID_MOBILE_KEY`). The mint calls themselves are not retried; a `429` is `RateLimitedException`.

The host stores a stable per-install `clientId` matching `^[A-Za-z0-9_-]{8,64}$`. The default is a valid random value, but persistence belongs to the app. `sessionId` is generated per journey unless supplied. `scanPrescription` returns an explicitly unconfirmed draft; only a shopper-confirmed `Prescription` passed to `setPrescription` affects routing. `finish()` is authoritative for price and returns the configuration id the host carries into its own cart. Its idempotency key is reused for the in-call 429/503 retry and across repeated `finish()` calls after a surfaced 503 or network error (same selections and prescription, same key, so the server does not create a second configuration); `start`, `select`, `back` and `setPrescription` start a new key. `back()` is local (no request) and undoes the last `select`; with nothing to undo (before `start`, right after `start`, or once every pick is undone) it throws `InvalidRequestException`. Changing the prescription does not add an undo step; after `back()` the returned state's prescription check reflects the current prescription, but the earlier step's options, `enabled` flags and gate are as they were when that step was evaluated. This is display only: `back()` makes no request by design, and the server re-evaluates on the next `select`, so a stale option can be shown but never accepted. A 400 with code `INVALID_CLIENT_ID` is `InvalidClientIdException`. Cancelling the calling coroutine surfaces `CancellationException` (never a `NetworkException`) and sends no retry. `HttpUrlConnectionTransport` aborts an in-flight read on cancel by closing the connection (blocking `HttpURLConnection` reads ignore thread interrupts), so cancelling does not wait out the 15 second read timeout.

Stateful calls are exclusive: an overlapping `start`, `select`, `back`, `setPrescription`, or `finish` throws `BusyException`. Selections must identify an enabled option in the current step; single-choice selections replace the node's prior value, while multiple-choice selections append without duplicates. Failed evaluations leave state unchanged, and `finish()` requires a summary terminal.

For 429 responses, `Retry-After` accepts delta-seconds or an HTTP date and is clamped to 0–30 seconds; invalid, negative, or overflowing values fall back to one second. Tests can inject `sleep` and `clockMillis`. Starting again clears picks/history; a prescription set before `start` or for the same category is retained, while changing category clears it.

## Errors (same mapping on iOS)

Errors the SDK raises itself (validation, state, lock, transport, decode) carry a `code`. Errors mapped from an HTTP response carry the server's own `code`, which is `null` when the server sent none (so a server-sent 413 or 415 can have `code == null`). The mapping matches iOS `LensJourneyError`:

| Situation | Exception (code) |
| --- | --- |
| Any status not otherwise listed, including an unlisted 4xx such as 418 (also 500, 502, 504); not retried | `ServiceUnavailableException` (server code, real `status`; read `status`, the class name is the shared "unavailable" kind) |
| `finish()` before `start`, with no picks, or before the journey reaches a summary | `JourneyIncompleteException` (`JOURNEY_INCOMPLETE`) |
| `select()` with no current step, or a call that needs `start` first | `InvalidStateException` (`INVALID_STATE`) |
| A 2xx whose body cannot be decoded (evaluate, finish, scan) | `MalformedResponseException` (`MALFORMED_RESPONSE`, with the HTTP `status`) |
| Client-side validation (lengths, limits) | `InvalidRequestException` (`INVALID_REQUEST`) |
| Transport failure | `NetworkException` (`NETWORK_ERROR`) |
| A secret `cs_` key, or a key that is not `cspk_` | `InvalidConfigurationException` (`SECRET_KEY_IN_APP`, `INVALID_MOBILE_KEY`) |
| The provider cannot attest, or the server refuses the attestation (`401`) | `AttestationException` (`ATTESTATION_FAILED`, `ATTESTATION_UNAVAILABLE`) |
| `403 MOBILE_CHANNEL_DISABLED` on a mint | `MobileChannelDisabledException` |

A malformed 2xx commits nothing: state, picks and history are as they were and the operation lock is released. `scanPrescription` takes the same exclusive lock as the other stateful calls (`BusyException` while another call is in flight, and the reverse).

## Price and brand

`finish()` returns a `LensConfiguration`. The server sends `subtotal` as a JSON **string** at the currency's decimals, such as `"120.00"` or `"22.345"` for KWD. The SDK reads it into an exact `BigDecimal` built from the text, never a `Double`. A JSON number is also accepted and read from its own token text, so it is exact (`1.50` stays `1.50`). Both a string and a number must be a plain decimal of at most 40 characters: an exponent form such as `1e3` or `1e999999999`, surrounding whitespace, or anything longer is a malformed response (a `BigDecimal` built from `1e999999999` is cheap to create but exhausts memory when printed or scaled). **iOS and Android differ on numbers:** iOS receives a JSON number as a `Double`, so it loses precision above about 15 significant digits and drops trailing zeros (`1.50` reads as `1.5`), and it accepts `1e3` because `Double` has already absorbed the exponent. The server only ever sends strings, so treat a numeric `subtotal` as a tolerance, not a contract. Use `subtotal` for arithmetic and `subtotalString` for the exact text (`toPlainString`, trailing zeros kept). A `BigDecimal` keeps the server's scale, so `120.00` does not `equals` `120`: compare with `compareTo`.

**`currency_decimals` is authoritative.** Catalogue markets (for example KWD and EUR) also send `subtotal_minor`, `currency_decimals`, `price_basis` and `price_basis_note`, and no `subtotal_paise`. `currencyMinorUnitDigits` returns the server's `currency_decimals`, and falls back to the ISO 4217 table (KWD 3, JPY 0, EUR 2) only when it is absent, as for India. `subtotalMinorUnits` returns the server's `subtotal_minor` when present and otherwise derives it from `subtotal` (null if the amount has more digits than the currency). India keeps `subtotal_paise` and sends none of the new fields; `subtotalPaise` is passed through unchanged. Format with the currency's decimals, not a fixed two places.

**`subtotal` is the lens only, not the frame, and it is a quote when `priceBasis == "quote"`** (`isQuote`): an indicative price. The merchant's cart re-prices every line and is the charging authority, and this configuration is never charged. `priceBasisNote` carries the server's wording for the UI.

A 2xx body that cannot be decoded (for example a `subtotal` that is neither a decimal string nor a number, or a `currency_decimals` that is not an integer from 0 to 6) throws `MalformedResponseException` (code `MALFORMED_RESPONSE`), not a raw `SerializationException`. This applies to `/evaluate`, `/rx-ocr` and `/configurations` responses.

`JourneyConfig(brand = ...)` is optional and takes a brand code the server knows (for example `"ACME"`). It must match the server's rule, a letter followed by up to 31 letters or digits after trimming (the server compares case-insensitively); anything else throws `InvalidRequestException` from the constructor, and the trimmed value is what is sent. When set, the SDK sends `brand` on every `/evaluate` call and on the `/configurations` call made by `finish()`, so the server picks that brand's workflow. When null it sends no `brand` field and behaviour is unchanged.

`LensJourney.VERSION` is the SDK version (`"0.3.0"`); it matches `version` in `build.gradle.kts`.

## Distribution

Add the library from Maven Central and pin an exact version while the API is `0.x` (use Gradle dependency locking):

```kotlin
implementation("ai.varai:lens-journey:0.3.0")
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## Environments

`MobileKeyConfig.baseUrl` selects the VARAi environment, and the mobile key must belong to that same environment, or every mint returns 401. There is no default base URL: pass the one for your environment. `currency` and `store` in `JourneyConfig` must match the tenant (for example a merchant selling in Kuwaiti dinar uses `currency = "KWD"`); the SDK does not check them. The SDK has no environment switch of its own.

## Handing the result to the merchant's cart

`finish()` returns a `LensConfiguration`. Its `id` is the configuration id; the session is `JourneyConfig.sessionId`. The app passes **only those two ids** (plus the frame and quantity) to the merchant's cart, and never a price: `subtotal` is an indicative quote for display, and the merchant's platform prices the line from its own catalogue (ADR-369). The platform reserves the configuration with VARAi, so a configuration cannot be used by two carts or by another shopper's session.

### Magento

Once the merchant's Magento store runs the `CommerceStudio/LensJourney` module that adds the lens cart (it ships separately from this SDK and is not in every store yet; the endpoints exist only with it, behind the store setting `commerce_studio_lens_journey/general/lens_cart_reserve_enabled`, off by default), it exposes:

- `POST /rest/V1/commerce-studio/lens-journey/cart` with the shopper's customer token (resource `self`).
- `POST /rest/V1/commerce-studio/lens-journey/guest-carts/{cartId}/items` for a guest, with the masked cart id.

```kotlin
// Your own HTTP client (OkHttp, Retrofit, ...). The body carries ids only.
val body = buildJsonObject {
    put("sku", "FRAME-1"); put("qty", 1)
    put("configuration_id", final.id); put("session_id", journeyConfig.sessionId)
}
// POST $merchantBase/rest/V1/commerce-studio/lens-journey/cart   Authorization: Bearer <customer token>
```

The body is `{ "sku" | "product_id": ..., "qty": ..., "configuration_id": ..., "session_id": ... }`. Any price in the body is ignored. Magento reserves the configuration, prices the frame and the lens products from its own catalogue and adds the line; if the reservation or the pricing fails, nothing is added and the shopper gets an error. When the order is placed, Magento confirms the configuration and carries the lens details onto the order.

## Compatibility: the proxy transport (deprecated)

> **Deprecated.** The 0.2 model, where the app talks only to a merchant-run proxy that holds a secret key, still works but is deprecated. Prefer the mobile key. A proxy must be a server you run; the secret key must never reach the app.

`HttpUrlConnectionTransport` with your own proxy URL and header provider keeps working unchanged:

```kotlin
// HttpUrlConnectionTransport throws InvalidRequestException for a non-https base URL
// (pass allowInsecureHttp = true only in a debug build, e.g. http://10.0.2.2:3000 from an emulator).
val journey = LensJourney(
    JourneyConfig(clientId = stableInstallId),
    HttpUrlConnectionTransport("https://customer.example/lens-proxy") {
        mapOf("Authorization" to "Bearer ${session.accessToken}")
    },
)
```

## Android image preparation

Implement `ImagePreparer` in the Android app, where `android.graphics` is available. Downsample to a 2048 px long edge and JPEG-encode below 4 MB; the core rejects non-image MIME types and anything over 10 MB before upload.

```kotlin
val preparer = ImagePreparer { input ->
    val bitmap = BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size)
    // Scale so max(width, height) <= 2048, then compress with decreasing quality.
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    PreparedImage(out.toByteArray(), "image/jpeg")
}
```

The snippet belongs in the Android host and is intentionally not compiled by this JVM module.

## Build and test

The Gradle 8.10.2 wrapper is included and needs JDK 17:

```text
./gradlew test --stacktrace
```

Tests replay the shared captured fixtures (`fixtures/`). CI pins Gradle 8.10.2, JDK 17 (a local JDK 21 works with a temporary toolchain override), Kotlin 2.0.21, serialization JSON 1.7.3, and coroutines 1.9.0.

# Lens Journey Android core

> **Pre-release (0.x).** The API can still change without notice. Pin an exact version.

Pure Kotlin/JVM client for the server-driven Commerce Studio lens journey. It has no Android framework dependency and never contains an API key: the host supplies a `Transport` (or a proxy URL plus headers with `HttpUrlConnectionTransport`). The customer's backend proxy owns authentication.

```kotlin
// HttpUrlConnectionTransport throws InvalidRequestException for a non-https base URL
// (pass allowInsecureHttp = true only in a debug build, e.g. http://10.0.2.2:3000 from an emulator).
val journey = LensJourney(
    JourneyConfig(clientId = stableInstallId),
    HttpUrlConnectionTransport("https://customer.example/lens-proxy") {
        mapOf("Authorization" to "Bearer ${session.accessToken}")
    },
)

val first = journey.start("single-vision", FrameRef(sku = "FRAME-1"))
val next = journey.select(first.step!!.options.first().id)
```

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

A malformed 2xx commits nothing: state, picks and history are as they were and the operation lock is released. `scanPrescription` takes the same exclusive lock as the other stateful calls (`BusyException` while another call is in flight, and the reverse).

## Price and brand

`finish()` returns a `LensConfiguration`. The server sends `subtotal` as a JSON **string** at the currency's decimals, such as `"120.00"` or `"22.345"` for KWD. The SDK reads it into an exact `BigDecimal` built from the text, never a `Double`. A JSON number is also accepted and read from its own token text, so it is exact (`1.50` stays `1.50`). Both a string and a number must be a plain decimal of at most 40 characters: an exponent form such as `1e3` or `1e999999999`, surrounding whitespace, or anything longer is a malformed response (a `BigDecimal` built from `1e999999999` is cheap to create but exhausts memory when printed or scaled). **iOS and Android differ on numbers:** iOS receives a JSON number as a `Double`, so it loses precision above about 15 significant digits and drops trailing zeros (`1.50` reads as `1.5`), and it accepts `1e3` because `Double` has already absorbed the exponent. The server only ever sends strings, so treat a numeric `subtotal` as a tolerance, not a contract. Use `subtotal` for arithmetic and `subtotalString` for the exact text (`toPlainString`, trailing zeros kept). A `BigDecimal` keeps the server's scale, so `120.00` does not `equals` `120`: compare with `compareTo`.

**`currency_decimals` is authoritative.** Catalogue markets (for example KWD and EUR) also send `subtotal_minor`, `currency_decimals`, `price_basis` and `price_basis_note`, and no `subtotal_paise`. `currencyMinorUnitDigits` returns the server's `currency_decimals`, and falls back to the ISO 4217 table (KWD 3, JPY 0, EUR 2) only when it is absent, as for India. `subtotalMinorUnits` returns the server's `subtotal_minor` when present and otherwise derives it from `subtotal` (null if the amount has more digits than the currency). India keeps `subtotal_paise` and sends none of the new fields; `subtotalPaise` is passed through unchanged. Format with the currency's decimals, not a fixed two places.

**`subtotal` is the lens only, not the frame, and it is a quote when `priceBasis == "quote"`** (`isQuote`): an indicative price. The merchant's cart re-prices every line and is the charging authority, and this configuration is never charged. `priceBasisNote` carries the server's wording for the UI.

A 2xx body that cannot be decoded (for example a `subtotal` that is neither a decimal string nor a number, or a `currency_decimals` that is not an integer from 0 to 6) throws `MalformedResponseException` (code `MALFORMED_RESPONSE`), not a raw `SerializationException`. This applies to `/evaluate`, `/rx-ocr` and `/configurations` responses.

`JourneyConfig(brand = ...)` is optional and takes a brand code the server knows (for example `"ACME"`). It must match the server's rule, a letter followed by up to 31 letters or digits after trimming (the server compares case-insensitively); anything else throws `InvalidRequestException` from the constructor, and the trimmed value is what is sent. When set, the SDK sends `brand` on every `/evaluate` call and on the `/configurations` call made by `finish()`, so the server picks that brand's workflow. When null it sends no `brand` field and behaviour is unchanged.

`LensJourney.VERSION` is the SDK version (`"0.2.0"`); it matches `version` in `build.gradle.kts`.

## Distribution

Add the library from Maven Central and pin an exact version while the API is `0.x` (use Gradle dependency locking):

```kotlin
implementation("ai.varai:lens-journey:0.2.0")
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## Environments

The app only ever talks to the merchant's proxy (the `HttpUrlConnectionTransport` base URL above). The proxy decides which VARAi environment it calls. The API key the proxy holds must belong to that same environment, or every call returns 401. `currency` and `store` in `JourneyConfig` must match the tenant (for example a merchant selling in Kuwaiti dinar uses `currency = "KWD"`); the SDK does not check them. The SDK has no environment switch of its own.

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

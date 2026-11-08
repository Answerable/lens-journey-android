# Changelog

All notable changes to the Lens Journey Android SDK. Versions are `0.x`: the API can change in a minor release (see the README).

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

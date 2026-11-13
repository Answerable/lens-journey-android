package ai.varai.lensjourney

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

data class TransportRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
) {
    /** Header values carry the mobile key and the install token, so only the header names are printed. */
    override fun toString() = "TransportRequest($method $path, headers=${headers.keys.sorted()}, body=${body?.size ?: 0} bytes)"
}

data class TransportResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

fun interface Transport {
    suspend fun send(request: TransportRequest): TransportResponse
}

/**
 * Plain HttpURLConnection transport. The base URL must be https: the app talks to the merchant's proxy and carries shopper
 * prescriptions and the app credential. [allowInsecureHttp] is a debug opt-in (an emulator reaching http://10.0.2.2) and must
 * never be set in a release build.
 */
private fun requireTransportScheme(baseUrl: String, allowInsecureHttp: Boolean): String {
    val scheme = runCatching { URI(baseUrl).scheme }.getOrNull()?.lowercase()
    if (scheme == "https" || (allowInsecureHttp && scheme == "http")) return baseUrl
    throw InvalidRequestException(
        if (allowInsecureHttp) "baseUrl must start with http:// or https://"
        else "baseUrl must use https (allowInsecureHttp = true is for local debugging only)",
    )
}

class HttpUrlConnectionTransport(
    baseUrl: String,
    allowInsecureHttp: Boolean = false,
    private val headerProvider: () -> Map<String, String> = { emptyMap() },
) : Transport {
    private val baseUrl = requireTransportScheme(baseUrl, allowInsecureHttp).trimEnd('/')

    /**
     * HttpURLConnection reads block and ignore thread interrupts, so cancelling the caller closes the connection
     * (disconnect() is the documented way to abort a call from another thread). Without that, a cancelled coroutine would sit
     * out the full 15 second read timeout. A cancelled call surfaces as CancellationException, never as NetworkException.
     */
    override suspend fun send(request: TransportRequest): TransportResponse = coroutineScope {
        val connection = (URL(baseUrl + request.path).openConnection() as HttpURLConnection)
        val call = async(Dispatchers.IO) {
            try {
                execute(connection, request)
            } catch (error: NetworkException) {
                // The read failed because the caller cancelled and we closed the socket: that is a cancellation, not a network fault.
                if (!isActive) throw CancellationException("Request cancelled").also { it.initCause(error) }
                throw error
            }
        }
        try {
            call.await()
        } catch (error: CancellationException) {
            connection.disconnect()
            throw error
        }
    }

    private fun execute(connection: HttpURLConnection, request: TransportRequest): TransportResponse {
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            (headerProvider() + request.headers).forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Response body exceeds $MAX_RESPONSE_BYTES bytes")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            val headers = connection.headerFields.entries.mapNotNull { (name, values) ->
                name?.let { it to values.joinToString(",") }
            }.toMap()
            return TransportResponse(status, headers, body)
        } catch (error: IOException) {
            throw NetworkException("Network request failed", error)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
    }
}

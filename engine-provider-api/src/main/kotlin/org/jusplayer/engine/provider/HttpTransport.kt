package org.jusplayer.engine.provider

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal blocking HTTP transport used by provider modules (LRCLIB, Cover Art
 * Archive, MusicBrainz). Providers may inject a fake implementation in tests via
 * their `internal` constructors.
 *
 * Redirects are followed automatically ([HttpURLConnection] default-like), which
 * matters for Cover Art Archive's `307` responses.
 */
interface HttpTransport {
    data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>> = emptyMap(),
    )

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response

    /**
     * Sends a form-urlencoded POST request. Only providers that need to write to
     * an API (e.g. Last.fm scrobbling) use this; the default implementation
     * throws so GET-only transports and test fakes are unaffected.
     */
    fun post(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Response = throw UnsupportedOperationException("POST not supported by this transport")
}

/**
 * Default [HttpTransport] backed by [HttpURLConnection].
 */
class JdkHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 15_000,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            val status = connection.responseCode
            val body = readBody(connection, status in 200..299)
            return HttpTransport.Response(
                status = status,
                body = body,
                headers = connection.headerFields ?: emptyMap(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, isSuccess: Boolean): String {
        val stream = if (isSuccess) connection.inputStream else connection.errorStream
            ?: return ""
        return stream.bufferedReader().use { it.readText() }
    }

    override fun post(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): HttpTransport.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            val encoded = form.entries.joinToString("&") { (name, value) ->
                "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val body = readBody(connection, status in 200..299)
            return HttpTransport.Response(
                status = status,
                body = body,
                headers = connection.headerFields ?: emptyMap(),
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "JusPlayerEngine (https://github.com/shubh72010/JusPlayer-Engine)"
    }
}
package org.jusplayer.engine.provider

import java.net.HttpURLConnection
import java.net.URL

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

    companion object {
        private const val DEFAULT_USER_AGENT =
            "JusPlayerEngine (https://github.com/shubh72010/JusPlayer-Engine)"
    }
}
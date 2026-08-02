package org.jusplayer.engine.provider.newpipe

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A plain JVM [Downloader] backed by [HttpURLConnection].
 *
 * NewPipeExtractor is Android-agnostic in its core and only needs a [Downloader]
 * implementation to perform HTTP requests. This keeps the provider usable from
 * any JVM application without Android dependencies.
 */
class JvmDownloader(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 15_000,
) : Downloader() {

    override fun execute(request: Request): Response {
        val url = request.url()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.httpMethod()
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true

            request.headers().forEach { (name, values) ->
                values.forEach { value -> connection.setRequestProperty(name, value) }
            }

            // NewPipeExtractor's request headers never include a User-Agent, relying
            // on the downloader to supply one. Without a browser-like UA, YouTube's
            // player endpoint rejects the request ("The page needs to be reloaded").
            if (request.headers().keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            }

            request.dataToSend()?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }

            val responseCode = connection.responseCode
            val isSuccess = responseCode in 200..299
            val body = readBody(connection, isSuccess)
            val headers = connection.headerFields

            return Response(
                responseCode,
                connection.responseMessage ?: "",
                headers,
                body,
                connection.url.toExternalForm(),
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    private fun readBody(connection: HttpURLConnection, isSuccess: Boolean): String {
        val stream = if (isSuccess) connection.inputStream else connection.errorStream
            ?: return ""

        val decodingStream = if (isGzip(connection)) GZIPInputStream(stream) else stream
        return decodingStream.bufferedReader().use { it.readText() }
    }

    private fun isGzip(connection: HttpURLConnection): Boolean {
        return connection.getHeaderField("Content-Encoding")
            ?.contains("gzip", ignoreCase = true) == true
    }
}

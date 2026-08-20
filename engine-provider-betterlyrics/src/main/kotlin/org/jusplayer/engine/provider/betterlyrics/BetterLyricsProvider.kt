package org.jusplayer.engine.provider.betterlyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.LyricsLine
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import org.jusplayer.engine.provider.LyricsProvider
import org.jusplayer.engine.provider.ProviderException
import java.io.IOException
import java.net.URLEncoder

/**
 * A [LyricsProvider] backed by the BetterLyrics API (https://lyrics-api.boidu.dev).
 *
 * Returns Apple Music TTML lyrics with word-level timing, matched from the
 * metadata on a [Song]: track name, artist name, optional album and duration in
 * seconds. The TTML document is parsed with [TTMLParser] and also kept in raw
 * XML form as [Lyrics.text]; responses that are not TTML (no XML marker and no
 * JSON `ttml`/`lyrics` envelope) are returned as plain text.
 *
 * The primary `getLyrics` endpoint is tried first; when it comes back empty the
 * `kugou/getLyrics` fallback endpoint is consulted. 404 surfaces as
 * [ProviderException.NotFound], 429 as [ProviderException.RateLimited].
 */
class BetterLyricsProvider(
    private val transport: HttpTransport,
) : LyricsProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "BetterLyrics"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val artist = song.artists.firstOrNull()?.name.orEmpty()
        if (song.title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        val payload = runExtraction("BetterLyrics lyrics for \"${song.title}\"") {
            fetchTTML(
                title = song.title,
                artist = artist,
                album = song.album?.title,
                durationSeconds = song.duration,
            )
        } ?: return@withContext null

        Lyrics(
            text = payload.raw,
            source = name,
            synced = payload.synced,
            lines = payload.lines,
        )
    }

    private fun fetchTTML(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Long,
    ): Payload? {
        ENDPOINTS.forEachIndexed { index, endpoint ->
            val url = buildUrl(endpoint, title, artist, album, durationSeconds)
            val response = transport.get(url)

            when (response.status) {
                in 200..299 -> {
                    parsePayload(response.body)?.let { return it }
                }

                404 -> if (index == ENDPOINTS.lastIndex) {
                    throw ProviderException.NotFound("No BetterLyrics lyrics for \"$title\"")
                }

                429 -> throw ProviderException.RateLimited("BetterLyrics rate limit exceeded")

                else -> throw ProviderException.ExtractionFailed(
                    "BetterLyrics returned unexpected status ${response.status}",
                )
            }
        }
        return null
    }

    private fun parsePayload(body: String): Payload? {
        val trimmed = body.trim()
        val ttml =
            if (trimmed.startsWith("<")) {
                trimmed
            } else {
                decodeEnvelope(trimmed)
            }
        if (ttml.isBlank()) return null

        return if (ttml.startsWith("<")) {
            Payload(
                raw = ttml,
                synced = true,
                lines = TTMLParser.toEngineLines(TTMLParser.parseTTML(ttml)),
            )
        } else {
            Payload(raw = ttml, synced = false, lines = emptyList())
        }
    }

    /**
     * A 2xx body is either TTML XML, the `{"ttml"|"lyrics": "..."}` JSON envelope,
     * or plain text. Only an envelope that decodes cleanly is unwrapped; anything
     * else is treated as plain-text lyrics rather than a parse failure.
     */
    private fun decodeEnvelope(text: String): String {
        if (!text.startsWith("{")) return text
        return try {
            json.decodeFromString<TtmlResponse>(text).content() ?: ""
        } catch (e: kotlinx.serialization.SerializationException) {
            text
        }
    }

    private fun buildUrl(
        endpoint: String,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Long,
    ): String = buildString {
        append(BASE_URL)
        append(endpoint)
        append("?s=").append(URLEncoder.encode(title, "UTF-8"))
        append("&a=").append(URLEncoder.encode(artist, "UTF-8"))
        if (!album.isNullOrBlank()) {
            append("&al=").append(URLEncoder.encode(album, "UTF-8"))
        }
        if (durationSeconds > 0) {
            append("&d=").append(durationSeconds)
        }
    }

    private inline fun <T> runExtraction(operation: String, block: () -> T): T {
        return try {
            block()
        } catch (e: IOException) {
            throw ProviderException.Network(operation, e)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ProviderException.ExtractionFailed(operation, e)
        } catch (e: RuntimeException) {
            throw ProviderException.ExtractionFailed(operation, e)
        }
    }

    private data class Payload(
        val raw: String,
        val synced: Boolean,
        val lines: List<LyricsLine>,
    )

    companion object {
        const val BASE_URL = "https://lyrics-api.boidu.dev/"
        private val ENDPOINTS = listOf("getLyrics", "kugou/getLyrics")
    }
}

@Serializable
internal data class TtmlResponse(
    val ttml: String? = null,
    val lyrics: String? = null,
    val provider: String? = null,
) {
    fun content(): String? = ttml?.takeIf { it.isNotBlank() } ?: lyrics
}

package org.jusplayer.engine.provider.lrclib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import org.jusplayer.engine.provider.LyricsProvider
import org.jusplayer.engine.provider.ProviderException
import java.io.IOException
import java.net.URLEncoder

/**
 * A [LyricsProvider] backed by the LRCLIB API (https://lrclib.net).
 *
 * Lyrics are matched from the metadata on a [Song]: track name, artist name,
 * album name (recommended) and duration in seconds. A client User-Agent is set by
 * the default [JdkHttpTransport], as LRCLIB requires.
 *
 * Per the LRCLIB requirements, requests are throttled sequentially with a short
 * delay, and 429 responses are surfaced as [ProviderException.RateLimited]
 * carrying the server's Retry-After value. A client User-Agent is set by the
 * default [JdkHttpTransport].
 */
class LRCLIBProvider(
    private val transport: HttpTransport,
    private val throttleMillis: Long = 300L,
) : LyricsProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "LRCLIB"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val artist = song.artists.firstOrNull()?.name.orEmpty()
        if (song.title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        val url = buildString {
            append(BASE_URL)
            append("/api/get")
            append("?track_name=").append(URLEncoder.encode(song.title, "UTF-8"))
            append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
            song.album?.title?.takeIf { it.isNotBlank() }?.let {
                append("&album_name=").append(URLEncoder.encode(it, "UTF-8"))
            }
            append("&duration=").append(song.duration.coerceIn(1, 3600))
        }

        delay(throttleMillis)

        val response = runExtraction("Lyrics for \"${song.title}\"") {
            transport.get(url, headers = mapOf("Accept" to "application/json"))
        }

        when (response.status) {
            in 200..299 -> parseLyrics(song, response.body)
            404 -> throw ProviderException.NotFound("No LRCLIB lyrics for \"${song.title}\"")
            429 -> {
                val retryAfter = response.headers["retry-after"]?.firstOrNull()
                    ?: response.headers["Retry-After"]?.firstOrNull()
                throw ProviderException.RateLimited(
                    "LRCLIB rate limit exceeded" + (retryAfter?.let { "; retry after $it seconds" } ?: ""),
                )
            }
            else -> throw ProviderException.ExtractionFailed(
                "LRCLIB returned unexpected status ${response.status}",
            )
        }
    }

    private fun parseLyrics(song: Song, body: String): Lyrics? {
        val parsed = json.decodeFromString<LrcLibResponse>(body)
        if (parsed.instrumental) return null

        val synced = parsed.syncedLyrics.orEmpty()
        val plain = parsed.plainLyrics.orEmpty()
        val text = synced.ifBlank { plain }.trim()
        if (text.isEmpty()) return null

        return Lyrics(
            text = text,
            source = "LRCLIB",
            synced = synced.isNotBlank(),
        )
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

    companion object {
        const val BASE_URL = "https://lrclib.net"
    }
}

@Serializable
internal data class LrcLibResponse(
    val id: Long? = null,
    val name: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
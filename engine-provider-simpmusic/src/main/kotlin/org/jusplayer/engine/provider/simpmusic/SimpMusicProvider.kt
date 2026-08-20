package org.jusplayer.engine.provider.simpmusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
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
import kotlin.math.abs

/**
 * A [LyricsProvider] backed by the crowd-sourced SimpMusic lyrics API
 * (https://api-lyrics.simpmusic.org), which indexes lyrics by YouTube video ID.
 *
 * SimpMusic songs carry a streaming-service id, so `song.id` is used as the
 * video ID. When the API returns several candidates for a video, the candidate
 * whose duration is closest to the song's (within a 5-second tolerance) is
 * preferred; a single candidate is used as-is.
 */
class SimpMusicProvider(
    private val transport: HttpTransport,
) : LyricsProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "SimpMusic"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        if (song.id.isBlank()) return@withContext null

        val url = BASE_URL + URLEncoder.encode(song.id, "UTF-8")

        val response = runExtraction("Lyrics for \"${song.title}\"") {
            transport.get(
                url,
                headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT),
            )
        }

        when (response.status) {
            in 200..299 -> parseLyrics(song, response.body)
            404 -> throw ProviderException.NotFound("No SimpMusic lyrics for \"${song.title}\"")
            429 -> throw ProviderException.RateLimited("SimpMusic rate limit exceeded")
            else -> throw ProviderException.ExtractionFailed(
                "SimpMusic returned unexpected status ${response.status}",
            )
        }
    }

    private fun parseLyrics(song: Song, body: String): Lyrics? {
        val parsed = json.decodeFromString<SimpMusicApiResponse>(body)
        val candidate = selectBestTrack(parsed.data, song.duration) ?: return null

        val synced = candidate.syncedLyrics.orEmpty().trim()
        val plain = candidate.plainLyrics.orEmpty().trim()
        val text = synced.ifBlank { plain }
        if (text.isEmpty()) return null

        return Lyrics(
            text = text,
            source = "SimpMusic",
            synced = synced.isNotEmpty(),
            lines = if (synced.isNotEmpty()) parseLrcLines(synced) else emptyList(),
        )
    }

    private fun selectBestTrack(
        tracks: List<SimpMusicLyricsData>,
        duration: Long,
    ): SimpMusicLyricsData? {
        val candidates = tracks.filter {
            !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank()
        }
        if (candidates.isEmpty()) return null
        if (duration <= 0 || candidates.size == 1) return candidates.first()

        val closest = candidates.minByOrNull { abs((it.duration ?: 0) - duration) } ?: return null
        return closest.takeIf { abs((it.duration ?: 0) - duration) <= DURATION_TOLERANCE_SECONDS }
    }

    private fun parseLrcLines(lrc: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        for (raw in lrc.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val starts = mutableListOf<Long>()
            var cursor = line
            while (true) {
                val match = TIMESTAMP_REGEX.find(cursor) ?: break
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fractionMs = match.groupValues[3].padEnd(3, '0').take(3).toLong()
                starts += minutes * 60_000L + seconds * 1_000L + fractionMs
                cursor = cursor.substring(match.range.last + 1)
            }

            val content = cursor.trim()
            if (content.isEmpty()) continue
            for (start in starts) {
                lines += LyricsLine(text = content, start = start)
            }
        }
        return lines
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
        const val BASE_URL = "https://api-lyrics.simpmusic.org/v1/"
        private const val USER_AGENT = "SimpMusicLyrics/1.0"
        private const val DURATION_TOLERANCE_SECONDS = 5L
        private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?]")
    }
}

@Serializable
internal data class SimpMusicApiResponse(
    val type: String? = null,
    val data: List<SimpMusicLyricsData> = emptyList(),
) {
    val success: Boolean
        get() = type == "success"
}

@Serializable
internal data class SimpMusicLyricsData(
    val id: String? = null,
    val videoId: String? = null,
    @SerialName("songTitle")
    val title: String? = null,
    @SerialName("artistName")
    val artist: String? = null,
    @SerialName("albumName")
    val album: String? = null,
    @SerialName("durationSeconds")
    val duration: Int? = null,
    val syncedLyrics: String? = null,
    @SerialName("plainLyric")
    val plainLyrics: String? = null,
    val richSyncLyrics: String? = null,
    val vote: Int? = null,
)

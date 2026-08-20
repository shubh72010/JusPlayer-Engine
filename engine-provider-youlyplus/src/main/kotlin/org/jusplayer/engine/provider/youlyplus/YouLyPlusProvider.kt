package org.jusplayer.engine.provider.youlyplus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.LyricsLine
import org.jusplayer.engine.model.LyricsWord
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import org.jusplayer.engine.provider.LyricsProvider
import org.jusplayer.engine.provider.ProviderException
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * A [LyricsProvider] backed by the YouLyPlus mirrors, ported from the
 * ArchiveTune/JusPlayer app.
 *
 * Queries each mirror (in order) for TTML lyrics first, then timed/plain LRC;
 * the first mirror that returns a usable result wins. Timed LRC lines are
 * exposed on [Lyrics.lines] with per-word timing when the server provides a
 * syllable breakdown.
 */
class YouLyPlusProvider(
    private val transport: HttpTransport,
) : LyricsProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "YouLyPlus"

    private val json = Json { isLenient = true; ignoreUnknownKeys = true; coerceInputValues = true }

    override suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val title = song.title.trim()
        val artist = song.artists.firstOrNull()?.name.orEmpty().trim()
        val album = song.album?.title?.trim().orEmpty()
        if (title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        val duration = song.duration.toInt()

        val ttml = fetchFromMirrors(TTML_PATH, title, artist, album, duration) { body ->
            val trimmed = body.trim()
            when {
                trimmed.startsWith("<") -> trimmed
                else -> runCatching { json.decodeFromString<YouLyPlusTtmlResponse>(body).ttml?.trim() }.getOrNull()
            }?.takeIf { it.isNotBlank() && it.startsWith("<") }
        }

        if (ttml != null) {
            return@withContext Lyrics(
                text = ttml,
                source = "YouLyPlus",
                synced = true,
            )
        }

        val lrc = fetchFromMirrors(LYRICS_PATH, title, artist, album, duration) { body ->
            runCatching { json.decodeFromString<YouLyPlusLyricsResponse>(body) }
                .getOrNull()
                ?.toLyrics()
        }

        if (lrc != null) {
            return@withContext Lyrics(
                text = lrc.text,
                source = "YouLyPlus",
                synced = lrc.synced,
                lines = lrc.lines,
            )
        }

        null
    }

    private fun <T> fetchFromMirrors(
        path: String,
        title: String,
        artist: String,
        album: String,
        duration: Int,
        decode: (String) -> T?,
    ): T? {
        for (baseUrl in baseUrls) {
            val params = buildList {
                add("title" to title)
                add("artist" to artist)
                if (album.isNotBlank()) add("album" to album)
                if (duration > 0) add("duration" to duration.toString())
            }
            val url = "$baseUrl$path?${params.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }}"

            val response = runExtraction("YouLyPlus lyrics from $baseUrl") {
                transport.get(url, headers = mapOf("Accept" to "application/json", "User-Agent" to "JusPlayer"))
            }

            if (response.status == 429) {
                throw ProviderException.RateLimited("YouLyPlus rate limit exceeded")
            }
            if (response.status !in 200..299) continue

            val decoded = runCatching { decode(response.body) }.getOrNull()
            if (decoded != null) return decoded
        }
        return null
    }

    private fun YouLyPlusLyricsResponse.toLyrics(): ParsedLyrics {
        if (lyrics.isEmpty()) return ParsedLyrics("", synced = false, lines = emptyList())

        val timedLines = lyrics.filter { it.time != null }
        if (timedLines.isNotEmpty()) {
            val lines = timedLines.mapNotNull { line -> line.toLyricsLine() }
            val text =
                timedLines.joinToString("\n") { line ->
                    buildString {
                        append(formatLrcTimestamp(line.time ?: 0L, bracketed = true))
                        val syllables = line.syllabus.orEmpty().filter { !it.text.isNullOrBlank() && it.time != null }
                        if (type.equals("Word", ignoreCase = true) && syllables.isNotEmpty()) {
                            syllables.forEach { syllable ->
                                append(formatLrcTimestamp(syllable.time ?: 0L, bracketed = false))
                                append(syllable.text.orEmpty())
                            }
                        } else {
                            append(line.text.orEmpty())
                        }
                    }
                }
            return ParsedLyrics(text, synced = true, lines = lines)
        }

        val plain = lyrics.mapNotNull { it.text }.map(String::trim).filter(String::isNotBlank).joinToString("\n")
        return ParsedLyrics(plain, synced = false, lines = emptyList())
    }

    private fun YouLyPlusLine.toLyricsLine(): LyricsLine? {
        val start = time ?: return null
        val end = start + (duration ?: 0L)
        val words =
            syllabus.orEmpty()
                .filter { !it.text.isNullOrBlank() }
                .map { syllable ->
                    val wStart = syllable.time ?: start
                    LyricsWord(
                        text = syllable.text.orEmpty(),
                        start = wStart,
                        end = wStart + (syllable.duration ?: 0L),
                    )
                }
        return LyricsLine(
            text = text.orEmpty(),
            start = start,
            end = if (duration != null) end else null,
            words = words,
        )
    }

    private data class ParsedLyrics(
        val text: String,
        val synced: Boolean,
        val lines: List<LyricsLine>,
    )

    private fun formatLrcTimestamp(
        timeMs: Long,
        bracketed: Boolean,
    ): String {
        val safeTime = timeMs.coerceAtLeast(0L)
        val minutes = safeTime / 60000L
        val seconds = (safeTime % 60000L) / 1000L
        val millis = safeTime % 1000L
        val timestamp = String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
        return if (bracketed) "[$timestamp]" else "<$timestamp>"
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
        private const val TTML_PATH = "v1/ttml/get"
        private const val LYRICS_PATH = "v2/lyrics/get"

        private val baseUrls =
            listOf(
                "https://lyricsplus.binimum.org/",
                "https://lyricsplus.prjktla.my.id/",
                "https://lyricsplus.prjktla.workers.dev/",
                "https://lyricsplus.atomix.one/",
                "https://lyricsplus-seven.vercel.app/",
            )
    }
}

@Serializable
internal data class YouLyPlusTtmlResponse(
    val ttml: String? = null,
)

@Serializable
internal data class YouLyPlusLyricsResponse(
    val type: String? = null,
    val lyrics: List<YouLyPlusLine> = emptyList(),
)

@Serializable
internal data class YouLyPlusLine(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    val syllabus: List<YouLyPlusSyllable>? = null,
)

@Serializable
internal data class YouLyPlusSyllable(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    val isBackground: Boolean = false,
)
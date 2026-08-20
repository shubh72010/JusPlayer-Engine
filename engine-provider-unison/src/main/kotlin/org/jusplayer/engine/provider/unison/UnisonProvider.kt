package org.jusplayer.engine.provider.unison

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * A [LyricsProvider] backed by the Unison API (https://unison.boidu.dev).
 *
 * Lookup starts with a fast path keyed on the YouTube video id of a [Song]
 * (`GET /lyrics?v=...`), then falls back to an exact metadata match
 * (`GET /lyrics?song=...&artist=...`), then to a ranked metadata search
 * (`GET /lyrics/search`). Search summaries are materialized into full entries
 * (from their embedded lyrics or `GET /lyrics/{id}`), capped at MAX_SEARCH_RESULTS.
 * A 404 on the fast/metadata paths falls through to the next step; a 404 from
 * the final search surfaces as [ProviderException.NotFound].
 *
 * Unison returns lyrics in several formats; [Lyrics.synced] reflects LRC/TTML/
 * karaoke formats (or timestamp-bearing text), and LRC bodies are parsed into
 * timed [LyricsLine]s.
 */
class UnisonProvider(
    private val transport: HttpTransport,
) : LyricsProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "Unison"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val artist = song.artists.firstOrNull()?.name.orEmpty()
        if (song.title.isBlank() || artist.isBlank()) return@withContext null

        val byVideo = song.id.takeIf { isYouTubeId(it) }?.let { fetchByVideoId(it) }
        if (byVideo != null) return@withContext byVideo.toLyrics()

        val byMetadata = fetchByMetadata(song, artist)
        if (byMetadata != null) return@withContext byMetadata.toLyrics()

        searchEntries(song, artist).firstOrNull()?.toLyrics()
    }

    private fun fetchByVideoId(videoId: String): UnisonEntry? {
        val url = "$BASE_URL/lyrics?v=${URLEncoder.encode(videoId, "UTF-8")}"
        return try {
            parseEntryResponse(getBody(url, "Unison lyrics for video $videoId"))
        } catch (e: ProviderException.NotFound) {
            null
        }
    }

    private fun fetchByMetadata(song: Song, artist: String): UnisonEntry? {
        val url = buildMetadataUrl("$BASE_URL/lyrics", song, artist)
        return try {
            parseEntryResponse(getBody(url, "Unison lyrics for \"${song.title}\""))
        } catch (e: ProviderException.NotFound) {
            null
        }
    }

    private fun fetchById(id: Long): UnisonEntry? {
        val url = "$BASE_URL/lyrics/$id"
        return try {
            parseEntryResponse(getBody(url, "Unison lyrics $id"))
        } catch (e: ProviderException.NotFound) {
            null
        }
    }

    private fun searchEntries(song: Song, artist: String): List<UnisonEntry> {
        val url = buildMetadataUrl("$BASE_URL/lyrics/search", song, artist)
        val summaries = parseSearchResponse(getBody(url, "Unison search for \"${song.title}\""))
        val entries = mutableListOf<UnisonEntry>()
        for (summary in summaries) {
            if (entries.size >= MAX_SEARCH_RESULTS) break
            val entry = summary.toEntry() ?: fetchById(summary.id)
            if (entry != null) entries += entry
        }
        return entries
    }

    private fun buildMetadataUrl(base: String, song: Song, artist: String): String = buildString {
        append(base)
        append("?song=").append(URLEncoder.encode(song.title, "UTF-8"))
        append("&artist=").append(URLEncoder.encode(artist, "UTF-8"))
        song.album?.title?.takeIf { it.isNotBlank() }?.let {
            append("&album=").append(URLEncoder.encode(it, "UTF-8"))
        }
        if (song.duration > 0) append("&duration=").append(song.duration)
    }

    private fun getBody(url: String, operation: String): String {
        val response = runExtraction(operation) {
            transport.get(url, headers = mapOf("Accept" to "application/json"))
        }
        return when (response.status) {
            in 200..299 -> response.body
            404 -> throw ProviderException.NotFound("$operation: no Unison lyrics")
            429 -> throw ProviderException.RateLimited("Unison rate limit exceeded")
            else -> throw ProviderException.ExtractionFailed("Unison returned unexpected status ${response.status}")
        }
    }

    private fun parseEntryResponse(body: String): UnisonEntry? = runExtraction("Unison response parse") {
        val parsed = json.decodeFromString<UnisonResponse>(body)
        parsed.takeIf { it.success }?.data?.takeIf { it.lyrics.isNotBlank() }
    }

    private fun parseSearchResponse(body: String): List<UnisonSearchEntry> =
        runExtraction("Unison search response parse") {
            val parsed = json.decodeFromString<UnisonSearchResponse>(body)
            parsed.takeIf { it.success }?.data.orEmpty()
        }

    private fun isYouTubeId(id: String): Boolean =
        id.length == 11 && id.all { it in YOUTUBE_ID_CHARS }

    private fun UnisonEntry.toLyrics(): Lyrics {
        val text = lyrics.trim()
        val synced = isSynced()
        val lrcLike = format.equals("lrc", ignoreCase = true) || LRC_TIMESTAMP.containsMatchIn(text)
        val lines = if (synced && lrcLike) parseLrc(text) else emptyList()
        return Lyrics(text = text, source = "Unison", synced = synced, lines = lines)
    }

    private fun UnisonEntry.isSynced(): Boolean {
        if (format.lowercase() in SYNCED_FORMATS) return true
        if (LRC_TIMESTAMP.containsMatchIn(lyrics)) return true
        if (lyrics.contains("<tt", ignoreCase = true)) return true
        if (INLINE_TIMESTAMP.containsMatchIn(lyrics)) return true
        return false
    }

    private fun parseLrc(text: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        for (raw in text.lineSequence()) {
            val matches = LRC_TIMESTAMP.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val content = raw.substring(matches.last().range.last + 1).trim()
            if (content.isEmpty()) continue
            for (start in matches.map(::lrcTimestampMillis)) {
                lines += LyricsLine(text = content, start = start)
            }
        }
        lines.sortBy { it.start }
        for (i in lines.indices) {
            if (i + 1 < lines.size) {
                lines[i] = lines[i].copy(end = lines[i + 1].start)
            }
        }
        return lines
    }

    private fun lrcTimestampMillis(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[4].takeIf { it.isNotEmpty() }?.let(::fractionMillis) ?: 0L
        return minutes * 60_000 + seconds * 1_000 + fraction
    }

    private fun fractionMillis(fraction: String): Long = when (fraction.length) {
        1 -> fraction.toLong() * 100
        2 -> fraction.toLong() * 10
        else -> fraction.toLong()
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
        const val BASE_URL = "https://unison.boidu.dev"
        const val MAX_SEARCH_RESULTS = 5

        private val SYNCED_FORMATS = setOf("lrc", "ttml", "karaoke")
        private const val YOUTUBE_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        private val LRC_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})([.:](\d{1,3}))?\]""")
        private val INLINE_TIMESTAMP = Regex("""[<{](\d{1,3}):(\d{2})([.:](\d{1,3}))?[>}]""")
    }
}
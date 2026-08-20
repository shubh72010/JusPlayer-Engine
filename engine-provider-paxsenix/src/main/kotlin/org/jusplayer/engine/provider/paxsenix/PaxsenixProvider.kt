package org.jusplayer.engine.provider.paxsenix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.LyricsLine
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import org.jusplayer.engine.provider.LyricsProvider
import org.jusplayer.engine.provider.ProviderException
import org.jusplayer.engine.provider.paxsenix.models.AppleMusicLyricsResponse
import org.jusplayer.engine.provider.paxsenix.models.NeteaseSearchResponse
import org.jusplayer.engine.provider.paxsenix.models.PaxsenixSearchItem
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/**
 * A [LyricsProvider] backed by the Paxsenix Lyrically proxy
 * (https://lyrics.paxsenix.org/), which aggregates Apple Music, NetEase, Spotify,
 * YouTube and Musixmatch.
 *
 * Lyrics are matched from a [Song]'s title, artist and duration. The auto chain
 * tries Apple Music, NetEase, Spotify then Musixmatch and returns the first hit; a
 * backend that 404s is treated as "no lyrics here" and the chain falls through.
 * The original client resolved the Apple Music catalog id via a direct AMP search
 * (with a hardcoded JWT); that search is deliberately dropped, so the chain passes
 * the [Song]'s own id to the proxy's Apple endpoint — callers whose ids are Apple
 * Music catalog ids get hits, everyone else falls through to NetEase.
 * [getAppleMusicLyrics] is exposed separately for callers who hold a real catalog id.
 */
class PaxsenixProvider internal constructor(
    private val transport: HttpTransport,
) : LyricsProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "Paxsenix"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    override suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val artist = song.artists.firstOrNull()?.name.orEmpty()
        if (song.title.isBlank() || artist.isBlank()) return@withContext null

        val text = getAppleMusicLyrics(song.id)
            ?: neteaseLyrics(song.title, artist, song.duration)
            ?: spotifyLyrics(song.title, artist, song.duration)
            ?: musixmatchLyrics(song.title, artist, song.duration)
            ?: return@withContext null

        buildLyrics(text)
    }

    /**
     * Fetches Apple Music lyrics for a known Apple Music catalog track id. With
     * `ttml=true` the proxy returns raw TTML; when that is unavailable the legacy
     * timed JSON is converted to LRC ([convertAppleMusicToLrc]).
     */
    suspend fun getAppleMusicLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        notFoundAsNull {
            runExtraction("Apple Music lyrics for id $songId") {
                val encodedId = URLEncoder.encode(songId, "UTF-8")
                val ttml =
                    transport.get("$BASE_URL/apple-music/lyrics?id=$encodedId&ttml=true", headers = JSON_HEADERS)
                if (ttml.status == 429) throw ProviderException.RateLimited("Paxsenix rate limit exceeded (Apple Music lyrics)")
                if (ttml.status in 200..299) {
                    val raw = ttml.body.trim()
                    if (raw.startsWith("<tt") || raw.startsWith("<?xml")) return@runExtraction raw

                    val wrapped = runCatching { json.decodeFromString<JsonObject>(raw) }.getOrNull()
                    val content = wrapped?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (content != null && (content.contains("<tt") || content.contains("<?xml"))) return@runExtraction content
                }

                val lyrics =
                    transport.get("$BASE_URL/apple-music/lyrics?id=$encodedId", headers = JSON_HEADERS)
                checkStatus(lyrics, "Apple Music lyrics")
                val response = json.decodeFromString<AppleMusicLyricsResponse>(lyrics.body)
                response.content.takeIf { it.isNotEmpty() }?.let { convertAppleMusicToLrc(response) }
            }
        }
    }

    /** Fetches lyrics via the YouTube backend (search by title/artist, then by id). */
    suspend fun getYouTubeLyrics(title: String, artist: String, durationSeconds: Long): String? =
        withContext(Dispatchers.IO) {
            notFoundAsNull {
                runExtraction("YouTube lyrics for \"$title\"") {
                    val id =
                        searchBestId("youtube", title, artist, resolveDurationMs(durationSeconds))
                            ?: return@runExtraction null
                    val lyrics =
                        transport.get("$BASE_URL/youtube/lyrics?id=" + URLEncoder.encode(id, "UTF-8"), headers = JSON_HEADERS)
                    checkStatus(lyrics, "YouTube lyrics")
                    cleanJsonLyrics(lyrics.body)
                }
            }
        }

    private suspend fun neteaseLyrics(title: String, artist: String, durationSeconds: Long): String? =
        notFoundAsNull { neteaseLyricsOrThrow(title, artist, durationSeconds) }

    private suspend fun neteaseLyricsOrThrow(title: String, artist: String, durationSeconds: Long): String? =
        runExtraction("NetEase lyrics for \"$title\"") {
            val durationMs = resolveDurationMs(durationSeconds)
            val search =
                transport.get("$BASE_URL/netease/search?q=" + URLEncoder.encode("$title $artist", "UTF-8"), headers = JSON_HEADERS)
            checkStatus(search, "NetEase search")

            val songs = json.decodeFromString<NeteaseSearchResponse>(search.body).result?.songs ?: emptyList()
            val best =
                (if (durationMs > 0) songs.minByOrNull { abs(it.duration.toLong() - durationMs) } else songs.firstOrNull())
                    ?: return@runExtraction null
            if (durationMs > 0 && abs(best.duration.toLong() - durationMs) >= 10_000) return@runExtraction null

            val lyrics =
                transport.get("$BASE_URL/netease/lyrics?id=${best.id}&word=true", headers = JSON_HEADERS)
            checkStatus(lyrics, "NetEase lyrics")

            val data = json.decodeFromString<JsonObject>(lyrics.body)
            val klyric = (data["klyric"] as? JsonObject)?.get("lyric")
            val klyricText = (klyric as? JsonPrimitive)?.contentOrNull
            if (!klyricText.isNullOrBlank()) return@runExtraction klyricText.trim()

            val lrc = (data["lrc"] as? JsonObject)?.get("lyric")
            (lrc as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }

    private suspend fun spotifyLyrics(title: String, artist: String, durationSeconds: Long): String? =
        notFoundAsNull { spotifyLyricsOrThrow(title, artist, durationSeconds) }

    private suspend fun spotifyLyricsOrThrow(title: String, artist: String, durationSeconds: Long): String? =
        runExtraction("Spotify lyrics for \"$title\"") {
            val id =
                searchBestId("spotify", title, artist, resolveDurationMs(durationSeconds))
                    ?: return@runExtraction null
            val lyrics =
                transport.get("$BASE_URL/spotify/lyrics?id=" + URLEncoder.encode(id, "UTF-8"), headers = JSON_HEADERS)
            checkStatus(lyrics, "Spotify lyrics")
            cleanJsonLyrics(lyrics.body)
        }

    private suspend fun musixmatchLyrics(title: String, artist: String, durationSeconds: Long): String? =
        notFoundAsNull { musixmatchLyricsOrThrow(title, artist, durationSeconds) }

    private suspend fun musixmatchLyricsOrThrow(title: String, artist: String, durationSeconds: Long): String? =
        runExtraction("Musixmatch lyrics for \"$title\"") {
            val base =
                buildString {
                    append("$BASE_URL/musixmatch/lyrics?")
                    append("q=").append(URLEncoder.encode("$title $artist", "UTF-8"))
                    append("&t=").append(URLEncoder.encode(title, "UTF-8"))
                    append("&a=").append(URLEncoder.encode(artist, "UTF-8"))
                    append("&d=").append(durationSeconds)
                }

            val word = transport.get("$base&type=word", headers = JSON_HEADERS)
            when (word.status) {
                429 -> throw ProviderException.RateLimited("Paxsenix rate limit exceeded (Musixmatch lyrics)")
                in 200..299 -> cleanJsonLyrics(word.body)?.let { return@runExtraction it }
            }

            val plain = transport.get(base, headers = JSON_HEADERS)
            checkStatus(plain, "Musixmatch lyrics")
            cleanJsonLyrics(plain.body)
        }

    private suspend fun searchBestId(backend: String, title: String, artist: String, durationMs: Long): String? {
        val search =
            transport.get("$BASE_URL/$backend/search?q=" + URLEncoder.encode("$title $artist", "UTF-8"), headers = JSON_HEADERS)
        checkStatus(search, "$backend search")
        val items = json.decodeFromString<List<PaxsenixSearchItem>>(search.body)
        val best =
            (if (durationMs > 0) items.minByOrNull { abs(it.durationMs - durationMs) } else items.firstOrNull())
                ?: return null
        if (durationMs > 0 && abs(best.durationMs - durationMs) >= 10_000) return null
        return best.realId.takeIf { it.isNotBlank() }
    }

    private fun resolveDurationMs(duration: Long): Long = when {
        duration <= 0 -> 0L
        duration > 360_000 -> duration
        else -> duration * 1000L
    }

    private fun checkStatus(response: HttpTransport.Response, operation: String) {
        when (response.status) {
            in 200..299 -> Unit
            404 -> throw ProviderException.NotFound("No Paxsenix $operation result")
            429 -> throw ProviderException.RateLimited("Paxsenix rate limit exceeded ($operation)")
            else -> throw ProviderException.ExtractionFailed("Paxsenix $operation returned unexpected status ${response.status}")
        }
    }

    private fun buildLyrics(raw: String): Lyrics? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val ttml = text.startsWith("<tt") || text.startsWith("<?xml")
        val synced = ttml || timeTagRegex.containsMatchIn(text)
        val lines = if (synced && !ttml) parseLrcLines(text) else emptyList()
        return Lyrics(text = text, source = name, synced = synced, lines = lines)
    }

    private fun parseLrcLines(text: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tags = timeTagRegex.findAll(line).toList()
            if (tags.isEmpty()) continue
            val content = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                lines.add(LyricsLine(text = content, start = timestampMillis(tag.groupValues)))
            }
        }
        return lines
    }

    private fun timestampMillis(groups: List<String>): Long {
        val fraction = groups.getOrNull(3).orEmpty()
        val millis =
            when (fraction.length) {
                1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                3 -> fraction.toLongOrNull() ?: 0L
                else -> 0L
            }
        return (groups[1].toLongOrNull() ?: 0L) * 60_000 + (groups[2].toLongOrNull() ?: 0L) * 1_000 + millis
    }

    private fun cleanJsonLyrics(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val payload = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
        return extractLyrics(payload)
    }

    private fun extractLyrics(element: JsonElement): String? = when (element) {
        JsonNull -> null
        is JsonPrimitive -> {
            if (!element.isString) {
                null
            } else {
                val value = element.content.trim()
                if (value.isEmpty()) {
                    null
                } else {
                    val nested = runCatching { json.parseToJsonElement(value) }.getOrNull()
                    if (nested != null && nested !is JsonPrimitive) extractLyrics(nested) else value
                }
            }
        }
        is JsonArray -> element.mapNotNull(::extractLyrics).joinToString("\n").trim().takeIf { it.isNotEmpty() }
        is JsonObject -> {
            if (element.isErrorPayload()) {
                null
            } else {
                lyricsContentKeys.asSequence()
                    .mapNotNull { key -> element[key]?.let(::extractLyrics) }
                    .firstOrNull()
                    ?: (element["metadata"] as? JsonObject)?.let { metadata ->
                        lyricsContentKeys.asSequence()
                            .mapNotNull { key -> metadata[key]?.let(::extractLyrics) }
                            .firstOrNull()
                    }
                    ?: element["words"]?.let { words ->
                        if (words is JsonArray) {
                            words.mapNotNull(::extractLyrics).joinToString(" ").trim().takeIf { it.isNotEmpty() }
                        } else {
                            extractLyrics(words)
                        }
                    }
            }
        }
    }

    private fun JsonObject.isErrorPayload(): Boolean {
        if ((this["isError"] as? JsonPrimitive)?.booleanOrNull == true) return true
        return when (val error = this["error"]) {
            null, JsonNull -> false
            is JsonPrimitive -> error.booleanOrNull ?: error.content.trim().isNotEmpty()
            is JsonArray -> error.isNotEmpty()
            is JsonObject -> error.isNotEmpty()
        }
    }

    private fun convertAppleMusicToLrc(response: AppleMusicLyricsResponse): String =
        response.content.joinToString("\n") { line ->
            val minutes = line.timestamp / 1000 / 60
            val seconds = (line.timestamp / 1000) % 60
            val hundredths = (line.timestamp % 1000) / 10
            val time = String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths)
            val text = line.text.joinToString(" ") { it.text.trim() }
            "$time$text"
        }

    private inline fun notFoundAsNull(block: () -> String?): String? = try {
        block()
    } catch (e: ProviderException.NotFound) {
        null
    }

    private inline fun <T> runExtraction(operation: String, block: () -> T): T = try {
        block()
    } catch (e: ProviderException) {
        // The provider's own error taxonomy passes through unmapped.
        throw e
    } catch (e: IOException) {
        throw ProviderException.Network(operation, e)
    } catch (e: SerializationException) {
        throw ProviderException.ExtractionFailed(operation, e)
    } catch (e: RuntimeException) {
        throw ProviderException.ExtractionFailed(operation, e)
    }

    companion object {
        const val BASE_URL = "https://lyrics.paxsenix.org/"

        private val JSON_HEADERS = mapOf("Accept" to "application/json")

        private val lyricsContentKeys =
            listOf("lyrics", "lrc", "content", "text", "plainLyrics", "syncedLyrics", "line", "lyric")
    }
}

package org.jusplayer.engine.provider.kugou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import org.jusplayer.engine.provider.LyricsProvider
import org.jusplayer.engine.provider.ProviderException
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.min

/**
 * A [LyricsProvider] backed by KuGou Music's mobile and lyrics APIs
 * (mobileservice.kugou.com / lyrics.kugou.com).
 *
 * Adapted from ArchiveTune's KuGou client (which itself descends from ViMusic).
 * The song is searched by a normalized "title - artist" keyword, candidates are
 * matched by duration within an 8-second tolerance, and the winning LRC is
 * base64-encoded in the download response. Metadata preamble lines (singer,
 * composer, ...) are stripped from the decoded LRC.
 */
class KuGouProvider internal constructor(
    private val transport: HttpTransport,
) : LyricsProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "KuGou"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getLyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val artist = song.artists.firstOrNull()?.name.orEmpty()
        if (song.title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        val keyword = generateKeyword(song.title, artist)
        val duration = song.duration.takeIf { it > 0 }

        val candidate = getLyricsCandidate(keyword, duration)
            ?: return@withContext null

        val downloaded = downloadLyrics(candidate.id, candidate.accesskey)
        val lrc = runExtraction("Decode LRC for \"${song.title}\"") {
            decodeLyrics(downloaded.content)
        }
        if (lrc.isBlank()) {
            return@withContext null
        }

        Lyrics(
            text = lrc,
            source = "KuGou",
            synced = true,
        )
    }

    private fun getLyricsCandidate(
        keyword: Keyword,
        duration: Long?,
    ): SearchLyricsResponse.Candidate? {
        searchSongs(keyword).data?.info.orEmpty().forEach { song ->
            // null duration (unknown) disables the tolerance filter, mirroring the
            // original's `duration == -1` sentinel.
            if (duration == null || abs(song.duration - duration) <= DURATION_TOLERANCE) {
                val candidate = searchLyricsByHash(song.hash).candidates.firstOrNull()
                if (candidate != null) return candidate
            }
        }
        return searchLyricsByKeyword(keyword, duration).candidates.firstOrNull()
    }

    private fun searchSongs(keyword: Keyword): SearchSongResponse {
        val url = buildString {
            append(SEARCH_SONG_URL)
            append("?version=9108&plat=0&pagesize=").append(PAGE_SIZE).append("&showtype=0")
            append("&keyword=").append(URLEncoder.encode("${keyword.title} - ${keyword.artist}", "UTF-8"))
        }
        return runExtraction(url) {
            json.decodeFromString(checkStatus(url, transport.get(url)).body)
        }
    }

    private fun searchLyricsByHash(hash: String): SearchLyricsResponse {
        val url = buildString {
            append(LYRICS_SEARCH_URL)
            append("?ver=1&man=yes&client=pc&hash=").append(hash)
        }
        return runExtraction(url) {
            json.decodeFromString(checkStatus(url, transport.get(url)).body)
        }
    }

    private fun searchLyricsByKeyword(keyword: Keyword, duration: Long?): SearchLyricsResponse {
        val url = buildString {
            append(LYRICS_SEARCH_URL)
            append("?ver=1&man=yes&client=pc")
            duration?.let { append("&duration=").append(it * 1000) }
            append("&keyword=").append(URLEncoder.encode("${keyword.title} - ${keyword.artist}", "UTF-8"))
        }
        return runExtraction(url) {
            json.decodeFromString(checkStatus(url, transport.get(url)).body)
        }
    }

    private fun downloadLyrics(id: Long, accessKey: String): DownloadLyricsResponse {
        val url = buildString {
            append(LYRICS_DOWNLOAD_URL)
            append("?fmt=lrc&charset=utf8&client=pc&ver=1&id=").append(id)
            append("&accesskey=").append(URLEncoder.encode(accessKey, "UTF-8"))
        }
        return runExtraction(url) {
            json.decodeFromString(checkStatus(url, transport.get(url)).body)
        }
    }

    private fun checkStatus(url: String, response: HttpTransport.Response): HttpTransport.Response {
        when (response.status) {
            in 200..299 -> Unit
            404 -> throw ProviderException.NotFound("KuGou returned 404 for $url")
            429 -> throw ProviderException.RateLimited("KuGou rate limit exceeded for $url")
            else -> throw ProviderException.ExtractionFailed(
                "KuGou returned unexpected status ${response.status} for $url",
            )
        }
        return response
    }

    private fun decodeLyrics(content: String): String {
        // KuGou may wrap the base64 payload in line breaks, which the strict
        // decoder rejects; strip whitespace before decoding.
        val bytes = Base64.getDecoder().decode(content.filterNot { it.isWhitespace() })
        return bytes.decodeToString().normalize().trim()
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

    private fun normalizeTitle(title: String): String =
        title
            .replace("\\(.*\\)".toRegex(), "")
            .replace("（.*）".toRegex(), "")
            .replace("「.*」".toRegex(), "")
            .replace("『.*』".toRegex(), "")
            .replace("<.*>".toRegex(), "")
            .replace("《.*》".toRegex(), "")
            .replace("〈.*〉".toRegex(), "")
            .replace("＜.*＞".toRegex(), "")

    private fun normalizeArtist(artist: String): String =
        artist
            .replace(", ", "、")
            .replace(" & ", "、")
            .replace(".", "")
            .replace("和", "、")
            .replace("\\(.*\\)".toRegex(), "")
            .replace("（.*）".toRegex(), "")

    private fun generateKeyword(title: String, artist: String): Keyword =
        Keyword(normalizeTitle(title), normalizeArtist(artist))

    private fun String.normalize(): String =
        replace("&apos;", "'")
            .lines()
            .filter { line -> line.matches(ACCEPTED_REGEX) }
            .let { lines ->
                var headCutLine = 0
                for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[i].matches(BANNED_REGEX)) {
                        headCutLine = i + 1
                        break
                    }
                }
                val filteredLines = lines.drop(headCutLine)

                var tailCutLine = 0
                for (i in min(lines.size - HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
                        tailCutLine = i + 1
                        break
                    }
                }

                filteredLines.dropLast(tailCutLine).joinToString("\n")
            }

    @Suppress("RegExpRedundantEscape")
    private val ACCEPTED_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*".toRegex()
    private val BANNED_REGEX = ".+].+[:：].+".toRegex()

    companion object {
        private const val SEARCH_SONG_URL = "https://mobileservice.kugou.com/api/v3/search/song"
        private const val LYRICS_SEARCH_URL = "https://lyrics.kugou.com/search"
        private const val LYRICS_DOWNLOAD_URL = "https://lyrics.kugou.com/download"

        private const val PAGE_SIZE = 8
        private const val HEAD_CUT_LIMIT = 30
        private const val DURATION_TOLERANCE = 8L
    }
}

package org.jusplayer.engine.provider.coverartarchive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import org.jusplayer.engine.provider.ProviderException
import org.jusplayer.engine.provider.ReleaseResolver
import java.io.IOException
import java.net.URLEncoder

/**
 * A [ReleaseResolver] backed by the MusicBrainz Search API that maps a [Song] to
 * a release MBID, which [CoverArtArchiveProvider] then looks artwork up by.
 *
 * The song is matched on its title and first artist via the recording search
 * endpoint; the first release of the best-matching recording wins. MusicBrainz
 * requires a client User-Agent, which the default [JdkHttpTransport] supplies,
 * and politely asks for at least one second between requests, so the public
 * constructor throttles by one second. The transport-injecting constructor
 * defaults to no throttling for tests.
 */
class MusicBrainzResolver(
    private val transport: HttpTransport,
    private val throttleMillis: Long = 0L,
) : ReleaseResolver {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport(), DEFAULT_THROTTLE_MILLIS)

    override val name: String = "MusicBrainz"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolveReleaseMbid(song: Song): String? = withContext(Dispatchers.IO) {
        val artist = song.artists.firstOrNull()?.name.orEmpty()
        if (song.title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        if (throttleMillis > 0) delay(throttleMillis)

        val query = URLEncoder.encode("recording:\"${song.title}\" AND artist:\"$artist\"", "UTF-8")
        val url = "$BASE_URL/ws/2/recording/?query=$query&fmt=json&limit=1"

        val response = runExtraction("MusicBrainz resolve \"${song.title}\"") {
            transport.get(url, headers = mapOf("Accept" to "application/json"))
        }

        when (response.status) {
            in 200..299 -> json.decodeFromString<MbSearchResponse>(response.body)
                .recordings
                .firstOrNull()
                ?.releases
                ?.firstOrNull()
                ?.id
            429 -> throw ProviderException.RateLimited(
                "MusicBrainz rate limit exceeded" + retryAfterSuffix(response),
            )
            else -> throw ProviderException.ExtractionFailed(
                "MusicBrainz returned unexpected status ${response.status}",
            )
        }
    }

    private fun retryAfterSuffix(response: HttpTransport.Response): String {
        val header = response.headers["retry-after"]?.firstOrNull()
            ?: response.headers["Retry-After"]?.firstOrNull()
        return header?.let { "; retry after $it seconds" } ?: ""
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
        const val BASE_URL = "https://musicbrainz.org"
        const val DEFAULT_THROTTLE_MILLIS = 1000L
    }
}

@Serializable
internal data class MbSearchResponse(
    val recordings: List<MbRecording> = emptyList(),
)

@Serializable
internal data class MbRecording(
    val id: String? = null,
    val title: String? = null,
    val releases: List<MbRelease> = emptyList(),
)

@Serializable
internal data class MbRelease(
    val id: String? = null,
    val title: String? = null,
)
package org.jusplayer.engine.provider.coverartarchive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.provider.ArtworkProvider
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import org.jusplayer.engine.provider.ProviderException
import java.io.IOException

/**
 * An [ArtworkProvider] backed by the Cover Art Archive
 * (https://coverartarchive.org), keyed by MusicBrainz release MBID.
 *
 * The [getArtwork] call fetches the JSON listing for a release and picks the
 * community-curated "front" image (falling back to the first image), exposing the
 * original and thumbnail URLs. The `307` redirects used by the `/front` endpoints
 * are followed by the transport; this provider works from the listing JSON so the
 * engine keeps URLs rather than image bytes.
 */
class CoverArtArchiveProvider(
    private val transport: HttpTransport,
) : ArtworkProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "CoverArtArchive"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getArtwork(releaseMbid: String): Artwork? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/release/$releaseMbid/"
        val response = runExtraction("Artwork for release $releaseMbid") {
            transport.get(url, headers = mapOf("Accept" to "application/json"))
        }

        when (response.status) {
            404 -> throw ProviderException.NotFound("No cover art for release $releaseMbid")
            503 -> throw ProviderException.RateLimited("Cover Art Archive rate limit exceeded")
            in 200..299 -> parseArtwork(releaseMbid, response.body)
            else -> throw ProviderException.ExtractionFailed(
                "Cover Art Archive returned unexpected status ${response.status}",
            )
        }
    }

    private fun parseArtwork(releaseMbid: String, body: String): Artwork? {
        val listing = json.decodeFromString<CaaListing>(body)
        val front = listing.images.firstOrNull { it.front }
        val back = listing.images.firstOrNull { it.back }
        val selected = front ?: listing.images.firstOrNull() ?: return null

        return Artwork(
            frontUrl = front?.image ?: selected.image,
            backUrl = back?.image,
            thumbnails = selected.thumbnails,
            sourceMbid = releaseMbid,
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
        const val BASE_URL = "https://coverartarchive.org"
    }
}

@Serializable
internal data class CaaListing(
    val images: List<CaaImage> = emptyList(),
)

@Serializable
internal data class CaaImage(
    val front: Boolean = false,
    val back: Boolean = false,
    val image: String? = null,
    val thumbnails: Map<String, String> = emptyMap(),
)
package org.jusplayer.engine.provider.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.ProviderException
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches Apple Music canvas/motion artwork, ported from the ArchiveTune/JusPlayer
 * app's canvas module.
 *
 * Resolution chain: primary server -> fallback server -> direct Apple Music AMP
 * lookup ([AppleMusicCanvas]). Results are cached in memory with a short TTL so
 * repeated lookups for the same song/album don't re-hit the network.
 */
class CanvasService(
    private val transport: HttpTransport,
    private val appleMusic: AppleMusicCanvas = AppleMusicCanvas(transport),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private data class CacheEntry(
        val value: CanvasArtwork?,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttlMs = 60_000L

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        storefront: String = "us",
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        val key = cacheKey("sa", song, artist, storefront)
        cache[key]?.let { entry ->
            if (entry.expiresAtMs > System.currentTimeMillis()) return@withContext entry.value
            cache.remove(key)
        }

        val primary =
            runCatching {
                transport.get("$PRIMARY_URL?${params("s" to song, "a" to artist, "storefront" to storefront)}")
            }.map { decode(it) }.getOrNull()

        val value = primary ?: runCatching {
            transport.get("$FALLBACK_URL?${params("s" to song, "a" to artist, "storefront" to storefront)}")
        }.map { decode(it) }.getOrNull() ?: appleMusic.getBySongArtist(song, artist, null, storefront)

        cache[key] = CacheEntry(value, System.currentTimeMillis() + ttlMs)
        value
    }

    suspend fun getByAlbumId(albumId: String): CanvasArtwork? = withContext(Dispatchers.IO) {
        val key = cacheKey("id", albumId)
        cache[key]?.let { entry ->
            if (entry.expiresAtMs > System.currentTimeMillis()) return@withContext entry.value
            cache.remove(key)
        }

        val primary =
            runCatching { transport.get("$PRIMARY_URL?${params("id" to albumId)}") }
                .map { decode(it) }.getOrNull()

        val value = primary ?: runCatching { transport.get("$FALLBACK_URL?${params("id" to albumId)}") }
            .map { decode(it) }.getOrNull() ?: appleMusic.getByAlbumId(albumId)

        cache[key] = CacheEntry(value, System.currentTimeMillis() + ttlMs)
        value
    }

    suspend fun getByAlbumUrl(url: String): CanvasArtwork? = withContext(Dispatchers.IO) {
        val key = cacheKey("url", url)
        cache[key]?.let { entry ->
            if (entry.expiresAtMs > System.currentTimeMillis()) return@withContext entry.value
            cache.remove(key)
        }

        val primary =
            runCatching { transport.get("$PRIMARY_URL?${params("url" to url)}") }
                .map { decode(it) }.getOrNull()

        val value = primary ?: runCatching { transport.get("$FALLBACK_URL?${params("url" to url)}") }
            .map { decode(it) }.getOrNull()
            ?: parseAppleMusicAlbumUrl(url)?.let { (albumId, storefront) ->
                appleMusic.getByAlbumId(albumId, storefront)
            }

        cache[key] = CacheEntry(value, System.currentTimeMillis() + ttlMs)
        value
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        runCatching { transport.get("$PRIMARY_URL/health") }
            .getOrNull()
            ?.status == 200
    }

    private fun decode(response: HttpTransport.Response): CanvasArtwork? {
        if (response.status !in 200..299) return null
        return runCatching { json.decodeFromString<CanvasArtwork>(response.body) }.getOrNull()
    }

    private fun params(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private fun parseAppleMusicAlbumUrl(url: String): Pair<String, String>? {
        if (!url.contains("music.apple.com")) return null
        val albumPart = url.substringAfter("/album/", "").substringBefore("?")
        val albumId = albumPart.substringAfterLast("/", "")
        if (albumId.isBlank() || !albumId.all { it.isDigit() }) return null
        val storefront = url.substringAfter("music.apple.com/").substringBefore("/")
        if (storefront.isBlank()) return null
        return albumId to storefront
    }

    private fun cacheKey(
        prefix: String,
        vararg parts: String,
    ): String = "$prefix|" + parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    companion object {
        const val PRIMARY_URL = "https://artwork-archivetune.koiiverse.cloud/"
        const val FALLBACK_URL = "https://artwork.boidu.dev/"
    }
}
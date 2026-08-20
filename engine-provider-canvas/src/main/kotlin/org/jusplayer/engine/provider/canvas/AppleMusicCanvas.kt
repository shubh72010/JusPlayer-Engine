package org.jusplayer.engine.provider.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jusplayer.engine.provider.HttpTransport
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct Apple Music AMP (amp-api.music.apple.com) canvas lookup, ported from the
 * ArchiveTune/JusPlayer app's `AppleMusicProvider`. Searches the catalog with a
 * public read-only web-player JWT, scores results against the requested
 * song/artist/album, and extracts the `editorialVideo` motion art.
 */
class AppleMusicCanvas(
    private val transport: HttpTransport,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private data class CacheEntry(
        val value: CanvasArtwork?,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttlMs = 24 * 60 * 60 * 1000L

    suspend fun getByAlbumArtist(
        album: String,
        artist: String,
        storefront: String = "us",
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        val key = cacheKey("sa", album, artist, storefront)
        cached(key) ?: searchAndFetchMotion(album, artist, album, storefront, "albums")
            .also { cache[key] = CacheEntry(it, System.currentTimeMillis() + ttlMs) }
    }

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        storefront: String = "us",
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        val key = cacheKey("song", song, artist, album ?: "", storefront)
        cached(key) ?: searchAndFetchMotion(song, artist, album, storefront, "songs")
            .also { cache[key] = CacheEntry(it, System.currentTimeMillis() + ttlMs) }
    }

    suspend fun getByAlbumId(
        albumId: String,
        storefront: String = "us",
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (albumId.startsWith("pl.")) return@withContext null
        val key = cacheKey("id", albumId, storefront)
        cached(key) ?: fetchMotionArtwork(albumId, storefront, null)
            .also { cache[key] = CacheEntry(it, System.currentTimeMillis() + ttlMs) }
    }

    private fun cached(key: String): CanvasArtwork? =
        cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.value

    private fun searchAndFetchMotion(
        term: String,
        artist: String,
        album: String?,
        storefront: String,
        type: String,
    ): CanvasArtwork? {
        var query = if (term.contains(artist, ignoreCase = true)) term else "$artist $term"
        if (!album.isNullOrBlank() && !query.contains(album, ignoreCase = true)) query = "$query $album"

        val root = getJson(
            "$AMP_BASE_URL/v1/catalog/$storefront/search",
            params = mapOf(
                "term" to query,
                "types" to type,
                "limit" to "10",
                "extend" to "editorialVideo",
                "include" to "albums",
            ),
        ) ?: return null

        val results =
            root["results"]?.jsonObject?.get(type)?.jsonObject?.get("data")?.jsonArray
                ?: return null

        val scored =
            results
                .mapNotNull { scoreAndFilterItem(it.jsonObject, term, artist, album) }
                .sortedByDescending { it.first }

        for ((score, obj) in scored) {
            if (score < 12) continue

            val attributes = obj["attributes"]?.jsonObject ?: continue
            val resultName = attributes["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val resultArtistName = attributes["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
            val itemType = obj["type"]?.jsonPrimitive?.contentOrNull

            val targetAlbumId = resolveAlbumId(obj, attributes, itemType, resultName)
            if (targetAlbumId == null || targetAlbumId.startsWith("pl.")) continue

            // Immediate motion art embedded in the search result.
            val ev = attributes["editorialVideo"]?.jsonObject
            if (ev != null) {
                val urls = extractEditorialVideoUrls(ev)
                if (!urls.animated.isNullOrBlank() || !urls.animatedVertical.isNullOrBlank()) {
                    val name = attributes["name"]?.jsonPrimitive?.contentOrNull
                    val collName = attributes["collectionName"]?.jsonPrimitive?.contentOrNull
                    val resolvedAlbumName = if (itemType == "songs") collName else name
                    return CanvasArtwork(
                        name = name,
                        artist = resultArtistName,
                        albumId = targetAlbumId,
                        albumName = resolvedAlbumName,
                        animated = urls.animated,
                        animatedVertical = urls.animatedVertical,
                    )
                }
            }

            val fetched =
                fetchMotionArtwork(
                    albumId = targetAlbumId,
                    storefront = storefront,
                    fallbackArtist = resultArtistName,
                    titleOverride = if (itemType == "songs") attributes["name"]?.jsonPrimitive?.contentOrNull else null,
                    artistOverride = if (itemType == "songs") resultArtistName else null,
                )
            if (fetched != null) return fetched
        }
        return null
    }

    private fun fetchMotionArtwork(
        albumId: String,
        storefront: String,
        fallbackArtist: String?,
        titleOverride: String? = null,
        artistOverride: String? = null,
    ): CanvasArtwork? {
        if (albumId.startsWith("pl.")) return null

        val root = getJson(
            "$AMP_BASE_URL/v1/catalog/$storefront/albums/$albumId",
            params = mapOf("extend" to "editorialVideo", "include" to "tracks"),
        ) ?: return null

        val data = root["data"]?.jsonArray ?: return null
        val albumObj = data.firstOrNull()?.jsonObject ?: return null
        val attributes = albumObj["attributes"]?.jsonObject ?: return null
        val albumName = attributes["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val artistName = attributes["artistName"]?.jsonPrimitive?.contentOrNull ?: fallbackArtist

        if (isBlacklisted(albumName)) return null

        val finalTitle = titleOverride ?: albumName
        val finalArtist = artistOverride ?: artistName

        val ev = attributes["editorialVideo"]?.jsonObject
        if (ev != null) {
            val urls = extractEditorialVideoUrls(ev)
            if (!urls.animated.isNullOrBlank() || !urls.animatedVertical.isNullOrBlank()) {
                return CanvasArtwork(
                    name = finalTitle,
                    artist = finalArtist,
                    albumId = albumId,
                    albumName = albumName,
                    animated = urls.animated,
                    animatedVertical = urls.animatedVertical,
                )
            }
        }
        return null
    }

    private fun scoreAndFilterItem(
        obj: JsonObject,
        term: String,
        artist: String,
        album: String?,
    ): Pair<Int, JsonObject>? {
        val attributes = obj["attributes"]?.jsonObject ?: return null
        val resultArtistName = attributes["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
        val resultName = attributes["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val resultCollectionName = attributes["collectionName"]?.jsonPrimitive?.contentOrNull ?: ""

        val nameLower = resultName.lowercase(Locale.ROOT)
        val collectionLower = resultCollectionName.lowercase(Locale.ROOT)
        if (isBlacklisted(resultName) || isBlacklisted(resultCollectionName)) return null

        val artistFuzzy =
            resultArtistName.contains(artist, ignoreCase = true) ||
                artist.contains(resultArtistName, ignoreCase = true)
        if (!artistFuzzy) return null

        var score = if (resultArtistName.equals(artist, ignoreCase = true)) 10 else 5

        val nameMatch = resultName.equals(term, ignoreCase = true)
        val nameFuzzy = resultName.contains(term, ignoreCase = true) || term.contains(resultName, ignoreCase = true)
        score += when {
            nameMatch -> 15
            nameFuzzy -> 7
            else -> -10
        }

        val editionWords = listOf("deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus")
        for (word in editionWords) {
            val inTerm = term.contains(word, ignoreCase = true)
            val inResult = resultName.contains(word, ignoreCase = true)
            score += when {
                inTerm && inResult -> 5
                inTerm != inResult && inResult -> -3
                else -> 0
            }
        }

        if (!album.isNullOrBlank() && resultCollectionName.isNotBlank()) {
            val albumMatch = resultCollectionName.equals(album, ignoreCase = true)
            val albumFuzzy =
                resultCollectionName.contains(album, ignoreCase = true) ||
                    album.contains(resultCollectionName, ignoreCase = true)
            score += when {
                albumMatch -> 20
                albumFuzzy -> 10
                else -> 0
            }
        }

        return score to obj
    }

    private fun resolveAlbumId(
        obj: JsonObject,
        attributes: JsonObject,
        itemType: String?,
        resultName: String,
    ): String? {
        if (itemType == "albums") return obj["id"]?.jsonPrimitive?.contentOrNull
        if (itemType != "songs") return null

        var albumId =
            obj["relationships"]?.jsonObject?.get("albums")?.jsonObject?.get("data")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?: attributes["collectionId"]?.jsonPrimitive?.contentOrNull

        if (albumId == null) {
            val url = attributes["url"]?.jsonPrimitive?.contentOrNull
            if (url != null) {
                val albumPart = url.substringAfter("/album/", "").substringBefore("?")
                val id = albumPart.substringAfterLast("/", "")
                if (id.isNotBlank() && id.all { it.isDigit() }) albumId = id
            }
        }
        return albumId
    }

    private data class EditorialVideoUrls(
        val animated: String?,
        val animatedVertical: String?,
    )

    private fun extractEditorialVideoUrls(ev: JsonObject): EditorialVideoUrls {
        fun JsonObject.videoUrl(): String? =
            this["video"]?.jsonPrimitive?.contentOrNull
                ?: this["videoUrl"]?.jsonPrimitive?.contentOrNull
                ?: this["hlsUrl"]?.jsonPrimitive?.contentOrNull
                ?: this["url"]?.jsonPrimitive?.contentOrNull

        val raw = ev["motionDetailRaw"]?.jsonObject?.videoUrl()
        val square = ev["motionDetailSquare"]?.jsonObject?.videoUrl()
        val tall = ev["motionDetailTall"]?.jsonObject?.videoUrl()
        val static = ev["motionDetailStatic"]?.jsonObject?.videoUrl()
        val animated = raw ?: square ?: static ?: tall

        return EditorialVideoUrls(
            animated = animated,
            animatedVertical = tall,
        )
    }

    private fun isBlacklisted(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.contains("playlist") || lower.contains("set list") ||
            lower.contains("essentials") || lower.contains("dj mix") ||
            lower.contains("mixed") || lower.contains("apple music") ||
            lower.contains("today's hits") || lower.contains("session")
    }

    private fun getJson(
        url: String,
        params: Map<String, String>,
    ): JsonObject? {
        val fullUrl = "$url?${params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }}"
        val response =
            transport.get(
                fullUrl,
                headers = mapOf(
                    "Authorization" to "Bearer $APPLE_MUSIC_TOKEN",
                    "Origin" to "https://music.apple.com",
                    "Referer" to "https://music.apple.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                ),
            )
        if (response.status !in 200..299) return null
        return runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
    }

    private fun cacheKey(
        prefix: String,
        vararg parts: String,
    ): String = "$prefix|" + parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    companion object {
        const val AMP_BASE_URL = "https://amp-api.music.apple.com"

        // Public read-only JWT used by the Apple Music web player for unauthenticated
        // catalog reads.
        private const val APPLE_MUSIC_TOKEN =
            "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IldlYlBsYXlLaWQifQ" +
                ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzc0NDU2MzgyLCJleHAiOjE3ODE3" +
                "MTM5ODIsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
                ".4n8qYF4qa18sL1E0G9A3qX35cD8wQ-IJcS9Bh8ZT8JV_yLBtVq46B-9-2ZS3EvWHuw3yK9BYFYAhAdTaDm38vQ"
    }
}
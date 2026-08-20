package org.jusplayer.engine.provider.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.JdkHttpTransport
import org.jusplayer.engine.provider.ProviderException
import org.jusplayer.engine.provider.SongArtworkProvider
import java.io.IOException

/**
 * An [SongArtworkProvider] for Apple Music canvas/motion artwork, ported from the
 * ArchiveTune/JusPlayer app.
 *
 * Unlike [org.jusplayer.engine.provider.ArtworkProvider]s that key off a MusicBrainz
 * release MBID, canvas art is resolved directly from a [Song]'s title/artist (and
 * album when present), so this implements [SongArtworkProvider] and the engine
 * bypasses the [org.jusplayer.engine.provider.ReleaseResolver] step for it.
 */
class CanvasArtworkProvider(
    private val transport: HttpTransport,
) : SongArtworkProvider {

    /** Public entry point that uses the default JVM [JdkHttpTransport]. */
    constructor() : this(JdkHttpTransport())

    override val name: String = "AppleMusicCanvas"

    private val service by lazy { CanvasService(transport) }

    override suspend fun getArtwork(song: Song): Artwork? = withContext(Dispatchers.IO) {
        val artist = song.artists.firstOrNull()?.name.orEmpty()
        if (song.title.isBlank()) return@withContext null

        val canvas = runExtraction("Canvas for \"${song.title}\"") {
            service.getBySongArtist(song.title, artist)
        } ?: return@withContext null

        Artwork(
            frontUrl = canvas.preferredAnimationUrl,
            verticalUrl = canvas.preferredVerticalAnimationUrl,
            thumbnails = buildMap {
                canvas.static?.let { put("static", it) }
                canvas.animated?.let { put("animated", it) }
                canvas.videoUrl?.let { put("video", it) }
                canvas.animatedVertical?.let { put("animatedVertical", it) }
            },
            source = name,
            width = null,
            height = null,
        )
    }

    private inline fun <T> runExtraction(operation: String, block: () -> T): T {
        return try {
            block()
        } catch (e: IOException) {
            throw ProviderException.Network(operation, e)
        } catch (e: RuntimeException) {
            throw ProviderException.ExtractionFailed(operation, e)
        }
    }
}
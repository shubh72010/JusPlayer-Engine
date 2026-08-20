package org.jusplayer.engine.core.service

import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.ArtworkProvider
import org.jusplayer.engine.provider.ReleaseResolver
import org.jusplayer.engine.provider.SongArtworkProvider

class ArtworkService(
    private val resolver: ReleaseResolver?,
    private val provider: ArtworkProvider?,
) {
    suspend fun artwork(song: Song): Artwork? {
        val artworkProvider = provider ?: return null
        // Direct-song providers (e.g. Apple Music canvas) resolve from the song
        // itself; the MBID path below needs a ReleaseResolver.
        val direct = artworkProvider as? SongArtworkProvider
        if (direct != null) return direct.getArtwork(song)

        val releaseResolver = resolver ?: return null
        val mbid = releaseResolver.resolveReleaseMbid(song) ?: return null
        return artworkProvider.getArtwork(mbid)
    }
}
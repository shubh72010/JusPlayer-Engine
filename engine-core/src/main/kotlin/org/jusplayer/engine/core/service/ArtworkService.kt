package org.jusplayer.engine.core.service

import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.ArtworkProvider
import org.jusplayer.engine.provider.ReleaseResolver

class ArtworkService(
    private val resolver: ReleaseResolver?,
    private val provider: ArtworkProvider?,
) {
    suspend fun artwork(song: Song): Artwork? {
        val releaseResolver = resolver ?: return null
        val artworkProvider = provider ?: return null
        val mbid = releaseResolver.resolveReleaseMbid(song) ?: return null
        return artworkProvider.getArtwork(mbid)
    }
}
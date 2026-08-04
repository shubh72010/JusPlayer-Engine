package org.jusplayer.engine.provider

import org.jusplayer.engine.model.Artwork

/**
 * A provider of cover art, decoupled from [MusicProvider].
 *
 * Cover Art Archive indexes images by MusicBrainz release MBID, so the engine
 * resolves a [Song] to a release via a [ReleaseResolver] first and passes the
 * MBID here. Return `null` when no artwork is available.
 */
interface ArtworkProvider {
    val name: String

    suspend fun getArtwork(releaseMbid: String): Artwork?
}
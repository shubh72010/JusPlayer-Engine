package org.jusplayer.engine.provider

import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.model.Song

/**
 * An [ArtworkProvider] that resolves artwork directly from a [Song]'s metadata,
 * without going through a [ReleaseResolver]/MusicBrainz MBID lookup.
 *
 * Some artwork sources (e.g. Apple Music canvas/motion art) are keyed by
 * song/album/artist names rather than MBIDs, so they cannot use the default
 * [ArtworkProvider.getArtwork] contract. The engine prefers this method when the
 * registered provider implements it and falls back to the MBID path otherwise.
 */
interface SongArtworkProvider : ArtworkProvider {

    /** Returns artwork for [song], or `null` when none is available. */
    suspend fun getArtwork(song: Song): Artwork?

    /** Unsupported on the direct path; the engine never calls this for a [SongArtworkProvider]. */
    override suspend fun getArtwork(releaseMbid: String): Artwork? = null
}
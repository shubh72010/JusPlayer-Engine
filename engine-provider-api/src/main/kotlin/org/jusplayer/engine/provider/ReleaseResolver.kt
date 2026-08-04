package org.jusplayer.engine.provider

import org.jusplayer.engine.model.Song

/**
 * Resolves a [Song] (whose id is streaming-service specific) to a MusicBrainz
 * release MBID, which is what [ArtworkProvider] implementations key their lookups
 * on. Return `null` when no release can be matched.
 */
interface ReleaseResolver {
    val name: String

    suspend fun resolveReleaseMbid(song: Song): String?
}
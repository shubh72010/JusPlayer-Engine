package org.jusplayer.engine.provider

import org.jusplayer.engine.model.Song

/**
 * Optional capability for providers that can surface platform-native
 * recommendations for a given song (e.g. YouTube's "related streams").
 *
 * Providers should declare `ProviderCapabilities.recommendations = true` when
 * they implement this, and must translate raw errors into [ProviderException]
 * subtypes exactly like their other operations. Implementations may return
 * fewer than [limit] songs (or an empty list) when nothing is available.
 */
interface RelatedProvider {

    /**
     * Returns up to [limit] songs the platform recommends as related to
     * [songId]. The result is best-effort: a network failure or missing related
     * list must not propagate raw exceptions to the caller.
     */
    suspend fun getRecommendations(songId: String, limit: Int): List<Song>
}
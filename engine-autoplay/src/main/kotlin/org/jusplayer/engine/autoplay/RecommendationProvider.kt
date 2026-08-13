package org.jusplayer.engine.autoplay

import org.jusplayer.engine.model.Song

/**
 * A provider-agnostic source of recommendation candidates.
 *
 * The core never hard-codes a specific backend's recommendation logic — any
 * source can plug in here: a related-tracks API, a local library, Last.fm /
 * ListenBrainz, MusicBrainz metadata, or an on-device model. Multiple providers
 * can be registered; the autoplay pipeline aggregates, filters, scores, and
 * diversifies their output.
 */
interface RecommendationProvider {
    val name: String

    /**
     * Returns up to [limit] candidate songs given the current listening [context].
     * Implementations must translate raw errors into a best-effort (possibly
     * empty) result rather than throwing, and should never mutate [context].
     */
    suspend fun recommend(context: AutoplayContext, limit: Int): List<Song>
}
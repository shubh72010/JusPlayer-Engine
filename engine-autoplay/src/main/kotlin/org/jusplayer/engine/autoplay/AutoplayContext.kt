package org.jusplayer.engine.autoplay

import java.time.Instant
import org.jusplayer.engine.model.RepeatMode
import org.jusplayer.engine.model.Song

/**
 * Everything the autoplay/recommendation layer knows about the current listening
 * session. Built by the engine right before asking for the next tracks.
 *
 * Providers may combine any of these signals. Fields are intentionally derived
 * from existing [Song] metadata (artists, genres) so no new metadata has to be
 * invented — providers just leave [Song.genres] empty until they can populate it.
 */
data class AutoplayContext(
    val currentSong: Song,
    val recentSongs: List<Song>,
    val recentArtists: List<String>,
    val recentGenres: List<String>,
    val queueSongs: List<Song>,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val timestamp: Instant,
)
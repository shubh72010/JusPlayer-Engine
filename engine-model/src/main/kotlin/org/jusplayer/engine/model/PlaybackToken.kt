package org.jusplayer.engine.model

import kotlinx.serialization.Serializable

/**
 * Identifies a single playback session.
 *
 * Every playback — user-initiated ([JusPlayerEngine.play]), manual skip, natural
 * auto-advance, or autoplay — receives a fresh [generation] from a monotonically
 * increasing counter, paired with the id of the song being played. The engine
 * commits asynchronous results (stream resolution, recommendations) only while
 * the session they were started under is still the active one, so a stale
 * result can never overwrite newer user intent.
 *
 * Completion events carry the token's [generation]; the engine ignores any
 * [org.jusplayer.engine.events.SongEnded] whose generation does not match the
 * currently active session (stale, duplicate, or delivered after stop).
 */
@Serializable
data class PlaybackToken(
    val generation: Long,
    val songId: String,
)
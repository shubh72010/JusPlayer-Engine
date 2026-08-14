package org.jusplayer.engine.events

import org.jusplayer.engine.model.PlaybackToken
import org.jusplayer.engine.model.RepeatMode

data class SongStarted(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
    /** The playback session that started, when emitted by the engine. */
    val token: PlaybackToken? = null,
) : Event

/**
 * Emitted when a song reaches its natural end. The engine treats this as the
 * trigger to advance the queue (or hand over to autoplay when the queue is
 * exhausted). Manual operations (skip, stop, pause, clear) must NOT emit this.
 *
 * [generation] is the playback session's generation (see
 * [PlaybackToken]). The engine only acts on a completion whose generation
 * matches the currently active playback session — stale, duplicate, or
 * post-stop completions are ignored. Emitting a completion with a `null`
 * generation is still supported for backward compatibility (the engine then
 * matches by song id only).
 */
data class SongEnded(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
    val generation: Long? = null,
) : Event

data class QueueChanged(
    val queueSize: Int,
    val currentIndex: Int,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val shuffleEnabled: Boolean = false,
) : Event

data class PlaybackPaused(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
) : Event

/** Emitted by the engine when playback resumes after a pause. */
data class PlaybackResumed(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
) : Event

data class PlaybackStopped(
    val song: org.jusplayer.engine.model.Song?,
    val position: Long,
) : Event

data class PlaybackError(
    val song: org.jusplayer.engine.model.Song?,
    val message: String,
) : Event

/**
 * Emitted when the autoplay layer replenishes an exhausted queue.
 */
data class AutoplayEnqueued(
    val songs: List<org.jusplayer.engine.model.Song>,
) : Event

data class ProviderChanged(
    val providerName: String,
) : Event

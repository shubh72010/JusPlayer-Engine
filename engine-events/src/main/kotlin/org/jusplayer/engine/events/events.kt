package org.jusplayer.engine.events

import org.jusplayer.engine.model.RepeatMode

data class SongStarted(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
) : Event

/**
 * Emitted when a song reaches its natural end. The engine treats this as the
 * trigger to advance the queue (or hand over to autoplay when the queue is
 * exhausted). Manual operations (skip, stop, previous) must NOT emit this.
 */
data class SongEnded(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
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
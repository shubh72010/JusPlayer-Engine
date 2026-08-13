package org.jusplayer.engine.queue

import org.jusplayer.engine.model.RepeatMode
import org.jusplayer.engine.model.Song

/**
 * Immutable, atomically-published view of the queue. Clients observe this on
 * [QueueEngine.state] — the queue exposes no other mutable state path.
 */
data class QueueSnapshot(
    val items: List<Song>,
    val currentIndex: Int,
    val currentSong: Song?,
    val size: Int,
    val isEmpty: Boolean,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
)
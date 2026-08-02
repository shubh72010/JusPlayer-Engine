package org.jusplayer.engine.events

data class SongStarted(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
) : Event

data class SongEnded(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
) : Event

data class QueueChanged(
    val queueSize: Int,
    val currentIndex: Int,
) : Event

data class PlaybackPaused(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
) : Event

data class PlaybackResumed(
    val song: org.jusplayer.engine.model.Song,
    val position: Long,
) : Event

data class ProviderChanged(
    val providerName: String,
) : Event
package org.jusplayer.engine.core.service

import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.PlaybackPaused
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.events.SongStarted
import org.jusplayer.engine.model.PlaybackState
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter

class PlaybackService(
    private val playerAdapter: PlayerAdapter,
    private val eventBus: EventBus,
) {
    private var currentSong: Song? = null

    var state: PlaybackState = PlaybackState.Idle
        private set

    suspend fun play(song: Song, stream: Stream) {
        currentSong = song
        playerAdapter.play(stream)
        eventBus.emit(SongStarted(song = song, position = 0))
        state = PlaybackState.Playing
    }

    fun pause() {
        playerAdapter.pause()
        val song = currentSong
        if (song != null) {
            state = PlaybackState.Paused
        }
    }

    suspend fun stop() {
        playerAdapter.stop()
        val song = currentSong
        if (song != null) {
            eventBus.emit(SongEnded(song = song, position = 0))
        }
        state = PlaybackState.Idle
        currentSong = null
    }

    fun seek(position: java.time.Duration) {
        playerAdapter.seek(position)
    }

    fun getCurrentSong(): Song? = currentSong
}
package org.jusplayer.engine.core.service

import java.time.Duration
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.SongStarted
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter

/**
 * Thin wrapper over the [PlayerAdapter]. Its only responsibilities are issuing
 * the raw play/pause/stop/seek calls and announcing when a track begins
 * ([SongStarted]).
 *
 * Natural track completion is NOT handled here: the adapter/app reports it by
 * emitting [SongEnded] on the shared [EventBus], and the engine reacts (advancing
 * the queue or handing over to autoplay). Stop deliberately neither emits
 * [SongEnded] nor touches engine state — the engine does that, so a user-initiated
 * stop is never confused with a natural end and can never trigger autoplay.
 *
 * The engine owns authoritative playback state ([PlaybackState], current song);
 * this service holds only the pointer needed to emit position-based events.
 */
class PlaybackService(
    private val playerAdapter: PlayerAdapter,
    @Suppress("unused") private val eventBus: EventBus,
) {
    private var currentSong: Song? = null

    /** Current playback position, when the adapter can report it. */
    val position: Duration?
        get() = playerAdapter.position

    fun currentSongOrNull(): Song? = currentSong

    suspend fun play(song: Song, stream: Stream) {
        currentSong = song
        playerAdapter.play(stream)
        eventBus.emit(SongStarted(song = song, position = 0))
    }

    fun pause() {
        playerAdapter.pause()
    }

    fun stop() {
        playerAdapter.stop()
        currentSong = null
    }

    fun seek(position: Duration) {
        playerAdapter.seek(position)
    }
}
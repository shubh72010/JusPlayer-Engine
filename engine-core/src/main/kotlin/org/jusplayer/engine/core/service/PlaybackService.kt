package org.jusplayer.engine.core.service

import java.time.Duration
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.PlaybackResumed
import org.jusplayer.engine.events.SongStarted
import org.jusplayer.engine.model.PlaybackToken
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter

/**
 * Thin wrapper over the [PlayerAdapter]. Its only responsibilities are issuing
 * the raw play/pause/stop/seek calls and announcing when a track begins
 * ([SongStarted]) or resumes ([PlaybackResumed]).
 *
 * Natural track completion is NOT handled here: the adapter/app reports it by
 * emitting [org.jusplayer.engine.events.SongEnded] on the shared [EventBus], and
 * the engine reacts (advancing the queue or handing over to autoplay). Stop
 * deliberately neither emits [SongEnded] nor touches engine state — the engine
 * does that, so a user-initiated stop is never confused with a natural end and
 * can never trigger autoplay.
 *
 * Announcements are issued via [EventBus.tryEmit] so a slow observer can never
 * stall playback transitions. The engine owns authoritative playback state
 * ([PlaybackState], current song); this service holds only the pointer needed
 * to emit position-based events and the last stream used to resume after a
 * pause.
 */
class PlaybackService(
    private val playerAdapter: PlayerAdapter,
    private val eventBus: EventBus,
) {
    private var currentSong: Song? = null
    private var lastStream: Stream? = null
    private var lastStreamSongId: String? = null

    /** Current playback position, when the adapter can report it. */
    val position: Duration?
        get() = playerAdapter.position

    fun currentSongOrNull(): Song? = currentSong

    suspend fun play(song: Song, stream: Stream, token: PlaybackToken) {
        currentSong = song
        lastStream = stream
        lastStreamSongId = song.id
        playerAdapter.play(stream)
        eventBus.tryEmit(SongStarted(song = song, position = 0, token = token))
    }

    /**
     * Replays the current stream after a pause, announcing [PlaybackResumed].
     * Returns `false` when there is no cached stream or when the cached stream
     * belongs to a different song (e.g. the user paused while an auto-advance
     * was still resolving and that advance never committed) — the caller must
     * then re-resolve the stream instead of resuming stale audio.
     */
    suspend fun resume(song: Song, token: PlaybackToken): Boolean {
        val stream = lastStream
        if (stream == null || lastStreamSongId != song.id) return false
        currentSong = song
        playerAdapter.play(stream)
        eventBus.tryEmit(PlaybackResumed(song = song, position = position?.toMillis() ?: 0L))
        return true
    }

    fun pause() {
        playerAdapter.pause()
    }

    fun stop() {
        playerAdapter.stop()
        currentSong = null
        lastStream = null
        lastStreamSongId = null
    }

    fun seek(position: Duration) {
        playerAdapter.seek(position)
    }
}
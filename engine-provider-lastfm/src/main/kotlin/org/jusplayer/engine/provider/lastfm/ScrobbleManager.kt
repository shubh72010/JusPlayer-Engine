package org.jusplayer.engine.provider.lastfm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Metadata about a playing track, decoupled from any concrete model so the
 * manager can be reused by any engine or app.
 */
data class ScrobbleTrack(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    /** Duration in seconds. */
    val durationSec: Long,
)

/**
 * Callbacks the [ScrobbleManager] invokes at decision points. Wire these to a
 * [LastFMClient] (or anything else) — e.g. in JusPlayerEngine's playback event
 * handling.
 */
interface ScrobbleListener {
    suspend fun onUpdateNowPlaying(track: ScrobbleTrack)
    suspend fun onScrobble(track: ScrobbleTrack, timestamp: Long)
}

/**
 * Decides when to scrobble a track, ported from the ArchiveTune/JusPlayer app's
 * `ScrobbleManager` (pure coroutines, no Android).
 *
 * Rules (all configurable): tracks shorter than [minSongDuration] seconds are
 * never scrobbled; otherwise the timer fires after
 * `duration * scrobbleDelayPercent` seconds capped at [scrobbleDelaySeconds]
 * (defaults: 50% or 180 s, whichever is smaller). The timer is pause/resume-aware
 * and the scrobble carries the timestamp the song actually started.
 */
class ScrobbleManager(
    private val scope: CoroutineScope,
    private val listener: ScrobbleListener,
    var minSongDuration: Int = LastFMClient.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
    var scrobbleDelayPercent: Float = LastFMClient.DEFAULT_SCROBBLE_DELAY_PERCENT,
    var scrobbleDelaySeconds: Int = LastFMClient.DEFAULT_SCROBBLE_DELAY_SECONDS,
) {
    var useNowPlaying: Boolean = true

    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
    }

    fun onSongStart(track: ScrobbleTrack) {
        songStartedAt = System.currentTimeMillis() / 1000
        songStarted = true
        startScrobbleTimer(track)
        if (useNowPlaying) {
            launchSafely { listener.onUpdateNowPlaying(track) }
        }
    }

    fun onSongResume(track: ScrobbleTrack) {
        resumeScrobbleTimer(track)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
    }

    /** Call on any play/pause state change; starts, pauses, or resumes as needed. */
    fun onPlayerStateChanged(
        isPlaying: Boolean,
        track: ScrobbleTrack,
    ) {
        if (isPlaying) {
            if (!songStarted) onSongStart(track) else onSongResume(track)
        } else {
            onSongPause()
        }
    }

    private fun startScrobbleTimer(track: ScrobbleTrack) {
        scrobbleJob?.cancel()
        if (track.durationSec <= minSongDuration) return

        val threshold = track.durationSec * 1000L * scrobbleDelayPercent
        scrobbleRemainingMillis = min(threshold.toLong(), scrobbleDelaySeconds * 1000L)

        if (scrobbleRemainingMillis <= 0) {
            scrobbleSong(track)
            return
        }
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(track)
                scrobbleJob = null
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(track: ScrobbleTrack) {
        if (scrobbleRemainingMillis <= 0) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(track)
                scrobbleJob = null
            }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
    }

    private fun scrobbleSong(track: ScrobbleTrack) {
        launchSafely { listener.onScrobble(track, songStartedAt) }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Scrobbling is best-effort; never let a failure escape the scope.
            }
        }
    }
}
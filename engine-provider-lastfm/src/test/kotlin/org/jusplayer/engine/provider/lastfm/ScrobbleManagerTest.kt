package org.jusplayer.engine.provider.lastfm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrobbleManagerTest {

    private class RecordingListener : ScrobbleListener {
        var scrobbles: MutableList<Pair<ScrobbleTrack, Long>> = mutableListOf()
        var nowPlaying: MutableList<ScrobbleTrack> = mutableListOf()

        override suspend fun onUpdateNowPlaying(track: ScrobbleTrack) {
            nowPlaying.add(track)
        }

        override suspend fun onScrobble(track: ScrobbleTrack, timestamp: Long) {
            scrobbles.add(track to timestamp)
        }
    }

    private fun track(durationSec: Long) = ScrobbleTrack(
        title = "Never Gonna Give You Up",
        artists = listOf("Rick Astley"),
        album = "Whenever You Need Somebody",
        durationSec = durationSec,
    )

    @Test
    fun shortTrackNeverScrobbles() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val listener = RecordingListener()
        val manager = ScrobbleManager(scope, listener, minSongDuration = 30)
        manager.onSongStart(track(durationSec = 20))
        delay(300)
        assertTrue(listener.scrobbles.isEmpty())
        assertEquals(1, listener.nowPlaying.size)
        manager.destroy()
    }

    @Test
    fun longTrackScrobblesAfterCappedDelay() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val listener = RecordingListener()
        val manager =
            ScrobbleManager(
                scope,
                listener,
                minSongDuration = 30,
                scrobbleDelayPercent = 1.0f,
                scrobbleDelaySeconds = 1,
            )
        val before = System.currentTimeMillis() / 1000
        manager.onSongStart(track(durationSec = 40))
        delay(1600)
        assertEquals(1, listener.scrobbles.size)
        val (scrobbledTrack, timestamp) = listener.scrobbles[0]
        assertEquals(40L, scrobbledTrack.durationSec)
        assertTrue(timestamp >= before, "scrobble timestamp should be the song start time")
        manager.destroy()
    }

    @Test
    fun nowPlayingDisabledSkipsUpdate() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val listener = RecordingListener()
        val manager = ScrobbleManager(scope, listener)
        manager.useNowPlaying = false
        manager.onSongStart(track(durationSec = 20))
        delay(100)
        assertTrue(listener.nowPlaying.isEmpty())
        manager.destroy()
    }

    @Test
    fun playPauseResumeKeepsTimer() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val listener = RecordingListener()
        val manager =
            ScrobbleManager(
                scope,
                listener,
                minSongDuration = 30,
                scrobbleDelayPercent = 1.0f,
                scrobbleDelaySeconds = 2,
            )
        manager.onPlayerStateChanged(isPlaying = true, track = track(40))
        delay(700)
        manager.onPlayerStateChanged(isPlaying = false, track = track(40))
        delay(1000)
        // Paused: should not have fired yet (timer paused at ~0.7s < 2s).
        assertTrue(listener.scrobbles.isEmpty(), "scrobble must not fire while paused")
        manager.onPlayerStateChanged(isPlaying = true, track = track(40))
        delay(1800)
        assertEquals(1, listener.scrobbles.size)
        manager.destroy()
    }
}
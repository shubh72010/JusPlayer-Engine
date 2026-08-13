package org.jusplayer.sample

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jusplayer.engine.api.JusPlayer
import org.jusplayer.engine.api.createJusPlayer
import org.jusplayer.engine.autoplay.AutoplayConfig
import org.jusplayer.engine.autoplay.AutoplayContext
import org.jusplayer.engine.autoplay.RecommendationProvider
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.coverartarchive.CoverArtArchiveProvider
import org.jusplayer.engine.provider.coverartarchive.MusicBrainzResolver
import org.jusplayer.engine.provider.lrclib.LRCLIBProvider
import org.jusplayer.engine.provider.newpipe.NewPipeProvider
import java.time.Duration

class ConsolePlayerAdapter : PlayerAdapter {
    override suspend fun play(stream: Stream) {
        println("Playing: ${stream.url}")
    }

    override fun pause() {
        println("Paused")
    }

    override fun stop() {
        println("Stopped")
    }

    override fun seek(position: Duration) {
        println("Seeked to: $position")
    }
}

/**
 * Stand-in autoplay source that mimics what a real provider would return.
 * Track the engine's event bus: when a track ends and the queue is exhausted,
 * these candidates are enqueued automatically and playback continues.
 */
class ConsoleRecommendationProvider : RecommendationProvider {
    override val name = "console-fallback"

    override suspend fun recommend(context: AutoplayContext, limit: Int): List<Song> {
        println("Autoplay: scheduling up to $limit follow-ups after '${context.currentSong.title}'")
        return (1..limit).map { n ->
            Song(
                id = "auto-$n-${context.timestamp.toEpochMilli()}",
                title = "Auto $n",
                artists = listOf(Artist("autopilot", "Autopilot", null)),
                album = null,
                duration = 120_000,
                thumbnailUrl = null,
                streamUrl = null,
            )
        }
    }
}

/**
 * Drives a synthetic playback lifecycle: plays each track ~0.5s then emits a
 * [SongEnded] on the bus so the engine auto-advances (and autoplays in when the
 * queue runs out).
 */
suspend fun JusPlayer.demoAutoAdvance() {
    var previousId: String? = null
    var guard = 0
    while (state != org.jusplayer.engine.model.PlaybackState.Ended && guard < 6) {
        val song = currentSong ?: break
        if (song.id != previousId) {
            println("Now playing: ${song.title}${if (song.id.startsWith("auto-")) "  [autoplay]" else ""}")
            previousId = song.id
        }
        delay(400)
        println("   (${song.title} finished naturally -> engine auto-advances)")
        events.emit(SongEnded(song = song, position = 120_000))
        // Wait for the engine's collector to react before reading currentSong again.
        var settled = false
        while (!settled && state != org.jusplayer.engine.model.PlaybackState.Ended) {
            val next = currentSong
            if (next == null || next.id != previousId) settled = true
            else delay(50)
        }
        guard++
    }
    println("Demo finished. Autoplay scheduled ${autoplayCandidatesCount()} candidates total.")
}

private fun JusPlayer.autoplayCandidatesCount(): Int {
    return engine.autoplayCandidates.value.size
}

fun main() = runBlocking {
    val jusPlayer = createJusPlayer {
        provider(NewPipeProvider())
        recommendationProvider(ConsoleRecommendationProvider())
        autoplay(AutoplayConfig(bufferSize = 2, recentlyPlayedWindowMs = 30_000L))
        autoplayEnabled(true)
        lyricsProvider(LRCLIBProvider())
        artworkProvider(CoverArtArchiveProvider())
        releaseResolver(MusicBrainzResolver())
        player(ConsolePlayerAdapter())
    }

    println("JusPlayer Engine v1")
    println("Repeat: ${jusPlayer.repeatMode}, shuffle: ${jusPlayer.shuffleEnabled}")

    val songs = jusPlayer.engine.search("Daft Punk")
    println("Search results: ${songs.size} songs")

    if (songs.isNotEmpty()) {
        val song = songs.first()
        jusPlayer.engine.play(song)
        println("Autoplay enabled: ${jusPlayer.autoplayEnabled}")

        runCatching { jusPlayer.engine.lyrics(song)?.let { println("Lyrics: ${it.text.take(80)}") } }
        runCatching { jusPlayer.engine.artwork(song)?.let { println("Artwork: ${it.frontUrl}") } }

        jusPlayer.demoAutoAdvance()
    } else {
        println("No search results to demo auto-advance; goodbye.")
    }

    println("Final state: ${jusPlayer.state}")
}
package org.jusplayer.sample

import kotlinx.coroutines.runBlocking
import org.jusplayer.engine.api.JusPlayer
import org.jusplayer.engine.api.createJusPlayer
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.coverartarchive.CoverArtArchiveProvider
import org.jusplayer.engine.provider.coverartarchive.MusicBrainzResolver
import org.jusplayer.engine.provider.lrclib.LRCLIBProvider
import org.jusplayer.engine.provider.newpipe.NewPipeProvider
import java.time.Duration

class ConsolePlayerAdapter : PlayerAdapter {
    override suspend fun play(stream: org.jusplayer.engine.model.Stream) {
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

fun main() = runBlocking {
    val jusPlayer = createJusPlayer {
        provider(NewPipeProvider())
        lyricsProvider(LRCLIBProvider())
        artworkProvider(CoverArtArchiveProvider())
        releaseResolver(MusicBrainzResolver())
        player(ConsolePlayerAdapter())
    }

    println("JusPlayer Engine v1")

    val songs = jusPlayer.engine.search("Daft Punk")
    println("Search results: ${songs.size} songs")

    if (songs.isNotEmpty()) {
        jusPlayer.queue.add(songs.first())
        println("Queue size: ${jusPlayer.queue.size}")

        jusPlayer.queue.next()
        println("Current song: ${jusPlayer.currentSong?.title}")

        jusPlayer.currentSong?.let { song ->
            runCatching {
                jusPlayer.engine.lyrics(song)?.let {
                    println("Lyrics: ${it.text.take(80)}")
                }
            }
            runCatching {
                jusPlayer.engine.artwork(song)?.let {
                    println("Artwork: ${it.frontUrl}")
                }
            }
        }
    }

    println("State: ${jusPlayer.state}")
}
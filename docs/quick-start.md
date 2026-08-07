# Quick Start

This is a complete, working application. Copy it, run it, and you'll have a music
search with playback, lyrics, and cover art wired up.

## Step 1 — dependencies

Add the repo and modules (see [Installation](installation.md)):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven(url = "https://jitpack.io") }
}

// build.gradle.kts
dependencies {
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.3.0")
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-newpipe:1.3.0")
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-lrclib:1.3.0")
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-coverartarchive:1.3.0")
}
```

## Step 2 — a PlayerAdapter

The engine doesn't play audio itself. For this quick start, a console adapter that
just prints what it would do:

```kotlin
import java.time.Duration
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter

class ConsolePlayerAdapter : PlayerAdapter {
    override suspend fun play(stream: Stream) = println("▶  ${stream.url}")
    override fun pause() = println("⏸  paused")
    override fun stop() = println("⏹  stopped")
    override fun seek(position: Duration) = println("⏩  seek $position")
}
```

## Step 3 — wire everything together

```kotlin
import kotlinx.coroutines.runBlocking
import org.jusplayer.engine.api.createJusPlayer
import org.jusplayer.engine.provider.coverartarchive.CoverArtArchiveProvider
import org.jusplayer.engine.provider.coverartarchive.MusicBrainzResolver
import org.jusplayer.engine.provider.lrclib.LRCLIBProvider
import org.jusplayer.engine.provider.newpipe.NewPipeProvider

fun main() = runBlocking {
    val jusPlayer = createJusPlayer {
        provider(NewPipeProvider())                // streams (YouTube)
        lyricsProvider(LRCLIBProvider())           // optional: lyrics
        artworkProvider(CoverArtArchiveProvider()) // optional: cover art
        releaseResolver(MusicBrainzResolver())     // optional: song -> release
        player(ConsolePlayerAdapter())             // required: audio output
    }

    // 1. Search
    val songs = jusPlayer.engine.search("Daft Punk Get Lucky")
    println("Found ${songs.size} songs")
    val song = songs.firstOrNull() ?: error("No results")

    // 2. Play
    jusPlayer.queue.add(song)
    jusPlayer.engine.play(song)
    println("Now playing: ${song.title} by ${song.artists.first().name}")

    // 3. Lyrics (null when none found)
    jusPlayer.engine.lyrics(song)?.let { lyrics ->
        println("Lyrics (${if (lyrics.synced) "synced" else "plain"}):")
        println(lyrics.text.take(120))
    }

    // 4. Artwork (null when the release can't be resolved)
    jusPlayer.engine.artwork(song)?.let { artwork ->
        println("Cover: ${artwork.frontUrl}")
        println("Thumbnails: ${artwork.thumbnails}")
    }
}
```

## Step 4 — observe state

The engine exposes coroutine flows. Start a collector to react to playback:

```kotlin
import kotlinx.coroutines.launch

val job = launch {
    jusPlayer.engine.state.collect { state ->
        println("State: $state")
    }
    jusPlayer.events.events.collect { event ->
        println("Event: $event")
    }
}

// ... play/pause/stop, then cancel the collectors when done:
job.cancel()
```

The event stream emits `SongStarted`, `SongEnded`, `QueueChanged`,
`PlaybackPaused`, `PlaybackResumed`, and `ProviderChanged`.

## That's it

You now have search → queue → play → lyrics → artwork working. Next, read:

- [Providers](providers.md) — what each provider does and its limits
- [PlayerAdapter](player-adapter.md) — real audio output (ExoPlayer, VLC, mpv, ...)
- [Cookbook](cookbook.md) — more copy-paste recipes

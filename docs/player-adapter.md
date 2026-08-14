# PlayerAdapter

JusPlayer does not play audio. It hands a `Stream` (a URL plus metadata) to a
`PlayerAdapter`, and your adapter is responsible for feeding it to a real media
engine (ExoPlayer, VLC, JavaFX, mpv, FFmpeg, ...).

## The interface

```kotlin
package org.jusplayer.engine.playback

interface PlayerAdapter {
    suspend fun play(stream: Stream)
    fun pause()
    fun stop()
    fun seek(position: Duration)

    /** Current playback position, if your media engine can report it (defaults to null). */
    val position: Duration?
        get() = null
}
```

That's the whole contract — four methods plus an optional position report.

### The `Stream` you receive

```kotlin
data class Stream(
    val url: String,
    val format: String,
    val bitrate: Long?,   // bits per second
    val sampleRate: Int?, // Hz, null when unknown
    val isLive: Boolean,
    val duration: Long,
    val codec: String?,
    val mimeType: String?,
)
```

`url` is a direct media URL (e.g. a YouTube audio stream). `isLive` tells you
whether it's a live broadcast (seek behavior differs). `duration` is in seconds.

## A minimal adapter

```kotlin
class ConsolePlayerAdapter : PlayerAdapter {
    override suspend fun play(stream: Stream) = println("Playing ${stream.url}")
    override fun pause() = println("Paused")
    override fun stop() = println("Stopped")
    override fun seek(position: Duration) = println("Seek ${position.toMillis()}ms")
}
```

## Registering it

`player(...)` is **required** — the DSL throws `IllegalStateException` if you omit it:

```kotlin
createJusPlayer {
    provider(NewPipeProvider())
    player(MyPlayerAdapter())
}
```

## Lifecycle you should implement

| Engine call | Adapter call | Your adapter should |
|-------------|--------------|---------------------|
| `engine.play(song)` | `play(stream)` (suspend) | Start decoding/output; the URL comes from the provider |
| `engine.pause()` | `pause()` | Pause but keep the position |
| `engine.resume()` | `play(stream)` (suspend) | Resume from the paused position. The engine re-sends the cached stream when it still belongs to the resumed song; otherwise it re-resolves the stream (e.g. the user paused while an auto-advance was still loading) |
| `engine.stop()` | `stop()` | Release resources, reset position |
| `engine.seek(d)` | `seek(d)` | Seek to the given position |

`pause()` and `resume()` are `suspend` and `resume()` begins a **new playback
session** (a fresh generation), so it can never be mistaken for a continuation of
the paused one.

Because `play` is `suspend`, long setup (prepare, buffer, open the URL) can run
suspending code without blocking the caller.

### The `position` property

Supplying `position` makes `engine.previous()` behave like a real media player:
more than 3 seconds in, `previous()` restarts the track (`seek(0)`); within the
first 3 seconds it goes back one queue item. Without a position, `previous()`
always goes back one.

## Events you should emit

The engine tracks state by *listening to your events* on the `EventBus`. Emitting
`SongEnded` is also what powers **auto-advance**: the engine advances the queue,
starts the next track, and — when the queue is empty — asks autoplay to restock.

```kotlin
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.events.SongStarted

suspend fun notifyStarted(bus: EventBus, song: Song) = bus.emit(SongStarted(song, 0L))
suspend fun notifyEnded(bus: EventBus, song: Song, generation: Long? = null) =
    bus.emit(SongEnded(song, positionMs, generation = generation))
```

`SongEnded` is the **natural-end** signal and must only be emitted when audio
actually reached its end. Manual actions (`stop`, `next`, `previous`, `pause`)
must never emit it — otherwise they'd be mistaken for a natural end and could
accidentally trigger autoplay or advance the wrong track.

The optional `generation` identifies the playback session (read from
`engine.currentPlayback`'s `generation`); attaching it lets the engine ignore
**stale** and **duplicate** completions — e.g. a late `ended` from a track the
user already skipped, or a second `ended` for the same session. Even with a
generation, the `SongEnded`'s song must match the song actually playing — the
generation identifies the session, not the track. When omitted, the engine falls
back to matching by song id only (fine unless the same song is replayed
back-to-back).

## Adapters for real engines

| Engine | Approach |
|--------|----------|
| **ExoPlayer / Media3** | Use `ExoPlayer.Builder(context).build()`, `setMediaItem`/`setMediaSource` with a progressive source, wire `Player.Listener` to emit events |
| **VLCJ** | `MediaPlayerFactory`, set the media URL, map `MediaPlayer.EventListener` to events |
| **JavaFX** | `MediaPlayer` with a `Media(url)` |
| **libmpv / mpv-android** | Set the URL as a playlist entry, poll/callback for ended |
| **FFmpeg (jave/JavaCV)** | Feed the URL, parse duration |

See [Creating a PlayerAdapter](creating-player-adapter.md) for a step-by-step
implementation guide.

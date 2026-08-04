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
}
```

That's the whole contract — four methods.

### The `Stream` you receive

```kotlin
data class Stream(
    val url: String,
    val format: String,
    val bitrate: Int,
    val sampleRate: Int,
    val isLive: Boolean,
    val duration: Long,
)
```

`url` is a direct media URL (e.g. a YouTube audio stream). `isLive` tells you
whether it's a live broadcast (seek behavior differs). `duration` is in
milliseconds.

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
| `engine.stop()` | `stop()` | Release resources, reset position |
| `engine.seek(d)` | `seek(d)` | Seek to the given position |

Because `play` is `suspend`, long setup (prepare, buffer, open the URL) can run
suspending code without blocking the caller.

## Events you should emit

The engine tracks state by *listening to your events* on the `EventBus` — it does
not assume anything. When your adapter actually starts/ends audio, emit:

```kotlin
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.events.SongStarted

suspend fun notifyStarted(bus: EventBus, song: Song) = bus.emit(SongStarted(song, 0L))
suspend fun notifyEnded(bus: EventBus, song: Song) = bus.emit(SongEnded(song, positionMs))
```

This drives `jusPlayer.state` (`Playing` on `SongStarted`, `Ended` on `SongEnded`)
and `jusPlayer.currentSong`. If you never emit, state stays `Idle`/`Paused` —
the engine is manual by design.

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

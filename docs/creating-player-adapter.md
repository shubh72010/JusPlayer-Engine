# Creating a PlayerAdapter

A `PlayerAdapter` turns the engine's `Stream` URLs into actual audio. The engine
side is tiny — four methods. The real work is driving your chosen media library.

## Recap of the contract

```kotlin
interface PlayerAdapter {
    suspend fun play(stream: Stream)   // start playing this URL
    fun pause()                        // pause, keep position
    fun stop()                         // release + reset
    fun seek(position: Duration)       // seek
}
```

## The shape of a real adapter

A good adapter:

1. Holds a reference to your media engine instance.
2. In `play(stream)`, configures it with `stream.url` (and format/live flags) and starts it.
3. In `pause`/`stop`/`seek`, delegates directly.
4. **Emits events** on the `EventBus` so the engine's `state`/`currentSong` stay correct.

## Example: Media3 / ExoPlayer (Android)

```kotlin
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.time.Duration
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.events.SongStarted
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter

class ExoPlayerAdapter(
    private val context: Context,
    private val eventBus: EventBus,
) : PlayerAdapter {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // surface the end so the engine can advance / update state
                    emitEnded()
                }
            }
        })
    }

    override suspend fun play(stream: Stream) {
        player.setMediaItem(MediaItem.fromUri(stream.url))
        player.prepare()
        player.play()
    }

    override fun pause() = player.pause()
    override fun stop() {
        player.stop()
        player.clearMediaItems()
    }
    override fun seek(position: Duration) = player.seekTo(position.toMillis())

    private suspend fun emitStarted(song: Song) = eventBus.emit(SongStarted(song, player.currentPosition))
    private suspend fun emitEnded() = eventBus.emit(SongEnded(currentSong, player.currentPosition))
}
```

In an Android app you'd also register the adapter in the DSL and drive notifications
from `eventBus.events` / `engine.state`.

## Example: JavaFX MediaPlayer

```kotlin
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration as JfxDuration
import java.time.Duration
import org.jusplayer.engine.playback.PlayerAdapter

class JavaFxPlayerAdapter : PlayerAdapter {
    private var player: MediaPlayer? = null

    override suspend fun play(stream: Stream) {
        player?.dispose()
        val mediaPlayer = MediaPlayer(Media(stream.url))
        this.player = mediaPlayer
        mediaPlayer.setOnEndOfMedia { /* emit SongEnded */ }
        mediaPlayer.play()
    }

    override fun pause() = player?.pause()
    override fun stop() {
        player?.stop()
        player?.dispose()
        player = null
    }
    override fun seek(position: Duration) {
        player?.seek(JfxDuration.millis(position.toMillis().toDouble()))
    }
}
```

## Example: VLCJ

```kotlin
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import org.jusplayer.engine.playback.PlayerAdapter

class VlcjPlayerAdapter : PlayerAdapter {
    private val mediaPlayer = EmbeddedMediaPlayerComponent().mediaPlayer()

    override suspend fun play(stream: Stream) {
        mediaPlayer.media().play(stream.url)
    }
    override fun pause() = mediaPlayer.controls().pause()
    override fun stop() {
        mediaPlayer.controls().stop()
        mediaPlayer.media().release()
    }
    override fun seek(position: Duration) =
        mediaPlayer.controls().setTime(position.toMillis())
}
```

## Emitting events (important)

The engine updates `state` and `currentSong` from events you emit, and uses
`SongEnded` as the **natural-end** trigger to auto-advance (or autoplay):

| You emit | Engine does |
|----------|-------------|
| `SongStarted(song, position)` | `state = Playing`, `currentSong = song` |
| `SongEnded(song, position)` | advance queue → play next; autoplay when empty |
| `PlaybackPaused(song, position)` | `state = Paused` |

If you never emit, `jusPlayer.state` stays `Idle`. Emit `SongStarted` from
`play()`'s success path and `SongEnded` from your engine's "ended" callback.
`SongEnded` must only fire when audio really reached its end — manual operations
(`stop`, `next`, `previous`) must never emit it, or they'd be mistaken for a
natural end.

## Checklist

- [ ] `play` handles `stream.isLive` (don't seek a live stream)
- [ ] `stop` releases resources
- [ ] `play` is `suspend` — long prepare/buffer work is fine there
- [ ] You emit `SongStarted`/`SongEnded` to the `EventBus`
- [ ] `seek` guards against calls before media is prepared

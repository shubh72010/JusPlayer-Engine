# Cookbook

Real-world, copy-paste recipes. Each one is self-contained; import lines assume the
[Quick Start](quick-start.md) setup (a `jusPlayer` instance with all providers wired).

## Search for a song

```kotlin
val songs = jusPlayer.engine.search("Daft Punk Get Lucky")
songs.forEach { println("${it.title} — ${it.artists.joinToString { a -> a.name }}") }
```

`search` returns `List<Song>` (the `SearchResult` also carries artists/albums/playlists
if you need them).

## Play the first result

```kotlin
val song = jusPlayer.engine.search("M83 Midnight City").firstOrNull() ?: return
jusPlayer.queue.add(song)        // put it in the queue
jusPlayer.engine.play(song)      // resolve stream + start via your PlayerAdapter
```

`engine.play(song)` fetches the stream URL through the provider and calls
`playerAdapter.play(stream)`. If your adapter is a no-op, nothing audible happens —
use it to verify wiring first.

## Play the next queued song

```kotlin
val next = jusPlayer.queue.next()      // advances the cursor
if (next != null) jusPlayer.engine.play(next)
```

`queue.next()` advances but does **not** auto-play — call `engine.play()` yourself.

## Fetch synced lyrics

```kotlin
val lyrics = jusPlayer.engine.lyrics(song)
if (lyrics != null && lyrics.synced) {
    // LRC timestamped lines look like: [00:12.34] Get lucky
    lyrics.text.lineSequence().forEach { line -> println(line) }
} else {
    println("No synced lyrics (${lyrics?.let { "plain only" } ?: "none at all"})")
}
```

## Download artwork bytes

```kotlin
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

val artwork = jusPlayer.engine.artwork(song) ?: error("no artwork")
val targetSize = artwork.thumbnails["500"] ?: artwork.frontUrl
val bytes = URI(targetSize).toURL().openStream().use { it.readBytes() }
Files.write(Path.of("cover-${song.id}.jpg"), bytes)
```

`Artwork` holds **URLs**, not bytes — downloading is your job (this uses plain JDK;
in an app use an image loader).

## Display album art in Jetpack Compose

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import coil.compose.AsyncImage

@Composable
fun ArtworkThumb(song: Song, engine: JusPlayerEngine) {
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(song.id) {
        url = engine.artwork(song)?.thumbnails?.get("500")
            ?: engine.artwork(song)?.frontUrl
    }
    val u = url
    if (u != null) AsyncImage(model = u, contentDescription = "cover") else Placeholder()
}
```

`engine.artwork` is a suspend call — run it in `LaunchedEffect`, not on the
composition thread.

## Cache metadata locally

The engine doesn't cache for you (the NewPipe provider keeps a small in-memory
cache internally). For persistent caching, key on `song.id`:

```kotlin
val cacheDir = Path.of("data/songs")
fun cachedSong(id: String): Song? =
    cacheDir.resolve("$id.json").takeIf { Files.exists(it) }
        ?.let { Json.decodeFromString<Song>(it.readText()) }

suspend fun getSongCached(id: String): Song =
    cachedSong(id) ?: jusPlayer.engine.run {
        provider.getSong(id).also {
            Files.write(cacheDir.resolve("$id.json"), Json.encodeToString(it).toByteArray())
        }
    }
```

All models are `@Serializable`, so `Json.encodeToString(...)` just works.

## Run the HTTP server

```bash
JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew :engine-http:run
# then:
curl "http://localhost:8368/v1/search?q=daft%20punk"
curl "http://localhost:8368/v1/stream/<songId>"
curl "http://localhost:8368/v1/lyrics/<songId>"
curl "http://localhost:8368/v1/artwork/<songId>"
```

Custom port: `--args=9000` or `JUS_ENGINE_PORT=9000`. Full API in
[HTTP Server](http-server.md).

## Embed in a desktop app (Ktor + Swing example sketch)

```kotlin
fun main() = runBlocking {
    val jusPlayer = createJusPlayer {
        provider(NewPipeProvider())
        lyricsProvider(LRCLIBProvider())
        player(JavaFxPlayerAdapter())
    }
    // launch a Swing/JavaFX window; wire buttons to:
    //   jusPlayer.queue.add(song); jusPlayer.engine.play(song)
    //   jusPlayer.engine.pause(); jusPlayer.engine.seek(Duration.ofSeconds(30))
    // collect state:
    launch { jusPlayer.engine.state.collect { s -> render(s) } }
}
```

## Use the engine from a Flutter app via HTTP

Run the HTTP server, then call the JSON endpoints from Dart:

```dart
final songs = await http.get(Uri.parse('http://host:8368/v1/search?q=daft punk'));
// play the stream URL yourself with just_audio/audio_service:
final stream = await http.get(Uri.parse('http://host:8368/v1/stream/$id'));
```

The server's player is a no-op — **your** client plays the stream URL it returns.

## Switch music providers

The DSL registers one `MusicProvider`. To support interchangeable backends at
runtime, either:

1. Build a forwarding provider:

```kotlin
class SwitchingProvider(var current: MusicProvider) : MusicProvider {
    override val name get() = current.name
    override val capabilities get() = current.capabilities
    override suspend fun search(q: String) = current.search(q)
    override suspend fun getSong(id: String) = current.getSong(id)
    override suspend fun getStream(id: String) = current.getStream(id)
}
```

2. Or create one `JusPlayer` per provider and swap which one your UI talks to.

## Listen for the current song

```kotlin
val job = launch {
    jusPlayer.engine.currentSong.collect { song ->
        println("Now showing: ${song?.title ?: "nothing"}")
    }
}
```

`currentSong` is a `StateFlow<Song?>`; it's set when a song starts and cleared on
`SongEnded`.

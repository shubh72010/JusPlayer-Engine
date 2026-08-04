# Creating a Provider

JusPlayer is designed for community providers. Implementing one is small — the
interfaces are a few methods each — and the engine never cares which backend you
plug in.

There are four contracts. You implement whichever make sense for your backend:

| Interface | Methods | You produce |
|-----------|---------|-------------|
| `MusicProvider` | `search`, `getSong`, `getStream` | `SearchResult`, `Song`, `Stream` |
| `LyricsProvider` | `getLyrics(song)` | `Lyrics?` |
| `ArtworkProvider` | `getArtwork(releaseMbid)` | `Artwork?` |
| `ReleaseResolver` | `resolveReleaseMbid(song)` | `String?` (an MBID) |

All return types are the engine's own `@Serializable` models from `engine-model` —
never a third-party type.

## The rules

1. **Never let raw exceptions escape.** Wrap every operation and throw
   `ProviderException` subtypes (`NotFound`, `Network`, `RateLimited`,
   `ExtractionFailed`, `Unsupported`).
2. **Return `null` for "not found"** on optional providers (lyrics/artwork/release).
3. **Declare `capabilities`** on a `MusicProvider` so UIs can adapt.
4. **Work off the models, not ids** where the data is metadata-based (lyrics,
   artwork, release resolution) — the engine passes you the full `Song`.
5. For HTTP APIs, reuse `HttpTransport`/`JdkHttpTransport` from
   `engine-provider-api` — it follows redirects (needed for Cover Art Archive's
   `307`) and lets tests inject a fake.

## A `MusicProvider` (search + stream)

```kotlin
package com.example.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jusplayer.engine.model.*
import org.jusplayer.engine.provider.*

class ExampleMusicProvider(
    private val transport: HttpTransport = JdkHttpTransport(),
) : MusicProvider {

    override val name = "Example"
    override val capabilities = ProviderCapabilities(
        search = true, getSong = true, getStream = true,
    )

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val response = request("/search?q=${query.encode()}") {
            HttpTransport.Response -> // JSON body
        }
        // parse JSON with kotlinx-serialization into engine models
        SearchResult(songs = parsed, artists = emptyList(), albums = emptyList(), playlists = emptyList())
    }

    override suspend fun getSong(id: String): Song = withContext(Dispatchers.IO) { /* ... */ }

    override suspend fun getStream(songId: String): Stream = withContext(Dispatchers.IO) { /* ... */ }

    // ---- error mapping ----
    private inline fun <T> runExtraction(operation: String, block: () -> T): T =
        try {
            block()
        } catch (e: java.io.IOException) {
            throw ProviderException.Network(operation, e)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ProviderException.ExtractionFailed(operation, e)
        }
}
```

### Error mapping cheat sheet

| Situation | Throw |
|-----------|-------|
| HTTP 404 / removed content | `ProviderException.NotFound` |
| Socket/timeout/IO | `ProviderException.Network` |
| HTTP 429 / reCAPTCHA / throttling | `ProviderException.RateLimited` |
| Bad JSON / parse failure | `ProviderException.ExtractionFailed` |
| Feature not implemented | `ProviderException.Unsupported` |

## A `LyricsProvider`

```kotlin
class MyLyricsProvider : LyricsProvider {
    override val name = "MyLyrics"

    override suspend fun getLyrics(song: Song): Lyrics? {
        val artist = song.artists.firstOrNull()?.name ?: return null
        if (song.title.isBlank() || artist.isBlank()) return null
        // ... fetch + parse ...
        return Lyrics(text = "...", source = "MyLyrics", synced = false)
    }
}
```

- Use `song.title` / `song.artists` / `song.album?.title` / `song.duration` to
  match — the engine passes the whole `Song` precisely because lyrics are
  metadata-matched, not id-matched.
- Return `null` when nothing matches (don't throw `NotFound` for "no result" —
  the engine treats `null` as "unavailable", which is graceful).

## An `ArtworkProvider` + `ReleaseResolver`

Artwork is keyed by MusicBrainz release MBID, so a resolver bridges a streaming
`Song` to a release first:

```kotlin
class MyReleaseResolver : ReleaseResolver {
    override val name = "MyResolver"
    override suspend fun resolveReleaseMbid(song: Song): String? = /* search -> MBID or null */
}

class MyArtworkProvider : ArtworkProvider {
    override val name = "MyArtwork"
    override suspend fun getArtwork(releaseMbid: String): Artwork? = /* fetch images -> Artwork? */
}
```

`Artwork` is URLs, not bytes:

```kotlin
Artwork(
    frontUrl = "https://.../front-500.jpg",
    backUrl = null,
    thumbnails = mapOf("250" to "https://.../front-250.jpg"),
    sourceMbid = releaseMbid,
)
```

## Registering your provider

```kotlin
createJusPlayer {
    provider(ExampleMusicProvider())   // a MusicProvider
    lyricsProvider(MyLyricsProvider()) // optional
    artworkProvider(MyArtworkProvider())
    releaseResolver(MyReleaseResolver())
    player(MyPlayerAdapter())
}
```

## Testing

Inject a fake `HttpTransport` through the primary constructor (the bundled
providers do exactly this — their public no-arg constructor wires `JdkHttpTransport`,
and tests pass a fake). Keep tests offline.

```kotlin
class ExampleMusicProviderTest {
    @Test
    fun `maps network error`() = runBlocking {
        val fake = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) =
                HttpTransport.Response(status = 503, body = "")
        }
        val provider = ExampleMusicProvider(transport = fake)
        val result = assertFailsWith<ProviderException.RateLimited> {
            provider.getStream("abc")
        }
        // bind to a val (JUnit4 rejects non-void runBlocking test methods)
    }
}
```

## Ship it

1. Add your module or class to the repo (a provider depends on
   `engine-provider-api` and `engine-model`).
2. Open a PR. Community providers are welcome.

# Providers

Providers plug the engine into real services. There are four roles, each with its
own interface — a backend does not need to provide all of them.

| Role | Interface | Owns |
|------|-----------|------|
| Music | `MusicProvider` | search, `getSong`, `getStream` |
| Lyrics | `LyricsProvider` | synced + plain lyrics for a `Song` |
| Artwork | `ArtworkProvider` | cover art for a MusicBrainz release |
| Direct artwork | `SongArtworkProvider` | artwork resolved from the `Song` itself (no MBID) |
| Release resolution | `ReleaseResolver` | `Song` → MusicBrainz release MBID |

The DSL registers them like this:

```kotlin
createJusPlayer {
    provider(NewPipeProvider())
    lyricsProvider(LRCLIBProvider())
    artworkProvider(CoverArtArchiveProvider())
    releaseResolver(MusicBrainzResolver())
    player(MyPlayerAdapter())
}
```

Only `provider(...)` and `player(...)` matter for the engine to run; the rest are
optional. `engine.lyrics(song)` returns `null` when no `LyricsProvider` is
registered. `engine.artwork(song)` returns `null` unless an `ArtworkProvider` is
registered — a `SongArtworkProvider` (e.g. Apple Music canvas) resolves from the
song directly, while a plain `ArtworkProvider` (e.g. Cover Art Archive) needs a
`ReleaseResolver` to map the song to an MBID first.

---

## NewPipe — `engine-provider-newpipe`

A `MusicProvider` for **YouTube** built on NewPipeExtractor.

```kotlin
val provider = NewPipeProvider()   // no args — uses the JVM downloader
```

- `capabilities`: search ✓, getSong ✓, getStream ✓ (no playlists/recommendations)
- Everything extractor-specific is confined to this module. You only ever see
  `engine-model` types and `ProviderException`.
- Uses a browser-like `User-Agent` (YouTube rejects requests without one).
- **Retries** transient "page needs to be reloaded" failures automatically (3 attempts).
- Results are cached in memory (`ProviderCache`).
- Errors map to: reCAPTCHA → `RateLimited`, removed video → `NotFound`, IO →
  `Network`, parse failures → `ExtractionFailed`.

**Limits:** no lyrics (use LRCLIB), no playlists/recommendations yet. YouTube can
rate-limit aggressively from datacenter IPs — treat it as a playground backend.

---

## LRCLIB — `engine-provider-lrclib`

A `LyricsProvider` for synced + plain lyrics from [lrclib.net](https://lrclib.net).

```kotlin
val lyricsProvider = LRCLIBProvider()
// or throttle differently:
val lyricsProvider = LRCLIBProvider(throttleMillis = 500)
```

- Matches by **track name + artist name**, optionally album name, and duration
  (from `Song`). The `Song` is passed whole because matching uses metadata, not
  the streaming id.
- Returns `Lyrics(text, source = "LRCLIB", synced = ...)`. `synced` is true when
  LRC timestamped lyrics exist; `text` falls back to plain lyrics.
- Returns `null` for instrumentals or when nothing matches.
- **Throttles** requests (~300ms between calls, configurable) per LRCLIB policy,
  and maps `429` to `ProviderException.RateLimited` with the server's `Retry-After`.
- Requires a client `User-Agent` (the default `JdkHttpTransport` provides one).

---

## Cover Art Archive — `engine-provider-coverartarchive`

Two classes: an `ArtworkProvider` (Cover Art Archive) and a `ReleaseResolver`
(MusicBrainz).

```kotlin
val artworkProvider = CoverArtArchiveProvider()
val releaseResolver = MusicBrainzResolver()
```

Cover Art Archive (and MusicBrainz) are keyed by **MBIDs**, not streaming ids. The
engine therefore composes:

```
Song ──MusicBrainzResolver──▶ release MBID ──CoverArtArchiveProvider──▶ Artwork
```

- `MusicBrainzResolver.resolveReleaseMbid(song)` searches the MusicBrainz recording
  API by title + artist and returns the first release MBID (or `null`).
- `CoverArtArchiveProvider.getArtwork(mbid)` fetches the release's image listing
  and returns `Artwork(frontUrl, backUrl, thumbnails, sourceMbid)`.
- URLs only — the engine keeps image URLs, not image bytes. You download them.
- Maps Cover Art Archive `503` to `RateLimited`. Redirects (`307`) are followed
  automatically by the transport.

**Note:** MusicBrainz requires a client `User-Agent` and asks for ~1 req/s;
be polite in production.

---

## Lyrics providers — `engine-provider-*`

Six additional `LyricsProvider`s are bundled. All match by `Song` metadata, return
`Lyrics` (with `lines`/`words` when the source carries timings), and map HTTP
failures to `ProviderException` (`404` → `NotFound`, `429` → `RateLimited`).

```kotlin
val lyricsProvider = KuGouProvider()
// or SimpMusicProvider(), PaxsenixProvider(), BetterLyricsProvider(),
//     UnisonProvider(), YouLyPlusProvider()
```

- **KuGou** (`engine-provider-kugou`) — KuGou's lyric API, matched by title +
  artist + duration.
- **SimpMusic** (`engine-provider-simpmusic`) — keys off the streaming **video
  id** (`song.id`), ideal for YouTube-sourced songs; single endpoint, no
  metadata matching.
- **Paxsenix** (`engine-provider-paxsenix`) — a fallback chain across Apple →
  NetEase → Spotify → Musixmatch; picks the best-synced match and prefers klyric
  over LRC. Returns `null` when every upstream fails.
- **BetterLyrics** (`engine-provider-betterlyrics`) — Apple Music's TTML lyrics
  API with a full TTML parser (frame-rate aware, translation/transliteration,
  word-level timings via `toEngineLines()`).
- **Unison** (`engine-provider-unison`) — video-id fast path → metadata lookup →
  search materialization (max 5 candidates).
- **YouLyPlus** (`engine-provider-youlyplus`) — YouLyPlus mirrors with failover
  (Binimum → Prjktla), parsing TTML → LRC → plain text; karaoke syllabi become
  word-level timings.

**Limits:** availability of each upstream varies by region; KuGou/Paxsenix hit
East-Asian services, BetterLyrics/YouLyPlus are Apple-music-oriented. Prefer
several providers and try them in order if you need broad coverage.

---

## Apple Music Canvas — `engine-provider-canvas`

An animated-artwork provider.

```kotlin
val artworkProvider = CanvasArtworkProvider()
```

- Implements **`SongArtworkProvider`**, so it needs **no `ReleaseResolver`** —
  `engine.artwork(song)` resolves straight from the song's title + artist.
- Tries `CanvasService` (Archivetune mirrors) with an Apple Music Canvas fallback
  (Apple Music API, public JWT).
- Returns `Artwork` with `frontUrl` pointing at the animated `m3u8` (HLS) and the
  new `verticalUrl` for portrait clips; static art is available in `thumbnails`.
- Responses are cached in memory (~60s TTL). Non-2xx mirrors are skipped, `429`
  maps to `RateLimited`, nothing matched → `null`.

---

## Last.fm — `engine-provider-lastfm`

Scrobbling is not a provider role — this module exposes `LastFMClient` and
`ScrobbleManager` directly.

```kotlin
val client = LastFMClient(apiKey, apiSecret)          // optional 4th arg: endpoint
val manager = ScrobbleManager(scope, client)          // or a custom ScrobbleListener
manager.onSongStart(track)                            // now-playing + start the timer
manager.onPlayerStateChanged(isPlaying, track)        // pause/resume-aware
```

- `LastFMClient` — token/session auth, MD5 `api_sig` signing, `updateNowPlaying`
  and `scrobble` (`artist[0]`/`track[0]`/`timestamp[0]`), works with **Libre.fm**
  via a custom endpoint. Throws `LastFmException` on API errors.
- `ScrobbleManager` — scrobbles after **50%** of the track (capped at 180s),
  never before 30s, with the `songStartedAt` timestamp. Pausing pauses the timer;
  seek-forward refreshes it.

---

## Capabilities

`MusicProvider.capabilities` advertises what it can do:

```kotlin
data class ProviderCapabilities(
    val search: Boolean = true,
    val getSong: Boolean = true,
    val getStream: Boolean = true,
    val playlists: Boolean = false,
    val recommendations: Boolean = false,
)
```

The engine (or your UI) can inspect this to degrade gracefully — e.g. hide a
"playlists" tab when `playlists` is false. `ProviderCapabilities.NONE` and
`ProviderCapabilities.FULL` are provided for convenience.

---

## Error model

Providers must **never** let raw exceptions escape. Translate everything into
`ProviderException` subtypes:

| Type | When |
|------|------|
| `NotFound` | 404, removed/private content |
| `Network` | IO failures, timeouts |
| `RateLimited` | 429, reCAPTCHA, 503-ish throttling |
| `ExtractionFailed` | parse/extractor failures |
| `Unsupported` | provider can't do the requested operation |

## Adding your own

Providers are ~20 lines of interface to implement. See
[Creating a Provider](creating-provider.md) for a full walkthrough.

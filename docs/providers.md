# Providers

Providers plug the engine into real services. There are four roles, each with its
own interface — a backend does not need to provide all of them.

| Role | Interface | Owns |
|------|-----------|------|
| Music | `MusicProvider` | search, `getSong`, `getStream` |
| Lyrics | `LyricsProvider` | synced + plain lyrics for a `Song` |
| Artwork | `ArtworkProvider` | cover art for a MusicBrainz release |
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
registered; `engine.artwork(song)` returns `null` unless **both** a
`ReleaseResolver` and an `ArtworkProvider` are registered.

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

# Architecture

JusPlayer separates *where music comes from*, *where audio goes*, and *the engine
that coordinates them*. This is why it has no Android dependencies and can power a
CLI, a desktop app, a server, or an Android app.

## Big picture

```
             Your app
  (UI, notifications, platform integration)
             │
             ▼
       PlayerAdapter ──────▶ audio device (ExoPlayer, VLC, mpv, ...)
             │  play/pause/stop/seek(Stream)
             ▼
      JusPlayer Engine            ◀──── the public DSL object
      (services, state, queue)
             │   search / getSong / getStream
             ▼
        MusicProvider  ────▶  NewPipe  ────▶  YouTube
             │
        LyricsProvider  ────▶  LRCLIB
             │
        ArtworkProvider ◀───  ReleaseResolver (MusicBrainz)
             │
             ▼
           Internet
```

## The pieces

### `engine-api` — the DSL facade

The only thing you need to import. `createJusPlayer { ... }` builds a `JusPlayer`
holding three things:

| Member | Type | What it is |
|--------|------|------------|
| `jusPlayer.engine` | `JusPlayerEngine` | Search, play, lyrics, artwork, state |
| `jusPlayer.queue` | `QueueEngine` | The play queue |
| `jusPlayer.events` | `EventBus` | The event stream |
| `jusPlayer.state` | `PlaybackState` | Current playback state |
| `jusPlayer.currentSong` | `Song?` | Song currently "in focus" |

### `engine-model` — the data

`@Serializable` models shared by every module:

- `Song(id, title, artists, album, duration, thumbnailUrl, releaseDate)`
- `Artist`, `Album`, `Playlist`
- `Stream(url, format, bitrate, sampleRate, isLive, duration, codec, mimeType)`
- `Lyrics(text, source, synced)`
- `Artwork(frontUrl, backUrl, thumbnails, sourceMbid, source, width, height)`
- `SearchResult(songs, artists, albums, playlists)`
- `PlaybackState` — `Idle`, `Playing`, `Paused`, `Buffering`, `Ended`, `Error`

`Song`/`Artist`/`Album` equality is by `id`, so the same track stays "the same"
even if you re-fetch it.

### `engine-provider-api` — the contracts

Four small interfaces plus the error model:

- `MusicProvider` — `search`, `getSong`, `getStream`, and `capabilities`
- `LyricsProvider` — `getLyrics(song): Lyrics?`
- `ArtworkProvider` — `getArtwork(releaseMbid): Artwork?`
- `ReleaseResolver` — `resolveReleaseMbid(song): String?`
- `ProviderException` — sealed: `NotFound`, `Network`, `RateLimited`,
  `ExtractionFailed`, `Unsupported`

Providers must translate raw HTTP/extractor errors into `ProviderException`
subtypes — your app never sees a YouTube/NewPipe exception.

### `engine-playback-api` — the audio contract

```kotlin
interface PlayerAdapter {
    suspend fun play(stream: Stream)
    fun pause()
    fun stop()
    fun seek(position: Duration)
}
```

That's the *entire* audio contract. See [PlayerAdapter](player-adapter.md).

### `engine-core` — the wiring

`JusPlayerEngine` holds `SearchService`, `PlaybackService`, `QueueService`,
`LyricsService`, and `ArtworkService`. It listens for `SongStarted`/`SongEnded` on
the event bus and updates `StateFlow`s. Playback is **manual** — the engine does
not self-drive transitions; you (or your app) decide what plays next.

### `engine-queue` — the queue

A simple ordered list with `add`, `addAll`, `remove`, `move`, `shuffle`, `next`,
`previous`, `repeat`, `clear`, `reset`. `next()` advances the cursor but does *not*
auto-play — call `engine.play()` afterwards.

### The providers

- **`engine-provider-newpipe`** — the only module importing NewPipeExtractor types.
  Mapper objects (`SongMapper`, `StreamMapper`, `SearchResultMapper`, ...) convert
  extractor results into `engine-model` types. Swap the extractor by editing one
  module.
- **`engine-provider-lrclib`** — `LyricsProvider` over the LRCLIB API. Matches on
  song metadata, throttles requests, surfaces `429` as `RateLimited`.
- **`engine-provider-coverartarchive`** — `ArtworkProvider` over Cover Art Archive
  plus a `MusicBrainzResolver` (`ReleaseResolver`). Artwork is keyed by MBID, which
  is why the resolver must run first.

## Dependency flow (bottom-up)

```
engine-utils ─────────▶ engine-model
engine-model ─────┐
engine-events ────┤
engine-playback-api ─┼──▶ engine-core
engine-provider-api ─┘
engine-queue ────────┘
engine-provider-api ──▶ engine-provider-newpipe
engine-provider-api ──▶ engine-provider-lrclib
engine-provider-api ──▶ engine-provider-coverartarchive
engine-api (DSL facade) ◀─ uses everything above
engine-http / sample-console ─▶ engine-api + every provider
```

## Why lyrics/artwork are separate providers

Lyrics match on song *metadata* (`Song`), artwork matches on a MusicBrainz *MBID*
— neither depends on the streaming backend. So a YouTube song can get LRCLIB lyrics
and Cover Art Archive artwork, and if the backend has no lyrics/art you simply
don't register those providers and `engine.lyrics(...)`/`engine.artwork(...)`
return `null`. Feature degradation instead of crashes.

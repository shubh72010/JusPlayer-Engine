# Getting Started

> **Read this first.** It explains what JusPlayer is, what it does for you, and
> what it deliberately does *not* do.

## What is JusPlayer?

JusPlayer Engine is a **modular, coroutine-first music engine for the JVM**. It
separates the hard parts of a music app — talking to streaming services, matching
lyrics, resolving cover art, managing a queue — into pluggable modules with one
small public API.

It is inspired by [NewPipe](https://github.com/TeamNewPipe/NewPipe)'s architecture:
the engine drives *providers* and a *player adapter*, and never knows which ones
you picked.

## How does it work?

You describe your stack in one DSL call:

```kotlin
val jusPlayer = createJusPlayer {
    provider(NewPipeProvider())                // where music comes from
    lyricsProvider(LRCLIBProvider())           // where lyrics come from
    artworkProvider(CoverArtArchiveProvider()) // where cover art comes from
    releaseResolver(MusicBrainzResolver())     // song -> MusicBrainz release
    player(MyPlayerAdapter())                  // where audio actually plays
}
```

The engine then gives you:

- `engine.search("...")` — find songs
- `engine.play(song)` / `pause()` / `stop()` / `seek(...)` — drive playback
- `engine.lyrics(song)` — fetch lyrics (if a lyrics provider is registered)
- `engine.artwork(song)` — fetch cover art (if resolver + artwork provider are registered)
- `queue` — add, move, shuffle, next/previous
- `state` / `currentSong` — observable coroutine flows
- `events` — a `SharedFlow` of `SongStarted`, `SongEnded`, `QueueChanged`, ...

## What does the engine do?

- **Providers** — `search`, `getSong`, `getStream` on a `MusicProvider`; lyrics on
  a `LyricsProvider`; artwork on an `ArtworkProvider`.
- **Metadata & stream URLs** — never audio bytes; you get clean, serializable models.
- **Lyrics** — matched from song metadata (title/artist/album/duration), not streaming IDs.
- **Artwork** — resolved from a `Song` → MusicBrainz release → Cover Art Archive URLs.
- **Queue** — a small `QueueEngine` for ordered playback.
- **Playback engine** — calls your `PlayerAdapter` and tracks state via events.

## What doesn't the engine do?

- ❌ **It is not a media player.** It will not decode or output audio.
- ❌ **It has no default player.** You *must* supply a `PlayerAdapter` or the DSL
  throws `IllegalStateException` at build time.
- ❌ **No UI.** No Compose, no Swing, no notification code.
- ❌ **No platform integration.** No Android services, audio focus, or hardware.

You provide those. That split is the whole point — one engine, many hosts.

## Core concepts

| Concept | Meaning |
|---------|---------|
| `MusicProvider` | Search + song + stream URLs for one backend (e.g. YouTube) |
| `LyricsProvider` | Returns `Lyrics` for a `Song`, or `null` |
| `ArtworkProvider` | Returns `Artwork` (URLs) for a MusicBrainz release MBID |
| `ReleaseResolver` | Bridges a streaming `Song` to a MusicBrainz release MBID |
| `PlayerAdapter` | The audio output — `play`, `pause`, `stop`, `seek` |
| `QueueEngine` | Ordered list of songs to play |
| `ProviderException` | Sealed error model — you never see raw extractor errors |
| `EventBus` | `SharedFlow` of playback/queue events |

## Next steps

1. [Installation](installation.md) — add the modules to your build.
2. [Quick Start](quick-start.md) — a complete working app in a few minutes.
3. [Architecture](architecture.md) — how the modules relate.

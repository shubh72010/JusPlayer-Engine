# FAQ

## Why doesn't JusPlayer play music by itself?

It's an **engine**, not a media player. Audio output is platform-specific
(ExoPlayer, VLC, JavaFX, hardware...) and coupling one into a JVM engine would tie
it to a platform and limit the hosts it can power. The engine hands a `Stream` URL
to your `PlayerAdapter`; you decide how audio is produced.

## Why do I need a PlayerAdapter?

Because the DSL has no default. The engine must know how to *play*, and "play"
has no universal JVM answer. `createJusPlayer { ... }` without `player(...)`
throws `IllegalStateException` at build time — a loud error instead of silent
broken playback. A console adapter that just prints is enough to start.

## Why are `lyrics(song)` / `artwork(song)` returning null?

`null` means "not available", and that's graceful by design. Possible reasons:

- No `LyricsProvider` (or `ArtworkProvider`) was registered. A `SongArtworkProvider`
  (e.g. Canvas) resolves from the song directly; a plain `ArtworkProvider`
  (e.g. Cover Art Archive) additionally needs a `ReleaseResolver`.
- The provider found no match (lyrics use title/artist matching; artwork needs a
  MusicBrainz release to resolve — titles with featured artists, covers, or typos
  often don't match).
- The song metadata is blank (empty title or no artist → the provider returns
  `null` immediately).
- The release genuinely has no cover in Cover Art Archive.

Distinguish "no provider" from "no match" by checking your wiring, then the
service. `artwork` additionally requires the resolver to succeed first — if
`resolveReleaseMbid` returns null, no artwork request is made.

## Can I use multiple providers?

The DSL currently takes **one** `MusicProvider`. You can, however:

- Register a `LyricsProvider` and `ArtworkProvider`/`ReleaseResolver` from
  *different* backends than the music provider — that's encouraged.
- Run multiple `JusPlayer` instances (one per provider) if you need parallel
  backends; or build a `MusicProvider` that composites/forwards to others.

## Can I make my own provider?

Yes — that's a headline feature. See [Creating a Provider](creating-provider.md).
Implement one of `MusicProvider`, `LyricsProvider`, `ArtworkProvider`, or
`ReleaseResolver`, translate errors into `ProviderException`, and register it.
Community providers are welcome.

## How do updates work?

Versions are git tags published through JitPack. Bump the version string in your
`build.gradle.kts` (e.g. `1.2.0` → `1.3.0`), sync, done. See [Updating](updating.md)
and [Migration](migration.md).

## Why JitPack and not Maven Central?

JitPack needs zero account/setup and builds straight from git tags — perfect for a
young project that releases often. Maven Central publishing (with signing + POM
metadata) is on the roadmap; the modules already publish as Maven artifacts, so
the move is mostly ceremony.

## Why isn't this on Maven Central yet?

See above — it's planned, not blocked. The publishing infrastructure exists; the
central-specific requirements (signing keys, POM metadata, coordinate decision)
just haven't been done yet.

## Do I need Android?

No. Everything runs on a plain JVM (JDK 11+; the build uses JDK 21). Use it in a
CLI, a Ktor server, a desktop app, or an Android app. There are no Android
dependencies.

## Can I stream live YouTube?

The NewPipe provider maps live streams (see the `isLive` flag on `Stream`), but
actual playback depends on your `PlayerAdapter` handling a live URL (no seeking,
continuous output). The engine passes `isLive` through so your adapter can adapt.

## Why can't I see lyrics from YouTube?

The NewPipe `MusicProvider` is streams-only. Lyrics are a separate concern handled
by `LyricsProvider` (the bundled one uses LRCLIB), which matches on song metadata —
that's why a YouTube song can still get LRCLIB lyrics.

## How do providers isolate me from NewPipeExtractor?

`engine-provider-newpipe` is the only module importing extractor types. Its public
API exposes only `@Serializable` engine models and `ProviderException`. The `Mapper`
objects are the single conversion point — swapping the extractor means editing one
module, and your app code never changes.

## What is a "release MBID" and why do I need a resolver for artwork?

MusicBrainz identifies releases by UUID ("MBID"). Cover Art Archive indexes images
by those MBIDs, not by streaming-service ids. So a `ReleaseResolver` maps your
`Song` (which has a YouTube id) to a MusicBrainz release MBID, and then the
`ArtworkProvider` can fetch cover art. Both must be registered for
`engine.artwork(song)` to return anything with MBID-keyed artwork. A
`SongArtworkProvider` (e.g. `CanvasArtworkProvider`) skips this — it keys off the
song itself, so no resolver is needed.

## Is playback gapless / auto-advancing?

Auto-advance is built in. When your player (or adapter) reaches the natural end of
a track, emit `SongEnded` on the event bus and the engine advances the queue,
starts the next track, and — when the queue is exhausted under `RepeatMode.NONE` —
hands over to autoplay. Manual operations (`stop`, `next`, `previous`, `pause`)
never look like a natural end, so they can't accidentally trigger autoplay.
Gapless playback still depends on your `PlayerAdapter` (ExoPlayer gapless, for
example, needs seamless transitions from the adapter itself).

## Which JDK do I need?

To *use* the library: JDK 11+ at runtime. To *build* the repo: JDK 21 (the Gradle
build requires it — the default JDK 25 fails with a cryptic daemon error).

## Can I display a `Song` in Jetpack Compose?

Yes. `Song`, `Artist`, `Album`, `Stream`, `Lyrics`, and `Artwork` are all
`@Serializable` and plain data classes — no Android types anywhere. See the
[Cookbook](cookbook.md) for a Compose snippet.

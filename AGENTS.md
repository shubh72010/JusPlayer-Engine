# AGENTS.md

Multi-module Kotlin/JVM Gradle project (`com.github.shubh72010.JusPlayer-Engine`
group — the JitPack-derived coordinate). Coroutine-first music
playback engine with pluggable providers; **no Android dependencies**. The README is
authoritative and detailed — read it before changing behavior.

## Build commands

- **JDK 21 is required.** The default `java` on this machine is JDK 25, which fails the
  Gradle/Kotlin daemon with a cryptic `25.0.4` error. Always prefix Gradle with
  `JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk` (or run with a JDK 11–21 on PATH):
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew build      # compile + test all modules
  JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew test
  JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew :engine-queue:test   # single module
  JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew :sample-console:run  # demo app
  JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew :engine-http:run     # REST server, port 8368; serves a browser demo at / and auto-opens it
  # server port: --args=9000 or env JUS_ENGINE_PORT
  ```
- **Versioning/publishing:** the version lives in **one place** — `version=` in
  `gradle.properties`. To release: bump it there, then `git tag v<version>` and push.
  README/docs code samples may pin an older version literal (e.g. README installs at
  `1.3.0` while `gradle.properties` is `1.5.1`) — don't "fix" those when bumping; the
  only source of truth is `gradle.properties`.
  `./gradlew publishToMavenLocal` publishes every module as
  `com.github.shubh72010.JusPlayer-Engine:<module>:<version>` — the group MUST be
  the `com.github.<owner>.<repo>` form or JitPack won't harvest submodules (it only
  renames the root).
  `jitpack.yml` makes JitPack builds work: it pre-builds NewPipeExtractor into
  `mavenLocal` (JitPack's clean environment has none) and pins JDK 21, then runs
  `publishToMavenLocal`. The extractor pre-build runs in a subshell so its `cd`
  doesn't leak into the `install` step.
- Tests use `kotlin.test` via `testImplementation(kotlin("test"))`; no other test framework.
- No CI, no lint/format plugins, no codegen — `./gradlew build` is the full gate.

## Documentation

The `docs/` directory holds the user-facing documentation (13 files, see
`README.md` for the full list and links). The README is a concise sales page
with links to each doc; each doc covers one topic in depth. Keep docs
accurate when changing APIs — the docs are the first thing a new user reads.

## Module layout

Dependency flow is bottom-up: `engine-utils` and `engine-model` are independent
leaves (don't depend on each other); `engine-events`, `engine-playback-api`,
`engine-provider-api`, `engine-queue`, `engine-autoplay` → `engine-core`;
`engine-provider-api` → `engine-provider-newpipe`, `engine-provider-lrclib`,
`engine-provider-coverartarchive`, `engine-provider-canvas`, and the six lyrics
providers (`engine-provider-kugou`, `-simpmusic`, `-paxsenix`, `-betterlyrics`,
`-unison`, `-youlyplus`); `engine-provider-lastfm` also depends on
`engine-provider-api` but exposes a client + `ScrobbleManager` (not a provider
role); `engine-api` is the DSL facade; `engine-http` and `sample-console` consume
everything.

`engine-utils` holds `IdGenerator` and `TrackMatching` (Spotify-style fuzzy
title/artist/duration matching). `engine-model`'s `Lyrics` now carries word-level
timings: `Lyrics(text, source, synced, lines)` → `LyricsLine(text, start?, end?,
words)` → `LyricsWord(text, start?, end?)`, timestamps in **ms** (while
`Song.duration` stays in **seconds**). `Artwork` gained `verticalUrl` for
portrait/vertical media (animated canvases).

- `engine-provider-newpipe` is the **only** module importing NewPipeExtractor types.
  Public APIs expose only `@Serializable` models and `ProviderException`; the objects in
  `mapping/` are the single conversion point. Keep extractor types confined here.
- Providers are split by concern. `MusicProvider` = search/getSong/getStream;
  `LyricsProvider.getLyrics(song)`; `ArtworkProvider.getArtwork(releaseMbid)` plus
  `SongArtworkProvider` (a `Song`-keyed `ArtworkProvider`, used by Canvas, that
  skips the resolver). MBID-keyed artwork needs a `ReleaseResolver`
  (`resolveReleaseMbid(song)`) because Cover Art Archive (and MusicBrainz) are
  keyed by MBID, not streaming ids. The DSL wires all four:
  `createJusPlayer { provider(...) lyricsProvider(...) artworkProvider(...) releaseResolver(...) player(...) }`;
  lyrics/artwork/releaseResolver are optional. `engine.lyrics(song)`/`engine.artwork(song)`
  return `null` when the corresponding provider isn't registered.
- `engine-provider-canvas` (`CanvasArtworkProvider`) is a `SongArtworkProvider`
  with mirror failover (`CanvasService`) + an Apple Music Canvas fallback; returns
  `Artwork` with `frontUrl` = animated m3u8 and `verticalUrl` for portrait clips.
- `engine-provider-lastfm` (`LastFMClient` + `ScrobbleManager`) is **not** a
  provider — no `Provider` interface. `LastFMClient` signs requests (MD5
  `api_sig`), supports Libre.fm via a custom endpoint, and throws
  `LastFmException(code)`. `ScrobbleManager` scrobbles after 50% (max 180s),
  never under 30s, is pause/resume-aware, and records `songStartedAt` timestamps.
- The six lyrics providers (`kugou`, `simpmusic`, `paxsenix`, `betterlyrics`,
  `unison`, `youlyplus`) all implement `LyricsProvider` over `HttpTransport` and
  return `null` on no-match, following the shared error-mapping contract below.
- `engine-api` (`createJusPlayer { provider(...) player(...) }`) **requires** a
  `PlayerAdapter` — it throws `IllegalStateException` otherwise. There is no default player.
  Optional autoplay wiring: `recommendationProvider(...)` (repeatable),
  `autoplayEnabled(...)`, `autoplay(config)`.
- `engine-queue` is the **single source of truth**: `QueueEngine` owns contents,
  cursor, shuffle, and repeat, published atomically as a `QueueSnapshot` on its
  `state` `StateFlow`. `next()` returns `null` at exhaustion under
  `RepeatMode.NONE` (that's what triggers autoplay); `ONE` re-selects current,
  `ALL` wraps; `shuffle()` keeps the current track first and remembers the
  pre-shuffle order for `restore()`; `addNext()` inserts right after current.
  Pure helper functions live in `QueueOrder` (`internal object`, unit-tested).
- Progression is **event-driven**: `JusPlayerEngine` listens for `SongEnded` on the
  `EventBus`, advances the queue, and plays the next track via `PlaybackService`.
  All queue/playback transitions are serialized through an internal `Mutex`. When
  the queue is exhausted under `RepeatMode.NONE` (autoplay enabled, an engine
  exists, a current song exists) it asks the optional `AutoplayEngine` to replenish.
  `stop()` emits `PlaybackStopped` and never triggers autoplay. Manual operations
  (`next`, `previous`, `stop`, `pause`, `clear`) must never emit `SongEnded`.
- `engine-autoplay` owns `RecommendationProvider` (candidate source),
  `AutoplayHistory` (starts/completions/skips + artist/genre affinity),
  `AutoplayPipeline` (pure: dedupe → exclude → score → rank → diversify → take),
  and `DefaultAutoplayEngine` (aggregates providers, per-call timeout). The engine
  records engagement signals synchronously in `AutoplayHistory` and exposes the
  latest candidates on `autoplayCandidates`.
- `PlayerAdapter.position: Duration?` is a defaulted interface property — supplying
  it lets `previous()` restart the current track when `> RESTART_THRESHOLD_MS`
  (`3000`) in, instead of always going back one.

## Provider contract

Implementing a provider means implementing one of `MusicProvider`,
`LyricsProvider`, `ArtworkProvider`, or `SongArtworkProvider`, plus
`ReleaseResolver` when bridging to MusicBrainz, and declaring
`ProviderCapabilities` on the music provider. Providers
must translate raw HTTP/extractor errors into `ProviderException` subtypes
(`NotFound`, `Network`, `RateLimited`, `ExtractionFailed`, `Unsupported`); never let
raw exceptions escape — each provider does this in its own `runExtraction`-style
wrapper. The NewPipe provider also retries transient "page needs to be reloaded"
failures via `withRetry`.

Non-NewPipe providers share the `HttpTransport`/`JdkHttpTransport` in
`engine-provider-api` and parse JSON with kotlinx-serialization; sustain a
`ProviderException` mapping (LRCLIB `404`/`429`, Cover Art Archive `503`, the
lyrics providers `404`/`429`/`503`). Providers expose an `internal` constructor
taking an `HttpTransport` for network-free tests plus a public no-arg
constructor defaulting to `JdkHttpTransport()`.

## Gotchas

- `settings.gradle.kts` uses `dependencyResolutionManagement` with
  `RepositoriesMode.FAIL_ON_PROJECT_REPOS` — declare repos **only** there, never in a
  module's `build.gradle.kts` (Gradle errors on project-scoped repos).
- `net.newpipe:extractor:v0.26.4` resolves from `mavenLocal()` (JitPack can't build recent
  tags). If resolution fails locally, build it first:
  `git clone --branch v0.26.4 https://github.com/TeamNewPipe/NewPipeExtractor.git && cd NewPipeExtractor && ./gradlew publishToMavenLocal`
  On JitPack this is handled automatically by `jitpack.yml`'s `before_install`.
- `NewPipeProvider`'s constructor taking a `Downloader` is `internal` — network-free tests
  inject a fake `Downloader`. Keep tests offline; never hit YouTube.
- The NewPipe provider sets a browser-like `User-Agent` in `JvmDownloader`; YouTube rejects
  requests without one.
- `MusicProvider` no longer exposes lyrics — use a `LyricsProvider` (the LRCLIB provider,
  which needs a client User-Agent and throttles 200–500ms per request, honoring `Retry-After`
  on `429`). MusicBrainz also requires a client User-Agent. Cover Art Archive responds with
  `307` redirects that `JdkHttpTransport` follows automatically.
- New tests for the LRCLIB/CoverArtArchive modules inject a fake `HttpTransport` (no network).
  Note: a `runBlocking { assertFailsWith(...) }` test method must bind the result to a `val`
  (or end in `Unit`) — JUnit4 rejects non-void test methods.
- Module versions/gradle: Kotlin 2.0.21, Gradle 8.10.2 (wrapper), kotlinx-serialization
  1.6.3, coroutines 1.7.3, Ktor 2.3.11. Root `build.gradle.kts` sets group/version and the
  Kotlin plugin; serialization plugin is applied per-module only where needed.

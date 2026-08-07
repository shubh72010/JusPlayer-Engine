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
`engine-provider-api`, `engine-queue` → `engine-core`;
`engine-provider-api` → `engine-provider-newpipe`, `engine-provider-lrclib`,
`engine-provider-coverartarchive`; `engine-api` is the DSL facade;
`engine-http` and `sample-console` consume everything.

- `engine-provider-newpipe` is the **only** module importing NewPipeExtractor types.
  Public APIs expose only `@Serializable` models and `ProviderException`; the objects in
  `mapping/` are the single conversion point. Keep extractor types confined here.
- Providers are split by concern. `MusicProvider` = search/getSong/getStream;
  `LyricsProvider.getLyrics(song)`; `ArtworkProvider.getArtwork(releaseMbid)`. Artwork
  also needs a `ReleaseResolver` (`resolveReleaseMbid(song)`) because Cover Art Archive
  (and MusicBrainz) are keyed by MBID, not streaming ids. The DSL wires all four:
  `createJusPlayer { provider(...) lyricsProvider(...) artworkProvider(...) releaseResolver(...) player(...) }`;
  lyrics/artwork/releaseResolver are optional. `engine.lyrics(song)`/`engine.artwork(song)`
  return `null` when the corresponding provider isn't registered.
- `engine-api` (`createJusPlayer { provider(...) player(...) }`) **requires** a
  `PlayerAdapter` — it throws `IllegalStateException` otherwise. There is no default player.
- Playback is manual: `JusPlayerEngine` listens for `SongStarted`/`SongEnded` on the
  `EventBus` to update `StateFlow` state; it does not self-drive transitions.

## Provider contract

Implementing a provider means implementing one of `MusicProvider`,
`LyricsProvider`, or `ArtworkProvider`, plus `ReleaseResolver` when bridging to
MusicBrainz, and declaring `ProviderCapabilities` on the music provider. Providers
must translate raw HTTP/extractor errors into `ProviderException` subtypes
(`NotFound`, `Network`, `RateLimited`, `ExtractionFailed`, `Unsupported`); never let
raw exceptions escape — each provider does this in its own `runExtraction`-style
wrapper. The NewPipe provider also retries transient "page needs to be reloaded"
failures via `withRetry`.

Non-NewPipe providers share the `HttpTransport`/`JdkHttpTransport` in
`engine-provider-api` and parse JSON with kotlinx-serialization; sustain a
`ProviderException` mapping (LRCLIB `404`/`429`, Cover Art Archive `503`).

## Gotchas

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

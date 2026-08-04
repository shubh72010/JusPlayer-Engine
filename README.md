<div align="center">

# JusPlayer Engine

**A modular, provider-based music playback engine for the JVM, inspired by NewPipe's architecture.**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-8.10.2-green.svg)](https://gradle.org)
[![Coroutines](https://img.shields.io/badge/coroutines-1.7.3-orange.svg)](https://github.com/Kotlin/kotlinx.coroutines)

Stream, queue, and play music from any provider through a single ergonomic API —
without ever leaking extractor-specific types into your application code.

</div>

---

## TL;DR

**The Problem**: Most music libraries hard-couple your app to a single streaming
service and its bespoke, Android-only dependencies. You cannot swap the backend,
reuse it from plain JVM tools, or keep the engine, its data models, and its
providers separate.

**The Solution**: JusPlayer Engine is a tiny, coroutine-first playground of
decoupled modules. A slim public API sits on top of an engine core, and music
providers plug in behind a sealed interface. The included **NewPipe** provider
extends YouTube search + streaming through NewPipeExtractor — with all
extractor types confined to one module, so your app only ever sees clean,
serializable models.

### Why JusPlayer Engine?

| Feature | What It Does |
|---------|--------------|
| **Provider abstraction** | Swap YouTube, or any backend, behind one `MusicProvider` interface |
| **`ProviderCapabilities`** | Engine adapts automatically (e.g. hide Lyrics when unsupported) |
| **Sealed error model** | `ProviderException` subtypes — apps never see extractor errors |
| **Coroutine flows** | `StateFlow` state, `SharedFlow` event bus, all `suspend`-friendly |
| **Zero Android deps** | Plain JVM — usable from CLIs, servers, desktop, or Android |
| **DSL builder** | `createJusPlayer { provider(...) player(...) }` in one call |

---

## Quick Example

```kotlin
import org.jusplayer.engine.api.createJusPlayer
import org.jusplayer.engine.provider.newpipe.NewPipeProvider
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val jusPlayer = createJusPlayer {
        provider(NewPipeProvider())                // plug in a backend
        lyricsProvider(LRCLIBProvider())           // optional: lyrics
        artworkProvider(CoverArtArchiveProvider()) // optional: cover art
        releaseResolver(MusicBrainzResolver())     // optional: song -> release
        player(ConsolePlayerAdapter())             // plug in an output (speaker/console)
    }

    val songs = jusPlayer.engine.search("Daft Punk")

    songs.firstOrNull()?.let {
        jusPlayer.queue.add(it)
        jusPlayer.queue.next()
        println("Now playing: ${jusPlayer.currentSong?.title}")
        jusPlayer.currentSong?.let { song ->
            println("Lyrics: ${jusPlayer.engine.lyrics(song)?.text?.take(40)}")
            println("Artwork: ${jusPlayer.engine.artwork(song)?.frontUrl}")
        }
    }
}
```

---

## Design Philosophy

1. **Providers are plugins, not the core.** The engine knows how to drive
   providers and players; it never knows *which ones*. Register any number via
   `ProviderRegistry` and pick at runtime.
2. **Confine external types.** NewPipeExtractor classes never leak past
   `engine-provider-newpipe`. Mappers in separate objects convert into the
   engine's `@Serializable` models. Swapping the extractor means touching one
   module.
3. **Capabilities drive the UI.** `ProviderCapabilities` tells the engine what
   a provider can do, so features degrade gracefully instead of crumbling.
4. **State as flow, events as stream.** `StateFlow` for snapshots, `SharedFlow`
   for one-shot events (`SongStarted`, `SongEnded`, `QueueChanged`, ...).
5. **Engineer for JVM, not just Android.** No Android framework types anywhere,
   so the same engine powers a CLI sample and a Ktor HTTP server.

---

## How It Compares

| Capability | JusPlayer Engine | Rolling your own | NewPipe-side |
|------------|------------------|------------------|--------------|
| Extractor isolation | ✅ One module | ⚠️ Coupled | ❌ Tied to Android |
| Multi-provider | ✅ Via interface + registry | ⚠️ Rewrite each | ❌ Single service |
| Runs on plain JVM | ✅ | ⚠️ | ❌ Android only |
| Capability negotiation | ✅ | ❌ | ❌ |
| Error typing | ✅ sealed `ProviderException` | ❌ raw exceptions | ❌ |

**When JusPlayer Engine shines:** you want a real Android/desktop music app with
a clean engine core, multiple providers, and your own player/UI on top.

**When it might not fit:** you need a turnkey, batteries-included player today.
This repo is a modular foundation, not yet a finished product — playback
currently routes through your own `PlayerAdapter` and a `Noop` HTTP adapter.

---

## Installation

Requires **JDK 11+** and the Gradle wrapper (bundled).

### From Source

```bash
git clone git@github.com:shubh72010/JusPlayer-Engine.git
cd JusPlayer-Engine
./gradlew build          # compile + run all module tests
```

### As a dependency (library use)

Every module publishes as a Maven artifact via **JitPack** — no account or setup
needed, you just reference the GitHub repo + tag. Multi-module coordinates use the
repo's group (`com.github.<owner>.<repo>`) with the module name as the artifact id:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven(url = "https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.1.0")
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-newpipe:1.1.0")
    // lyrics / artwork / resolver are optional:
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-lrclib:1.1.0")
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-coverartarchive:1.1.0")
}
```

**Versioning / updating:** the version lives in **one place** — `version=` in
`gradle.properties`. Releases are **git tags** (`v1.0.0`, `v1.1.0`, …); a new tag is a
new JitPack release, and consumers update by changing the tag in their dependency
string. `jitpack.yml` pre-builds NewPipeExtractor (JitPack builds in a clean
environment) and pins JDK 21, then publishes via `publishToMavenLocal`.

Each module is a separate Gradle project — see [`settings.gradle.kts`](settings.gradle.kts).

---

## Running the Sample

```bash
./gradlew :sample-console:run
```

The console sample builds a player, searches for **Daft Punk**, adds the first
result to the queue, and reports its state.

### Run the HTTP server endpoint

```bash
./gradlew :engine-http:run                # default port 8368
# or
./gradlew :engine-http:run --args=9000    # custom port
JUS_ENGINE_PORT=9100 ./gradlew :engine-http:run
```

Endpoints:

| Method | Path            | Description                  |
|--------|-----------------|------------------------------|
| GET    | `/health`       | Liveness check → `{"status":"ok"}` |
| GET    | `/v1/search?q=` | Search songs via the provider |
| GET    | `/v1/stream/{id}` | Stream URL for a song ID  |
| GET    | `/v1/lyrics/{id}` | Lyrics when a provider supports them |
| GET    | `/v1/artwork/{id}` | Cover art URL(s) for a song |

---

## Modules

| Module | Role |
|--------|------|
| `engine-api` | Public DSL (`JusPlayerBuilder`, `createJusPlayer`) — the main entry point |
| `engine-model` | `@Serializable` models: `Song`, `Artist`, `Album`, `Stream`, `SearchResult` |
| `engine-events` | `EventBus` + event data classes (`SongStarted`, `SongEnded`, `QueueChanged`, ...) |
| `engine-provider-api` | `MusicProvider` interface, `ProviderCapabilities`, `ProviderException` |
| `engine-playback-api` | `PlayerAdapter` interface (play/pause/stop/seek) |
| `engine-queue` | `QueueEngine` — add, move, shuffle, next/previous, repeat/clear |
| `engine-core` | Wiring layer: `JusPlayerEngine`, `ProviderRegistry`, services (`Search`, `Queue`, `Playback`) |
| `engine-api` | Fast DSL facade that ties everything together |
| `engine-provider-newpipe` | NewPipeExtractor (YouTube) provider + JVM downloader + mappers + cache |
| `engine-provider-lrclib` | `LyricsProvider` backed by the LRCLIB API |
| `engine-provider-coverartarchive` | `ArtworkProvider` (Cover Art Archive) + `MusicBrainzResolver` |
| `engine-http` | Optional Ktor server exposing the engine over REST |
| `engine-utils` | `IdGenerator`, validation helpers |

### Dependency flow (bottom-up)

```
engine-utils  →  engine-model
                  engine-model
engine-events ───────────────────────▶ engine-core
engine-playback-api ─────────────┘
engine-provider-api ─────────────→ engine-provider-newpipe
engine-provider-api ─────────────→ engine-provider-lrclib
engine-provider-api ─────────────→ engine-provider-coverartarchive
engine-queue ──────────────────────┘
engine-api (facade) ◀── uses everything above
engine-http / sample-console ─▶ engine-api + every provider
```

### Building a provider (contract)

Providers are split by concern. A *music* provider (search/get/stream):

```kotlin
class MyProvider : MusicProvider {
    override val name = "MyProvider"
    override val capabilities = ProviderCapabilities(search = true, getSong = true, getStream = true)

    override suspend fun search(query: String): SearchResult = /* ... */
    override suspend fun getSong(id: String): Song = /* ... */
    override suspend fun getStream(songId: String): Stream = /* ... */
}
```

Lyrics and artwork are separate providers so a given backend can swap them freely:

```kotlin
class MyLyricsProvider : LyricsProvider {
    override val name = "MyLyrics"
    override suspend fun getLyrics(song: Song): Lyrics? = /* ... */   // null = none
}

class MyArtworkProvider : ArtworkProvider {
    override val name = "MyArtwork"
    override suspend fun getArtwork(releaseMbid: String): Artwork? = /* ... */
}
```

Cover art is keyed by MusicBrainz release MBID, so a `ReleaseResolver` bridges a
streaming `Song` to a release before the artwork provider is consulted. The engine
composes them: `engine.lyrics(song)` and `engine.artwork(song)` return `null` when the
relevant provider isn't registered, so all of these are optional at build time.

Throw `ProviderException.NotFound`, `.Network`, `.RateLimited`, `.ExtractionFailed`,
or `.Unsupported` instead of letting raw exceptions escape. LRCLIB's `429` and Cover
Art Archive's `503` map to `RateLimited`; both services recommend honoring
`Retry-After` and throttling requests.

---

## Commands

```bash
./gradlew build          # compile + test all modules
./gradlew test           # run unit tests
./gradlew :sample-console:run
./gradlew :engine-http:run
./gradlew clean          # wipe build/ directories
```

---

## Configuration

All settings live in `gradle.properties`:

```properties
kotlin.code.style=official
kotlin.incremental=true
org.gradle.jvmargs=-Xmx2g
```

Modules declare dependencies independently; the root [`build.gradle.kts`](build.gradle.kts)
sets the shared `group = com.github.shubh72010.JusPlayer-Engine` (JitPack-friendly)
and `version = 1.1.0` and the Kotlin JVM plugin version.

---

## Troubleshooting

### "Could not resolve net.newpipe:extractor"

Your build expects NewPipeExtractor `v0.26.4` in `mavenLocal()`. JitPack cannot
build the newest tags, so build the extractor locally and install it:

```bash
git clone --branch v0.26.4 https://github.com/TeamNewPipe/NewPipeExtractor.git
cd NewPipeExtractor && ./gradlew publishToMavenLocal
```

### "The page needs to be reloaded" on YouTube

A known transient NewPipeExtractor state. The provider retries automatically
(see `withRetry`); a slow/blocked network or a missing user-agent on the
`Downloader` commonly triggers it. `JvmDownloader` sets a browser-like
`User-Agent` to mitigate this.

### How do I add the engine to app code?

Inject a `PlayerAdapter`. There's no default; the engine refuses to build without
one, so provide your own (e.g. a Media3/ExoPlayer adapter).

---

## Limitations

- **No default player.** You must supply a `PlayerAdapter` (Media3-based UI
  adapters aren't included yet).
- **Playback state is manual.** The engine tracks events you emit; it does not
  yet self-drive gapless transitions or auto-advance.
- **Lyrics unsupported by the NewPipe provider** (`MusicProvider` no longer owns
  lyrics; use the LRCLIB `LyricsProvider` instead).
- **JitPack-ready, not yet on Maven Central.** Modules publish as Maven artifacts
  (`com.github.shubh72010.JusPlayer-Engine:<module>:<version>`) and are consumable
  via JitPack from git tags;
  Central is future work. See [Installation → As a dependency](#as-a-dependency-library-use).

## Roadmap / Ideas

- Media3 (ExoPlayer) `PlayerAdapter`
- Gapless / auto-advance playback in `QueueEngine`
- Publish to Maven Central (beyond JitPack)
- Additional providers (SoundCloud, Bandcamp, ...)

---

## FAQ

### Why "JusPlayer"?

A working-name for a **J**VM **us**er-agnostic **Player** engine; the goal is to stay flexible enough for many hosts.

### Do I need Android?

No. Everything runs on a plain JVM. Add UI in Compose/JavaFX, or keep it headless.

### Can I stream live YouTube?

The provider maps live streams (`isLive` flag) via `StreamMapper`, but actual
playback depends on your `PlayerAdapter`.

### How do providers isolate me from NewPipeExtractor?

`engine-provider-newpipe` is the only module importing extractor types. Public
APIs expose only `@Serializable` models and `ProviderException`. The `Mapper`
objects are the single point of conversion, so swapping extractors = editing one
module.

---

## Contributing

Contributions are welcome — open an issue or a pull request for bug fixes,
additional providers, or roadmap items.

## License

[GPL-3.0](LICENSE). All contributions are licensed under the GPL-3.0 license, in accordance with
the NewPipeExtractor dependency.

<div align="center"><sub>Made for developers who like clean engine boundaries.</sub></div>
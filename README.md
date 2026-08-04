<div align="center">

# JusPlayer Engine

**A modular, provider-based music engine for the JVM — search, stream, queue, and play music from any backend through one clean API.**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-8.10.2-green.svg)](https://gradle.org)
[![JitPack](https://jitpack.io/v/shubh72010/JusPlayer-Engine.svg)](https://jitpack.io/#shubh72010/JusPlayer-Engine)

</div>

---

## What is JusPlayer?

A coroutine-first **music engine**, not a media player. It gives you providers
(streams, lyrics, artwork), a queue, and a playback engine — and hands the actual
audio output to a `PlayerAdapter` you provide. No Android dependencies; it runs on
a plain JVM.

## Features

| Feature | What it does |
|---------|--------------|
| **Provider abstraction** | Swap backends behind one `MusicProvider` interface |
| **Streams** | YouTube search + stream URLs via the bundled NewPipe provider |
| **Lyrics** | Synced + plain lyrics via LRCLIB |
| **Artwork** | Cover art via Cover Art Archive + MusicBrainz resolution |
| **Queue** | Add, move, shuffle, next/previous, repeat, clear |
| **Coroutine flows** | `StateFlow` state, `SharedFlow` events, all `suspend`-friendly |
| **Zero Android deps** | Plain JVM — CLIs, servers, desktop, or Android |
| **DSL builder** | `createJusPlayer { ... }` wires everything in one call |
| **HTTP server** | Optional Ktor module exposes the engine over REST |

## 30-second example

```kotlin
val jusPlayer = createJusPlayer {
    provider(NewPipeProvider())                // streams (YouTube)
    lyricsProvider(LRCLIBProvider())           // optional: lyrics
    artworkProvider(CoverArtArchiveProvider()) // optional: cover art
    releaseResolver(MusicBrainzResolver())     // optional: song -> release
    player(MyPlayerAdapter())                  // required: your audio output
}

val songs = jusPlayer.engine.search("Daft Punk")
songs.firstOrNull()?.let {
    jusPlayer.queue.add(it)
    jusPlayer.engine.play(it)
}
```

## Architecture

```
       Your app (UI, notifications, platform integration)
                           │
                     PlayerAdapter  ──▶  audio device
                           │
                      JusPlayer Engine  (queue, services, state)
                           │
             ┌─────────────┼─────────────┐
        MusicProvider  LyricsProvider  ArtworkProvider
             │               │               │
          NewPipe          LRCLIB       CoverArtArchive
                                           (MusicBrainzResolver)
             │               │               │
                       Internet
```

## Installation

Requires **JDK 11+** (JDK 21 recommended) and a Maven repo.

```kotlin
repositories { maven(url = "https://jitpack.io") }

dependencies {
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.1.0")
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-newpipe:1.1.0")
    // optional:
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-lrclib:1.1.0")
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-coverartarchive:1.1.0")
}
```

## Documentation

| Doc | What it covers |
|-----|----------------|
| [Getting Started](docs/getting-started.md) | Start here — core concepts |
| [Installation](docs/installation.md) | Every module, exactly what to add |
| [Quick Start](docs/quick-start.md) | A complete working app |
| [Architecture](docs/architecture.md) | How the pieces fit together |
| [Providers](docs/providers.md) | NewPipe, LRCLIB, CoverArtArchive in detail |
| [PlayerAdapter](docs/player-adapter.md) | Implementing audio output |
| [HTTP Server](docs/http-server.md) | REST API endpoints |
| [Creating a Provider](docs/creating-provider.md) | Add your own backend |
| [Creating a PlayerAdapter](docs/creating-player-adapter.md) | Add your own audio engine |
| [Cookbook](docs/cookbook.md) | Copy-paste recipes |
| [Updating](docs/updating.md) | Version bumps |
| [Migration](docs/migration.md) | Breaking changes between versions |
| [FAQ](docs/faq.md) | Common questions |

## Commands

```bash
JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew build          # compile + test all modules
JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew :sample-console:run
JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk ./gradlew :engine-http:run     # REST server, port 8368
```

## License

[GPL-3.0](LICENSE). All contributions are licensed under the GPL-3.0 license, in accordance with
the NewPipeExtractor dependency.

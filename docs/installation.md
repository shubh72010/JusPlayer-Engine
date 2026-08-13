# Installation

Artifacts are published to **JitPack** — no account, no API keys, no setup. You
just add the repo and depend on the modules you want. All coordinates are
`com.github.shubh72010.JusPlayer-Engine:<module>:<version>`.

## Requirements

- **JDK 11+** (JDK 21 recommended — the build itself requires 21; runtime is more lenient)
- Gradle (the wrapper is bundled if you build from source)

## Add the repository

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven(url = "https://jitpack.io")
    }
}
```

## Add the dependencies

```kotlin
// build.gradle.kts
dependencies {
    // Core — always add this
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.3.0")

    // Streaming (YouTube) — always add a MusicProvider
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-newpipe:1.3.0")

    // Optional: lyrics
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-lrclib:1.3.0")

    // Optional: cover art (+ the MusicBrainz resolver that powers it)
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-coverartarchive:1.3.0")
}
```

> **Tip:** you don't need to add `engine-model`, `engine-core`, `engine-queue`,
> etc. — they are pulled in transitively by `engine-api`.

## Which module is which

| Module | Do I need it? | Purpose |
|--------|---------------|---------|
| `engine-api` | ✅ Yes | The `createJusPlayer` DSL — the single entry point |
| `engine-provider-newpipe` | ✅ Yes (to stream) | YouTube search/streams via NewPipeExtractor |
| `engine-provider-lrclib` | 🟡 Optional | Synced + plain lyrics from LRCLIB |
| `engine-provider-coverartarchive` | 🟡 Optional | Cover art from Cover Art Archive |
| `engine-model` | 🔵 Transitive | `Song`, `Artist`, `Album`, `Stream`, `Lyrics`, `Artwork` |
| `engine-core` | 🔵 Transitive | Wiring layer — `JusPlayerEngine`, services |
| `engine-queue` | 🔵 Transitive | `QueueEngine` |
| `engine-provider-api` | 🔵 Transitive | Provider interfaces + `ProviderException` |
| `engine-playback-api` | 🔵 Transitive | `PlayerAdapter` interface |
| `engine-events` | 🔵 Transitive | `EventBus` + event classes |
| `engine-utils` | 🔵 Transitive | `IdGenerator`, validation |
| `engine-http` | 🟡 Optional | Ktor REST server (see [HTTP Server](http-server.md)) |
| `sample-console` | ❌ No | Demo app — for reference only |

## Picking a version

The version lives in **one place** in the repo — `version=` in `gradle.properties` —
and releases are **git tags** (`v1.0.0`, `v1.1.0`, ...). Point at any published tag.

```kotlin
implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.3.0")
```

To track `master` you can use `main-SNAPSHOT` (the first commit hash) instead of a
tag, but tags are stable and recommended. See [Updating](updating.md).

## Android apps

JusPlayer has **no Android dependencies**, but it works fine inside an Android app.
Use it as a normal JVM library; supply a `PlayerAdapter` backed by
ExoPlayer/Media3 in your app module.

## Next

[Quick Start](quick-start.md) — build a working app.

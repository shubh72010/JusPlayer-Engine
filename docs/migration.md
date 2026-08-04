# Migration

> There are currently **no released API-breaking changes** to migrate from. This
> page documents the history and what to do if you're coming from a broken/old
> build.

## The 1.0.0 → 1.1.0 fix (coordinate change)

`v1.0.0` published **only a broken empty root artifact** — none of the module
coordinates resolved. This was fixed in `v1.1.0`.

If you tried `v1.0.0` and hit "could not resolve":

1. Change every coordinate to the correct form:

   ```kotlin
   // wrong (used a 4-part Maven-style coordinate)
   implementation("com.github.shubh72010:JusPlayer-Engine:engine-api:1.0.0")

   // right (dot-group + module artifactId)
   implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.1.0")
   ```

2. Bump **all** modules to `1.1.0` or later — do not stay on `1.0.0`.

## Pre-1.0 design: lyrics lived on `MusicProvider`

Before the provider split, `MusicProvider` owned lyrics and `getStream` returned a
`Stream`. Those APIs were never released (the only tag is `v1.0.0`, which was
unusable), so **no published code is affected**. For local branches that used the
old shape:

| Old | New |
|-----|-----|
| `MusicProvider.getLyrics(song)` | `LyricsProvider.getLyrics(song)` (separate provider) |
| `capabilities.lyrics` | gone — check whether a `LyricsProvider` is registered |
| One provider doing everything | `provider(...)` + `lyricsProvider(...)` + `artworkProvider(...)` + `releaseResolver(...)` |

## Future migrations

Breaking changes will be documented **here** with exact before/after snippets and
bump versions. The [Updating](updating.md) page covers routine version bumps.

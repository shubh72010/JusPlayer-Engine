# Migration

> This page documents breaking changes **with exact before/after snippets**. Last
> reviewed for `v1.3.0`; for routine version bumps see [Updating](updating.md).

## 1.2.0 → 1.3.0 (model changes)

`v1.3.0` touches the `Song` and `Stream` models and the HTTP lyrics/artwork
responses.

### `Song.streamUrl` was removed

Stream URLs belong on the stream you fetch via `getStream`, not on the search
result. If you read `song.streamUrl`, drop that line and use the provider's stream
instead.

### `Stream` fields changed

`Stream` gained `codec`/`mimeType`, and two fields changed shape:

| Field | 1.2.0 | 1.3.0 |
|-------|-------|-------|
| `bitrate` | `Int` (kbps) | `Long?` — **bits per second** (`null` if unknown) |
| `sampleRate` | `Int` | `Int?` — `null` if the provider can't determine it |

So `bitrate` for a 160 kbps file is now `160000`, not `160`. In code that consumes
a stream: convert `bitrate / 1000` to get kbps, and nullable-check both fields.

### Lyrics/artwork over HTTP now return a status envelope

`GET /v1/lyrics/{id}` and `GET /v1/artwork/{id}` no longer return the bare model
(or `{"error": ...}` on failure). They return:

```json
{
  "status": "ok" | "not_found" | "error",
  "provider": "LRCLIB",
  "message": "only set on not_found/error",
  "lyrics":  { … },   // present when status is "ok"
  "artwork": { … }    // present when status is "ok"
}
```

`not_found` (a valid miss) and `error` (provider failure) are both HTTP 200 — read
the `status` field rather than the HTTP code. The [HTTP Server](http-server.md)
doc has full examples.

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

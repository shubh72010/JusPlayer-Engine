# HTTP Server

The `engine-http` module exposes the engine over REST with Ktor. Use it when you
want to:

- Drive the engine from a **non-JVM** client (Flutter, React Native, a web app, ...)
- Keep the engine in a **server/backend process** and consume it over HTTP
- Share one engine across many clients

It's an *optional* module — a normal Kotlin app embeds the engine directly and
doesn't need it.

## Running it

```bash
# default port 8368; opens the in-built browser demo at /
./gradlew :engine-http:run

# custom port via CLI arg
./gradlew :engine-http:run --args=9000

# or via env var
JUS_ENGINE_PORT=9000 ./gradlew :engine-http:run
```

The server wires `NewPipeProvider` + `LRCLIBProvider` +
`CoverArtArchiveProvider` + `MusicBrainzResolver` and a no-op player.

## In-built browser demo

Pointing a browser at `http://localhost:8368/` (auto-opened on start) loads a
self-contained **single-file HTML/JS demo** (`engine-http/src/main/resources/index.html`)
with three views:

- **Player** — search songs; clicking a result loads the stream into an `<audio>`
  element, plus cover art and lyrics, with a live playback readout.
- **Details** — per-category metadata tables for the song, stream, artwork, and
  lyrics (album, upload date, provider URL, codec/MIME/bitrate, artwork source and
  dimensions, lyrics source/sync).
- **Developer** — registered providers and their capabilities, the playback-state
  readout, and pretty-printed raw JSON for every response. Great for debugging
  providers.

No build step or Node/JS toolchain is involved.

## Endpoints

All responses are JSON. Errors return `{"error": "message"}`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | In-built browser demo (HTML) |
| GET | `/health` | Liveness → `{"status":"ok"}` |
| GET | `/v1/diagnostics` | Provider names + music-provider capabilities |
| GET | `/v1/search?q=<query>` | Search songs |
| GET | `/v1/stream/{id}` | Stream URL for a song id |
| GET | `/v1/lyrics/{id}` | Lyrics for a song id |
| GET | `/v1/artwork/{id}` | Cover art for a song id |

### `GET /health`

```json
{ "status": "ok" }
```

### `GET /v1/search?q=...`

```json
{
  "songs": [
    {
      "id": "dQw4w9WgXcQ",
      "title": "Song title",
      "artists": [{ "id": "UC...", "name": "Artist", "thumbnailUrl": null }],
      "album": null,
      "duration": 214,
      "thumbnailUrl": "https://...",
      "releaseDate": "2016-07-22"
    }
  ],
  "artists": [],
  "albums": []
}
```

`q` is required; a blank/missing value returns `{"error": "missing q"}`.

### `GET /v1/stream/{id}`

```json
{
  "url": "https://...",
  "format": "webm",
  "bitrate": 160000,
  "sampleRate": null,
  "isLive": false,
  "duration": 214,
  "codec": "opus",
  "mimeType": "audio/webm"
}
```

`bitrate` is in **bits per second** (160000 = 160 kbps); `sampleRate` is null when
the provider can't determine it. `duration` is in **seconds**.

### `GET /v1/lyrics/{id}`

Resolves the song by id first, then fetches lyrics. Returns a **status envelope**
that distinguishes a true miss from a provider failure:

```json
{ "status": "ok", "provider": "LRCLIB", "lyrics": { "text": "[00:12.00] ...", "source": "LRCLIB", "synced": true } }
```

- `ok` — `lyrics` holds the model.
- `not_found` — the provider found no lyrics for this track.
- `error` — the provider threw; `message` carries the reason.

```json
{ "status": "not_found", "provider": "LRCLIB", "message": "no lyrics found for this track" }
{ "status": "error", "provider": "LRCLIB", "message": "..." }
```

### `GET /v1/artwork/{id}`

Resolves song → release MBID → artwork. Same envelope, with `artwork` instead of
`lyrics`:

```json
{
  "status": "ok",
  "artwork": {
    "frontUrl": "https://coverartarchive.org/.../front-500.jpg",
    "backUrl": null,
    "thumbnails": { "250": "https://...", "500": "https://...", "1200": "https://..." },
    "sourceMbid": "123e4567-e89b-12d3-a456-426614174000"
  }
}
```

```json
{ "status": "not_found", "provider": "CoverArtArchive", "message": "no artwork found for this track" }
```

## Embedding the server

```kotlin
val engine = JusPlayerHttpEngine(port = 9000)
engine.start()   // blocks; serves until process exit
```

## CORS & clients

CORS is enabled for any host (`anyHost()`), so browser clients work out of the box.
The server uses a no-op `PlayerAdapter` — playback is the client's job; it gets the
stream URL and plays it itself.

## When to use HTTP vs embedding

| | Embed the library | HTTP server |
|---|---|---|
| Language | Kotlin/JVM | Any language |
| Latency | Zero (in-process) | One hop |
| State | Shared in-process | Centralized server |
| Best for | Desktop/Android apps | Remote clients, Flutter/web, multi-client |

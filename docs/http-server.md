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
# default port 8368
./gradlew :engine-http:run

# custom port via CLI arg
./gradlew :engine-http:run --args=9000

# or via env var
JUS_ENGINE_PORT=9000 ./gradlew :engine-http:run
```

The server wires `NewPipeProvider` + `LRCLIBProvider` +
`CoverArtArchiveProvider` + `MusicBrainzResolver` and a no-op player.

## Endpoints

All responses are JSON. Errors return `{"error": "message"}`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness → `{"status":"ok"}` |
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
      "duration": 214000,
      "thumbnailUrl": "https://...",
      "streamUrl": null
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
  "format": "WEBM_OPUS",
  "bitrate": 160000,
  "sampleRate": 48000,
  "isLive": false,
  "duration": 214000
}
```

### `GET /v1/lyrics/{id}`

Resolves the song by id first, then fetches lyrics:

```json
{
  "text": "[00:12.00] First line...",
  "source": "LRCLIB",
  "synced": true
}
```

Returns `{"error": "lyrics unavailable"}` when no lyrics exist for the song.

### `GET /v1/artwork/{id}`

Resolves song → release MBID → artwork:

```json
{
  "frontUrl": "https://coverartarchive.org/.../front-500.jpg",
  "backUrl": null,
  "thumbnails": { "250": "https://...", "500": "https://...", "1200": "https://..." },
  "sourceMbid": "123e4567-e89b-12d3-a456-426614174000"
}
```

Returns `{"error": "artwork unavailable"}` when resolution or lookup fails.

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

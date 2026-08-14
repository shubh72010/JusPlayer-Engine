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
| GET | `/v1/recommendations/{id}?limit=` | Platform-native (YouTube related) recommendations |
| GET | `/v1/player/state` | Playback state: state, current song, queue, repeat, shuffle, autoplay, generation |
| POST | `/v1/player/play/{id}` | Queue + play a song through the engine |
| POST | `/v1/player/next` | Advance to the next track |
| POST | `/v1/player/previous` | Go back one (restarts current >3s in) |
| POST | `/v1/player/pause` | Pause |
| POST | `/v1/player/stop` | Stop |
| POST | `/v1/player/seek?ms=` | Seek to a position |
| POST | `/v1/player/song-ended?ms=&song=&generation=` | Report a natural end (triggers auto-advance/autoplay) |
| POST | `/v1/player/queue/add/{id}` | Add a song to the queue |
| POST | `/v1/player/queue/remove/{index}` | Remove by queue index |
| POST | `/v1/player/queue/clear` | Clear the queue |
| POST | `/v1/player/repeat?mode=NONE\|ONE\|ALL` | Set repeat mode |
| POST | `/v1/player/shuffle?enabled=` | Toggle shuffle |
| POST | `/v1/player/autoplay?enabled=` | Toggle autoplay |

### Natural-end reporting (`song-ended`)

The web client owns the `<audio>` element, so when a track ends in the browser it
must tell the engine — that's what triggers auto-advance (and autoplay when the
queue is exhausted). Since the engine cannot see the browser's `ended` event
itself, the client identifies **which playback session** ended:

```
POST /v1/player/song-ended?ms=214000&song=dQw4w9WgXcQ&generation=7
```

- `song` — the id of the song that ended (must match the engine's current song).
- `generation` — the playback session's generation, read from the `generation`
  field of `GET /v1/player/state`.

The engine validates both against its active playback session, so a **stale**
completion (e.g. the previous track's `ended` event arriving after the user
already skipped or the poll started a new song) or a **duplicate** completion is
rejected instead of advancing/restarting playback out of order. A request whose
`song` doesn't match the current song is answered with
`{"error": "stale or unknown song"}` and ignored.

### `GET /v1/player/state`

```json
{
  "state": "PLAYING",
  "currentSong": { "id": "dQw4w9WgXcQ", "title": "...", "artists": [...] },
  "queue": [],
  "currentIndex": 0,
  "repeatMode": "NONE",
  "shuffleEnabled": false,
  "autoplayEnabled": true,
  "autoplayCandidates": [],
  "generation": 7
}
```

`generation` identifies the active playback session (see above). It is `null`
when idle/paused/stopped. Poll it alongside `state`/`currentSong` so the UI can
send the correct value on `song-ended`.

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

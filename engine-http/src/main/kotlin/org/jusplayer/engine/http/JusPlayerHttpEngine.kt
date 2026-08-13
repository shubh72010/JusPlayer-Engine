package org.jusplayer.engine.http

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.jusplayer.engine.api.createJusPlayer
import org.jusplayer.engine.autoplay.AutoplayConfig
import org.jusplayer.engine.autoplay.AutoplayContext
import org.jusplayer.engine.autoplay.RecommendationProvider
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.model.Album
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.PlaybackState
import org.jusplayer.engine.model.RepeatMode
import org.jusplayer.engine.model.Song as JpSong
import org.jusplayer.engine.model.Stream as JpStream
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.RelatedProvider
import org.jusplayer.engine.provider.coverartarchive.CoverArtArchiveProvider
import org.jusplayer.engine.provider.coverartarchive.MusicBrainzResolver
import org.jusplayer.engine.provider.lrclib.LRCLIBProvider
import org.jusplayer.engine.provider.newpipe.NewPipeProvider

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull()
        ?: System.getenv("JUS_ENGINE_PORT")?.toIntOrNull()
        ?: 8368
    JusPlayerHttpEngine(port).start()
}

class JusPlayerHttpEngine(private val port: Int = 8368) {
    private val provider: MusicProvider = NewPipeProvider()

    private val player = runBlocking {
        createJusPlayer {
            provider(this@JusPlayerHttpEngine.provider)
            lyricsProvider(LRCLIBProvider())
            artworkProvider(CoverArtArchiveProvider())
            releaseResolver(MusicBrainzResolver())
            player(NoopPlayerAdapter())
            // Bake autoplay into the engine: search the current song's artists to
            // generate candidates, then let the engine's real pipeline (dedupe,
            // exclude, score, rank, diversify) decide what actually gets queued.
            recommendationProvider(SearchRecommendationProvider(this@JusPlayerHttpEngine.provider))
            autoplay(AutoplayConfig(bufferSize = 5, maxConsecutiveSameArtist = 2))
            autoplayEnabled(true)
        }
    }

    fun start() {
        embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json()
            }
            install(CORS) {
                anyHost()
            }
            routing {
                get("/") {
                    call.respond(
                        RootResponse(
                            service = "JusPlayer Engine HTTP",
                            endpoints = listOf(
                                "GET /health",
                                "GET /v1/search?q=",
                                "GET /v1/stream/{id}",
                                "GET /v1/lyrics/{id}",
                                "GET /v1/artwork/{id}",
                                "GET /v1/recommendations/{id}",
                                "GET /v1/player/state",
                                "POST /v1/player/play/{id}",
                                "POST /v1/player/next",
                                "POST /v1/player/previous",
                                "POST /v1/player/pause",
                                "POST /v1/player/stop",
                                "POST /v1/player/seek?ms=",
                                "POST /v1/player/song-ended",
                                "POST /v1/player/queue/add/{id}",
                                "POST /v1/player/queue/remove/{index}",
                                "POST /v1/player/queue/clear",
                                "POST /v1/player/repeat?mode=NONE|ONE|ALL",
                                "POST /v1/player/shuffle?enabled=",
                                "POST /v1/player/autoplay?enabled=",
                            ),
                        ),
                    )
                }

                get("/health") { call.respond(mapOf("status" to "ok")) }

                get("/v1/search") {
                    val q = call.parameters["q"]
                    if (q.isNullOrBlank()) {
                        call.respond(mapOf("error" to "missing q"))
                        return@get
                    }
                    runCatching {
                        val songs = runBlocking { player.engine.search(q) }
                        call.respond(SearchResponse(songs = songs))
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                get("/v1/stream/{id}") {
                    val id = call.parameters["id"] ?: ""
                    runCatching {
                        val stream = runBlocking { provider.getStream(id) }
                        call.respond(stream)
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                get("/v1/lyrics/{id}") {
                    val id = call.parameters["id"] ?: ""
                    runCatching {
                        val song = runBlocking { provider.getSong(id) }
                        val lyrics = runBlocking { player.engine.lyrics(song) }
                        if (lyrics != null) {
                            call.respond(lyrics)
                        } else {
                            call.respond(mapOf("error" to "lyrics unavailable"))
                        }
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                get("/v1/artwork/{id}") {
                    val id = call.parameters["id"] ?: ""
                    runCatching {
                        val song = runBlocking { provider.getSong(id) }
                        val artwork = runBlocking { player.engine.artwork(song) }
                        if (artwork != null) {
                            call.respond(artwork)
                        } else {
                            call.respond(mapOf("error" to "artwork unavailable"))
                        }
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                // Platform-native recommendations (YouTube related streams).
                get("/v1/recommendations/{id}") {
                    val id = call.parameters["id"] ?: ""
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 30) ?: 10
                    val recProvider = provider as? RelatedProvider
                    if (recProvider == null) {
                        call.respond(mapOf("error" to "provider does not support recommendations"))
                        return@get
                    }
                    runCatching {
                        val songs = runBlocking { recProvider.getRecommendations(id, limit) }
                        call.respond(SearchResponse(songs = songs))
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                // ---- Player control: everything routes through the engine ----

                get("/v1/player/state") { call.respond(playerState()) }

                post("/v1/player/play/{id}") {
                    val id = call.parameters["id"] ?: ""
                    runCatching {
                        val song = runBlocking { provider.getSong(id) }
                        runBlocking { player.engine.play(song) }
                        call.respond(mapOf("ok" to true))
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                post("/v1/player/next") {
                    runBlocking { player.engine.next() }
                    call.respond(mapOf("ok" to true))
                }

                post("/v1/player/previous") {
                    runBlocking { player.engine.previous() }
                    call.respond(mapOf("ok" to true))
                }

                post("/v1/player/pause") {
                    player.engine.pause()
                    call.respond(mapOf("ok" to true))
                }

                post("/v1/player/stop") {
                    runBlocking { player.engine.stop() }
                    call.respond(mapOf("ok" to true))
                }

                post("/v1/player/seek") {
                    val ms = call.request.queryParameters["ms"]?.toLongOrNull()
                    if (ms != null) {
                        player.engine.seek(Duration.ofMillis(ms))
                        call.respond(mapOf("ok" to true))
                    } else {
                        call.respond(mapOf("error" to "missing ms"))
                    }
                }

                // The web client owns the <audio> element. When a track ends in
                // the browser it reports the natural end here; the engine then
                // auto-advances (and autoplays when the queue is exhausted).
                post("/v1/player/song-ended") {
                    val ms = call.request.queryParameters["ms"]?.toLongOrNull() ?: 0L
                    val current = player.engine.currentSong.value
                    if (current != null) {
                        runBlocking { player.events.emit(SongEnded(song = current, position = ms)) }
                        call.respond(mapOf("ok" to true))
                    } else {
                        call.respond(mapOf("error" to "no current song"))
                    }
                }

                post("/v1/player/queue/add/{id}") {
                    val id = call.parameters["id"] ?: ""
                    runCatching {
                        val song = runBlocking { provider.getSong(id) }
                        player.queue.add(song)
                        call.respond(mapOf("ok" to true))
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                post("/v1/player/queue/remove/{index}") {
                    val index = call.parameters["index"]?.toIntOrNull()
                    if (index != null) {
                        player.queue.remove(index)
                        call.respond(mapOf("ok" to true))
                    } else {
                        call.respond(mapOf("error" to "missing index"))
                    }
                }

                post("/v1/player/queue/clear") {
                    player.queue.clear()
                    call.respond(mapOf("ok" to true))
                }

                post("/v1/player/repeat") {
                    val mode = call.request.queryParameters["mode"]
                    val parsed = mode?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() }
                    if (parsed != null) {
                        player.engine.setRepeatMode(parsed)
                        call.respond(mapOf("ok" to true))
                    } else {
                        call.respond(mapOf("error" to "mode must be NONE, ONE, or ALL"))
                    }
                }

                post("/v1/player/shuffle") {
                    val enabled = call.request.queryParameters["enabled"]?.toBooleanStrictOrNull()
                    if (enabled != null) {
                        player.engine.setShuffle(enabled)
                        call.respond(mapOf("ok" to true))
                    } else {
                        call.respond(mapOf("error" to "missing enabled"))
                    }
                }

                post("/v1/player/autoplay") {
                    val enabled = call.request.queryParameters["enabled"]?.toBooleanStrictOrNull()
                    if (enabled != null) {
                        player.engine.setAutoplayEnabled(enabled)
                        call.respond(mapOf("ok" to true))
                    } else {
                        call.respond(mapOf("error" to "missing enabled"))
                    }
                }
            }
        }.start(wait = true)
    }

    fun stop() {
        // graceful shutdown would go here
    }

    private fun playerState(): PlayerStateResponse {
        val snapshot = player.queue.state.value
        return PlayerStateResponse(
            state = player.state.name,
            currentSong = player.engine.currentSong.value,
            queue = snapshot.items,
            currentIndex = snapshot.currentIndex,
            repeatMode = snapshot.repeatMode.name,
            shuffleEnabled = snapshot.shuffleEnabled,
            autoplayEnabled = player.engine.autoplayEnabled.value,
            autoplayCandidates = player.engine.autoplayCandidates.value,
        )
    }
}

/**
 * Generates autoplay candidates. Prefers the platform's own recommendations
 * (YouTube related streams, via [RelatedProvider]) — those are what the user
 * would actually see next on the site. When a provider has no native
 * recommendations, falls back to searching the *listening context*: the current
 * song's artists are always queried, and any artists the user has been listening
 * to this session are folded in weighted by how often they appeared (implicit
 * affinity). The engine's [AutoplayPipeline] then does the real work — dedupe
 * against the queue, exclude recently played, score by affinity, rank,
 * diversify, and take only what the buffer needs.
 */
private class SearchRecommendationProvider(
    private val musicProvider: MusicProvider,
) : RecommendationProvider {
    override val name: String = "search-similar"

    override suspend fun recommend(context: AutoplayContext, limit: Int): List<JpSong> {
        val native = (musicProvider as? RelatedProvider)?.let { related ->
            runCatching { related.getRecommendations(context.currentSong.id, limit * 2) }
                .getOrNull()
                .orEmpty()
        } ?: emptyList()

        val searchBased = contextBasedCandidates(context, limit)

        // Prefer native recommendations; blend in search-based ones so the
        // pipeline still has diversity beyond the current track's related set.
        return (native + searchBased)
            .distinctBy { it.id }
            .filter { it.id != context.currentSong.id }
    }

    /** Session-aware candidates via artist search (implicit affinity). */
    private suspend fun contextBasedCandidates(context: AutoplayContext, limit: Int): List<JpSong> {
        // 1. The current track's artists (coherence) always get searched.
        val queries = LinkedHashMap<String, Int>() // artist name -> weight
        context.currentSong.artists.map { it.name }.distinct()
            .forEach { queries[it] = (queries[it] ?: 0) + 2 }

        // 2. Learn from the session: artists that keep showing up in recently
        //    played tracks are likely enjoyed — weight them by frequency so the
        //    recommendations spread across what the user actually listens to.
        context.recentSongs
            .flatMap { it.artists.map { artist -> artist.name } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .forEach { (artist, count) ->
                if (artist.isNotBlank()) queries[artist] = (queries[artist] ?: 0) + count
            }

        if (queries.isEmpty()) return emptyList()

        // 3. Allocate the candidate budget across queries proportionally to
        //    weight; if a query returns fewer results the slack flows to the
        //    next artist.
        val budget = limit
        val totalWeight = queries.values.sum().coerceAtLeast(1)
        val out = mutableListOf<JpSong>()
        var remaining = budget
        for ((artist, weight) in queries.entries) {
            if (remaining <= 0) break
            val share = (budget * weight / totalWeight).coerceAtLeast(1).coerceAtMost(remaining)
            val found = runCatching { musicProvider.search(artist).songs }
                .getOrNull()
                .orEmpty()
                .filter { it.id != context.currentSong.id }
                .take(share)
            out += found
            remaining -= found.size
        }
        return out
    }
}

private class NoopPlayerAdapter : PlayerAdapter {
    override suspend fun play(stream: JpStream) {
        println("Playback handled by client; ignoring stream: ${stream.url}")
    }

    override fun pause() {}

    override fun stop() {}

    override fun seek(position: Duration) {}
}

@Serializable
data class RootResponse(
    val service: String,
    val endpoints: List<String>,
)

@Serializable
data class SearchResponse(
    val songs: List<JpSong>,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList()
)

@Serializable
data class PlayerStateResponse(
    val state: String,
    val currentSong: JpSong?,
    val queue: List<JpSong>,
    val currentIndex: Int,
    val repeatMode: String,
    val shuffleEnabled: Boolean,
    val autoplayEnabled: Boolean,
    val autoplayCandidates: List<JpSong>,
)

@Serializable
data class ErrorResponse(
    val error: String
)
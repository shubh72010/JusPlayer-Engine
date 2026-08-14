package org.jusplayer.engine.http

import io.ktor.http.ContentType
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
import kotlinx.serialization.Serializable
import java.awt.Desktop
import java.net.URI
import org.jusplayer.engine.api.createJusPlayer
import org.jusplayer.engine.autoplay.AutoplayConfig
import org.jusplayer.engine.autoplay.AutoplayContext
import org.jusplayer.engine.autoplay.RecommendationProvider
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.model.Album
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.model.Lyrics
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
    private val lyricsProvider = LRCLIBProvider()
    private val artworkProvider = CoverArtArchiveProvider()
    private val releaseResolver = MusicBrainzResolver()

    private val player = createJusPlayer {
        provider(this@JusPlayerHttpEngine.provider)
        lyricsProvider(this@JusPlayerHttpEngine.lyricsProvider)
        artworkProvider(this@JusPlayerHttpEngine.artworkProvider)
        releaseResolver(this@JusPlayerHttpEngine.releaseResolver)
        player(NoopPlayerAdapter())
        // Bake autoplay into the engine: prefer the platform's own
        // recommendations (YouTube related streams) and blend in session
        // search, then let the engine's real pipeline (dedupe, exclude,
        // score, rank, diversify) decide what actually gets queued.
        recommendationProvider(SearchRecommendationProvider(this@JusPlayerHttpEngine.provider))
        autoplay(AutoplayConfig(bufferSize = 5, maxConsecutiveSameArtist = 2))
        autoplayEnabled(true)
    }
    fun start(openBrowser: Boolean = true) {
        embeddedServer(Netty, port = port) {
            environment.monitor.subscribe(ApplicationStarted) {
                if (openBrowser) this@JusPlayerHttpEngine.openBrowser("http://localhost:$port/")
            }
            install(ContentNegotiation) {
                json()
            }
            install(CORS) {
                anyHost()
            }
            routing {
                // Self-contained browser demo (Player/Details/Developer), auto-opened.
                get("/") {
                    val html = javaClass.classLoader.getResource("index.html")
                    if (html == null) {
                        call.respondText("Demo UI not found", ContentType.Text.Plain)
                    } else {
                        call.respondText(html.readText(), ContentType.Text.Html)
                    }
                }
                get("/health") { call.respond(mapOf("status" to "ok")) }

                get("/v1/diagnostics") {
                    call.respond(
                        DiagnosticsResponse(
                            musicProvider = ProviderInfo(
                                name = provider.name,
                                capabilities = CapabilitiesInfo(
                                    search = provider.capabilities.search,
                                    getSong = provider.capabilities.getSong,
                                    getStream = provider.capabilities.getStream,
                                    playlists = provider.capabilities.playlists,
                                    recommendations = provider.capabilities.recommendations,
                                ),
                            ),
                            lyricsProvider = lyricsProvider.name,
                            artworkProvider = artworkProvider.name,
                            releaseResolver = releaseResolver.name,
                        ),
                    )
                }

                get("/v1/search") {
                    val q = call.parameters["q"]
                    if (q.isNullOrBlank()) {
                        call.respond(mapOf("error" to "missing q"))
                        return@get
                    }
                    runCatching {
                        val songs = player.engine.search(q)
                        call.respond(SearchResponse(songs = songs))
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                get("/v1/stream/{id}") {
                    val id = call.parameters["id"] ?: ""
                    runCatching {
                        val stream = provider.getStream(id)
                        call.respond(stream)
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                get("/v1/lyrics/{id}") {
                    val id = call.parameters["id"] ?: ""
                    try {
                        val song = provider.getSong(id)
                        val lyrics = player.engine.lyrics(song)
                        if (lyrics != null) {
                            call.respond(LyricsResponse(status = "ok", provider = lyricsProvider.name, lyrics = lyrics))
                        } else {
                            call.respond(
                                LyricsResponse(
                                    status = "not_found",
                                    provider = lyricsProvider.name,
                                    message = "no lyrics found for this track",
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        call.respond(LyricsResponse(status = "error", provider = lyricsProvider.name, message = e.message))
                    }
                }

                get("/v1/artwork/{id}") {
                    val id = call.parameters["id"] ?: ""
                    try {
                        val song = provider.getSong(id)
                        val artwork = player.engine.artwork(song)
                        if (artwork != null) {
                            call.respond(ArtworkResponse(status = "ok", provider = artworkProvider.name, artwork = artwork))
                        } else {
                            call.respond(
                                ArtworkResponse(
                                    status = "not_found",
                                    provider = artworkProvider.name,
                                    message = "no artwork found for this track",
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        call.respond(ArtworkResponse(status = "error", provider = artworkProvider.name, message = e.message))
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
                        val songs = recProvider.getRecommendations(id, limit)
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
                        val song = provider.getSong(id)
                        player.engine.play(song)
                        call.respond(mapOf("ok" to true))
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                post("/v1/player/next") {
                    player.engine.next()
                    call.respond(mapOf("ok" to true))
                }

                post("/v1/player/previous") {
                    player.engine.previous()
                    call.respond(mapOf("ok" to true))
                }

                post("/v1/player/pause") {
                    player.engine.pause()
                    call.respond(mapOf("ok" to true))
                }
                post("/v1/player/stop") {
                    player.engine.stop()
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
                // the browser it reports the natural end here, identifying the
                // song and playback session (generation) that just ended. The
                // engine validates both against its active session so a stale or
                // duplicate completion from a previous track cannot advance or
                // restart playback out of order.
                post("/v1/player/song-ended") {
                    val ms = call.request.queryParameters["ms"]?.toLongOrNull() ?: 0L
                    val songId = call.request.queryParameters["song"].orEmpty()
                    val generation = call.request.queryParameters["generation"]?.toLongOrNull()
                    val current = player.engine.currentSong.value
                    if (current == null || songId != current.id) {
                        call.respond(mapOf("error" to "stale or unknown song"))
                        return@post
                    }
                    player.events.emit(SongEnded(song = current, position = ms, generation = generation))
                    call.respond(mapOf("ok" to true))
                }

                post("/v1/player/queue/add/{id}") {
                    val id = call.parameters["id"] ?: ""
                    runCatching {
                        val song = provider.getSong(id)
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
            generation = player.engine.currentPlayback.value?.generation,
        )
    }

    private fun openBrowser(url: String) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                println("Not opening browser automatically: AWT Desktop unavailable. Open $url")
                return
            }
            Desktop.getDesktop().browse(URI(url))
        } catch (e: Exception) {
            println("Could not open browser automatically (open $url): ${e.message}")
        }
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
    val generation: Long? = null,
)

@Serializable
data class LyricsResponse(
    val status: String,
    val provider: String? = null,
    val message: String? = null,
    val lyrics: Lyrics? = null,
)

@Serializable
data class ArtworkResponse(
    val status: String,
    val provider: String? = null,
    val message: String? = null,
    val artwork: Artwork? = null,
)

@Serializable
data class StreamResponse(
    val stream: JpStream
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class DiagnosticsResponse(
    val musicProvider: ProviderInfo,
    val lyricsProvider: String,
    val artworkProvider: String,
    val releaseResolver: String,
)

@Serializable
data class ProviderInfo(
    val name: String,
    val capabilities: CapabilitiesInfo,
)

@Serializable
data class CapabilitiesInfo(
    val search: Boolean,
    val getSong: Boolean,
    val getStream: Boolean,
    val playlists: Boolean,
    val recommendations: Boolean,
)

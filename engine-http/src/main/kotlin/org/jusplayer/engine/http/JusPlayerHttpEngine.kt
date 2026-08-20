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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import org.jusplayer.engine.provider.ArtworkProvider
import org.jusplayer.engine.provider.LyricsProvider
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.RelatedProvider
import org.jusplayer.engine.provider.SongArtworkProvider
import org.jusplayer.engine.provider.betterlyrics.BetterLyricsProvider
import org.jusplayer.engine.provider.canvas.CanvasArtworkProvider
import org.jusplayer.engine.provider.coverartarchive.CoverArtArchiveProvider
import org.jusplayer.engine.provider.coverartarchive.MusicBrainzResolver
import org.jusplayer.engine.provider.kugou.KuGouProvider
import org.jusplayer.engine.provider.lrclib.LRCLIBProvider
import org.jusplayer.engine.provider.newpipe.NewPipeProvider
import org.jusplayer.engine.provider.paxsenix.PaxsenixProvider
import org.jusplayer.engine.provider.simpmusic.SimpMusicProvider
import org.jusplayer.engine.provider.unison.UnisonProvider
import org.jusplayer.engine.provider.youlyplus.YouLyPlusProvider

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull()
        ?: System.getenv("JUS_ENGINE_PORT")?.toIntOrNull()
        ?: 8368
    JusPlayerHttpEngine(port).start()
}

class JusPlayerHttpEngine(private val port: Int = 8368) {
    private val provider: MusicProvider = NewPipeProvider()
    // Every bundled provider, in the order the chain endpoints try them.
    private val lyricsProviders: List<LyricsProvider> = listOf(
        LRCLIBProvider(),
        KuGouProvider(),
        SimpMusicProvider(),
        PaxsenixProvider(),
        BetterLyricsProvider(),
        UnisonProvider(),
        YouLyPlusProvider(),
    )
    private val artworkProviders: List<ArtworkProvider> = listOf(
        CanvasArtworkProvider(),
        CoverArtArchiveProvider(),
    )
    private val releaseResolver = MusicBrainzResolver()

    // The engine DSL takes a single provider per role; the chain endpoints below
    // iterate the full lists directly so every provider can be exercised.
    private val player = createJusPlayer {
        provider(this@JusPlayerHttpEngine.provider)
        lyricsProvider(this@JusPlayerHttpEngine.lyricsProviders.first())
        artworkProvider(this@JusPlayerHttpEngine.artworkProviders.first())
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

    // Background work (e.g. topping up the queue after a play) so endpoints
    // respond fast while the engine does the recommendation fetch.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                            lyricsProvider = lyricsProviders.first().name,
                            artworkProvider = artworkProviders.first().name,
                            lyricsProviders = lyricsProviders.map { it.name },
                            artworkProviders = artworkProviders.map { it.name },
                            releaseResolver = releaseResolver.name,
                        ),
                    )
                }

                // Everything the server can test, with how each artwork source
                // resolves (direct from the Song vs MusicBrainz MBID).
                get("/v1/providers") {
                    call.respond(
                        ProvidersResponse(
                            lyricsProviders = lyricsProviders.map { ProviderDescriptor(name = it.name) },
                            artworkProviders = artworkProviders.map {
                                ProviderDescriptor(
                                    name = it.name,
                                    kind = if (it is SongArtworkProvider) "song" else "mbid",
                                )
                            },
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

                // Lyrics: chain across every registered lyrics provider and
                // return the first match, reporting each provider's outcome.
                get("/v1/lyrics/{id}") {
                    val id = call.parameters["id"] ?: ""
                    try {
                        val song = provider.getSong(id)
                        val attempts = lyricsProviders.map { lyricsAttempt(it, song) }
                        respondLyricsChain(call, attempts)
                    } catch (e: Exception) {
                        call.respond(LyricsResponse(status = "error", message = e.message))
                    }
                }

                // Lyrics: exercise one specific provider by name.
                get("/v1/lyrics/{lyricsProvider}/{id}") {
                    val id = call.parameters["id"] ?: ""
                    val name = call.parameters["lyricsProvider"] ?: ""
                    val lyricsProvider = lyricsProviders.firstOrNull {
                        it.name.equals(name, ignoreCase = true) || it.name.lowercase() == name.lowercase()
                    }
                    if (lyricsProvider == null) {
                        call.respond(LyricsResponse(status = "error", message = "unknown lyrics provider '$name'"))
                        return@get
                    }
                    try {
                        val song = provider.getSong(id)
                        val attempt = lyricsAttempt(lyricsProvider, song)
                        call.respond(
                            LyricsResponse(
                                status = attempt.status,
                                provider = lyricsProvider.name,
                                message = attempt.message,
                                lyrics = attempt.lyrics,
                                attempts = listOf(attempt),
                            ),
                        )
                    } catch (e: Exception) {
                        call.respond(LyricsResponse(status = "error", provider = lyricsProvider.name, message = e.message))
                    }
                }

                // Artwork: chain across every registered artwork provider. When
                // none match, fall back to the song's own thumbnail (already
                // shown in the player) instead of a bare not_found.
                get("/v1/artwork/{id}") {
                    val id = call.parameters["id"] ?: ""
                    try {
                        val song = provider.getSong(id)
                        val attempts = artworkProviders.map { artworkAttempt(it, song) }
                        val hit = attempts.firstOrNull { it.status == "ok" }
                        val artwork = hit?.artwork ?: song.thumbnailUrl?.let {
                            Artwork(
                                frontUrl = it,
                                source = "YouTubeThumbnail",
                                thumbnails = mapOf("thumbnail" to it),
                            )
                        }
                        if (artwork != null) {
                            call.respond(
                                ArtworkResponse(
                                    status = "ok",
                                    provider = hit?.provider ?: "YouTubeThumbnail",
                                    message = hit?.message,
                                    artwork = artwork,
                                    attempts = attempts,
                                ),
                            )
                        } else {
                            call.respond(
                                ArtworkResponse(
                                    status = "not_found",
                                    message = "no artwork found for this track",
                                    attempts = attempts,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        call.respond(ArtworkResponse(status = "error", message = e.message))
                    }
                }

                // Artwork: exercise one specific artwork provider by name.
                get("/v1/artwork/{artworkProvider}/{id}") {
                    val id = call.parameters["id"] ?: ""
                    val name = call.parameters["artworkProvider"] ?: ""
                    val artworkProvider = artworkProviders.firstOrNull {
                        it.name.equals(name, ignoreCase = true) || it.name.lowercase() == name.lowercase()
                    }
                    if (artworkProvider == null) {
                        call.respond(ArtworkResponse(status = "error", message = "unknown artwork provider '$name'"))
                        return@get
                    }
                    try {
                        val song = provider.getSong(id)
                        val attempt = artworkAttempt(artworkProvider, song)
                        call.respond(
                            ArtworkResponse(
                                status = attempt.status,
                                provider = artworkProvider.name,
                                message = attempt.message,
                                artwork = attempt.artwork,
                                attempts = listOf(attempt),
                            ),
                        )
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
                        // Build the queue automatically: top up with autoplay
                        // recommendations behind the song the user just picked.
                        engineScope.launch { runCatching { player.engine.enqueueAutoplay(song) } }
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
                post("/v1/player/resume") {
                    player.engine.resume()
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

                post("/v1/player/queue/add-next/{id}") {
                    val id = call.parameters["id"] ?: ""
                    runCatching {
                        val song = provider.getSong(id)
                        player.queue.addNext(song)
                        call.respond(mapOf("ok" to true))
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                // Points the queue cursor at [index] and plays that track via the
                // engine (adds nothing, so a stray id can't grow the queue).
                post("/v1/player/queue/jump/{index}") {
                    val index = call.parameters["index"]?.toIntOrNull()
                    if (index == null) {
                        call.respond(mapOf("error" to "missing index"))
                        return@post
                    }
                    runCatching {
                        if (player.queue.jumpTo(index) == null) {
                            call.respond(mapOf("error" to "index out of range"))
                        } else {
                            player.engine.play()
                            call.respond(mapOf("ok" to true))
                        }
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }

                post("/v1/player/queue/move") {
                    val from = call.request.queryParameters["from"]?.toIntOrNull()
                    val to = call.request.queryParameters["to"]?.toIntOrNull()
                    if (from != null && to != null) {
                        player.queue.move(from, to)
                        call.respond(mapOf("ok" to true))
                    } else {
                        call.respond(mapOf("error" to "missing from/to"))
                    }
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

    private suspend fun lyricsAttempt(lyricsProvider: LyricsProvider, song: JpSong): ProviderAttempt =
        try {
            val lyrics = lyricsProvider.getLyrics(song)
            if (lyrics != null) {
                ProviderAttempt(provider = lyricsProvider.name, status = "ok", lyrics = lyrics)
            } else {
                ProviderAttempt(provider = lyricsProvider.name, status = "not_found", message = "no lyrics found")
            }
        } catch (e: Exception) {
            ProviderAttempt(provider = lyricsProvider.name, status = "error", message = e.message)
        }

    private suspend fun artworkAttempt(artworkProvider: ArtworkProvider, song: JpSong): ProviderAttempt =
        try {
            val direct = artworkProvider as? SongArtworkProvider
            val artwork =
                if (direct != null) {
                    direct.getArtwork(song)
                } else {
                    val mbid = releaseResolver.resolveReleaseMbid(song)
                    if (mbid == null) null else artworkProvider.getArtwork(mbid)
                }
            if (artwork != null) {
                ProviderAttempt(provider = artworkProvider.name, status = "ok", artwork = artwork)
            } else {
                ProviderAttempt(provider = artworkProvider.name, status = "not_found", message = "no artwork found")
            }
        } catch (e: Exception) {
            ProviderAttempt(provider = artworkProvider.name, status = "error", message = e.message)
        }

    private suspend fun respondLyricsChain(call: ApplicationCall, attempts: List<ProviderAttempt>) {
        val hit = attempts.firstOrNull { it.status == "ok" }
        if (hit != null) {
            call.respond(
                LyricsResponse(
                    status = "ok",
                    provider = hit.provider,
                    message = hit.message,
                    lyrics = hit.lyrics,
                    attempts = attempts,
                ),
            )
        } else {
            val allFailed = attempts.all { it.status == "error" }
            call.respond(
                LyricsResponse(
                    status = if (allFailed) "error" else "not_found",
                    message = if (allFailed) "all lyrics providers failed" else "no lyrics found by any provider",
                    attempts = attempts,
                ),
            )
        }
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
            hasNext = snapshot.hasNext,
            hasPrevious = snapshot.hasPrevious,
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
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val generation: Long? = null,
)

@Serializable
data class LyricsResponse(
    val status: String,
    val provider: String? = null,
    val message: String? = null,
    val lyrics: Lyrics? = null,
    val attempts: List<ProviderAttempt> = emptyList(),
)

@Serializable
data class ArtworkResponse(
    val status: String,
    val provider: String? = null,
    val message: String? = null,
    val artwork: Artwork? = null,
    val attempts: List<ProviderAttempt> = emptyList(),
)

@Serializable
data class ProviderAttempt(
    val provider: String,
    val status: String,
    val message: String? = null,
    val lyrics: Lyrics? = null,
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
    val lyricsProviders: List<String> = emptyList(),
    val artworkProviders: List<String> = emptyList(),
    val releaseResolver: String,
)

@Serializable
data class ProvidersResponse(
    val lyricsProviders: List<ProviderDescriptor>,
    val artworkProviders: List<ProviderDescriptor>,
    val releaseResolver: String,
)

@Serializable
data class ProviderDescriptor(
    val name: String,
    /** For artwork providers: "song" resolves from the Song directly, "mbid" via MusicBrainz. */
    val kind: String? = null,
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

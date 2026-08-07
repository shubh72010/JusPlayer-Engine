package org.jusplayer.engine.http

import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.awt.Desktop
import java.net.URI
import org.jusplayer.engine.api.createJusPlayer
import org.jusplayer.engine.model.Album
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.Song as JpSong
import org.jusplayer.engine.model.Stream as JpStream
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.coverartarchive.CoverArtArchiveProvider
import org.jusplayer.engine.provider.coverartarchive.MusicBrainzResolver
import org.jusplayer.engine.provider.lrclib.LRCLIBProvider
import org.jusplayer.engine.provider.newpipe.NewPipeProvider
import java.time.Duration

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

    private val player = runBlocking {
        createJusPlayer {
            provider(this@JusPlayerHttpEngine.provider)
            lyricsProvider(this@JusPlayerHttpEngine.lyricsProvider)
            artworkProvider(this@JusPlayerHttpEngine.artworkProvider)
            releaseResolver(this@JusPlayerHttpEngine.releaseResolver)
            player(NoopPlayerAdapter())
        }
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
                    try {
                        val song = runBlocking { provider.getSong(id) }
                        val lyrics = runBlocking { player.engine.lyrics(song) }
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
                        val song = runBlocking { provider.getSong(id) }
                        val artwork = runBlocking { player.engine.artwork(song) }
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
            }
        }.start(wait = true)
    }

    fun stop() {
        // graceful shutdown would go here
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

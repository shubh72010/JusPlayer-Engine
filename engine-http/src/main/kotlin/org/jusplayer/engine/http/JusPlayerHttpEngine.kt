package org.jusplayer.engine.http

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
import org.jusplayer.engine.api.createJusPlayer
import org.jusplayer.engine.model.Album
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song as JpSong
import org.jusplayer.engine.model.Stream as JpStream
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.MusicProvider
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

    private val player = runBlocking {
        createJusPlayer {
            provider(this@JusPlayerHttpEngine.provider)
            player(NoopPlayerAdapter())
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
                        val lyrics = runBlocking { provider.getLyrics(id) }
                        if (lyrics != null) {
                            call.respond(lyrics)
                        } else {
                            call.respond(mapOf("error" to "lyrics unavailable"))
                        }
                    }.getOrElse {
                        call.respond(mapOf("error" to (it.message ?: "unknown")))
                    }
                }
            }
        }.start(wait = true)
    }

    fun stop() {
        // graceful shutdown would go here
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
data class StreamResponse(
    val stream: JpStream
)

@Serializable
data class ErrorResponse(
    val error: String
)

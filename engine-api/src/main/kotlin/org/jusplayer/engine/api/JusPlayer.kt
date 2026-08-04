package org.jusplayer.engine.api

import org.jusplayer.engine.core.JusPlayerEngine
import org.jusplayer.engine.core.JusPlayerConfig
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.model.PlaybackState
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.ArtworkProvider
import org.jusplayer.engine.provider.LyricsProvider
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.ReleaseResolver
import org.jusplayer.engine.queue.QueueEngine

@JusPlayerDsl
class JusPlayerBuilder internal constructor() {
    var provider: MusicProvider? = null
    var playerAdapter: PlayerAdapter? = null
    var eventBus: EventBus? = null
    var queueEngine: QueueEngine? = null
    var lyricsProvider: LyricsProvider? = null
    var artworkProvider: ArtworkProvider? = null
    var releaseResolver: ReleaseResolver? = null

    fun provider(provider: MusicProvider) {
        this.provider = provider
    }

    fun lyricsProvider(provider: LyricsProvider) {
        this.lyricsProvider = provider
    }

    fun artworkProvider(provider: ArtworkProvider) {
        this.artworkProvider = provider
    }

    fun releaseResolver(resolver: ReleaseResolver) {
        this.releaseResolver = resolver
    }

    fun player(adapter: PlayerAdapter) {
        this.playerAdapter = adapter
    }

    fun eventBus(bus: EventBus) {
        this.eventBus = bus
    }

    fun queue(engine: QueueEngine) {
        this.queueEngine = engine
    }

    internal fun build(): JusPlayer {
        val resolvedEventBus = eventBus ?: EventBus()
        val resolvedQueue = queueEngine ?: QueueEngine()
        val resolvedPlayer = playerAdapter
            ?: throw IllegalStateException("A PlayerAdapter is required")
        val config = JusPlayerConfig(
            provider = provider,
            lyricsProvider = lyricsProvider,
            artworkProvider = artworkProvider,
            releaseResolver = releaseResolver,
        )
        val engine = JusPlayerEngine(config, resolvedEventBus, resolvedQueue, resolvedPlayer)
        return JusPlayer(engine, resolvedQueue, resolvedEventBus)
    }
}

@DslMarker
annotation class JusPlayerDsl

class JusPlayer internal constructor(
    val engine: JusPlayerEngine,
    val queue: QueueEngine,
    val events: EventBus,
) {
    val state: PlaybackState
        get() = engine.state.value

    val currentSong: Song?
        get() = engine.currentSong.value
}

fun createJusPlayer(build: JusPlayerBuilder.() -> Unit): JusPlayer {
    val builder = JusPlayerBuilder()
    builder.apply(build)
    return builder.build()
}
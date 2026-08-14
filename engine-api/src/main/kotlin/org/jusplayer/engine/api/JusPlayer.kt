package org.jusplayer.engine.api

import org.jusplayer.engine.autoplay.AutoplayConfig
import org.jusplayer.engine.autoplay.RecommendationProvider
import org.jusplayer.engine.core.JusPlayerConfig
import org.jusplayer.engine.core.JusPlayerEngine
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.model.PlaybackState
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.ArtworkProvider
import org.jusplayer.engine.provider.LyricsProvider
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.ReleaseResolver
import org.jusplayer.engine.queue.QueueEngine

/**
 * DSL builder for [JusPlayer]. Configuration is only reachable through the
 * builder functions (the underlying fields are private), so a builder cannot be
 * mutated out from under the engine.
 */
@JusPlayerDsl
class JusPlayerBuilder internal constructor() {
    private var provider: MusicProvider? = null
    private var playerAdapter: PlayerAdapter? = null
    private var eventBus: EventBus? = null
    private var queueEngine: QueueEngine? = null
    private var lyricsProvider: LyricsProvider? = null
    private var artworkProvider: ArtworkProvider? = null
    private var releaseResolver: ReleaseResolver? = null
    private var autoplayConfig: AutoplayConfig? = null

    private val _recommendationProviders = mutableListOf<RecommendationProvider>()
    val recommendationProviders: List<RecommendationProvider> = _recommendationProviders

    /** Defaults to `true` when at least one [recommendationProvider] is registered. */
    private var autoplayEnabled: Boolean? = null

    private var streamTimeoutMs: Long = 15_000
    private var autoplayTimeoutMs: Long = 15_000

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

    /** Registers an autoplay candidate source. Call multiple times to combine sources. */
    fun recommendationProvider(provider: RecommendationProvider) {
        _recommendationProviders += provider
    }

    fun autoplayEnabled(enabled: Boolean) {
        this.autoplayEnabled = enabled
    }

    /** Tunes the autoplay pipeline (buffer size, recency window, diversity, ...). */
    fun autoplay(config: AutoplayConfig) {
        this.autoplayConfig = config
    }

    /** Engine-level timeout (ms) for resolving a playable stream from a provider. */
    fun streamTimeoutMillis(timeoutMs: Long) {
        this.streamTimeoutMs = timeoutMs
    }

    /** Engine-level timeout (ms) for a single autoplay recommendation pass. */
    fun autoplayTimeoutMillis(timeoutMs: Long) {
        this.autoplayTimeoutMs = timeoutMs
    }

    internal fun build(): JusPlayer {
        val resolvedEventBus = eventBus ?: EventBus()
        val resolvedQueue = queueEngine ?: QueueEngine()
        val resolvedPlayer = playerAdapter
            ?: throw IllegalStateException("A PlayerAdapter is required (player(...))")
        val config = JusPlayerConfig(
            provider = provider,
            lyricsProvider = lyricsProvider,
            artworkProvider = artworkProvider,
            releaseResolver = releaseResolver,
            recommendationProviders = recommendationProviders,
            autoplayConfig = autoplayConfig,
            autoplayEnabled = autoplayEnabled ?: recommendationProviders.isNotEmpty(),
            streamTimeoutMs = streamTimeoutMs,
            autoplayTimeoutMs = autoplayTimeoutMs,
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

    val repeatMode: org.jusplayer.engine.model.RepeatMode
        get() = queue.repeatMode

    val shuffleEnabled: Boolean
        get() = queue.shuffleEnabled

    val hasNext: Boolean
        get() = queue.hasNext

    val autoplayEnabled: Boolean
        get() = engine.autoplayEnabled.value
}

fun createJusPlayer(build: JusPlayerBuilder.() -> Unit): JusPlayer {
    val builder = JusPlayerBuilder()
    builder.apply(build)
    return builder.build()
}
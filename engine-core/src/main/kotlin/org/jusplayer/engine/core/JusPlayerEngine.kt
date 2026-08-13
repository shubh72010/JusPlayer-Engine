package org.jusplayer.engine.core

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jusplayer.engine.autoplay.AutoplayConfig
import org.jusplayer.engine.autoplay.AutoplayContext
import org.jusplayer.engine.autoplay.AutoplayEngine
import org.jusplayer.engine.autoplay.AutoplayHistory
import org.jusplayer.engine.autoplay.DefaultAutoplayEngine
import org.jusplayer.engine.autoplay.RecommendationProvider
import org.jusplayer.engine.core.service.ArtworkService
import org.jusplayer.engine.core.service.LyricsService
import org.jusplayer.engine.core.service.PlaybackService
import org.jusplayer.engine.core.service.SearchService
import org.jusplayer.engine.events.AutoplayEnqueued
import org.jusplayer.engine.events.Event
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.PlaybackError
import org.jusplayer.engine.events.PlaybackPaused
import org.jusplayer.engine.events.PlaybackStopped
import org.jusplayer.engine.events.QueueChanged
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.events.SongStarted
import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.PlaybackState
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.queue.QueueEngine
import org.jusplayer.engine.queue.QueueSnapshot

/**
 * The coordinator.
 *
 * Owns three concerns and keeps them consistent:
 * - **Queue** — the single canonical [QueueEngine] (contents, cursor, repeat,
 *   shuffle). Everything the DSL, playback, and autoplay see is this one object.
 * - **Playback** — a [PlaybackService] wrapping the [PlayerAdapter].
 * - **Autoplay** — an optional [AutoplayEngine] that replenishes the queue when
 *   it is exhausted.
 *
 * Natural completion is event-driven: playback (or the app's adapter) emits
 * [SongEnded]; the engine advances the queue, then plays the next track, then —
 * only when the queue is truly exhausted and nothing stopped it — asks autoplay.
 * User-initiated operations (skip, previous, stop, pause, clear) never emit
 * [SongEnded], so they can never be mistaken for a natural end.
 *
 * All queue/playback transitions are serialized through a [Mutex] so races such
 * as `SongEnded` arriving while the user presses next are resolved one at a time.
 */
class JusPlayerEngine(
    private val config: JusPlayerConfig,
    private val eventBus: EventBus,
    private val queueEngine: QueueEngine,
    private val playerAdapter: PlayerAdapter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transitionMutex = Mutex()

    private val searchService = SearchService(config.provider!!)
    private val playbackService = PlaybackService(playerAdapter, eventBus)
    private val lyricsService = LyricsService(config.lyricsProvider)
    private val artworkService = ArtworkService(config.releaseResolver, config.artworkProvider)

    private val autoplayHistory = AutoplayHistory()
    private val autoplayEngine: AutoplayEngine? = buildAutoplayEngine(config)

    private val _state = MutableStateFlow(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _autoplayEnabled = MutableStateFlow(config.autoplayEnabled)
    val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()

    private val _autoplayCandidates = MutableStateFlow<List<Song>>(emptyList())
    val autoplayCandidates: StateFlow<List<Song>> = _autoplayCandidates.asStateFlow()

    /** Reactive view of the canonical queue (items, cursor, repeat, shuffle, hasNext...). */
    val queueState: StateFlow<QueueSnapshot> = queueEngine.state

    private val _providerRegistry = MutableStateFlow<ProviderRegistry?>(null)
    val providerRegistry: StateFlow<ProviderRegistry?> = _providerRegistry.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is SongStarted -> {
                        // Idempotent with playInternal; covers starts emitted by
                        // an external playback path (adapter-driven).
                        _state.value = PlaybackState.Playing
                        _currentSong.value = event.song
                    }
                    is SongEnded -> handleSongEnded(event.song)
                    else -> Unit
                }
            }
        }
        scope.launch {
            queueEngine.state.collect { snapshot ->
                eventBus.emit(
                    QueueChanged(
                        queueSize = snapshot.size,
                        currentIndex = snapshot.currentIndex,
                        repeatMode = snapshot.repeatMode,
                        shuffleEnabled = snapshot.shuffleEnabled,
                    ),
                )
            }
        }
    }

    suspend fun search(query: String): List<Song> {
        return searchService.search(query).songs
    }

    suspend fun lyrics(song: Song): Lyrics? {
        return lyricsService.lyrics(song)
    }

    suspend fun artwork(song: Song): Artwork? {
        return artworkService.artwork(song)
    }

    /**
     * Plays [song], pointing the queue cursor at it (adding it first if it is
     * not queued) so queue state and playback never diverge.
     */
    suspend fun play(song: Song) {
        if (config.provider == null) throw IllegalStateException("No provider configured")
        transitionMutex.withLock {
            placeSongInQueue(song)
            playInternal(song)
        }
    }

    /** Plays the song currently under the queue cursor. */
    suspend fun play() {
        val song = queueEngine.currentSong ?: throw IllegalStateException("No song in queue")
        play(song)
    }

    /** Manually skip: advance to the next scheduled track (or autoplay when exhausted). */
    suspend fun next() {
        transitionMutex.withLock {
            advance(recordSkip = true)
        }
    }

    /**
     * Conventional previous behavior: restart the current track when more than
     * [RESTART_THRESHOLD_MS] in, otherwise move to the previous track (falls
     * back to "previous" when the adapter reports no position).
     */
    suspend fun previous() {
        transitionMutex.withLock {
            val positionMs = playbackService.position?.toMillis() ?: 0L
            val current = _currentSong.value
            if (positionMs > RESTART_THRESHOLD_MS && current != null) {
                playbackService.seek(Duration.ZERO)
            } else {
                val previousSong = queueEngine.previous()
                if (previousSong != null) {
                    playInternal(previousSong)
                }
            }
        }
    }

    fun pause() {
        playbackService.pause()
        val song = _currentSong.value
        if (song != null) {
            scope.launch { eventBus.emit(PlaybackPaused(song = song, position = positionMs())) }
        }
        _state.value = PlaybackState.Paused
    }

    /** Stops playback. Never triggers autoplay and never looks like a natural end. */
    suspend fun stop() {
        transitionMutex.withLock {
            val song = _currentSong.value
            playbackService.stop()
            _state.value = PlaybackState.Idle
            _currentSong.value = null
            eventBus.emit(PlaybackStopped(song = song, position = 0))
        }
    }

    fun seek(position: Duration) {
        playbackService.seek(position)
    }

    // ── Queue / playback settings ──

    fun setRepeatMode(mode: org.jusplayer.engine.model.RepeatMode) {
        queueEngine.setRepeatMode(mode)
    }

    fun setShuffle(enabled: Boolean) {
        queueEngine.setShuffle(enabled)
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        _autoplayEnabled.value = enabled
    }

    val queue: QueueEngine = queueEngine

    fun setProviderRegistry(registry: ProviderRegistry) {
        _providerRegistry.value = registry
    }

    // ── Progression core ──

    /** Must be called with [transitionMutex] held. */
    private suspend fun advance(recordSkip: Boolean) {
        val current = _currentSong.value
        if (recordSkip && current != null) {
            autoplayHistory.recordSkip(current)
        }

        val nextSong = queueEngine.next()
        if (nextSong != null) {
            playInternal(nextSong)
            return
        }

        // Queue is exhausted or empty. Replenish it only when autoplay is
        // engaged and there is something to build context from.
        if (_autoplayEnabled.value) {
            val engine = autoplayEngine
            val nowPlaying = _currentSong.value
            if (engine != null && nowPlaying != null) {
                val candidates = runCatching {
                    engine.recommend(autoplayContext(nowPlaying))
                }.getOrElse { emptyList() }
                _autoplayCandidates.value = candidates
                if (candidates.isNotEmpty()) {
                    queueEngine.addAll(candidates)
                    eventBus.emit(AutoplayEnqueued(candidates))
                    val auto = queueEngine.next()
                    if (auto != null) {
                        playInternal(auto)
                        return
                    }
                }
            }
        }
        endOfPlayback()
    }

    private suspend fun handleSongEnded(ended: Song) {
        transitionMutex.withLock {
            autoplayHistory.recordComplete(ended)
            advance(recordSkip = false)
        }
    }

    /** Must be called with [transitionMutex] held. */
    private suspend fun playInternal(song: Song) {
        try {
            val stream = resolveStream(song)
            playbackService.play(song, stream)
            // Keep engine state synchronous with playback; the event collector
            // mirrors this for externally-emitted starts.
            _currentSong.value = song
            _state.value = PlaybackState.Playing
            autoplayHistory.recordStart(song)
        } catch (e: Exception) {
            eventBus.emit(PlaybackError(song = song, message = e.message ?: "playback failed"))
            _state.value = PlaybackState.Error
        }
    }

    private suspend fun resolveStream(song: Song): Stream =
        config.provider?.getStream(song.id)
            ?: throw IllegalStateException("No provider configured")

    private fun placeSongInQueue(song: Song) {
        val index = queueEngine.indexOf(song)
        if (index >= 0) {
            queueEngine.jumpTo(index)
        } else {
            queueEngine.add(song)
            queueEngine.jumpTo(queueEngine.size - 1)
        }
    }

    private fun endOfPlayback() {
        _state.value = PlaybackState.Ended
        _currentSong.value = null
    }

    private fun autoplayContext(current: Song): AutoplayContext {
        val recent = autoplayHistory.recentSongs(20)
        return AutoplayContext(
            currentSong = current,
            recentSongs = recent,
            recentArtists = recent.flatMap { it.artists.map { artist -> artist.name } }.distinct(),
            recentGenres = recent.flatMap { it.genres }.distinct(),
            queueSongs = queueEngine.items,
            repeatMode = queueEngine.repeatMode,
            shuffleEnabled = queueEngine.shuffleEnabled,
            timestamp = Instant.now(),
        )
    }

    private fun positionMs(): Long = playbackService.position?.toMillis() ?: 0L

    private fun buildAutoplayEngine(config: JusPlayerConfig): AutoplayEngine? {
        if (config.recommendationProviders.isEmpty()) return null
        return DefaultAutoplayEngine(
            providers = config.recommendationProviders,
            history = autoplayHistory,
            config = config.autoplayConfig ?: AutoplayConfig(),
        )
    }

    companion object {
        /** Position (in milliseconds) beyond which `previous()` restarts instead of going back. */
        const val RESTART_THRESHOLD_MS: Long = 3000
    }

    /** Releases the engine's internal coroutine scope (event collection, queue mirroring). */
    fun close() {
        scope.cancel()
    }
}

data class JusPlayerConfig(
    val provider: MusicProvider? = null,
    val lyricsProvider: org.jusplayer.engine.provider.LyricsProvider? = null,
    val artworkProvider: org.jusplayer.engine.provider.ArtworkProvider? = null,
    val releaseResolver: org.jusplayer.engine.provider.ReleaseResolver? = null,
    val recommendationProviders: List<RecommendationProvider> = emptyList(),
    val autoplayConfig: AutoplayConfig? = null,
    val autoplayEnabled: Boolean = true,
)
package org.jusplayer.engine.core

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.PlaybackError
import org.jusplayer.engine.events.PlaybackPaused
import org.jusplayer.engine.events.PlaybackStopped
import org.jusplayer.engine.events.QueueChanged
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.model.Artwork
import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.PlaybackState
import org.jusplayer.engine.model.PlaybackToken
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.ProviderException
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
 * ## Session token model
 *
 * Every playback session is stamped with a [PlaybackToken] (a fresh
 * [PlaybackToken.generation] + the song id). Slow asynchronous work — provider
 * stream resolution and autoplay recommendations — is performed **outside** the
 * [transitionMutex] so it cannot serialize the engine behind the network; the
 * result is only committed while the session it was started under is still
 * active. A stale or cancelled result can therefore never overwrite newer user
 * intent:
 *
 * ```
 * play(A) → play(B) → A resolves late  → A does NOT start
 * play(A) → pause()  → A resolves late  → A does NOT start
 * play(A) → stop()   → A resolves late  → A does NOT restart
 * ```
 *
 * [SongEnded] completions are validated against the active token: completions
 * for an old generation, duplicates of an already-consumed generation, or
 * completions after [stop] are ignored.
 */
class JusPlayerEngine(
    private val config: JusPlayerConfig,
    private val eventBus: EventBus,
    private val queueEngine: QueueEngine,
    private val playerAdapter: PlayerAdapter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transitionMutex = Mutex()

    private val searchService = config.provider?.let { SearchService(it) }
    private val playbackService = PlaybackService(playerAdapter, eventBus)
    private val lyricsService = LyricsService(config.lyricsProvider)
    private val artworkService = ArtworkService(config.releaseResolver, config.artworkProvider)

    private val autoplayHistory = AutoplayHistory()
    private val autoplayEngine: AutoplayEngine? = buildAutoplayEngine(config)

    private val _state = MutableStateFlow(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    /** The active playback session, or `null` when idle, paused, or stopped. */
    private val _currentPlayback = MutableStateFlow<PlaybackToken?>(null)
    val currentPlayback: StateFlow<PlaybackToken?> = _currentPlayback.asStateFlow()

    // ── Session/generation state ──
    // Guarded by [transitionMutex]; the event collector's fast-path gate reads
    // [activePlayback] without the lock (benign, reads-only race).
    @Volatile
    private var activePlayback: PlaybackToken? = null
    private var generationCounter = 0L

    /** Generation whose natural end has already been consumed (dedup guard). */
    private var lastConsumedGeneration: Long? = null

    private val _autoplayEnabled = MutableStateFlow(config.autoplayEnabled)
    val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()

    private val _autoplayCandidates = MutableStateFlow<List<Song>>(emptyList())
    val autoplayCandidates: StateFlow<List<Song>> = _autoplayCandidates.asStateFlow()

    /** Reactive view of the canonical queue (items, cursor, repeat, shuffle, hasNext...). */
    val queueState: StateFlow<QueueSnapshot> = queueEngine.state

    private val _providerRegistry = MutableStateFlow<ProviderRegistry?>(null)
    val providerRegistry: StateFlow<ProviderRegistry?> = _providerRegistry.asStateFlow()

    init {
        // The event collector only consumes the one internal control signal the
        // engine reacts to: [SongEnded]. It drains quickly — actual transition
        // work runs in separate coroutines serialized by [transitionMutex] — so
        // the SharedFlow buffer can never stall behind slow network calls.
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is SongEnded -> {
                        if (matchesActivePlayback(event)) {
                            scope.launch { handleSongEnded(event) }
                        }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            queueEngine.state.collect { snapshot ->
                eventBus.tryEmit(
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

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val service = searchService ?: throw IllegalStateException("No music provider configured")
        service.search(query).songs
    }

    suspend fun lyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        try {
            lyricsService.lyrics(song)
        } catch (e: ProviderException.NotFound) {
            null
        }
    }

    suspend fun artwork(song: Song): Artwork? = withContext(Dispatchers.IO) {
        try {
            artworkService.artwork(song)
        } catch (e: ProviderException.NotFound) {
            null
        }
    }

    /**
     * Plays [song], pointing the queue cursor at it (adding it first if it is
     * not queued) so queue state and playback never diverge.
     */
    suspend fun play(song: Song) {
        if (config.provider == null) throw IllegalStateException("No music provider configured")
        val intent = transitionMutex.withLock {
            placeSongInQueue(song)
            beginPlayIntent(song)
        }
        startAndCommit(intent)
    }

    /** Plays the song currently under the queue cursor. */
    suspend fun play() {
        val song = queueEngine.currentSong ?: throw IllegalStateException("No song in queue")
        play(song)
    }

    /** Manually skip: advance to the next scheduled track (or autoplay when exhausted). */
    suspend fun next() {
        val step = transitionMutex.withLock {
            if (_state.value == PlaybackState.Error) {
                // Escape hatch: the previous track failed to load. Under
                // RepeatMode.ONE the plain next() would re-select the same
                // broken track forever, so advance past it explicitly.
                val song = queueEngine.nextIgnoringRepeatOne() ?: return@withLock NextStep.End
                NextStep.Play(beginPlayIntent(song))
            } else {
                nextStepLocked(recordSkip = true)
            }
        }
        executeStep(step)
    }

    /**
     * Conventional previous behavior: restart the current track when more than
     * [RESTART_THRESHOLD_MS] in, otherwise move to the previous track (falls
     * back to "previous" when the adapter reports no position).
     */
    suspend fun previous() {
        val intent = transitionMutex.withLock {
            val positionMs = playbackService.position?.toMillis() ?: 0L
            val current = _currentSong.value
            if (positionMs > RESTART_THRESHOLD_MS && current != null) {
                playbackService.seek(Duration.ZERO)
                null
            } else {
                val previousSong = queueEngine.previous()
                previousSong?.let { beginPlayIntent(it) }
            }
        }
        if (intent != null) startAndCommit(intent)
    }

    /**
     * Pauses playback. Invalidates the active session so neither a pending
     * stream resolution nor a stale completion can start or advance playback
     * afterwards.
     */
    suspend fun pause() {
        transitionMutex.withLock {
            playbackService.pause()
            val song = _currentSong.value
            activePlayback = null
            _currentPlayback.value = null
            _state.value = PlaybackState.Paused
            if (song != null) {
                eventBus.tryEmit(PlaybackPaused(song = song, position = positionMs()))
            }
        }
    }

    /**
     * Resumes the paused song (new session). No-op unless currently paused.
     *
     * The cached stream is only reused when it belongs to the song being
     * resumed; otherwise (e.g. the user paused while an auto-advance was still
     * resolving and that advance never committed) the stream is re-resolved so
     * stale audio is never played for a different track.
     */
    suspend fun resume() {
        val intent = transitionMutex.withLock {
            if (_state.value != PlaybackState.Paused) return
            val song = _currentSong.value ?: return
            beginPlayIntent(song)
        }
        val reused = try {
            transitionMutex.withLock {
                if (activePlayback != intent.token) {
                    null
                } else {
                    val resumed = playbackService.resume(intent.song, intent.token)
                    if (resumed) {
                        _currentSong.value = intent.song
                        _state.value = PlaybackState.Playing
                        autoplayHistory.recordStart(intent.song)
                    }
                    resumed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failIntent(intent, e)
            return
        }
        if (reused == false) {
            startAndCommit(intent)
        }
    }

    /** Stops playback. Never triggers autoplay and never looks like a natural end. */
    suspend fun stop() {
        transitionMutex.withLock {
            val song = _currentSong.value
            playbackService.stop()
            activePlayback = null
            _currentPlayback.value = null
            lastConsumedGeneration = null
            _state.value = PlaybackState.Idle
            _currentSong.value = null
            eventBus.tryEmit(PlaybackStopped(song = song, position = 0))
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

    /**
     * Resolves what to do after the current track moved on. Must be called with
     * [transitionMutex] held.
     *
     * The queue cursor and the playing song are kept aligned: when the song that
     * is leaving focus is no longer the queue's current track (it was removed or
     * the queue was cleared), whatever the cursor points at now is played
     * instead of skipping it with a naive [QueueEngine.next].
     */
    private fun nextStepLocked(recordSkip: Boolean): NextStep {
        val current = _currentSong.value
        if (recordSkip && current != null) {
            autoplayHistory.recordSkip(current)
        }
        val queueCurrent = queueEngine.currentSong
        val aligned = current != null && queueCurrent != null && queueCurrent.id == current.id
        val nextSong = if (aligned) queueEngine.next() else queueEngine.currentSong
        if (nextSong != null) {
            return NextStep.Play(beginPlayIntent(nextSong))
        }

        val engine = autoplayEngine
        return if (_autoplayEnabled.value && engine != null && current != null) {
            NextStep.Autoplay(AutoplayRequest(engine, current, activePlayback))
        } else {
            NextStep.End
        }
    }

    private suspend fun executeStep(step: NextStep) {
        when (step) {
            is NextStep.Play -> startAndCommit(step.intent)
            is NextStep.Autoplay -> replenishAutoplay(step.request)
            NextStep.End -> transitionMutex.withLock { endOfPlayback() }
        }
    }

    private suspend fun handleSongEnded(event: SongEnded) {
        val step = transitionMutex.withLock {
            val token = activePlayback ?: return
            if (event.generation != null) {
                if (event.generation != token.generation) return
                if (event.generation == lastConsumedGeneration) return
                if (event.song.id != token.songId) return
            } else if (event.song.id != token.songId) {
                return
            }
            autoplayHistory.recordComplete(event.song)
            lastConsumedGeneration = token.generation
            nextStepLocked(recordSkip = false)
        }
        executeStep(step)
    }

    /**
     * Fast-path relevance gate for the event collector. Mirrors the validation
     * done under the lock in [handleSongEnded]; the lock is authoritative.
     */
    private fun matchesActivePlayback(event: SongEnded): Boolean {
        val token = activePlayback ?: return false
        if (event.generation != null) {
            if (event.generation != token.generation) return false
            if (event.generation == lastConsumedGeneration) return false
            return event.song.id == token.songId
        }
        return event.song.id == token.songId
    }

    /**
     * Records the intent to play [song] under a fresh session token. Must be
     * called with [transitionMutex] held. Sets the engine into
     * [PlaybackState.Buffering]; the actual playback is committed by
     * [startAndCommit] once the stream resolves and the session is still current.
     */
    private fun beginPlayIntent(song: Song): PlayIntent {
        val token = PlaybackToken(++generationCounter, song.id)
        activePlayback = token
        _currentPlayback.value = token
        _currentSong.value = song
        _state.value = PlaybackState.Buffering
        return PlayIntent(token, song)
    }

    /**
     * Resolves the stream for [intent] outside the lock, then commits playback
     * only if [intent] is still the active session.
     */
    private suspend fun startAndCommit(intent: PlayIntent) {
        val stream = try {
            resolveStream(intent.song)
        } catch (e: CancellationException) {
            cancelIntent(intent)
            throw e
        } catch (e: Exception) {
            failIntent(intent, e)
            return
        }
        try {
            transitionMutex.withLock {
                if (activePlayback != intent.token) return@withLock
                commitPlayLocked(intent, stream)
            }
        } catch (e: CancellationException) {
            cancelIntent(intent)
            throw e
        } catch (e: Exception) {
            failIntent(intent, e)
        }
    }

    /** Must be called with [transitionMutex] held and [intent] verified current. */
    private suspend fun commitPlayLocked(intent: PlayIntent, stream: Stream) {
        playbackService.play(intent.song, stream, intent.token)
        _currentSong.value = intent.song
        _state.value = PlaybackState.Playing
        autoplayHistory.recordStart(intent.song)
    }

    private suspend fun failIntent(intent: PlayIntent, e: Exception) {
        transitionMutex.withLock { failIntentLocked(intent, e) }
    }

    /** Must be called with [transitionMutex] held. */
    private fun failIntentLocked(intent: PlayIntent, e: Exception) {
        if (activePlayback != intent.token) return
        activePlayback = null
        _currentPlayback.value = null
        _currentSong.value = null
        _state.value = PlaybackState.Error
        eventBus.tryEmit(PlaybackError(song = intent.song, message = e.message ?: "playback failed"))
    }

    private suspend fun cancelIntent(intent: PlayIntent) {
        // Runs under NonCancellable: this is called from a catch of
        // CancellationException, and a cancelled coroutine cannot acquire the
        // mutex on its own — the cleanup must still run to clear the session.
        withContext(NonCancellable) {
            transitionMutex.withLock {
                if (activePlayback != intent.token) return@withLock
                activePlayback = null
                _currentPlayback.value = null
                _currentSong.value = null
                _state.value = PlaybackState.Idle
            }
        }
    }

    /**
     * Asks the autoplay layer to replenish an exhausted queue. Recommendations
     * run outside the lock; the result is only applied while the session that
     * triggered them is still active. Consumed tracks are pruned so the queue
     * cannot grow without bound.
     */
    private suspend fun replenishAutoplay(request: AutoplayRequest) {
        val candidates = try {
            withContext(Dispatchers.IO) {
                withTimeout(config.autoplayTimeoutMs) {
                    request.engine.recommend(autoplayContext(request.current))
                }
            }
        } catch (e: TimeoutCancellationException) {
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val autoIntent = transitionMutex.withLock {
            if (activePlayback != request.token) return@withLock null
            _autoplayCandidates.value = candidates
            if (candidates.isNotEmpty()) {
                queueEngine.pruneConsumed()
                queueEngine.addAll(candidates)
                eventBus.tryEmit(AutoplayEnqueued(candidates))
                queueEngine.next()?.let { beginPlayIntent(it) }
            } else {
                endOfPlayback()
                null
            }
        }
        if (autoIntent != null) startAndCommit(autoIntent)
    }

    private suspend fun resolveStream(song: Song): Stream = withContext(Dispatchers.IO) {
        val provider = config.provider ?: throw IllegalStateException("No music provider configured")
        val stream = try {
            withTimeout(config.streamTimeoutMs) { provider.getStream(song.id) }
        } catch (e: TimeoutCancellationException) {
            throw ProviderException.Network(
                "Timed out resolving a stream for ${song.id} after ${config.streamTimeoutMs}ms",
                e,
            )
        }
        if (stream.url.isBlank()) {
            throw ProviderException.NotFound("No playable stream for ${song.id}")
        }
        stream
    }

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
        activePlayback = null
        _currentPlayback.value = null
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

    /**
     * Releases the engine's internal coroutine scope and stops the owned
     * [PlayerAdapter]. In-flight transitions are cancelled; the engine resets to
     * [PlaybackState.Idle] with no active session.
     */
    fun close() {
        scope.cancel()
        playbackService.stop()
        activePlayback = null
        _currentPlayback.value = null
        _currentSong.value = null
        _state.value = PlaybackState.Idle
    }

    // ── Step model ──

    private sealed interface NextStep {
        data class Play(val intent: PlayIntent) : NextStep
        data class Autoplay(val request: AutoplayRequest) : NextStep
        object End : NextStep
    }

    private data class PlayIntent(val token: PlaybackToken, val song: Song)

    private data class AutoplayRequest(
        val engine: AutoplayEngine,
        val current: Song,
        /** Session to re-validate against before committing; may be `null`. */
        val token: PlaybackToken?,
    )
}

data class JusPlayerConfig(
    val provider: MusicProvider? = null,
    val lyricsProvider: org.jusplayer.engine.provider.LyricsProvider? = null,
    val artworkProvider: org.jusplayer.engine.provider.ArtworkProvider? = null,
    val releaseResolver: org.jusplayer.engine.provider.ReleaseResolver? = null,
    val recommendationProviders: List<RecommendationProvider> = emptyList(),
    val autoplayConfig: AutoplayConfig? = null,
    val autoplayEnabled: Boolean = true,
    /** Engine-level timeout for resolving a playable stream from a provider. */
    val streamTimeoutMs: Long = 15_000,
    /** Engine-level timeout for a single autoplay recommendation pass. */
    val autoplayTimeoutMs: Long = 15_000,
)
package org.jusplayer.engine.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.PlaybackPaused
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
import org.jusplayer.engine.core.service.ArtworkService
import org.jusplayer.engine.core.service.LyricsService
import org.jusplayer.engine.core.service.PlaybackService
import org.jusplayer.engine.core.service.QueueService
import org.jusplayer.engine.core.service.SearchService

class JusPlayerEngine(
    private val config: JusPlayerConfig,
    private val eventBus: EventBus,
    private val queueEngine: QueueEngine,
    private val playerAdapter: PlayerAdapter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val searchService = SearchService(config.provider!!)
    private val playbackService = PlaybackService(playerAdapter, eventBus)
    private val queueService = QueueService(eventBus)
    private val lyricsService = LyricsService(config.lyricsProvider)
    private val artworkService = ArtworkService(config.releaseResolver, config.artworkProvider)

    private val _state = MutableStateFlow(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _providerRegistry = MutableStateFlow<ProviderRegistry?>(null)
    val providerRegistry: StateFlow<ProviderRegistry?> = _providerRegistry.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is SongStarted -> {
                        _state.value = PlaybackState.Playing
                        _currentSong.value = event.song
                    }
                    is SongEnded -> {
                        _state.value = PlaybackState.Ended
                        _currentSong.value = null
                    }
                    else -> Unit
                }
            }
        }
    }

    suspend fun search(query: String): List<Song> {
        val result = searchService.search(query)
        return result.songs
    }

    /**
     * Returns lyrics for [song] when a [LyricsProvider] is registered, otherwise `null`.
     */
    suspend fun lyrics(song: Song): Lyrics? {
        return lyricsService.lyrics(song)
    }

    /**
     * Resolves [song] to a release and fetches cover art when both a
     * [ReleaseResolver] and an [ArtworkProvider] are registered, otherwise `null`.
     */
    suspend fun artwork(song: Song): Artwork? {
        return artworkService.artwork(song)
    }

    suspend fun play(song: Song) {
        val stream = config.provider?.getStream(song.id)
            ?: throw IllegalStateException("No provider configured")
        playbackService.play(song, stream)
        _currentSong.value = song
        _state.value = PlaybackState.Playing
    }

    suspend fun play() {
        val song = queueEngine.currentSong ?: throw IllegalStateException("No song in queue")
        play(song)
    }

    fun pause() {
        playbackService.pause()
        val song = queueEngine.currentSong
        if (song != null) {
            scope.launch {
                eventBus.emit(PlaybackPaused(song = song, position = 0))
            }
        }
        _state.value = PlaybackState.Paused
    }

    suspend fun stop() {
        playbackService.stop()
        _state.value = PlaybackState.Idle
        _currentSong.value = null
    }

    fun seek(position: java.time.Duration) {
        playbackService.seek(position)
    }

    val queue: QueueEngine = queueEngine

    fun setProviderRegistry(registry: ProviderRegistry) {
        _providerRegistry.value = registry
    }
}

data class JusPlayerConfig(
    val provider: MusicProvider? = null,
    val lyricsProvider: org.jusplayer.engine.provider.LyricsProvider? = null,
    val artworkProvider: org.jusplayer.engine.provider.ArtworkProvider? = null,
    val releaseResolver: org.jusplayer.engine.provider.ReleaseResolver? = null,
)
package org.jusplayer.engine.core

import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jusplayer.engine.autoplay.AutoplayConfig
import org.jusplayer.engine.autoplay.AutoplayContext
import org.jusplayer.engine.autoplay.RecommendationProvider
import org.jusplayer.engine.events.AutoplayEnqueued
import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.PlaybackStopped
import org.jusplayer.engine.events.SongEnded
import org.jusplayer.engine.events.SongStarted
import org.jusplayer.engine.model.PlaybackState
import org.jusplayer.engine.model.RepeatMode
import org.jusplayer.engine.model.SearchResult
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.playback.PlayerAdapter
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.ProviderCapabilities
import org.jusplayer.engine.queue.QueueEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playback-progression / autoplay integration tests.
 *
 * The engine reacts to [SongEnded] on the shared event bus asynchronously, so the
 * harness pre-subscribes captured events and polls the observable state instead
 * of trying to replay single events.
 */
class JusPlayerEngineTest {

    private val queue = QueueEngine()
    private val eventBus = EventBus()
    private val adapter = FakePlayerAdapter()
    private lateinit var engine: JusPlayerEngine
    private val started = mutableListOf<Song>()
    private val stopped = mutableListOf<Any>()
    private val autoplayEnqueued = mutableListOf<AutoplayEnqueued>()
    private var collectorJob: kotlinx.coroutines.Job? = null

    private fun build(
        autoplayEnabled: Boolean = true,
        provider: RecommendationProvider? = FakeRecommendationProvider(),
    ): JusPlayerEngine {
        val config = JusPlayerConfig(
            provider = StubProvider(),
            recommendationProviders = if (provider != null) listOf(provider) else emptyList(),
            autoplayConfig = AutoplayConfig(bufferSize = 3),
            autoplayEnabled = autoplayEnabled,
        )
        engine = JusPlayerEngine(config, eventBus, queue, adapter)
        // Subscribe a test-side capture on the same flow BEFORE any test action,
        // so no emitted event is dropped by the SharedFlow (replay = 0).
        val subscribed = CompletableDeferred<Unit>()
        collectorJob = kotlinx.coroutines.GlobalScope.launch {
            eventBus.events.onSubscription { subscribed.complete(Unit) }.collect { event ->
                when (event) {
                    is SongStarted -> started += event.song
                    is PlaybackStopped -> stopped += event
                    is AutoplayEnqueued -> autoplayEnqueued += event
                    else -> Unit
                }
            }
        }
        runBlocking { subscribed.await() }
        return engine
    }

    @AfterTest
    fun teardown() = runBlocking {
        collectorJob?.cancelAndJoin()
        engine.close()
        queue.clear()
        adapter.reset()
        started.clear()
        stopped.clear()
        autoplayEnqueued.clear()
    }

    // ── Progression ──

    @Test
    fun songNaturallyEndsAdvancesToNext() = runBlocking {
        queue.addAll(listOf(song("1"), song("2")))
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        eventBus.emit(SongEnded(song("1"), 100_000))
        await { started.any { it.id == "2" } }

        assertEquals(song("2"), engine.currentSong.value)
        assertEquals(PlaybackState.Playing, engine.state.value)
        assertEquals(listOf("1", "2"), started.map { it.id })
    }

    @Test
    fun songEndsAtQueueEndStops() = runBlocking {
        queue.add(song("1"))
        build(autoplayEnabled = false)

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        eventBus.emit(SongEnded(song("1"), 100_000))
        awaitState(PlaybackState.Ended)

        assertNull(engine.currentSong.value)
        assertEquals(listOf("1"), started.map { it.id })
    }

    @Test
    fun repeatOneRepeatsCurrentTrack() = runBlocking {
        queue.addAll(listOf(song("1"), song("2")))
        queue.setRepeatMode(org.jusplayer.engine.model.RepeatMode.ONE)
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        eventBus.emit(SongEnded(song("1"), 100_000))
        await { started.count { it.id == "1" } >= 2 }

        assertEquals(song("1"), engine.currentSong.value)
        assertEquals(listOf("1", "1"), started.map { it.id })
    }

    @Test
    fun repeatAllWrapsToFront() = runBlocking {
        queue.addAll(listOf(song("1"), song("2")))
        queue.setRepeatMode(org.jusplayer.engine.model.RepeatMode.ALL)
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        eventBus.emit(SongEnded(song("1"), 100_000))
        await { started.count { it.id == "2" } >= 1 }
        eventBus.emit(SongEnded(song("2"), 100_000))
        await { started.count { it.id == "1" } >= 2 }

        assertEquals(song("1"), engine.currentSong.value)
        assertEquals(listOf("1", "2", "1"), started.map { it.id })
    }

    @Test
    fun manualSkipAdvancesTheQueue() = runBlocking {
        queue.addAll(listOf(song("1"), song("2")))
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        engine.next()
        await { started.any { it.id == "2" } }

        assertEquals(song("2"), engine.currentSong.value)
    }

    @Test
    fun stopDoesNotTriggerAutoplay() = runBlocking {
        queue.add(song("1"))
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        engine.stop()
        await { stopped.isNotEmpty() }

        assertEquals(PlaybackState.Idle, engine.state.value)
        assertNull(engine.currentSong.value)
        assertTrue(autoplayEnqueued.isEmpty())
        assertEquals(listOf("1"), started.map { it.id })
    }

    @Test
    fun pauseDoesNotAdvanceOrAutoplay() = runBlocking {
        queue.add(song("1"))
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        engine.pause()
        assertEquals(PlaybackState.Paused, engine.state.value)
        assertEquals(listOf("1"), started.map { it.id })
        assertTrue(autoplayEnqueued.isEmpty())
    }

    @Test
    fun explicitQueueClearDoesNotStartPlayback() = runBlocking {
        queue.add(song("1"))
        build()

        queue.clear()
        assertEquals(0, engine.queue.size)
        assertEquals(emptyList(), started)
        assertEquals(PlaybackState.Idle, engine.state.value)
    }

    // ── Autoplay ──

    @Test
    fun autoplayFillsExhaustedQueue() = runBlocking {
        queue.add(song("1"))
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        eventBus.emit(SongEnded(song("1"), 100_000))
        await { started.any { it.id == "auto-1" } }

        assertEquals(setOf("auto-1", "auto-2", "auto-3"), autoplayEnqueued.single().songs.map { it.id }.toSet())
        assertEquals("auto-1", engine.currentSong.value?.id)
        assertEquals(listOf("1", "auto-1", "auto-2", "auto-3"), engine.queue.items.map { it.id })
        assertEquals(setOf("auto-1", "auto-2", "auto-3"), engine.autoplayCandidates.value.map { it.id }.toSet())
    }

    @Test
    fun autoplayDisabledLeavesQueueEnded() = runBlocking {
        queue.add(song("1"))
        build(autoplayEnabled = false)

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        eventBus.emit(SongEnded(song("1"), 100_000))
        awaitState(PlaybackState.Ended)

        assertNull(engine.currentSong.value)
        assertTrue(autoplayEnqueued.isEmpty())
        assertEquals(listOf("1"), engine.queue.items.map { it.id })
    }

    @Test
    fun failingRecommendationProviderEndsPlaybackGracefully() = runBlocking {
        queue.add(song("1"))
        build(provider = FailingRecommendationProvider())

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        eventBus.emit(SongEnded(song("1"), 100_000))
        awaitState(PlaybackState.Ended)

        assertNull(engine.currentSong.value)
        assertTrue(engine.autoplayCandidates.value.isEmpty())
        assertTrue(autoplayEnqueued.isEmpty())
    }

    @Test
    fun emptyRecommendationResultEndsPlayback() = runBlocking {
        queue.add(song("1"))
        build(provider = EmptyRecommendationProvider())

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        eventBus.emit(SongEnded(song("1"), 100_000))
        awaitState(PlaybackState.Ended)

        assertNull(engine.currentSong.value)
        assertTrue(engine.autoplayCandidates.value.isEmpty())
    }

    @Test
    fun autoplayRespectsRepeatAll() = runBlocking {
        queue.addAll(listOf(song("1"), song("2")))
        queue.setRepeatMode(org.jusplayer.engine.model.RepeatMode.ALL)
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        assertTrue(engine.queue.hasNext)
        eventBus.emit(SongEnded(song("1"), 100_000))
        await { started.count { it.id == "2" } >= 1 }

        // Repeat-all means the queue never exhausts: autoplay must not have run.
        assertTrue(autoplayEnqueued.isEmpty())
    }

    // ── Previous / position ──

    @Test
    fun previousDeepInTrackRestartsCurrent() = runBlocking {
        queue.addAll(listOf(song("1"), song("2")))
        build()

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)
        adapter.positionValue = Duration.ofSeconds(30)

        engine.previous()

        assertEquals(song("1"), engine.currentSong.value)
        assertEquals(listOf("1"), started.map { it.id })
        assertEquals(Duration.ZERO, adapter.seeks.lastOrNull())
    }

    @Test
    fun previousEarlyInTrackGoesBackOne() = runBlocking {
        queue.addAll(listOf(song("1"), song("2")))
        build()

        engine.play(song("2"))
        awaitState(PlaybackState.Playing)
        adapter.positionValue = Duration.ofMillis(500)

        engine.previous()
        await { started.any { it.id == "1" } }

        assertEquals(song("1"), engine.currentSong.value)
    }

    // ── Concurrency ──

    @Test
    fun concurrentNextAndSongEndedAreSerialized() = runBlocking {
        queue.addAll((1..10).map { song("$it") })
        build(autoplayEnabled = false)

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        val jobs = (1..5).map {
            launch { engine.next() }
        } + launch {
            eventBus.emit(SongEnded(song("1"), 100_000))
        }
        jobs.forEach { it.join() }

        val currentId = engine.currentSong.value?.id
        assertNotNull(currentId)
        assertTrue(currentId.toInt() in 1..10, "unexpected current song $currentId")
        assertTrue(engine.state.value in setOf(PlaybackState.Playing, PlaybackState.Ended))
    }

    @Test
    fun concurrentPlayAndSongEndedDoNotCorruptQueue() = runBlocking {
        queue.addAll(listOf(song("1"), song("2")))
        build(autoplayEnabled = false)

        engine.play(song("1"))
        awaitState(PlaybackState.Playing)

        val jobs = listOf(
            launch { engine.play(song("2")) },
            launch { eventBus.emit(SongEnded(song("1"), 100_000)) },
        )
        jobs.forEach { it.join() }

        val ids = started.map { it.id }
        assertTrue(ids.isNotEmpty(), "nothing ever played")
        assertTrue(ids.all { it in setOf("1", "2") }, "unexpected songs played: $ids")
        // Depending on interleaving, the SongEnded may land last and exhaust the
        // queue (Ended), or play("2") may land last (Playing). Either is a valid
        // serialized outcome — the point is the queue is never corrupted.
        assertTrue(engine.state.value in setOf(PlaybackState.Playing, PlaybackState.Ended))
        if (engine.state.value == PlaybackState.Playing) {
            assertEquals(song("2"), engine.currentSong.value)
        } else {
            assertNull(engine.currentSong.value)
        }
        assertEquals(2, engine.queue.size)
    }

    // ── Helpers ──

    private suspend fun awaitState(target: PlaybackState) {
        withTimeoutOrNull(5_000) {
            engine.state.first { it == target }
        } ?: error("state did not become $target (was ${engine.state.value})")
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeoutOrNull(5_000) {
            while (!condition()) delay(5)
        } ?: error("condition not met in time (started=${started.map { it.id }}, state=${engine.state.value})")
    }

    private fun song(id: String) = Song(
        id = id,
        title = "Song $id",
        artists = listOf(org.jusplayer.engine.model.Artist("artist-$id", "Artist $id", null)),
        album = null,
        duration = 180_000,
        thumbnailUrl = null,
        streamUrl = null,
    )

    // ── Fakes ──

    private class StubProvider : MusicProvider {
        override val name = "stub"
        override val capabilities = ProviderCapabilities()

        override suspend fun search(query: String): SearchResult =
            SearchResult(emptyList(), emptyList(), emptyList(), emptyList())

        override suspend fun getSong(id: String) = throw UnsupportedOperationException()

        override suspend fun getStream(songId: String): Stream =
            Stream(url = "stream://$songId", format = "mp3", bitrate = 0, sampleRate = 0, isLive = false, duration = 180_000)
    }

    private class FakePlayerAdapter : PlayerAdapter {
        val seeks = mutableListOf<Duration>()
        var positionValue: Duration? = null
        override val position: Duration?
            get() = positionValue

        override suspend fun play(stream: Stream) = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun seek(position: Duration) {
            seeks += position
        }

        fun reset() {
            seeks.clear()
            positionValue = null
        }
    }

    private class FakeRecommendationProvider : RecommendationProvider {
        override val name = "fake-rec"
        override suspend fun recommend(context: AutoplayContext, limit: Int): List<Song> {
            return (1..limit).map {
                Song(
                    id = "auto-$it",
                    title = "Auto $it",
                    artists = listOf(org.jusplayer.engine.model.Artist("auto", "Auto", null)),
                    album = null,
                    duration = 1000,
                    thumbnailUrl = null,
                    streamUrl = null,
                )
            }
        }
    }

    private class FailingRecommendationProvider : RecommendationProvider {
        override val name = "failing"
        override suspend fun recommend(context: AutoplayContext, limit: Int): List<Song> =
            throw IllegalStateException("no recommendations today")
    }

    private class EmptyRecommendationProvider : RecommendationProvider {
        override val name = "empty"
        override suspend fun recommend(context: AutoplayContext, limit: Int): List<Song> = emptyList()
    }
}
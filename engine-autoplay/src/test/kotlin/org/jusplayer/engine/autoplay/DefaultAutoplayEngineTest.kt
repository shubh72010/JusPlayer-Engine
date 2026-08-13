package org.jusplayer.engine.autoplay

import kotlinx.coroutines.delay
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.RepeatMode
import org.jusplayer.engine.model.Song
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultAutoplayEngineTest {

    private fun context(current: Song, queue: List<Song> = emptyList()) = AutoplayContext(
        currentSong = current,
        recentSongs = emptyList(),
        recentArtists = emptyList(),
        recentGenres = emptyList(),
        queueSongs = queue,
        repeatMode = RepeatMode.NONE,
        shuffleEnabled = false,
        timestamp = Instant.now(),
    )

    private fun song(id: String, vararg artists: Artist) = Song(
        id = id,
        title = id,
        artists = artists.toList(),
        album = null,
        duration = 1000,
        thumbnailUrl = null,
        streamUrl = null,
    )

    private fun artist(id: String) = Artist(id, id, null)

    @Test
    fun emptyProvidersYieldNothing() = kotlinx.coroutines.runBlocking {
        val engine = DefaultAutoplayEngine(providers = emptyList(), history = AutoplayHistory())
        assertEquals(emptyList(), engine.recommend(context(song("current"))))
    }

    @Test
    fun providerCandidatesArePipelined() = kotlinx.coroutines.runBlocking {
        val provider = object : RecommendationProvider {
            override val name = "test"
            override suspend fun recommend(context2: AutoplayContext, limit: Int): List<Song> =
                listOf(song("current"), song("fresh"), song("fresh2"))
        }
        val engine = DefaultAutoplayEngine(
            providers = listOf(provider),
            history = AutoplayHistory(),
            config = AutoplayConfig(bufferSize = 5),
        )
        val result = engine.recommend(context(song("current")))
        // current track filtered out by the pipeline.
        assertEquals(setOf("fresh", "fresh2"), result.map { it.id }.toSet())
    }

    @Test
    fun failingProviderIsIsolated() = kotlinx.coroutines.runBlocking {
        val broken = object : RecommendationProvider {
            override val name = "broken"
            override suspend fun recommend(context2: AutoplayContext, limit: Int): List<Song> =
                throw IllegalStateException("boom")
        }
        val good = object : RecommendationProvider {
            override val name = "good"
            override suspend fun recommend(context2: AutoplayContext, limit: Int): List<Song> =
                listOf(song("ok"))
        }
        val engine = DefaultAutoplayEngine(
            providers = listOf(broken, good),
            history = AutoplayHistory(),
            config = AutoplayConfig(bufferSize = 5, providerTimeoutMs = 5_000L),
        )
        val result = engine.recommend(context(song("current")))
        assertEquals(listOf("ok"), result.map { it.id })
    }

    @Test
    fun slowProviderTimesOutInsteadOfBlocking() = kotlinx.coroutines.runBlocking {
        val slow = object : RecommendationProvider {
            override val name = "slow"
            override suspend fun recommend(context2: AutoplayContext, limit: Int): List<Song> {
                delay(10_000)
                return listOf(song("too-late"))
            }
        }
        val fast = object : RecommendationProvider {
            override val name = "fast"
            override suspend fun recommend(context2: AutoplayContext, limit: Int): List<Song> =
                listOf(song("now"))
        }
        val engine = DefaultAutoplayEngine(
            providers = listOf(slow, fast),
            history = AutoplayHistory(),
            config = AutoplayConfig(bufferSize = 5, providerTimeoutMs = 200L),
        )
        val result = engine.recommend(context(song("current")))
        assertTrue(result.map { it.id }.contains("now"))
    }

    @Test
    fun combinedProvidersAreAggregated() = kotlinx.coroutines.runBlocking {
        val p1 = object : RecommendationProvider {
            override val name = "p1"
            override suspend fun recommend(c: AutoplayContext, limit: Int): List<Song> = listOf(song("a"))
        }
        val p2 = object : RecommendationProvider {
            override val name = "p2"
            override suspend fun recommend(c: AutoplayContext, limit: Int): List<Song> = listOf(song("b"))
        }
        val engine = DefaultAutoplayEngine(
            providers = listOf(p1, p2),
            history = AutoplayHistory(),
            config = AutoplayConfig(bufferSize = 5),
        )
        val result = engine.recommend(context(song("current")))
        assertEquals(setOf("a", "b"), result.map { it.id }.toSet())
    }
}
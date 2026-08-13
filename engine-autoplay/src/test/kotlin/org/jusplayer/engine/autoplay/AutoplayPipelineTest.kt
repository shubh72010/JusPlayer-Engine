package org.jusplayer.engine.autoplay

import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoplayPipelineTest {

    private val history = AutoplayHistory(maxRecent = 10)
    private val config = AutoplayConfig(
        bufferSize = 5,
        recentlyPlayedWindowMs = 1_000L,
        maxRecentExclusion = 3,
        maxConsecutiveSameArtist = 2,
    )

    @Test
    fun dedupeKeepsFirstOccurrence() {
        val result = AutoplayPipeline.dedupe(listOf(song("a"), song("b"), song("a")))
        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun excludeDropsMatchingIds() {
        val result = AutoplayPipeline.exclude(
            listOf(song("a"), song("b"), song("c")),
            setOf("a", "c"),
        )
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun processExcludesCurrentQueueAndRecentlyPlayed() {
        history.recordStart(song("recent"))
        val current = song("current")
        val candidates = listOf(
            current,
            song("recent"),
            song("queued"),
            song("fresh"),
            song("also-fresh"),
        )
        val result = AutoplayPipeline.process(
            candidates = candidates,
            currentSong = current,
            queueSongs = listOf(song("queued")),
            history = history,
            config = config,
        )
        assertEquals(setOf("fresh", "also-fresh"), result.map { it.id }.toSet())
    }

    @Test
    fun processCapsToBufferSize() {
        val current = song("current")
        val candidates = (1..20).map { song("c$it") }
        val result = AutoplayPipeline.process(
            candidates = candidates,
            currentSong = current,
            queueSongs = emptyList(),
            history = history,
            config = config,
        )
        assertTrue(result.size <= config.bufferSize)
    }

    @Test
    fun processRanksAffinityHigher() {
        // "loved" has strong artist affinity; "cold" never played.
        val current = song("current", artist("me"))
        val loved = song("loved", artist("hero"))
        val cold = song("cold", artist("stranger"))
        history.recordComplete(loved) // hero affinity 1.0

        val result = AutoplayPipeline.process(
            candidates = listOf(cold, loved),
            currentSong = current,
            queueSongs = emptyList(),
            history = history,
            config = config,
        )
        assertEquals(listOf("loved", "cold"), result.map { it.id })
    }

    @Test
    fun diversifyBreaksSameArtistRuns() {
        val ranked = listOf(
            song("a1", artist("X")),
            song("a2", artist("X")),
            song("a3", artist("X")),
            song("a4", artist("X")),
            song("b1", artist("Y")),
        )
        val result = AutoplayPipeline.diversify(ranked, maxConsecutiveSameArtist = 2)

        // No more than 2 consecutive tracks from artist X.
        var run = 0
        var maxRun = 0
        var lastArtist: String? = null
        for (s in result) {
            val artists = s.artists.map { it.id }
            val currentArtist = artists.firstOrNull()
            run = if (currentArtist == lastArtist && currentArtist != null) run + 1 else 1
            maxRun = maxOf(maxRun, run)
            lastArtist = currentArtist
        }
        assertTrue(maxRun <= 2, "artist run exceeded 2: $result")
        assertEquals(ranked.toSet(), result.toSet())
    }

    @Test
    fun processReturnsEmptyForEmptyCandidates() {
        val result = AutoplayPipeline.process(
            candidates = emptyList(),
            currentSong = song("current"),
            queueSongs = emptyList(),
            history = history,
            config = config,
        )
        assertTrue(result.isEmpty())
    }

    private fun artist(id: String) = Artist(id, id, null)

    private fun song(id: String, vararg artists: Artist): Song = Song(
        id = id,
        title = id,
        artists = artists.toList(),
        album = null,
        duration = 1000,
        thumbnailUrl = null,
        streamUrl = null,
    )
}
package org.jusplayer.engine.autoplay

import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoplayHistoryTest {
    private val history = AutoplayHistory(maxRecent = 10)

    @AfterTest
    fun teardown() {
        // nothing global; keeps the suite isolated
    }

    @Test
    fun recordStartTracksMostRecentFirst() {
        history.recordStart(song("a"))
        history.recordStart(song("b"))
        history.recordStart(song("a"))
        // "a" is the most recent start; duplicates move to the front.
        assertEquals(listOf("a", "b"), history.recentSongs().map { it.id })
    }

    @Test
    fun recentIdsRespectTheLimit() {
        history.recordStart(song("a"))
        history.recordStart(song("b"))
        // "b" is the most recent start.
        assertEquals(setOf("b"), history.recentlyPlayedIds(1))
        assertEquals(setOf("b", "a"), history.recentlyPlayedIds(5))
    }

    @Test
    fun recentlyPlayedWithinUsesTheWindow() {
        val clock = Clock(10_000L)
        val h = AutoplayHistory(now = { clock.t })
        h.recordStart(song("fresh"))
        clock.t = 10_000L + 1_000L
        h.recordStart(song("old"))
        clock.t = 11_000L + 1_000L
        assertEquals(setOf("old"), h.recentlyPlayedWithin(1_000L))
    }

    @Test
    fun recordCompleteBuildsArtistAffinity() {
        history.recordComplete(song("a", artist("aria")))
        history.recordComplete(song("b", artist("aria")))
        history.recordComplete(song("c", artist("zeph")))
        assertEquals(1.0, history.artistAffinity(listOf("aria")), 0.001)
        assertEquals(0.5, history.artistAffinity(listOf("zeph")), 0.001)
        assertEquals(0.0, history.artistAffinity(listOf("missing")), 0.001)
        assertEquals(0.0, history.artistAffinity(emptyList()), 0.001)
    }

    @Test
    fun recordCompleteBuildsGenreAffinity() {
        history.recordComplete(song("a", genres = listOf("synth")))
        history.recordComplete(song("b", genres = listOf("synth", "chill")))
        // synth accumulates in 2 of the 2 completed listens → dominates.
        assertEquals(1.0, history.genreAffinity(listOf("synth")), 0.001)
        assertEquals(0.5, history.genreAffinity(listOf("chill")), 0.001)
    }

    @Test
    fun recordSkipFeedsSkipRatio() {
        history.recordStart(song("a"))
        history.recordStart(song("a"))
        history.recordSkip(song("a"))
        assertEquals(0.5, history.skipRatio("a"), 0.001)
        assertEquals(0.0, history.skipRatio("never"), 0.001)
    }

    private class Clock(var t: Long)

    private fun artist(id: String) = Artist(id, id, null)

    private fun song(
        id: String,
        vararg artists: Artist,
        genres: List<String> = emptyList(),
    ): Song = Song(
        id = id,
        title = id,
        artists = artists.toList(),
        album = null,
        duration = 1000,
        thumbnailUrl = null,
        streamUrl = null,
        genres = genres,
    )
}
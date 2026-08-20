package org.jusplayer.engine.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackMatchingTest {

    @Test
    fun normalizationStripsAnnotations() {
        assertEquals(
            "never gonna give you up",
            TrackMatching.normalizeTitle("Never Gonna Give You Up (feat. Barry White)"),
        )
        assertEquals(
            "smooth criminal",
            TrackMatching.normalizeTitle("Smooth Criminal [Official Video] (Remastered)"),
        )
        assertEquals(
            "hotel california live at the forum",
            TrackMatching.normalizeTitle("Hotel California (Live at the Forum)"),
        )
    }

    @Test
    fun exactMatchScoresOne() {
        val score =
            TrackMatching.matchScore(
                "Never Gonna Give You Up",
                "Rick Astley",
                213000,
                "Never Gonna Give You Up",
                "Rick Astley",
                213,
            )
        assertTrue(score >= 0.99, "expected near-perfect match, got $score")
    }

    @Test
    fun durationPenaltyApplied() {
        val close = TrackMatching.matchScore("Song", "Artist", 120000, "Song", "Artist", 121)
        val far = TrackMatching.matchScore("Song", "Artist", 120000, "Song", "Artist", 500)
        assertTrue(close > far, "closer duration should score higher: $close vs $far")
    }

    @Test
    fun precomputedFastPathMatchesPlain() {
        val pre = TrackMatching.precompute("Never Gonna Give You Up", "Rick Astley", 213000)
        val precomputed =
            TrackMatching.matchScorePrecomputed(pre, "Never Gonna Give You Up", "Rick Astley", 213)
        val plain =
            TrackMatching.matchScore("Never Gonna Give You Up", "Rick Astley", 213000, "Never Gonna Give You Up", "Rick Astley", 213)
        assertEquals(plain, precomputed)
    }

    @Test
    fun dissimilarTracksScoreLow() {
        val score = TrackMatching.matchScore("Bohemian Rhapsody", "Queen", 354000, "Gangnam Style", "Psy", 219)
        assertTrue(score < 0.3, "expected low score, got $score")
    }

    @Test
    fun buildSearchQueryCombinesArtistTitle() {
        assertEquals("Rick Astley Never Gonna Give You Up", TrackMatching.buildSearchQuery("Never Gonna Give You Up", "Rick Astley"))
        assertEquals("Solo Track", TrackMatching.buildSearchQuery("Solo Track", ""))
    }

    @Test
    fun nullDurationGetsNeutralScore() {
        val score = TrackMatching.matchScore("Song", "Artist", 0, "Song", "Artist", null)
        // title 1.0 + artist 1.0 + duration 0.5 neutral weight
        assertTrue(score > 0.8, "got $score")
    }
}
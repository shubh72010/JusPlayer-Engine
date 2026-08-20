package org.jusplayer.engine.utils

/**
 * Reusable track-matching logic for cross-referencing a song against candidates
 * from another source (e.g. matching a Spotify or local track to a YouTube search
 * result, or deduplicating autoplay candidates).
 *
 * Ported from the ArchiveTune/JusPlayer app's `SpotifyMapper` and generalized to
 * plain strings so any provider can use it. Scoring weights title similarity
 * highest, then artist, then duration proximity:
 * `title*0.45 + artist*0.35 + duration*0.20`.
 */
object TrackMatching {

    private val FEAT_PATTERN = Regex("\\(feat\\..*?\\)")
    private val FT_PATTERN = Regex("\\(ft\\..*?\\)")
    private val BRACKET_PATTERN = Regex("\\[.*?]")
    private val REMASTER_PATTERN = Regex("\\(.*?remaster.*?\\)", RegexOption.IGNORE_CASE)
    private val REMIX_PATTERN = Regex("\\(.*?remix.*?\\)", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_PATTERN = Regex("[^a-z0-9\\s]")
    private val MULTI_SPACE_PATTERN = Regex("\\s+")

    private const val NORM_CACHE_MAX_SIZE = 256
    private const val EARLY_EXIT_THRESHOLD = 0.95

    /**
     * LRU cache for normalized strings. Avoids re-running 7 regex replacements on
     * the same title/artist across multiple candidate comparisons.
     */
    private val normalizeCache =
        object : LinkedHashMap<String, String>(NORM_CACHE_MAX_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > NORM_CACHE_MAX_SIZE
        }

    /**
     * LRU cache for pre-computed bigram sets, avoiding re-creating [Set]s on
     * every similarity call for the same normalized string.
     */
    private val bigramCache =
        object : LinkedHashMap<String, Set<String>>(NORM_CACHE_MAX_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean =
                size > NORM_CACHE_MAX_SIZE
        }

    /**
     * Pre-computed data for one side of a match comparison, created once per
     * source track and reused across all candidates.
     */
    data class PrecomputedTrack(
        val normalizedTitle: String,
        val titleBigrams: Set<String>,
        val normalizedArtist: String,
        val artistBigrams: Set<String>,
        val durationMs: Int,
    )

    /**
     * Builds a search query for a track, optimized for finding the matching song
     * on YouTube Music / YouTube.
     */
    fun buildSearchQuery(title: String, artist: String?): String {
        val a = artist.orEmpty().trim()
        val t = title.trim()
        return if (a.isEmpty()) t else "$a $t"
    }

    /**
     * Pre-computes normalized title/artist and their bigrams for a source track.
     * Call once before scoring against many candidates to avoid redundant work.
     */
    fun precompute(
        title: String,
        artist: String,
        durationMs: Int,
    ): PrecomputedTrack {
        val normTitle = cachedNormalize(title)
        val normArtist = cachedNormalize(artist)
        return PrecomputedTrack(
            normalizedTitle = normTitle,
            titleBigrams = cachedBigrams(normTitle),
            normalizedArtist = normArtist,
            artistBigrams = cachedBigrams(normArtist),
            durationMs = durationMs,
        )
    }

    /**
     * Computes a match confidence score (0.0–1.0) between a source track and a
     * candidate result based on title, artist, and duration similarity.
     */
    fun matchScore(
        title: String,
        artist: String,
        durationMs: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val normTitle = cachedNormalize(title)
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normArtist = cachedNormalize(artist)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore =
            bigramSimilarity(normTitle, cachedBigrams(normTitle), normCandidateTitle, cachedBigrams(normCandidateTitle))
        val artistScore =
            bigramSimilarity(normArtist, cachedBigrams(normArtist), normCandidateArtist, cachedBigrams(normCandidateArtist))
        val durationScore = durationScore(durationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    /**
     * Scores a candidate against pre-computed source-track data. This is the fast
     * path: normalization and bigrams for the source side are computed once and
     * reused across all candidates.
     */
    fun matchScorePrecomputed(
        precomputed: PrecomputedTrack,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore =
            bigramSimilarity(
                precomputed.normalizedTitle,
                precomputed.titleBigrams,
                normCandidateTitle,
                cachedBigrams(normCandidateTitle),
            )
        val artistScore =
            bigramSimilarity(
                precomputed.normalizedArtist,
                precomputed.artistBigrams,
                normCandidateArtist,
                cachedBigrams(normCandidateArtist),
            )
        val durationScore = durationScore(precomputed.durationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    /** Threshold above which a match is considered good enough to stop early. */
    fun earlyExitThreshold(): Double = EARLY_EXIT_THRESHOLD

    private fun durationScore(
        sourceDurationMs: Int,
        candidateDurationSec: Int?,
    ): Double {
        if (candidateDurationSec == null || sourceDurationMs <= 0) return 0.5
        val diff = kotlin.math.abs(sourceDurationMs / 1000 - candidateDurationSec)
        return when {
            diff <= 2 -> 1.0
            diff <= 5 -> 0.8
            diff <= 10 -> 0.5
            diff <= 30 -> 0.2
            else -> 0.0
        }
    }

    /**
     * Normalizes a title for comparison: strips feat./ft./remix/remaster/bracket
     * annotations, removes non-alphanumeric characters, and lowercases — with LRU
     * caching.
     */
    fun normalizeTitle(title: String): String = cachedNormalize(title)

    private fun cachedNormalize(title: String): String {
        normalizeCache[title]?.let { return it }
        val normalized =
            title
                .lowercase()
                .replace(FEAT_PATTERN, "")
                .replace(FT_PATTERN, "")
                .replace(BRACKET_PATTERN, "")
                .replace(REMASTER_PATTERN, "")
                .replace(REMIX_PATTERN, "")
                .replace(NON_ALNUM_PATTERN, "")
                .replace(MULTI_SPACE_PATTERN, " ")
                .trim()
        normalizeCache[title] = normalized
        return normalized
    }

    private fun cachedBigrams(normalized: String): Set<String> {
        bigramCache[normalized]?.let { return it }
        val bigrams = if (normalized.length < 2) emptySet() else normalized.windowed(2).toSet()
        bigramCache[normalized] = bigrams
        return bigrams
    }

    /**
     * Dice coefficient of two normalized strings computed from their bigram sets.
     */
    private fun bigramSimilarity(
        a: String,
        bigramsA: Set<String>,
        b: String,
        bigramsB: Set<String>,
    ): Double {
        if (a == b) return 1.0
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
        val intersection = bigramsA.count { it in bigramsB }
        return (2.0 * intersection) / (bigramsA.size + bigramsB.size)
    }
}
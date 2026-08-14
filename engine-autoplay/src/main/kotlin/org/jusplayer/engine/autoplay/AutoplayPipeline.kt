package org.jusplayer.engine.autoplay

import org.jusplayer.engine.model.Song

/**
 * Pure, deterministic autoplay pipeline steps — designed so each stage can be
 * unit-tested in isolation and so the scoring/diversity logic is not buried in a
 * manager:
 *
 * ```text
 * candidate generation → filtering → scoring → ranking → diversity → take(buffer)
 * ```
 */
object AutoplayPipeline {

    /**
     * Runs the full pipeline over raw [candidates].
     *
     * 1. **Filter** — drop the current track, anything already queued, and
     *    anything played recently (repetition control).
     * 2. **Score** — rank by coherence with the current track, artist/genre
     *    affinity, freshness, and skip penalty.
     * 3. **Diversify** — avoid long runs of the same artist while keeping the
     *    strongest recommendations first.
     */
    fun process(
        candidates: List<Song>,
        currentSong: Song,
        queueSongs: List<Song>,
        history: AutoplayHistory,
        config: AutoplayConfig,
    ): List<Song> {
        val filtered = exclude(
            dedupe(normalize(candidates)),
            excludedIds = buildSet {
                add(currentSong.id)
                addAll(queueSongs.map { it.id })
                addAll(history.recentlyPlayedWithin(config.recentlyPlayedWindowMs))
                addAll(history.recentlyPlayedIds(config.maxRecentExclusion))
            },
        )
        val ranked = filtered
            .map { it to score(it, currentSong, history) }
            .sortedWith(
                compareByDescending<Pair<Song, Double>> { it.second }
                    .thenBy { it.first.id },
            )
            .map { it.first }
        return diversify(ranked, config.maxConsecutiveSameArtist).take(config.bufferSize)
    }

    /**
     * Normalizes song ids before pipeline stages: trims surrounding whitespace
     * and drops songs with blank/whitespace-only ids so deduplication and
     * exclusion are reliable.
     */
    fun normalize(songs: List<Song>): List<Song> =
        songs.mapNotNull { song ->
            val id = song.id.trim()
            if (id.isEmpty()) null else if (id == song.id) song else song.copy(id = id)
        }

    /** Removes duplicate song ids (first occurrence wins). */
    fun dedupe(songs: List<Song>): List<Song> = songs.distinctBy { it.id }

    /** Removes every song whose id is in [excludedIds]. */
    fun exclude(songs: List<Song>, excludedIds: Set<String>): List<Song> =
        songs.filter { it.id !in excludedIds }

    /**
     * Scores how well [candidate] fits the session. Higher is better.
     *
     * - +0.25 shares an artist with the current track (coherence).
     * - up to +1.0 artist affinity from completed listens.
     * - up to +0.5 genre affinity.
     * - +0.15 freshness bonus for never-played tracks.
     * - up to −0.5 for a history of skips.
     */
    fun score(candidate: Song, currentSong: Song, history: AutoplayHistory): Double {
        var s = 0.0
        val candidateArtistIds = candidate.artists.map { it.id }.toSet()
        val currentArtistIds = currentSong.artists.map { it.id }.toSet()
        if (candidateArtistIds.isNotEmpty() && candidateArtistIds.intersect(currentArtistIds).isNotEmpty()) {
            s += 0.25
        }
        s += history.artistAffinity(candidateArtistIds) * 1.0
        if (candidate.genres.isNotEmpty()) {
            s += history.genreAffinity(candidate.genres) * 0.5
        }
        if (history.lastPlayedAt(candidate.id) == 0L) {
            s += 0.15
        }
        s -= history.skipRatio(candidate.id) * 0.5
        return s.coerceIn(-1.0, 2.0)
    }

    /**
     * Reorders [ranked] so no artist appears more than [maxConsecutiveSameArtist]
     * times in a row, preferring higher-ranked songs. Stable and deterministic
     * for a given input (artist ordering is fixed by song id in [process]).
     */
    fun diversify(ranked: List<Song>, maxConsecutiveSameArtist: Int): List<Song> {
        if (ranked.size <= 1) return ranked
        val result = mutableListOf<Song>()
        val remaining = ranked.toMutableList()
        val recentArtistWindows = ArrayDeque<Set<String>>()

        fun wouldExtendSameArtistRun(song: Song): Boolean {
            val artistIds = song.artists.map { it.id }.toSet()
            if (artistIds.isEmpty()) return false
            // An extension is only a violation once the trailing window is full
            // AND every track in it shares the candidate's artist (that would make
            // a run of max+1). A smaller window means the run can still grow to
            // exactly `max` without exceeding it.
            return recentArtistWindows.size >= maxConsecutiveSameArtist &&
                recentArtistWindows.all { it.isNotEmpty() && it.intersect(artistIds).isNotEmpty() }
        }

        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { !wouldExtendSameArtistRun(it) } ?: remaining.first()
            result.add(next)
            remaining.remove(next)
            recentArtistWindows.addLast(next.artists.map { it.id }.toSet())
            while (recentArtistWindows.size > maxConsecutiveSameArtist) {
                recentArtistWindows.removeFirst()
            }
        }
        return result
    }
}
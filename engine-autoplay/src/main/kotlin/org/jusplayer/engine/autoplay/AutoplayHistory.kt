package org.jusplayer.engine.autoplay

import org.jusplayer.engine.model.Song

/**
 * Tuning knobs for the autoplay pipeline. All defaults are conservative so an
 * engine works sensibly without configuration.
 */
data class AutoplayConfig(
    /** How many tracks autoplay should schedule each time it replenishes. */
    val bufferSize: Int = 5,
    /** Tracks played within this window are excluded from recommendations. */
    val recentlyPlayedWindowMs: Long = 30 * 60 * 1000L,
    /** Cap on how many most-recent tracks are excluded regardless of the window. */
    val maxRecentExclusion: Int = 10,
    /** Maximum number of consecutive tracks sharing an artist before diversity forces a break. */
    val maxConsecutiveSameArtist: Int = 2,
    /** Per-provider timeout for a single recommendation call. */
    val providerTimeoutMs: Long = 15_000L,
)

/**
 * In-memory record of the listening session — the engagement signals that later
 * feed candidate scoring (completion, skip, recency, artist/genre affinity).
 *
 * This is deliberately a plain JVM object with no storage dependency: persistence
 * (disk/profile export) can be layered on later by serializing the collected
 * stats, but the engine itself does not require it.
 *
 * All methods are thread-safe; the engine's coordinator and its event collector
 * may both mutate it.
 */
class AutoplayHistory(
    private val maxRecent: Int = 100,
    private val now: () -> Long = System::currentTimeMillis,
) {

    class Stats(
        var starts: Int = 0,
        var completions: Int = 0,
        var skips: Int = 0,
        var lastPlayedAt: Long = 0L,
    )

    private val lock = Any()
    private val statsById = LinkedHashMap<String, Stats>()
    private val artistCompletionCounts = mutableMapOf<String, Int>()
    private val genreCompletionCounts = mutableMapOf<String, Int>()
    private val recentSongsDeque = ArrayDeque<Song>()

    // ── Recording (fed by the engine coordinator) ──

    /** A track started playing (manually or via auto-advance). */
    fun recordStart(song: Song) = synchronized(lock) {
        val stats = statsById.getOrPut(song.id) { Stats() }
        stats.starts++
        stats.lastPlayedAt = now()
        recentSongsDeque.removeAll { it.id == song.id }
        recentSongsDeque.addLast(song)
        while (recentSongsDeque.size > maxRecent) recentSongsDeque.removeFirst()
    }

    /** A track reached its natural end. */
    fun recordComplete(song: Song) = synchronized(lock) {
        statsById.getOrPut(song.id) { Stats() }.completions++
        song.artists.map { it.id }.forEach { id ->
            artistCompletionCounts[id] = (artistCompletionCounts[id] ?: 0) + 1
        }
        song.genres.forEach { genre ->
            genreCompletionCounts[genre] = (genreCompletionCounts[genre] ?: 0) + 1
        }
    }

    /** A track was skipped or abandoned early. */
    fun recordSkip(song: Song) = synchronized(lock) {
        statsById.getOrPut(song.id) { Stats() }.skips++
    }

    // ── Reads (used by the pipeline and the engine) ──

    /** Most-recently-started tracks, newest first. */
    fun recentSongs(limit: Int = maxRecent): List<Song> = synchronized(lock) {
        recentSongsDeque.takeLast(limit.coerceIn(0, recentSongsDeque.size)).asReversed()
    }

    /** Ids of tracks started within [windowMs] of now. */
    fun recentlyPlayedWithin(windowMs: Long): Set<String> = synchronized(lock) {
        val at = now()
        recentSongsDeque
            .filter { song -> at - (statsById[song.id]?.lastPlayedAt ?: 0L) <= windowMs }
            .map { it.id }
            .toSet()
    }

    /** Ids of the [limit] most recently started tracks. */
    fun recentlyPlayedIds(limit: Int): Set<String> = synchronized(lock) {
        recentSongsDeque.takeLast(limit.coerceIn(0, recentSongsDeque.size)).map { it.id }.toSet()
    }

    fun lastPlayedAt(songId: String): Long = synchronized(lock) {
        statsById[songId]?.lastPlayedAt ?: 0L
    }

    /** Fraction of starts that ended in a skip (0..1). */
    fun skipRatio(songId: String): Double = synchronized(lock) {
        val stats = statsById[songId] ?: return 0.0
        if (stats.starts == 0) 0.0 else stats.skips.toDouble() / stats.starts
    }

    fun completions(songId: String): Int = synchronized(lock) {
        statsById[songId]?.completions ?: 0
    }

    /**
     * Normalized (0..1) affinity for any of [artistIds]: the share of all
     * completed listens that a matching artist accounts for. A value of 1.0
     * means every completed listen has that artist in common.
     */
    fun artistAffinity(artistIds: Collection<String>): Double = synchronized(lock) {
        affinity(artistIds, artistCompletionCounts)
    }

    /** Normalized (0..1) affinity for any of [genres]: same share semantics. */
    fun genreAffinity(genres: Collection<String>): Double = synchronized(lock) {
        affinity(genres, genreCompletionCounts)
    }

    private fun affinity(keys: Collection<String>, counts: Map<String, Int>): Double {
        if (keys.isEmpty()) return 0.0
        val best = keys.maxOfOrNull { counts[it] ?: 0 } ?: return 0.0
        if (best == 0) return 0.0
        return best.toDouble() / counts.values.max()
    }
}
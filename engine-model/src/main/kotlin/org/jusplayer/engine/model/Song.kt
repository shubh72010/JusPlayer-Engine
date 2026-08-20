package org.jusplayer.engine.model

import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val album: Album?,
    /** Duration in seconds. */
    val duration: Long,
    val thumbnailUrl: String?,
    val streamUrl: String?,
    val genres: List<String> = emptyList(),
    val releaseDate: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Song) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Artist) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val duration: Long,
    val thumbnailUrl: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Album) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val songs: List<Song>,
    val thumbnailUrl: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Playlist) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

@Serializable
data class Stream(
    val url: String,
    val format: String,
    /** Bits per second. */
    val bitrate: Long? = null,
    /** Sample rate in Hz, or null when the provider can't determine it. */
    val sampleRate: Int? = null,
    val isLive: Boolean,
    /** Duration in seconds. */
    val duration: Long,
    val codec: String? = null,
    val mimeType: String? = null,
)

/**
 * Song lyrics, optionally with word-level timings.
 *
 * [text] always holds the raw lyrics in their native format (plain text, LRC,
 * TTML) so existing consumers keep working; [lines] exposes a parsed,
 * format-independent view with per-line and per-word timing when a provider can
 * produce one. All timestamps are milliseconds from the start of the song.
 */
@Serializable
data class Lyrics(
    val text: String,
    val source: String,
    val synced: Boolean,
    val lines: List<LyricsLine> = emptyList(),
)

/**
 * A single line of timed lyrics. [start] and [end] are milliseconds from the
 * start of the song; either may be null when the source only provides one bound.
 */
@Serializable
data class LyricsLine(
    val text: String = "",
    val start: Long? = null,
    val end: Long? = null,
    val words: List<LyricsWord> = emptyList(),
)

/** A single word within a [LyricsLine], with optional per-word timing in ms. */
@Serializable
data class LyricsWord(
    val text: String = "",
    val start: Long? = null,
    val end: Long? = null,
)

@Serializable
data class SearchResult(
    val songs: List<Song>,
    val artists: List<Artist>,
    val albums: List<Album>,
    val playlists: List<Playlist>,
) {
    val all: List<Any>
        get() = songs + artists + albums + playlists
}

data class QueueItem(
    val song: Song,
    val addedAt: Long = System.currentTimeMillis(),
)

enum class RepeatMode {
    NONE,
    ONE,
    ALL,
}
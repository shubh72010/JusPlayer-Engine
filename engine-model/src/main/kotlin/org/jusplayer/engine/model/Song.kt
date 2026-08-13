package org.jusplayer.engine.model

import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val album: Album?,
    val duration: Long,
    val thumbnailUrl: String?,
    val streamUrl: String?,
    val genres: List<String> = emptyList(),
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
    val bitrate: Int,
    val sampleRate: Int,
    val isLive: Boolean,
    val duration: Long,
)

@Serializable
data class Lyrics(
    val text: String,
    val source: String,
    val synced: Boolean,
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
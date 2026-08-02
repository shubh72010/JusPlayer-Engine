package org.jusplayer.engine.provider.newpipe.mapping

import org.jusplayer.engine.model.Song
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Maps a NewPipeExtractor stream into the engine's [Song] model.
 */
object SongMapper {

    /** Maps a lightweight search/related result item. */
    fun map(item: StreamInfoItem): Song {
        return Song(
            id = item.url,
            title = item.name,
            artists = listOfNotNull(ArtistMapper.map(item.uploaderName, item.uploaderUrl)),
            album = null,
            duration = item.duration.coerceAtLeast(0),
            thumbnailUrl = item.thumbnails.firstOrNull()?.url,
            streamUrl = null,
        )
    }

    /** Maps a fully extracted [StreamInfo] into a [Song]. */
    fun map(info: StreamInfo): Song {
        return Song(
            id = info.url,
            title = info.name,
            artists = listOfNotNull(ArtistMapper.map(info.uploaderName, info.uploaderUrl)),
            album = null,
            duration = info.duration.coerceAtLeast(0),
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            streamUrl = null,
        )
    }
}

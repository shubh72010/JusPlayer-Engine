package org.jusplayer.engine.provider.newpipe.mapping

import org.jusplayer.engine.model.SearchResult
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Maps a NewPipeExtractor [SearchInfo] into the engine's [SearchResult].
 */
object SearchResultMapper {

    fun map(info: SearchInfo): SearchResult {
        val items = info.relatedItems
        val songs = items.filterIsInstance<StreamInfoItem>().map { SongMapper.map(it) }

        return SearchResult(
            songs = songs,
            artists = emptyList(),
            albums = emptyList(),
            playlists = emptyList(),
        )
    }
}

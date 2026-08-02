package org.jusplayer.engine.provider.newpipe.mapping

import org.jusplayer.engine.model.Artist

/**
 * Maps a NewPipeExtractor uploader/channel into the engine's [Artist] model.
 *
 * Keeping the mapping isolated here means that swapping NewPipeExtractor for
 * another extractor only requires changing this file.
 */
object ArtistMapper {

    fun map(name: String?, url: String?): Artist? {
        if (name.isNullOrBlank()) return null
        return Artist(
            id = url ?: name,
            name = name,
            thumbnailUrl = null,
        )
    }
}

package org.jusplayer.engine.model

import kotlinx.serialization.Serializable

/**
 * Cover art for a song or album, resolved as URLs rather than image bytes.
 *
 * [frontUrl] and [backUrl] point to the largest originals; [thumbnails] maps a
 * size label (e.g. "250", "500", "1200") to its URL. [sourceMbid] records the
 * MusicBrainz release the artwork was resolved from, when known.
 */
@Serializable
data class Artwork(
    val frontUrl: String? = null,
    val backUrl: String? = null,
    val thumbnails: Map<String, String> = emptyMap(),
    val sourceMbid: String? = null,
    val source: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Portrait/video variant of the artwork (e.g. Apple Music canvas), when available. */
    val verticalUrl: String? = null,
)
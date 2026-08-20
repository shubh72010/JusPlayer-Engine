package org.jusplayer.engine.provider.kugou

import kotlinx.serialization.Serializable

@Serializable
internal data class DownloadLyricsResponse(
    val content: String = "",
)

package org.jusplayer.engine.provider.kugou

import kotlinx.serialization.Serializable

@Serializable
internal data class SearchSongResponse(
    val status: Int? = null,
    val errcode: Int? = null,
    val error: String? = null,
    val data: Data? = null,
) {
    @Serializable
    data class Data(
        val info: List<Info> = emptyList(),
    )

    @Serializable
    data class Info(
        val duration: Long = 0,
        val hash: String = "",
    )
}

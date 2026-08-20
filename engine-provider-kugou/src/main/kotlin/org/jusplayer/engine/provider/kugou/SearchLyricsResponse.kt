package org.jusplayer.engine.provider.kugou

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SearchLyricsResponse(
    val status: Int? = null,
    val info: String? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
    val expire: Int? = null,
    val candidates: List<Candidate> = emptyList(),
) {
    @Serializable
    data class Candidate(
        val id: Long = 0,
        @SerialName("product_from")
        val productFrom: String? = null,
        val duration: Long? = null,
        val accesskey: String = "",
    )
}

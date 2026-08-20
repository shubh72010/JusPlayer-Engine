package org.jusplayer.engine.provider.paxsenix.models

import kotlinx.serialization.Serializable

@Serializable
internal data class AppleMusicLyricsResponse(
    val type: String? = null,
    val content: List<AppleMusicLine> = emptyList(),
)

@Serializable
internal data class AppleMusicLine(
    val timestamp: Long = 0,
    val text: List<AppleMusicWord> = emptyList(),
)

@Serializable
internal data class AppleMusicWord(
    val text: String,
    val timestamp: Long? = null,
)

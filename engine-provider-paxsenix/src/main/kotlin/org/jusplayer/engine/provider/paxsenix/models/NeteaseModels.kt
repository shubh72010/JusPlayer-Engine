package org.jusplayer.engine.provider.paxsenix.models

import kotlinx.serialization.Serializable

@Serializable
internal data class NeteaseSearchResponse(
    val result: NeteaseSearchResult? = null,
)

@Serializable
internal data class NeteaseSearchResult(
    val songs: List<NeteaseSong> = emptyList(),
)

@Serializable
internal data class NeteaseSong(
    val id: Long = 0,
    val name: String? = null,
    val artists: List<NeteaseArtist> = emptyList(),
    val duration: Int = 0,
)

@Serializable
internal data class NeteaseArtist(
    val name: String,
)

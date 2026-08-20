package org.jusplayer.engine.provider.lastfm

import kotlinx.serialization.Serializable

@Serializable
data class LastFmAuthentication(
    val session: Session,
) {
    @Serializable
    data class Session(
        val name: String,
        val key: String,
        val subscriber: Int = 0,
    )
}

@Serializable
data class LastFmTokenResponse(
    val token: String,
)

@Serializable
data class LastFmError(
    val error: Int,
    val message: String,
)
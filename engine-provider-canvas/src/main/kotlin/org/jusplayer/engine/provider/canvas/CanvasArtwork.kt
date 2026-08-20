package org.jusplayer.engine.provider.canvas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Motion/canvas artwork metadata, as returned by the canvas artwork servers
 * and the Apple Music editorial-video lookups.
 */
@Serializable
data class CanvasArtwork(
    val name: String? = null,
    val artist: String? = null,
    @SerialName("albumId")
    val albumId: String? = null,
    val albumName: String? = null,
    val static: String? = null,
    val animated: String? = null,
    val animatedVertical: String? = null,
    val videoUrl: String? = null,
    val videoUrlVertical: String? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
) {
    val preferredAnimationUrl: String?
        get() = animated ?: videoUrl

    val preferredVerticalAnimationUrl: String?
        get() = animatedVertical ?: videoUrlVertical
}
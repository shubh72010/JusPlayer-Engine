package org.jusplayer.engine.provider.paxsenix.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class PaxsenixSearchItem(
    val id: String? = null,
    @SerialName("trackId") val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val name: String? = null,
    val songName: String? = null,
    val artistName: String? = null,
    val duration: JsonElement? = null,
) {
    val realId: String
        get() = id ?: trackId ?: ""

    val durationMs: Long
        get() {
            val primitive =
                try {
                    duration?.jsonPrimitive
                } catch (e: Exception) {
                    null
                } ?: return 0

            return primitive.longOrNull ?: run {
                val parts = primitive.content.trim().split(":")
                if (parts.size >= 2) {
                    val seconds = parts.last().toLongOrNull() ?: 0
                    val minutes = parts[parts.size - 2].toLongOrNull() ?: 0
                    val hours = if (parts.size >= 3) parts[parts.size - 3].toLongOrNull() ?: 0 else 0
                    (hours * 3600 + minutes * 60 + seconds) * 1000
                } else {
                    0
                }
            }
        }
}

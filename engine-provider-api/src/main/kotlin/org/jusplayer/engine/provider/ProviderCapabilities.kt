package org.jusplayer.engine.provider

/**
 * Declares which features a [MusicProvider] supports.
 *
 * The engine can inspect these capabilities and adapt automatically (e.g. hide a
 * "lyrics" button when no [LyricsProvider] is registered, or fall back to another
 * provider when [search] is false).
 */
data class ProviderCapabilities(
    val search: Boolean = true,
    val getSong: Boolean = true,
    val getStream: Boolean = true,
    val playlists: Boolean = false,
    val recommendations: Boolean = false,
) {
    companion object {
        val NONE = ProviderCapabilities(
            search = false,
            getSong = false,
            getStream = false,
        )

        val FULL = ProviderCapabilities(
            search = true,
            getSong = true,
            getStream = true,
            playlists = true,
            recommendations = true,
        )
    }
}

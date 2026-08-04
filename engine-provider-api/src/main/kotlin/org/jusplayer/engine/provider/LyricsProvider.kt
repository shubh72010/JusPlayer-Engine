package org.jusplayer.engine.provider

import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.ProviderException

/**
 * A provider of song lyrics, decoupled from [MusicProvider].
 *
 * Lyrics are matched from the metadata on a [Song] (title, artist, duration,
 * album) rather than the streaming-service id, which is why a full [Song] is
 * passed in. Return `null` when no lyrics are available for the song.
 */
interface LyricsProvider {
    val name: String

    suspend fun getLyrics(song: Song): Lyrics?
}
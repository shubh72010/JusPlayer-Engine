package org.jusplayer.engine.core.service

import org.jusplayer.engine.model.Lyrics
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.LyricsProvider

class LyricsService(
    private val provider: LyricsProvider?,
) {
    suspend fun lyrics(song: Song): Lyrics? {
        return provider?.getLyrics(song)
    }
}
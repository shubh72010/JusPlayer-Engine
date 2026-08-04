package org.jusplayer.engine.provider

import org.jusplayer.engine.model.SearchResult
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream

interface MusicProvider {
    val name: String

    /**
     * Declares which operations this provider supports. The engine inspects this
     * to adapt its behavior automatically.
     */
    val capabilities: ProviderCapabilities

    suspend fun search(query: String): SearchResult

    suspend fun getSong(id: String): Song

    suspend fun getStream(songId: String): Stream
}

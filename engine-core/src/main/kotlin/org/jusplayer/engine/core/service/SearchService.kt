package org.jusplayer.engine.core.service

import org.jusplayer.engine.model.SearchResult
import org.jusplayer.engine.provider.MusicProvider

class SearchService(
    private val provider: MusicProvider,
) {
    suspend fun search(query: String): SearchResult {
        return provider.search(query)
    }
}
package org.jusplayer.engine.provider.newpipe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jusplayer.engine.model.SearchResult
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.ProviderCapabilities
import org.jusplayer.engine.provider.ProviderException
import org.jusplayer.engine.provider.RelatedProvider
import org.jusplayer.engine.provider.newpipe.cache.ProviderCache
import org.jusplayer.engine.provider.newpipe.mapping.SearchResultMapper
import org.jusplayer.engine.provider.newpipe.mapping.SongMapper
import org.jusplayer.engine.provider.newpipe.mapping.StreamMapper
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/**
 * A [MusicProvider] backed by NewPipeExtractor (YouTube).
 *
 * All NewPipeExtractor types are confined to this module: callers only ever see
 * the engine's own models and [ProviderException].
 */
class NewPipeProvider : MusicProvider, RelatedProvider {

    private val cache: ProviderCache
    private val downloader: Downloader

    /** Public entry point used by consumers; uses the JVM [JvmDownloader]. */
    constructor(cache: ProviderCache = ProviderCache()) : this(cache, JvmDownloader())

    /** Internal constructor that accepts a custom downloader, for tests. */
    internal constructor(cache: ProviderCache, downloader: Downloader) {
        this.cache = cache
        this.downloader = downloader
        NewPipe.init(downloader)
    }

    override val name: String = "NewPipe"

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        search = true,
        getSong = true,
        getStream = true,
        recommendations = true,
    )

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val cacheKey = "search:$query"
        cache.get<SearchResult>(cacheKey)?.let { return@withContext it }

        val result = runExtraction("Search \"$query\"") {
            withRetry {
                val extractor = ServiceList.YouTube.getSearchExtractor(query)
                extractor.fetchPage()
                SearchResultMapper.map(SearchInfo.getInfo(extractor))
            }
        }

        cache.put(cacheKey, result)
        result
    }

    override suspend fun getSong(id: String): Song = withContext(Dispatchers.IO) {
        val cacheKey = "song:$id"
        cache.get<Song>(cacheKey)?.let { return@withContext it }

        val song = runExtraction("Get song $id") {
            withRetry {
                SongMapper.map(StreamInfo.getInfo(ServiceList.YouTube, id))
            }
        }

        cache.put(cacheKey, song)
        song
    }

    override suspend fun getStream(songId: String): Stream = withContext(Dispatchers.IO) {
        val info = runExtraction("Get stream $songId") {
            withRetry {
                StreamInfo.getInfo(ServiceList.YouTube, songId)
            }
        }
        StreamMapper.map(
            audioStreams = info.audioStreams,
            videoStreams = info.videoStreams,
            streamType = info.streamType,
            duration = info.duration,
        )
    }

    override suspend fun getRecommendations(songId: String, limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            val cacheKey = "related:$songId:$limit"
            cache.get<List<Song>>(cacheKey)?.let { return@withContext it }

            val info = runExtraction("Get related streams $songId") {
                withRetry {
                    StreamInfo.getInfo(ServiceList.YouTube, songId)
                }
            }

            val songs = info.relatedItems
                .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                .filter { it.url != songId }
                .take(limit)
                .map { SongMapper.map(it) }

            cache.put(cacheKey, songs)
            songs
        }

    /**
     * Retries an extraction when the extractor reports a *transient* "page needs
     * to be reloaded" failure — a known NewPipeExtractor condition on YouTube
     * where a second attempt usually succeeds. Permanent availability errors
     * (e.g. a genuinely missing video) are not retried and surface immediately
     * as [ProviderException.NotFound].
     */
    private suspend inline fun <T> withRetry(attempts: Int = 3, block: () -> T): T {
        var remaining = attempts
        while (true) {
            try {
                return block()
            } catch (e: ContentNotAvailableException) {
                if (!isTransient(e)) throw e
                if (remaining-- <= 1) throw e
                delay(RETRY_DELAY_MILLIS)
            }
        }
    }

    private fun isTransient(e: ContentNotAvailableException): Boolean {
        val message = e.message?.lowercase() ?: return false
        return TRANSIENT_MARKERS.any { message.contains(it) }
    }

    private inline fun <T> runExtraction(operation: String, block: () -> T): T {
        return try {
            block()
        } catch (e: ReCaptchaException) {
            throw ProviderException.RateLimited(operation, e)
        } catch (e: ContentNotAvailableException) {
            throw ProviderException.NotFound(operation, e)
        } catch (e: IOException) {
            throw ProviderException.Network(operation, e)
        } catch (e: ExtractionException) {
            throw ProviderException.ExtractionFailed(operation, e)
        } catch (e: RuntimeException) {
            throw ProviderException.ExtractionFailed(operation, e)
        }
    }

    companion object {
        private const val RETRY_DELAY_MILLIS = 500L
        private val TRANSIENT_MARKERS = listOf("reload", "try again", "temporarily", "retry")
    }
}

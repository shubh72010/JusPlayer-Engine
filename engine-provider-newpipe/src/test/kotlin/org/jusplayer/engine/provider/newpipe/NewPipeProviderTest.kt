package org.jusplayer.engine.provider.newpipe

import kotlinx.coroutines.runBlocking
import org.jusplayer.engine.provider.ProviderException
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import org.jusplayer.engine.provider.newpipe.cache.ProviderCache
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NewPipeProviderTest {

    private class NetworkDownDownloader : Downloader() {
        override fun execute(request: Request): Response {
            throw IOException("connection refused")
        }
    }

    private class GarbageDownloader : Downloader() {
        override fun execute(request: Request): Response {
            return Response(200, "OK", emptyMap(), "<html>not youtube data</html>", request.url())
        }
    }

    @Test
    fun networkFailureMapsToProviderExceptionNetwork() = runBlocking {
        val provider = NewPipeProvider(cache = ProviderCache(), downloader = NetworkDownDownloader())

        val ex = assertFailsWith<ProviderException.Network> {
            provider.search("Daft Punk")
        }
        assertTrue(ex.message!!.contains("Search"))
    }

    @Test
    fun unparsableResponseMapsToProviderExceptionExtractionFailed() = runBlocking {
        val provider = NewPipeProvider(cache = ProviderCache(), downloader = GarbageDownloader())

        val ex = assertFailsWith<ProviderException.ExtractionFailed> {
            provider.search("Daft Punk")
        }
        assertTrue(ex.message!!.contains("Search"))
    }

    @Test
    fun capabilitiesDeclareNoLyrics() {
        val provider = NewPipeProvider(cache = ProviderCache(), downloader = NetworkDownDownloader())
        assertTrue(provider.capabilities.search)
        assertTrue(provider.capabilities.getStream)
        assertTrue(!provider.capabilities.lyrics)
    }
}

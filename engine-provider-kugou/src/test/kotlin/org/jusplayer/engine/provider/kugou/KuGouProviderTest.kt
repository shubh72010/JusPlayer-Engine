package org.jusplayer.engine.provider.kugou

import kotlinx.coroutines.runBlocking
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KuGouProviderTest {

    private class StubTransport(
        private val handler: (String) -> HttpTransport.Response,
    ) : HttpTransport {
        val urls = mutableListOf<String>()
        var lastUrl: String? = null

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
            lastUrl = url
            urls += url
            return handler(url)
        }
    }

    private fun song(
        title: String = "Never Gonna Give You Up",
        artist: String = "Rick Astley",
        duration: Long = 213,
    ): Song {
        return Song(
            id = "id-1",
            title = title,
            artists = listOf(Artist(id = "a", name = artist, thumbnailUrl = null)),
            album = null,
            duration = duration,
            thumbnailUrl = null,
            streamUrl = null,
        )
    }

    private fun successTransport(): StubTransport {
        return StubTransport { url ->
            when {
                url.contains("mobileservice.kugou.com/api/v3/search/song") -> HttpTransport.Response(
                    status = 200,
                    body = """{"status":0,"errcode":0,"error":"","data":{"info":[{"duration":213,"hash":"abc123"}]}}""",
                )
                url.contains("lyrics.kugou.com/search") && url.contains("hash=") -> HttpTransport.Response(
                    status = 200,
                    body = """{"status":0,"info":"","errcode":0,"errmsg":"","expire":0,"candidates":[{"id":12345,"product_from":"pc","duration":213000,"accesskey":"secret"}]}""",
                )
                url.contains("lyrics.kugou.com/download") -> HttpTransport.Response(
                    status = 200,
                    body = """{"content":"$BASE64_LRC"}""",
                )
                else -> error("Unexpected URL: $url")
            }
        }
    }

    @Test
    fun fetchesAndDecodesLyrics() = runBlocking {
        val provider = KuGouProvider(successTransport())

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertEquals(
            "[00:00.00]We're no strangers to love\n[00:02.00]You know the rules and so do I",
            lyrics.text,
        )
        assertTrue(lyrics.synced)
        assertEquals("KuGou", lyrics.source)
    }

    @Test
    fun sendsNormalizedKeywordUrlEncoded() = runBlocking {
        val transport = successTransport()
        val provider = KuGouProvider(transport)

        provider.getLyrics(song(title = "Never Gonna Give You Up (Remastered)", artist = "Rick Astley & Co."))

        val keywordUrl = transport.urls.first { it.contains("keyword=") }
        assertTrue(keywordUrl.contains("keyword=Never+Gonna+Give+You+Up"), "unexpected keyword URL: $keywordUrl")
        assertTrue(keywordUrl.contains("Rick+Astley%E3%80%81Co"), "artists not joined with 、: $keywordUrl")
        assertTrue(!keywordUrl.contains("Remastered"), "bracketed title suffix not stripped: $keywordUrl")
        assertTrue(!keywordUrl.contains(".Co"), "trailing period not stripped: $keywordUrl")
    }

    @Test
    fun sendsDurationInMillisecondsForKeywordSearch() = runBlocking {
        val transport = StubTransport { url ->
            when {
                url.contains("mobileservice.kugou.com/api/v3/search/song") -> HttpTransport.Response(
                    status = 200,
                    body = """{"data":{"info":[{"duration":300,"hash":"abc123"}]}}""",
                )
                url.contains("lyrics.kugou.com/search") -> HttpTransport.Response(
                    status = 200,
                    body = """{"status":0,"candidates":[]}""",
                )
                else -> error("Unexpected URL: $url")
            }
        }
        val provider = KuGouProvider(transport)

        assertNull(provider.getLyrics(song(duration = 213)))
        assertTrue(transport.lastUrl!!.contains("duration=213000"))
    }

    @Test
    fun returnsNullWhenNoCandidates() = runBlocking {
        val transport = StubTransport { url ->
            when {
                url.contains("mobileservice.kugou.com/api/v3/search/song") -> HttpTransport.Response(
                    status = 200,
                    body = """{"status":0,"data":{"info":[]}}""",
                )
                url.contains("lyrics.kugou.com/search") -> HttpTransport.Response(
                    status = 200,
                    body = """{"status":0,"candidates":[]}""",
                )
                else -> error("Unexpected URL: $url")
            }
        }
        val provider = KuGouProvider(transport)

        assertNull(provider.getLyrics(song()))
    }

    @Test
    fun returnsNullWhenNoArtist() = runBlocking {
        val transport = successTransport()
        val provider = KuGouProvider(transport)

        assertNull(provider.getLyrics(song().copy(artists = emptyList())))
    }

    @Test
    fun maps404ToNotFound() = runBlocking {
        val transport = StubTransport { HttpTransport.Response(status = 404, body = "") }
        val provider = KuGouProvider(transport)

        val ex = assertFailsWith<ProviderException.NotFound> {
            provider.getLyrics(song())
        }
    }

    @Test
    fun maps429ToRateLimited() = runBlocking {
        val transport = StubTransport { HttpTransport.Response(status = 429, body = "") }
        val provider = KuGouProvider(transport)

        val ex = assertFailsWith<ProviderException.RateLimited> {
            provider.getLyrics(song())
        }
    }

    @Test
    fun mapsInvalidBase64ToExtractionFailed() = runBlocking {
        val transport = StubTransport { url ->
            when {
                url.contains("mobileservice.kugou.com/api/v3/search/song") -> HttpTransport.Response(
                    status = 200,
                    body = """{"data":{"info":[{"duration":213,"hash":"abc123"}]}}""",
                )
                url.contains("lyrics.kugou.com/search") -> HttpTransport.Response(
                    status = 200,
                    body = """{"status":0,"candidates":[{"id":12345,"accesskey":"secret"}]}""",
                )
                url.contains("lyrics.kugou.com/download") -> HttpTransport.Response(
                    status = 200,
                    body = """{"content":"not base64!"}""",
                )
                else -> error("Unexpected URL: $url")
            }
        }
        val provider = KuGouProvider(transport)

        val ex = assertFailsWith<ProviderException.ExtractionFailed> {
            provider.getLyrics(song())
        }
    }

    private companion object {
        const val BASE64_LRC =
            "WzAwOjAwLjAwXVdlJmFwb3M7cmUgbm8gc3RyYW5nZXJzIHRvIGxvdmUKWzAwOjAyLjAwXVlvdSBrbm93IHRoZSBydWxlcyBhbmQgc28gZG8gSQo="
    }
}
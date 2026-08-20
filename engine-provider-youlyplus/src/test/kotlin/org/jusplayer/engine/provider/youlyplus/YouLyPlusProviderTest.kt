package org.jusplayer.engine.provider.youlyplus

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.ProviderException

class YouLyPlusProviderTest {

    private fun song(
        id: String = "vid1",
        title: String = "Never Gonna Give You Up",
        artist: String = "Rick Astley",
        duration: Long = 213,
    ) = Song(
        id = id,
        title = title,
        artists = listOf(Artist(id = "a1", name = artist, thumbnailUrl = null)),
        album = null,
        duration = duration,
        thumbnailUrl = null,
        streamUrl = null,
    )

    private class FakeTransport(
        private val respond: (url: String) -> HttpTransport.Response,
    ) : HttpTransport {
        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response = respond(url)

        override fun post(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): HttpTransport.Response = throw UnsupportedOperationException()
    }

    private fun fake(responseBody: String, status: Int = 200) =
        FakeTransport { HttpTransport.Response(status = status, body = responseBody, headers = emptyMap()) }

    private val ttml =
        "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00:01.00\" end=\"00:00:04.00\">Line one</p></div></body></tt>"

    private val lrcJson =
        """{
            "type": "Word",
            "lyrics": [
                {"time": 1000, "duration": 500, "text": "Never", "syllabus": [
                    {"time": 1000, "duration": 250, "text": "Nev"},
                    {"time": 1250, "duration": 250, "text": "er"}
                ]},
                {"time": 2000, "duration": 800, "text": "gonna"}
            ]
        }"""

    @Test
    fun ttmlFromFirstMirrorWins() = runBlocking {
        val provider = YouLyPlusProvider(fake(ttml))
        val result = provider.getLyrics(song())
        assertNotNull(result)
        assertTrue(result.synced)
        assertTrue(result.text.startsWith("<tt"))
    }

    @Test
    fun fallsBackToLrcWhenTtmlMissing() = runBlocking {
        val transport = FakeTransport { url ->
            when {
                url.contains("v1/ttml") -> HttpTransport.Response(200, """{"ttml":null}""", emptyMap())
                url.contains("v2/lyrics") -> HttpTransport.Response(200, lrcJson, emptyMap())
                else -> HttpTransport.Response(404, "", emptyMap())
            }
        }
        val provider = YouLyPlusProvider(transport)
        val result = provider.getLyrics(song())
        assertNotNull(result)
        assertTrue(result.synced)
        assertTrue(result.text.contains("[00:01.000]<00:01.000>Nev<00:01.250>er"))
        assertEquals(2, result.lines.size)
        val first = result.lines[0]
        assertEquals(1000L, first.start)
        assertEquals(1500L, first.end)
        assertEquals(2, first.words.size)
        assertEquals("Nev", first.words[0].text)
        assertEquals(1250L, first.words[1].start)
    }

    @Test
    fun mirrorFailoverHitsLaterMirror() = runBlocking {
        var hits = 0
        val transport = FakeTransport { url ->
            hits++
            when {
                url.contains("binimum.org") -> HttpTransport.Response(500, "", emptyMap())
                url.contains("prjktla.my.id") -> HttpTransport.Response(200, """{"ttml":"<tt><p>Second mirror</p></tt>"}""", emptyMap())
                else -> HttpTransport.Response(404, "", emptyMap())
            }
        }
        val provider = YouLyPlusProvider(transport)
        val result = provider.getLyrics(song())
        assertNotNull(result)
        assertTrue(result.text.startsWith("<tt"))
        assertTrue(hits >= 2)
    }

    @Test
    fun allMirrorsFailReturnsNull() = runBlocking {
        val provider = YouLyPlusProvider(fake("", status = 503))
        val result = provider.getLyrics(song())
        assertNull(result)
    }

    @Test
    fun rateLimitedThrows() = runBlocking {
        val provider = YouLyPlusProvider(fake("", status = 429))
        val failure = assertFailsWith<ProviderException.RateLimited> {
            provider.getLyrics(song())
        }
        assertTrue(failure.message.orEmpty().isNotEmpty())
    }

    @Test
    fun blankArtistReturnsNull() = runBlocking {
        val provider = YouLyPlusProvider(fake(ttml))
        val result = provider.getLyrics(song(artist = ""))
        assertNull(result)
    }
}
package org.jusplayer.engine.provider.simpmusic

import kotlinx.coroutines.runBlocking
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport
import org.jusplayer.engine.provider.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpMusicProviderTest {

    private class StubTransport(
        private val status: Int = 200,
        private val body: String = "",
    ) : HttpTransport {
        var lastUrl: String? = null
        var lastHeaders: Map<String, String>? = null

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
            lastUrl = url
            lastHeaders = headers
            return HttpTransport.Response(status = status, body = body)
        }
    }

    private fun song(id: String = "dQw4w9WgXcQ", title: String = "Never Gonna Give You Up", duration: Long = 213): Song {
        return Song(
            id = id,
            title = title,
            artists = listOf(Artist(id = "a", name = "Rick Astley", thumbnailUrl = null)),
            album = null,
            duration = duration,
            thumbnailUrl = null,
            streamUrl = null,
        )
    }

    private fun successBody(vararg tracks: String): String {
        val data = tracks.joinToString(",")
        return """{"type":"success","data":[$data]}"""
    }

    @Test
    fun parsesSyncedLyricsWithTimings() = runBlocking {
        val body = successBody(
            """{
                "id":"1",
                "videoId":"dQw4w9WgXcQ",
                "songTitle":"Never Gonna Give You Up",
                "artistName":"Rick Astley",
                "durationSeconds":213,
                "syncedLyrics":"[00:17.12] We're no strangers to love\n[00:20.5] You know the rules and so do I"
            }""".trimIndent(),
        )
        val transport = StubTransport(status = 200, body = body)
        val provider = SimpMusicProvider(transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertTrue(lyrics.synced)
        assertEquals("SimpMusic", lyrics.source)
        assertTrue(lyrics.text.startsWith("[00:17.12]"))
        assertEquals(2, lyrics.lines.size)
        assertEquals("We're no strangers to love", lyrics.lines[0].text)
        assertEquals(17_120L, lyrics.lines[0].start)
        assertEquals("You know the rules and so do I", lyrics.lines[1].text)
        assertEquals(20_500L, lyrics.lines[1].start)
    }

    @Test
    fun fallsBackToPlainLyricsWhenNoSynced() = runBlocking {
        val body = successBody(
            """{
                "id":"1",
                "videoId":"dQw4w9WgXcQ",
                "durationSeconds":213,
                "plainLyric":"Never gonna give you up\nNever gonna let you down"
            }""".trimIndent(),
        )
        val transport = StubTransport(status = 200, body = body)
        val provider = SimpMusicProvider(transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertFalse(lyrics.synced)
        assertEquals("Never gonna give you up\nNever gonna let you down", lyrics.text)
        assertTrue(lyrics.lines.isEmpty())
    }

    @Test
    fun prefersSingleCandidateRegardlessOfDuration() = runBlocking {
        val body = successBody(
            """{"id":"1","videoId":"dQw4w9WgXcQ","durationSeconds":10,"syncedLyrics":"[00:01.00] A far-off match"}""",
        )
        val transport = StubTransport(status = 200, body = body)
        val provider = SimpMusicProvider(transport)

        val lyrics = provider.getLyrics(song(duration = 213))

        assertNotNull(lyrics)
        assertTrue(lyrics.text.contains("A far-off match"))
    }

    @Test
    fun selectsClosestDurationCandidateWithinTolerance() = runBlocking {
        val body = successBody(
            """{"id":"1","videoId":"dQw4w9WgXcQ","durationSeconds":60,"syncedLyrics":"[00:01.00] Wrong guess"}""",
            """{"id":"2","videoId":"dQw4w9WgXcQ","durationSeconds":214,"syncedLyrics":"[00:01.00] Best match"}""",
            """{"id":"3","videoId":"dQw4w9WgXcQ","durationSeconds":300,"syncedLyrics":"[00:01.00] Way off"}""",
        )
        val transport = StubTransport(status = 200, body = body)
        val provider = SimpMusicProvider(transport)

        val lyrics = provider.getLyrics(song(duration = 213))

        assertNotNull(lyrics)
        assertTrue(lyrics.text.contains("Best match"))
    }

    @Test
    fun returnsNullWhenClosestCandidateIsOutsideTolerance() = runBlocking {
        val body = successBody(
            """{"id":"1","videoId":"dQw4w9WgXcQ","durationSeconds":10,"syncedLyrics":"[00:01.00] Too far"}""",
            """{"id":"2","videoId":"dQw4w9WgXcQ","durationSeconds":300,"syncedLyrics":"[00:01.00] Too far 2"}""",
        )
        val transport = StubTransport(status = 200, body = body)
        val provider = SimpMusicProvider(transport)

        assertNull(provider.getLyrics(song(duration = 213)))
    }

    @Test
    fun returnsNullForEmptyData() = runBlocking {
        val transport = StubTransport(status = 200, body = """{"type":"success","data":[]}""")
        val provider = SimpMusicProvider(transport)

        assertNull(provider.getLyrics(song()))
    }

    @Test
    fun returnsNullForUnsuccessfulType() = runBlocking {
        val transport = StubTransport(status = 200, body = """{"type":"error","data":[]}""")
        val provider = SimpMusicProvider(transport)

        assertNull(provider.getLyrics(song()))
    }

    @Test
    fun hitsEndpointWithSongIdAndHeaders() = runBlocking {
        val transport = StubTransport(status = 200, body = """{"type":"success","data":[]}""")
        val provider = SimpMusicProvider(transport)

        provider.getLyrics(song(id = "dQw4w9WgXcQ"))

        assertEquals("https://api-lyrics.simpmusic.org/v1/dQw4w9WgXcQ", transport.lastUrl)
        assertEquals("application/json", transport.lastHeaders!!["Accept"])
        assertEquals("SimpMusicLyrics/1.0", transport.lastHeaders!!["User-Agent"])
    }

    @Test
    fun maps404ToNotFound() = runBlocking {
        val transport = StubTransport(status = 404, body = """{"type":"error","data":[]}""")
        val provider = SimpMusicProvider(transport)

        val ex = assertFailsWith<ProviderException.NotFound> {
            provider.getLyrics(song())
        }
        assertTrue(ex.message!!.contains("Never Gonna Give You Up"))
    }

    @Test
    fun maps429ToRateLimited() = runBlocking {
        val transport = StubTransport(status = 429, body = "")
        val provider = SimpMusicProvider(transport)

        val ex = assertFailsWith<ProviderException.RateLimited> {
            provider.getLyrics(song())
        }
        assertTrue(ex.message!!.contains("rate limit"))
    }

    @Test
    fun returnsNullForBlankSongId() = runBlocking {
        val transport = StubTransport(status = 200, body = """{"type":"success","data":[]}""")
        val provider = SimpMusicProvider(transport)

        assertNull(provider.getLyrics(song(id = "")))
    }
}

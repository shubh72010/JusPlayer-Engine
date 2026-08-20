package org.jusplayer.engine.provider.paxsenix

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

class PaxsenixProviderTest {

    private class StubTransport(
        var routes: Map<String, String> = emptyMap(),
        var statuses: Map<String, Int> = emptyMap(),
    ) : HttpTransport {
        val calls = mutableListOf<String>()

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
            calls += url
            val fragment = routes.keys.firstOrNull { url.contains(it) }
            return if (fragment != null) {
                HttpTransport.Response(status = statuses[fragment] ?: 200, body = routes.getValue(fragment))
            } else {
                HttpTransport.Response(status = 404, body = "")
            }
        }
    }

    private fun song(title: String = "I Want to Live", duration: Long = 233): Song {
        return Song(
            id = "id-1",
            title = title,
            artists = listOf(Artist(id = "a", name = "Borislav Slavov", thumbnailUrl = null)),
            album = null,
            duration = duration,
            thumbnailUrl = null,
            streamUrl = null,
        )
    }

    private val neteaseSearchOk =
        """
        {"result":{"songs":[
            {"id":186016,"name":"I Want to Live","artists":[{"name":"Borislav Slavov"}],"duration":233000}
        ]}}
        """.trimIndent()

    private val neteaseLyricsLrc =
        """
        {"lrc":{"lyric":"[00:00.00]Wait for the moment\n[00:17.12]I feel your breath upon my neck"},"klyric":null}
        """.trimIndent()

    private val neteaseLyricsKlyric =
        """
        {"klyric":{"lyric":"[00:17.12]WORD lyrics"},"lrc":{"lyric":"[00:17.12]LINE lyrics"}}
        """.trimIndent()

    @Test
    fun neteaseSuccessReturnsSyncedLyrics() = runBlocking {
        val transport = StubTransport(
            routes = mapOf(
                "netease/search" to neteaseSearchOk,
                "netease/lyrics" to neteaseLyricsLrc,
            ),
        )
        val provider = PaxsenixProvider(transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertEquals("Paxsenix", lyrics.source)
        assertTrue(lyrics.synced)
        assertTrue(lyrics.text.contains("[00:17.12]I feel your breath upon my neck"))
        assertEquals(2, lyrics.lines.size)
        assertEquals(17_120L, lyrics.lines[1].start)
    }

    @Test
    fun neteasePrefersKlyricOverLrc() = runBlocking {
        val transport = StubTransport(
            routes = mapOf(
                "netease/search" to neteaseSearchOk,
                "netease/lyrics" to neteaseLyricsKlyric,
            ),
        )
        val provider = PaxsenixProvider(transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertEquals("[00:17.12]WORD lyrics", lyrics.text)
    }

    @Test
    fun fallbackChainContinuesWhenFirstBackend404s() = runBlocking {
        val transport = StubTransport(
            routes = mapOf(
                "apple-music/lyrics" to "{}",
                "netease/search" to neteaseSearchOk,
                "netease/lyrics" to neteaseLyricsLrc,
            ),
            statuses = mapOf("apple-music/lyrics" to 404),
        )
        val provider = PaxsenixProvider(transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertTrue(lyrics.text.contains("[00:17.12]"))
        assertTrue(transport.calls.any { it.contains("apple-music/lyrics") })
        assertTrue(transport.calls.any { it.contains("netease/lyrics") })
    }

    @Test
    fun fallsThroughToSpotifyWhenNetEaseEmpty() = runBlocking {
        val spotifySearchOk =
            """
            [{"id":"4iV5W9uYEdYUVa79Axb7Rh","name":"I Want to Live","artist":"Borislav Slavov","duration":233000}]
            """.trimIndent()
        val spotifyLyricsOk = """{"lyrics":"[00:17.12] I feel your breath upon my neck"}"""
        val transport = StubTransport(
            routes = mapOf(
                "netease/search" to """{"result":{"songs":[]}}""",
                "spotify/search" to spotifySearchOk,
                "spotify/lyrics" to spotifyLyricsOk,
            ),
        )
        val provider = PaxsenixProvider(transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertTrue(lyrics.text.contains("[00:17.12]"))
        assertTrue(transport.calls.any { it.contains("spotify/lyrics") })
    }

    @Test
    fun maps429ToRateLimited() = runBlocking {
        val transport = StubTransport(
            routes = mapOf("apple-music/lyrics" to "{}"),
            statuses = mapOf("apple-music/lyrics" to 429),
        )
        val provider = PaxsenixProvider(transport)

        val ex = assertFailsWith<ProviderException.RateLimited> {
            provider.getLyrics(song())
        }
        assertTrue(ex.message!!.contains("Apple Music"))
    }

    @Test
    fun returnsNullWhenEveryBackendFails() = runBlocking {
        val transport = StubTransport(
            routes = mapOf(
                "apple-music/lyrics" to "{}",
                "netease/search" to "{}",
                "spotify/search" to "{}",
                "musixmatch/lyrics" to "{}",
            ),
            statuses = mapOf(
                "apple-music/lyrics" to 404,
                "netease/search" to 404,
                "spotify/search" to 404,
                "musixmatch/lyrics" to 404,
            ),
        )
        val provider = PaxsenixProvider(transport)

        assertNull(provider.getLyrics(song()))
    }

    @Test
    fun returnsNullWhenNoArtist() = runBlocking {
        val transport = StubTransport()
        val provider = PaxsenixProvider(transport)

        val noArtist = song().copy(artists = emptyList())
        assertNull(provider.getLyrics(noArtist))
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun appleMusicJsonConvertsToLrc() = runBlocking {
        val ttmlBody = """{"content":{"text":"not ttml"}}"""
        val jsonBody =
            """
            {"type":"legacy","content":[
                {"timestamp":17120,"text":[{"text":"I feel","timestamp":17120},{"text":"your breath","timestamp":17480}]}
            ]}
            """.trimIndent()
        val transport = StubTransport(
            routes = mapOf(
                "apple-music/lyrics?id=id-1&ttml=true" to ttmlBody,
                "apple-music/lyrics?id=id-1" to jsonBody,
            ),
        )
        val provider = PaxsenixProvider(transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertEquals("[00:17.12]I feel your breath", lyrics.text)
        assertTrue(lyrics.synced)
    }
}
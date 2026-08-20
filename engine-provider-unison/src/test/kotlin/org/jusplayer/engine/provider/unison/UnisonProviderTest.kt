package org.jusplayer.engine.provider.unison

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

class UnisonProviderTest {

    private class StubTransport(
        private val handler: (url: String) -> HttpTransport.Response,
    ) : HttpTransport {
        val urls = mutableListOf<String>()

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
            urls += url
            return handler(url)
        }
    }

    private fun song(
        id: String = "id-1",
        title: String = "Never Gonna Give You Up",
        duration: Long = 213,
    ): Song {
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

    @Test
    fun fetchesByVideoIdFastPath() = runBlocking {
        val body = """
            {
              "success": true,
              "data": {
                "id": 42,
                "videoId": "dQw4w9WgXcQ",
                "song": "Never Gonna Give You Up",
                "artist": "Rick Astley",
                "lyrics": "[00:17.12] We're no strangers to love\n[00:20.05] You know the rules and so do I",
                "format": "lrc",
                "syncType": "synced"
              }
            }
        """.trimIndent()
        val transport = StubTransport { HttpTransport.Response(status = 200, body = body) }
        val provider = UnisonProvider(transport = transport)

        val lyrics = provider.getLyrics(song(id = "dQw4w9WgXcQ"))

        assertNotNull(lyrics)
        assertTrue(transport.urls.single().contains("lyrics?v=dQw4w9WgXcQ"))
        assertEquals("Unison", lyrics.source)
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals("We're no strangers to love", lyrics.lines[0].text)
        assertEquals(17_120, lyrics.lines[0].start)
        assertEquals(20_050, lyrics.lines[0].end)
    }

    @Test
    fun fallsBackToMetadataSearch() = runBlocking {
        val searchBody = """
            {
              "success": true,
              "data": [
                {
                  "id": 7,
                  "song": "Never Gonna Give You Up",
                  "artist": "Rick Astley",
                  "lyrics": "Never gonna give you up, never gonna let you down",
                  "format": "plain",
                  "syncType": "unsynced"
                }
              ]
            }
        """.trimIndent()
        val transport = StubTransport { url ->
            if (url.contains("/lyrics/search")) {
                HttpTransport.Response(status = 200, body = searchBody)
            } else {
                HttpTransport.Response(status = 404, body = "{}")
            }
        }
        val provider = UnisonProvider(transport = transport)

        val lyrics = provider.getLyrics(song(id = "not-a-youtube-id"))

        assertNotNull(lyrics)
        assertEquals("Never gonna give you up, never gonna let you down", lyrics.text)
        assertTrue(!lyrics.synced)
        assertTrue(lyrics.lines.isEmpty())
        assertEquals(2, transport.urls.size)
        assertTrue(transport.urls[0].contains("lyrics?song="))
        assertTrue(transport.urls[1].contains("/lyrics/search"))
    }

    @Test
    fun materializesSearchSummaryViaFetchById() = runBlocking {
        val searchBody = """
            {
              "success": true,
              "data": [
                {
                  "id": 7,
                  "song": "Never Gonna Give You Up",
                  "artist": "Rick Astley",
                  "lyrics": null,
                  "format": "plain",
                  "syncType": "unsynced"
                }
              ]
            }
        """.trimIndent()
        val entryBody = """
            {
              "success": true,
              "data": {
                "id": 7,
                "song": "Never Gonna Give You Up",
                "artist": "Rick Astley",
                "lyrics": "[00:17.12] We're no strangers to love",
                "format": "lrc",
                "syncType": "synced"
              }
            }
        """.trimIndent()
        val transport = StubTransport { url ->
            when {
                url.contains("/lyrics/search") -> HttpTransport.Response(status = 200, body = searchBody)
                url.endsWith("/lyrics/7") -> HttpTransport.Response(status = 200, body = entryBody)
                else -> HttpTransport.Response(status = 404, body = "{}")
            }
        }
        val provider = UnisonProvider(transport = transport)

        val lyrics = provider.getLyrics(song(id = "not-a-youtube-id"))

        assertNotNull(lyrics)
        assertEquals("[00:17.12] We're no strangers to love", lyrics.text)
        assertTrue(lyrics.synced)
        assertTrue(transport.urls.any { it.endsWith("/lyrics/7") })
    }

    @Test
    fun capsSearchMaterializationAtFiveResults() = runBlocking {
        val summaries = (1..6).joinToString(",") { i ->
            """{"id":$i,"song":"Never Gonna Give You Up","artist":"Rick Astley","lyrics":null,"format":"plain","syncType":"unsynced"}"""
        }
        val searchBody = """{"success":true,"data":[$summaries]}"""
        val transport = StubTransport { url ->
            when {
                url.contains("/lyrics/search") -> HttpTransport.Response(status = 200, body = searchBody)
                Regex("""/lyrics/\d+""").containsMatchIn(url) -> {
                    val id = Regex("""/lyrics/(\d+)""").find(url)!!.groupValues[1]
                    HttpTransport.Response(
                        status = 200,
                        body = """{"success":true,"data":{"id":$id,"song":"x","artist":"y","lyrics":"lyrics for $id","format":"plain","syncType":"unsynced"}}""",
                    )
                }
                else -> HttpTransport.Response(status = 404, body = "{}")
            }
        }
        val provider = UnisonProvider(transport = transport)

        val lyrics = provider.getLyrics(song(id = "not-a-youtube-id"))

        assertNotNull(lyrics)
        assertEquals("lyrics for 1", lyrics.text)
        val fetched = transport.urls.count { Regex("""/lyrics/\d+""").containsMatchIn(it) }
        assertEquals(5, fetched)
    }

    @Test
    fun maps404ToNotFound() = runBlocking {
        val transport = StubTransport { HttpTransport.Response(status = 404, body = "{}") }
        val provider = UnisonProvider(transport = transport)

        val ex = assertFailsWith<ProviderException.NotFound> {
            provider.getLyrics(song(id = "not-a-youtube-id"))
        }
    }

    @Test
    fun maps429ToRateLimited() = runBlocking {
        val transport = StubTransport {
            HttpTransport.Response(status = 429, body = """{"code":429,"name":"TooManyRequests"}""")
        }
        val provider = UnisonProvider(transport = transport)

        val ex = assertFailsWith<ProviderException.RateLimited> {
            provider.getLyrics(song(id = "not-a-youtube-id"))
        }
    }

    @Test
    fun marksTtmlLyricsAsSyncedWithoutLineParsing() = runBlocking {
        val body = """
            {
              "success": true,
              "data": {
                "id": 1,
                "song": "One More Time",
                "artist": "Daft Punk",
                "lyrics": "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p begin=\"00:30.28\">One more time</p></body></tt>",
                "format": "ttml",
                "syncType": "synced"
              }
            }
        """.trimIndent()
        val transport = StubTransport { HttpTransport.Response(status = 200, body = body) }
        val provider = UnisonProvider(transport = transport)

        val lyrics = provider.getLyrics(song(id = "dQw4w9WgXcQ", title = "One More Time"))

        assertNotNull(lyrics)
        assertTrue(lyrics.synced)
        assertTrue(lyrics.lines.isEmpty())
    }

    @Test
    fun returnsNullWhenNoArtist() = runBlocking {
        val transport = StubTransport { HttpTransport.Response(status = 200, body = "{}") }
        val provider = UnisonProvider(transport = transport)

        val noArtist = song().copy(artists = emptyList())
        assertNull(provider.getLyrics(noArtist))
        assertTrue(transport.urls.isEmpty())
    }
}
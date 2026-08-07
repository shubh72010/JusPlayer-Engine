package org.jusplayer.engine.provider.lrclib

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

class LRCLIBProviderTest {

    private class StubTransport(
        private val status: Int = 200,
        private val body: String = "",
        private val responseHeaders: Map<String, List<String>> = emptyMap(),
    ) : HttpTransport {
        var lastUrl: String? = null

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
            lastUrl = url
            return HttpTransport.Response(status = status, body = body, headers = responseHeaders)
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
        )
    }

    @Test
    fun parsesSyncedAndPlainLyrics() = runBlocking {
        val body = """
            {
              "id": 3396226,
              "trackName": "I Want to Live",
              "artistName": "Borislav Slavov",
              "plainLyrics": "I feel your breath upon my neck",
              "syncedLyrics": "[00:17.12] I feel your breath upon my neck"
            }
        """.trimIndent()
        val transport = StubTransport(status = 200, body = body)
        val provider = LRCLIBProvider(transport = transport, throttleMillis = 0)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertEquals("[00:17.12] I feel your breath upon my neck", lyrics.text)
        assertTrue(lyrics.synced)
        assertEquals("LRCLIB", lyrics.source)
    }

    @Test
    fun sendsDurationSecondsInUrl() = runBlocking {
        val transport = StubTransport(status = 404, body = "{}")
        val provider = LRCLIBProvider(transport = transport, throttleMillis = 0)

        assertFailsWith<ProviderException.NotFound> {
            provider.getLyrics(song(duration = 233))
        }

        assertTrue(transport.lastUrl!!.contains("duration=233"))
    }

    @Test
    fun acceptsFractionalDurationFromServer() = runBlocking {
        // LRCLIB returns fractional-second durations (e.g. 321.0); this must not
        // fail decoding into the LrcLibResponse model.
        val body = """
            {
              "id": 3396226,
              "trackName": "One More Time",
              "duration": 321.0,
              "syncedLyrics": "[00:30.28] One more time"
            }
        """.trimIndent()
        val transport = StubTransport(status = 200, body = body)
        val provider = LRCLIBProvider(transport = transport, throttleMillis = 0)

        val lyrics = provider.getLyrics(song(title = "One More Time"))

        assertNotNull(lyrics)
        assertEquals("[00:30.28] One more time", lyrics.text)
    }

    @Test
    fun maps404ToNotFound() = runBlocking {
        val transport = StubTransport(status = 404, body = "{}")
        val provider = LRCLIBProvider(transport = transport, throttleMillis = 0)

        val ex = assertFailsWith<ProviderException.NotFound> {
            provider.getLyrics(song())
        }
    }

    @Test
    fun maps429ToRateLimitedWithRetryAfter() = runBlocking {
        val transport = StubTransport(
            status = 429,
            body = """{"code":429,"name":"TooManyRequests"}""",
            responseHeaders = mapOf("retry-after" to listOf("120")),
        )
        val provider = LRCLIBProvider(transport = transport, throttleMillis = 0)

        val ex = assertFailsWith<ProviderException.RateLimited> {
            provider.getLyrics(song())
        }
        assertTrue(ex.message!!.contains("retry after 120"))
    }

    @Test
    fun returnsNullForInstrumental() = runBlocking {
        val body = """{"instrumental":true,"artistName":"Game"}"""
        val transport = StubTransport(status = 200, body = body)
        val provider = LRCLIBProvider(transport = transport, throttleMillis = 0)

        assertNull(provider.getLyrics(song()))
    }

    @Test
    fun returnsNullWhenNoArtist() = runBlocking {
        val transport = StubTransport(status = 200, body = "{}")
        val provider = LRCLIBProvider(transport = transport, throttleMillis = 0)

        val noArtist = song().copy(artists = emptyList())
        assertNull(provider.getLyrics(noArtist))
    }
}
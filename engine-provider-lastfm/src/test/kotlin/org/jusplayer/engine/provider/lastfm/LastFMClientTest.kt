package org.jusplayer.engine.provider.lastfm

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jusplayer.engine.provider.HttpTransport

class LastFMClientTest {

    private class RecordingTransport : HttpTransport {
        var lastForm: Map<String, String>? = null
        var lastUrl: String? = null
        var response: HttpTransport.Response = HttpTransport.Response(200, """{}""", emptyMap())

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response =
            HttpTransport.Response(200, "", emptyMap())

        override fun post(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): HttpTransport.Response {
            lastUrl = url
            lastForm = form
            return response
        }
    }

    private fun client(
        transport: RecordingTransport,
        key: String = "api-key",
        secret: String = "secret",
    ) = LastFMClient(transport, key, secret)

    @Test
    fun normalizeEndpointAppendsPath() {
        val transport = RecordingTransport()
        val c = LastFMClient(transport, "k", "s", "https://ws.audioscrobbler.com")
        assertEquals("https://ws.audioscrobbler.com/2.0/", c.normalizeEndpoint("https://ws.audioscrobbler.com"))
    }

    @Test
    fun normalizeEndpointKeepsCustomPath() {
        val transport = RecordingTransport()
        val c = LastFMClient(transport, "k", "s", "https://libre.fm/2.0")
        assertEquals("https://libre.fm/2.0/", c.normalizeEndpoint("https://libre.fm/2.0"))
    }

    @Test
    fun scrobbleBuildsSignedForm() = runBlocking {
        val transport = RecordingTransport()
        val c = client(transport)
        c.sessionKey = "sk-123"
        c.scrobble(
            artist = "Rick Astley",
            track = "Never Gonna Give You Up",
            timestamp = 1_700_000_000L,
            album = "Whenever You Need Somebody",
            duration = 213,
        )
        val form = requireNotNull(transport.lastForm)
        assertEquals("Rick Astley", form["artist[0]"])
        assertEquals("Never Gonna Give You Up", form["track[0]"])
        assertEquals("1700000000", form["timestamp[0]"])
        assertEquals("sk-123", form["sk"])
        assertEquals("api-key", form["api_key"])
        assertEquals("json", form["format"])
        assertEquals("track.scrobble", form["method"])
        // 32-char MD5 hex api_sig
        assertEquals(32, form["api_sig"]?.length)
    }

    @Test
    fun updateNowPlayingUsesMethod() = runBlocking {
        val transport = RecordingTransport()
        val c = client(transport)
        c.sessionKey = "sk-123"
        c.updateNowPlaying(artist = "A", track = "T")
        val form = requireNotNull(transport.lastForm)
        assertEquals("track.updateNowPlaying", form["method"])
    }

    @Test
    fun missingSessionThrows() = runBlocking {
        val transport = RecordingTransport()
        val c = client(transport)
        val failure = assertFailsWith<LastFMClient.LastFmException> {
            c.scrobble(artist = "A", track = "T", timestamp = 1L)
        }
        assertEquals(9, failure.code)
    }

    @Test
    fun apiErrorIsMapped() = runBlocking {
        val transport = RecordingTransport()
        transport.response = HttpTransport.Response(200, """{"error":6,"message":"Invalid parameters"}""", emptyMap())
        val c = client(transport)
        c.sessionKey = "sk"
        val failure = assertFailsWith<LastFMClient.LastFmException> {
            c.scrobble(artist = "A", track = "T", timestamp = 1L)
        }
        assertEquals(6, failure.code)
    }

    @Test
    fun getAuthUrlPointsAtLastFm() {
        val transport = RecordingTransport()
        val c = client(transport)
        val url = c.getAuthUrl("token-1")
        assertTrue(url.startsWith("https://www.last.fm/api/auth/"), url)
        assertTrue(url.contains("token-1"), url)
    }
}
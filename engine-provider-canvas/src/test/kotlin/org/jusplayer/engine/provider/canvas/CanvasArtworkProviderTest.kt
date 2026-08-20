package org.jusplayer.engine.provider.canvas

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.jusplayer.engine.model.Artist
import org.jusplayer.engine.model.Song
import org.jusplayer.engine.provider.HttpTransport

class CanvasArtworkProviderTest {

    private class FakeTransport(
        private val respond: (url: String) -> HttpTransport.Response,
    ) : HttpTransport {
        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response = respond(url)
    }

    private fun song(title: String = "Never Gonna Give You Up", artist: String = "Rick Astley") = Song(
        id = "id1",
        title = title,
        artists = listOf(Artist(id = "a1", name = artist, thumbnailUrl = null)),
        album = null,
        duration = 213,
        thumbnailUrl = null,
        streamUrl = null,
    )

    private val canvasJson =
        """{
            "name": "Never Gonna Give You Up",
            "artist": "Rick Astley",
            "albumId": "123456789",
            "albumName": "Whenever You Need Somebody",
            "static": "https://img/static.png",
            "animated": "https://img/animated.m3u8",
            "animatedVertical": "https://img/vertical.m3u8",
            "videoUrl": "https://img/video.m3u8"
        }"""

    @Test
    fun mapsCanvasToArtwork() = runBlocking {
        val transport = FakeTransport {
            HttpTransport.Response(200, canvasJson, emptyMap())
        }
        val provider = CanvasArtworkProvider(transport)
        val artwork = provider.getArtwork(song())
        assertNotNull(artwork)
        assertEquals("https://img/animated.m3u8", artwork.frontUrl)
        assertEquals("https://img/vertical.m3u8", artwork.verticalUrl)
        assertEquals("AppleMusicCanvas", artwork.source)
        assertEquals("https://img/animated.m3u8", artwork.thumbnails["animated"])
    }

    @Test
    fun fallsBackToVideoUrl() = runBlocking {
        val transport = FakeTransport {
            HttpTransport.Response(
                200,
                """{"animated": null, "videoUrl": "https://img/video.m3u8"}""",
                emptyMap(),
            )
        }
        val provider = CanvasArtworkProvider(transport)
        val artwork = provider.getArtwork(song())
        assertNotNull(artwork)
        assertEquals("https://img/video.m3u8", artwork.frontUrl)
    }

    @Test
    fun nullWhenServerReturnsNotFound() = runBlocking {
        val transport = FakeTransport {
            HttpTransport.Response(404, "", emptyMap())
        }
        val provider = CanvasArtworkProvider(transport)
        assertNull(provider.getArtwork(song()))
    }

    @Test
    fun nullWhenBlankTitle() = runBlocking {
        val provider = CanvasArtworkProvider(FakeTransport { error("should not be called") })
        assertNull(provider.getArtwork(song(title = "  ")))
    }
}
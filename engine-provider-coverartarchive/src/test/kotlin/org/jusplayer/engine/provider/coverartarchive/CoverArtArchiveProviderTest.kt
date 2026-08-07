package org.jusplayer.engine.provider.coverartarchive

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

class CoverArtArchiveProviderTest {

    private class StubTransport(
        private val status: Int = 200,
        private val body: String = "",
    ) : HttpTransport {
        var lastUrl: String? = null

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
            lastUrl = url
            return HttpTransport.Response(status = status, body = body)
        }
    }

    private val listingBody = """
        {
          "images": [
            {
              "types": ["Front"],
              "front": true,
              "back": false,
              "image": "http://coverartarchive.org/release/abc/1.jpg",
              "thumbnails": {
                "250": "http://coverartarchive.org/release/abc/1-250.jpg",
                "500": "http://coverartarchive.org/release/abc/1-500.jpg"
              }
            },
            {
              "types": ["Back"],
              "front": false,
              "back": true,
              "image": "http://coverartarchive.org/release/abc/2.jpg",
              "thumbnails": {}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesFrontAndBackImages() = runBlocking {
        val transport = StubTransport(status = 200, body = listingBody)
        val provider = CoverArtArchiveProvider(transport = transport)

        val artwork = provider.getArtwork("76df3287-6cda-33eb-8e9a-044b5e15ffdd")

        assertNotNull(artwork)
        assertEquals("http://coverartarchive.org/release/abc/1.jpg", artwork.frontUrl)
        assertEquals("http://coverartarchive.org/release/abc/2.jpg", artwork.backUrl)
        assertEquals("http://coverartarchive.org/release/abc/1-250.jpg", artwork.thumbnails["250"])
        assertEquals("76df3287-6cda-33eb-8e9a-044b5e15ffdd", artwork.sourceMbid)
        assertTrue(transport.lastUrl!!.contains("/release/76df3287-6cda-33eb-8e9a-044b5e15ffdd/"))
    }

    @Test
    fun returnsNullWhenNoImages() = runBlocking {
        val transport = StubTransport(status = 200, body = """{"images":[]}""")
        val provider = CoverArtArchiveProvider(transport = transport)

        assertNull(provider.getArtwork("76df3287-6cda-33eb-8e9a-044b5e15ffdd"))
    }

    @Test
    fun maps404ToNotFound() = runBlocking {
        val transport = StubTransport(status = 404, body = "")
        val provider = CoverArtArchiveProvider(transport = transport)

        val ex = assertFailsWith<ProviderException.NotFound> {
            provider.getArtwork("76df3287-6cda-33eb-8e9a-044b5e15ffdd")
        }
    }

    @Test
    fun maps503ToRateLimited() = runBlocking {
        val transport = StubTransport(status = 503, body = "")
        val provider = CoverArtArchiveProvider(transport = transport)

        val ex = assertFailsWith<ProviderException.RateLimited> {
            provider.getArtwork("76df3287-6cda-33eb-8e9a-044b5e15ffdd")
        }
    }
}

class MusicBrainzResolverTest {

    private class StubTransport(
        private val status: Int = 200,
        private val body: String = "",
    ) : HttpTransport {
        var lastUrl: String? = null

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
            lastUrl = url
            return HttpTransport.Response(status = status, body = body)
        }
    }

    private fun song(title: String = "I Want to Live", artist: String = "Borislav Slavov"): Song {
        return Song(
            id = "id-1",
            title = title,
            artists = listOf(Artist(id = "a", name = artist, thumbnailUrl = null)),
            album = null,
            duration = 233_000,
            thumbnailUrl = null,
        )
    }

    @Test
    fun resolvesReleaseMbidFromFirstRecording() = runBlocking {
        val body = """
            {
              "count": 1,
              "recordings": [
                {
                  "title": "I Want to Live",
                  "releases": [
                    { "id": "f268b8bc-2768-426b-901b-c7966e76de29", "title": "Baldur's Gate 3" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val transport = StubTransport(status = 200, body = body)
        val resolver = MusicBrainzResolver(transport = transport)

        val mbid = resolver.resolveReleaseMbid(song())

        assertEquals("f268b8bc-2768-426b-901b-c7966e76de29", mbid)
        assertTrue(transport.lastUrl!!.contains("/recording/"))
        assertTrue(transport.lastUrl!!.contains("recording%3A"))
    }

    @Test
    fun returnsNullWhenNoRecordingMatches() = runBlocking {
        val transport = StubTransport(status = 200, body = """{"count":0,"recordings":[]}""")
        val resolver = MusicBrainzResolver(transport = transport)

        assertNull(resolver.resolveReleaseMbid(song()))
    }

    @Test
    fun returnsNullWithoutArtist() = runBlocking {
        val transport = StubTransport(status = 200, body = "{}")
        val resolver = MusicBrainzResolver(transport = transport)

        assertNull(resolver.resolveReleaseMbid(song().copy(artists = emptyList())))
    }

    @Test
    fun mapsNetworkFailureToProviderNetwork() = runBlocking {
        val throwing = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
                throw java.io.IOException("connection refused")
            }
        }
        val resolver = MusicBrainzResolver(transport = throwing)

        val ex = assertFailsWith<ProviderException.Network> {
            resolver.resolveReleaseMbid(song())
        }
    }
}
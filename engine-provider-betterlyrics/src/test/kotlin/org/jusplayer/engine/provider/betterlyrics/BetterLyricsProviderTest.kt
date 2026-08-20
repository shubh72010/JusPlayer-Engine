package org.jusplayer.engine.provider.betterlyrics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jusplayer.engine.model.Album
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

class BetterLyricsProviderTest {

    private class StubTransport(
        private val always: HttpTransport.Response? = null,
        private val queue: ArrayDeque<HttpTransport.Response> = ArrayDeque(),
    ) : HttpTransport {
        var lastUrl: String? = null

        override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
            lastUrl = url
            always?.let { return it }
            return queue.removeFirst()
        }

        companion object {
            fun single(status: Int, body: String): StubTransport =
                StubTransport(always = HttpTransport.Response(status = status, body = body))
        }
    }

    private fun song(
        title: String = "I Want to Live",
        duration: Long = 233,
        album: Album? = null,
    ): Song {
        return Song(
            id = "id-1",
            title = title,
            artists = listOf(Artist(id = "a", name = "Borislav Slavov", thumbnailUrl = null)),
            album = album,
            duration = duration,
            thumbnailUrl = null,
            streamUrl = null,
        )
    }

    @Test
    fun parsesTTMLWithWordTimings() = runBlocking {
        val transport = StubTransport.single(status = 200, body = SAMPLE_TTML)
        val provider = BetterLyricsProvider(transport = transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertEquals(SAMPLE_TTML, lyrics.text)
        assertEquals("BetterLyrics", lyrics.source)
        assertTrue(lyrics.synced)

        assertEquals(2, lyrics.lines.size)

        val first = lyrics.lines[0]
        assertEquals("I feel your breath", first.text)
        assertEquals(0, first.start)
        assertEquals(3000, first.end)
        assertEquals(
            listOf(
                LyricsWordLike("I", 0, 800),
                LyricsWordLike("feel", 800, 1600),
                LyricsWordLike("your", 1600, 2400),
                LyricsWordLike("breath", 2400, 3000),
            ),
            first.words.map { LyricsWordLike(it.text, it.start!!, it.end!!) },
        )

        val second = lyrics.lines[1]
        assertEquals("upon my neck", second.text)
        assertEquals(5000, second.start)
        assertEquals(8000, second.end)
        assertEquals(
            listOf(
                LyricsWordLike("upon", 5000, 6000),
                LyricsWordLike("my", 6000, 7000),
                LyricsWordLike("neck", 7000, 8000),
            ),
            second.words.map { LyricsWordLike(it.text, it.start!!, it.end!!) },
        )
    }

    @Test
    fun parsesTTMLFromJsonEnvelope() = runBlocking {
        val body = buildJsonObject { put("ttml", SAMPLE_TTML) }.toString()
        val transport = StubTransport.single(status = 200, body = body)
        val provider = BetterLyricsProvider(transport = transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals("I feel your breath", lyrics.lines[0].text)
    }

    @Test
    fun fallsBackToPlainText() = runBlocking {
        val plain = "Never gonna give you up\nNever gonna let you down"
        val transport = StubTransport.single(status = 200, body = plain)
        val provider = BetterLyricsProvider(transport = transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertEquals(plain, lyrics.text)
        assertTrue(!lyrics.synced)
        assertTrue(lyrics.lines.isEmpty())
    }

    @Test
    fun maps404ToNotFound() = runBlocking {
        val transport = StubTransport.single(status = 404, body = "{}")
        val provider = BetterLyricsProvider(transport = transport)

        val ex = assertFailsWith<ProviderException.NotFound> {
            provider.getLyrics(song())
        }

        assertTrue(ex.message!!.contains("I Want to Live"))
        assertTrue(transport.lastUrl!!.contains("kugou/getLyrics"))
        assertTrue(transport.lastUrl!!.contains("s=I+Want+to+Live"))
        assertTrue(transport.lastUrl!!.contains("a=Borislav+Slavov"))
        assertTrue(transport.lastUrl!!.contains("d=233"))
        assertTrue(!transport.lastUrl!!.contains("al="))
    }

    @Test
    fun includesAlbumParamWhenPresent() = runBlocking {
        val transport = StubTransport.single(status = 404, body = "{}")
        val provider = BetterLyricsProvider(transport = transport)

        val ex = assertFailsWith<ProviderException.NotFound> {
            provider.getLyrics(song(album = Album(id = "al", title = "Whenever You Need Somebody", artists = emptyList(), duration = 233, thumbnailUrl = null)))
        }

        assertTrue(transport.lastUrl!!.contains("al=Whenever+You+Need+Somebody"))
    }

    @Test
    fun fallsBackToKugouEndpointWhenPrimaryEmpty() = runBlocking {
        val transport = StubTransport(
            queue = ArrayDeque(
                listOf(
                    HttpTransport.Response(status = 200, body = """{"ttml": ""}"""),
                    HttpTransport.Response(status = 200, body = SAMPLE_TTML),
                ),
            ),
        )
        val provider = BetterLyricsProvider(transport = transport)

        val lyrics = provider.getLyrics(song())

        assertNotNull(lyrics)
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertTrue(transport.lastUrl!!.contains("kugou/getLyrics"))
    }

    @Test
    fun maps429ToRateLimited() = runBlocking {
        val transport = StubTransport.single(status = 429, body = "{}")
        val provider = BetterLyricsProvider(transport = transport)

        val ex = assertFailsWith<ProviderException.RateLimited> {
            provider.getLyrics(song())
        }
    }

    @Test
    fun returnsNullWhenNoArtist() = runBlocking {
        val transport = StubTransport.single(status = 200, body = SAMPLE_TTML)
        val provider = BetterLyricsProvider(transport = transport)

        val noArtist = song().copy(artists = emptyList())
        assertNull(provider.getLyrics(noArtist))
    }

    @Test
    fun parsesTransliterationAndTranslation() {
        val lines = TTMLParser.parseTTML(TTML_WITH_TRANSLATION)

        assertEquals(1, lines.size)
        assertEquals("I feel", lines[0].text)
        assertEquals("I feel your breath", lines[0].providerRomanizedText)
        assertEquals(listOf("I", "feel", "your", "breath"), lines[0].providerRomanizedWords)
        assertEquals("ja-Latn", lines[0].providerRomanizedLanguage)
        assertEquals("私はあなたの息を感じます", lines[0].providerTranslationText)
    }

    private data class LyricsWordLike(val text: String, val start: Long, val end: Long)

    private companion object {
        val SAMPLE_TTML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" frameRate="30">
              <head>
                <metadata>
                  <ttm:title>I Want to Live</ttm:title>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="00:00:00.000" end="00:00:03.000" ttm:key="L1">
                    <span begin="0.00" end="0.80">I</span>
                    <span begin="0.80" end="1.60"> feel</span>
                    <span begin="1.60" end="2.40"> your</span>
                    <span begin="2.40" end="3.00"> breath</span>
                  </p>
                </div>
                <div>
                  <p begin="5.000" dur="3.0" ttm:key="L2">
                    <span begin="0.00" end="1.00">upon</span>
                    <span begin="1.00" end="2.00"> my</span>
                    <span begin="2.00" end="3.00"> neck</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val TTML_WITH_TRANSLATION = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:transliteration xml:lang="ja-Latn">
                    <ttm:text for="L1"><span>I</span> <span>feel</span> <span>your</span> <span>breath</span></ttm:text>
                  </ttm:transliteration>
                  <ttm:translation>
                    <ttm:text for="L1">私はあなたの息を感じます</ttm:text>
                  </ttm:translation>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="0.0" end="3.0" ttm:key="L1">
                    <span begin="0.0" end="0.8">I</span><span begin="0.8" end="1.6"> feel</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()
    }
}

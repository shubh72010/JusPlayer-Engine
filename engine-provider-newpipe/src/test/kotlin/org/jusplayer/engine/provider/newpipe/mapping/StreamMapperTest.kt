package org.jusplayer.engine.provider.newpipe.mapping

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamMapperTest {

    private fun audioStream(id: String, bitrate: Int): AudioStream {
        return AudioStream.Builder()
            .setId(id)
            .setContent("https://example.com/$id.m4a", true)
            .setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(bitrate)
            .build()
    }

    @Test
    fun picksHighestBitrateAudioStream() {
        val low = audioStream("low", 48_000)
        val high = audioStream("high", 320_000)

        val stream = StreamMapper.map(
            audioStreams = listOf(low, high),
            videoStreams = emptyList(),
            streamType = StreamType.AUDIO_STREAM,
            duration = 240,
        )

        assertEquals("https://example.com/high.m4a", stream.url)
        assertEquals("m4a", stream.format)
        assertEquals(320_000, stream.bitrate)
        assertEquals(240, stream.duration)
        assertFalse(stream.isLive)
    }

    @Test
    fun marksLiveStreamAsLive() {
        val stream = StreamMapper.map(
            audioStreams = listOf(audioStream("live", 128_000)),
            videoStreams = emptyList(),
            streamType = StreamType.AUDIO_LIVE_STREAM,
            duration = 0,
        )

        assertTrue(stream.isLive)
    }

    @Test
    fun fallsBackToVideoWhenNoAudioAvailable() {
        val video = VideoStream.Builder()
            .setId("137")
            .setContent("https://example.com/video.mp4", true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setIsVideoOnly(true)
            .setResolution("720p")
            .build()

        val stream = StreamMapper.map(
            audioStreams = emptyList(),
            videoStreams = listOf(video),
            streamType = StreamType.VIDEO_STREAM,
            duration = 90,
        )

        assertEquals("https://example.com/video.mp4", stream.url)
        assertEquals("mp4", stream.format)
    }
}

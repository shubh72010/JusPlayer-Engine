package org.jusplayer.engine.provider.newpipe.mapping

import org.jusplayer.engine.model.Stream
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Maps a NewPipeExtractor audio/video stream into the engine's [Stream] model.
 */
object StreamMapper {

    /**
     * Picks the best audio stream from a fully extracted stream.
     *
     * Audio-only streams are preferred for a music engine. The highest-bitrate
     * audio stream wins.
     */
    fun map(
        audioStreams: List<AudioStream>,
        videoStreams: List<VideoStream>,
        streamType: StreamType,
        duration: Long,
    ): Stream {
        val bestAudio = audioStreams.maxByOrNull { effectiveBitrate(it) }
            ?: audioStreams.firstOrNull()
        val fallbackVideo = videoStreams.firstOrNull()

        val selected = bestAudio ?: fallbackVideo
        return Stream(
            url = selected?.content ?: "",
            format = selected?.format?.suffix ?: "unknown",
            bitrate = bestAudio?.let { effectiveBitrate(it) } ?: 0,
            sampleRate = 0,
            isLive = streamType.isLive(),
            duration = duration.coerceAtLeast(0),
        )
    }

    /**
     * The extractor reports [AudioStream] bitrate as -1 when it is unknown, in
     * which case the average bitrate is used instead.
     */
    private fun effectiveBitrate(audio: AudioStream): Int {
        return if (audio.bitrate > 0) audio.bitrate else audio.averageBitrate.coerceAtLeast(0)
    }

    private fun StreamType.isLive(): Boolean {
        return this == StreamType.LIVE_STREAM ||
            this == StreamType.AUDIO_LIVE_STREAM ||
            this == StreamType.POST_LIVE_STREAM ||
            this == StreamType.POST_LIVE_AUDIO_STREAM
    }
}

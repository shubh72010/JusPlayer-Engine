package org.jusplayer.engine.provider.newpipe.mapping

import org.jusplayer.engine.model.Stream
import org.jusplayer.engine.provider.ProviderException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Stream as ExtractStream
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
        val bestAudio = audioStreams.maxByOrNull { effectiveBitrate(it) ?: -1 }
            ?: audioStreams.firstOrNull()
        val fallbackVideo = videoStreams.firstOrNull()

        val selected = bestAudio ?: fallbackVideo
        val url = selected?.content?.takeIf { it.isNotBlank() }
            ?: throw ProviderException.NotFound(
                "No playable stream for this song (no audio/video URL extracted)",
            )
        return Stream(
            url = url,
            format = selected.format?.suffix ?: "unknown",
            bitrate = bestAudio?.let { effectiveBitrate(it)?.toLong() },
            sampleRate = null,
            isLive = streamType.isLive(),
            duration = duration.coerceAtLeast(0),
            codec = codecOf(selected),
            mimeType = selected?.format?.mimeType,
        )
    }

    /**
     * The extractor exposes [getCodec] on [AudioStream] and [VideoStream] but not
     * on the base [Stream] type, so narrow the type before reading it.
     */
    private fun codecOf(stream: ExtractStream?): String? = when (stream) {
        is AudioStream -> stream.codec
        is VideoStream -> stream.codec
        else -> null
    }

    /**
     * The extractor reports [AudioStream] bitrate as -1 when it is unknown, in
     * which case the average bitrate is used instead. Returns null when neither
     * is known.
     */
    private fun effectiveBitrate(audio: AudioStream): Int? {
        return if (audio.bitrate > 0) audio.bitrate else audio.averageBitrate.takeIf { it > 0 }
    }

    private fun StreamType.isLive(): Boolean {
        return this == StreamType.LIVE_STREAM ||
            this == StreamType.AUDIO_LIVE_STREAM ||
            this == StreamType.POST_LIVE_STREAM ||
            this == StreamType.POST_LIVE_AUDIO_STREAM
    }
}

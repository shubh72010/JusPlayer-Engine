package org.jusplayer.engine.playback

import java.time.Duration
import org.jusplayer.engine.model.Stream

interface PlayerAdapter {
    suspend fun play(stream: Stream)

    fun pause()

    fun stop()

    fun seek(position: Duration)
}
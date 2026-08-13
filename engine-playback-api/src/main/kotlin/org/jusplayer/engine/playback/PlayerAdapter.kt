package org.jusplayer.engine.playback

import java.time.Duration
import org.jusplayer.engine.model.Stream

interface PlayerAdapter {
    suspend fun play(stream: Stream)

    fun pause()

    fun stop()

    fun seek(position: Duration)

    /**
     * Current playback position, when the adapter can report it. Used by the
     * engine's previous-track logic (restart the current track when more than a
     * few seconds in, otherwise go back one). Defaults to `null` — engines
     * degrade to "go to previous track" when position is unknown.
     */
    val position: Duration?
        get() = null
}
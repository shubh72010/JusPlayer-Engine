package org.jusplayer.engine.autoplay

import org.jusplayer.engine.model.Song

/**
 * The autoplay/recommendation layer.
 *
 * Responsibilities are deliberately split from the queue: the queue answers
 * "what is currently scheduled?", autoplay answers "what should we schedule
 * next?". The engine coordinates the two — it hands [AutoplayEngine] a
 * session [AutoplayContext] when the queue is exhausted and inserts the returned
 * tracks back into the queue.
 */
interface AutoplayEngine {
    val config: AutoplayConfig

    /** Returns up to [config.bufferSize] songs to schedule next. */
    suspend fun recommend(context: AutoplayContext): List<Song>
}
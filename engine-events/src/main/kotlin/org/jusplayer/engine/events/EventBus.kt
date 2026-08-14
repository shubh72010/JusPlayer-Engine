package org.jusplayer.engine.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class EventBus {
    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Suspending emission used by control signals that must not be dropped
     * ([SongEnded]) and by application code. Suspends only when the internal
     * buffer is full and subscribers cannot keep up.
     */
    suspend fun emit(event: Event) {
        _events.emit(event)
    }

    /**
     * Non-blocking emission used by the engine's own state announcements
     * ([SongStarted], [QueueChanged], ...). Drops the event when the buffer is
     * full rather than letting a slow subscriber stall core transitions —
     * observers should rely on the reactive [org.jusplayer.engine.core.JusPlayerEngine.state]
     * StateFlows, which are never dropped.
     */
    fun tryEmit(event: Event): Boolean {
        return _events.tryEmit(event)
    }
}
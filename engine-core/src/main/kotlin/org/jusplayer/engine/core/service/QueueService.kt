package org.jusplayer.engine.core.service

import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.QueueChanged
import org.jusplayer.engine.model.QueueItem
import org.jusplayer.engine.model.Song

class QueueService(
    private val eventBus: EventBus,
) {
    private val items = mutableListOf<QueueItem>()
    private var currentIndex: Int = -1

    val size: Int
        get() = items.size

    val currentIndexValue: Int
        get() = currentIndex

    val currentSong: Song?
        get() = if (currentIndex in items.indices) items[currentIndex].song else null

    val isEmpty: Boolean
        get() = items.isEmpty()

    suspend fun add(song: Song) {
        items.add(QueueItem(song))
        emitQueueChanged()
    }

    suspend fun addAll(songs: List<Song>) {
        items.addAll(songs.map { QueueItem(it) })
        emitQueueChanged()
    }

    suspend fun remove(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        if (currentIndex > index) {
            currentIndex--
        } else if (currentIndex == index && currentIndex >= items.size) {
            currentIndex = items.size - 1
        }
        emitQueueChanged()
    }

    suspend fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in items.indices) return
        if (toIndex !in items.indices) return
        val item = items.removeAt(fromIndex)
        items.add(toIndex, item)
        if (currentIndex == fromIndex) {
            currentIndex = toIndex
        } else if (fromIndex < currentIndex && toIndex >= currentIndex) {
            currentIndex--
        } else if (fromIndex > currentIndex && toIndex <= currentIndex) {
            currentIndex++
        }
        emitQueueChanged()
    }

    suspend fun next(): Song? {
        if (items.isEmpty()) return null
        currentIndex = (currentIndex + 1).coerceIn(0, items.size - 1)
        emitQueueChanged()
        return currentSong
    }

    suspend fun previous(): Song? {
        if (items.isEmpty()) return null
        currentIndex = (currentIndex - 1).coerceIn(0, items.size - 1)
        emitQueueChanged()
        return currentSong
    }

    suspend fun shuffle() {
        if (items.size <= 1) return
        val current = if (currentIndex in items.indices) items[currentIndex] else null
        items.shuffle()
        if (current != null) {
            currentIndex = items.indexOfFirst { it.song.id == current.song.id }
        }
        emitQueueChanged()
    }

    suspend fun repeat(): Song? {
        if (items.isEmpty()) return null
        return currentSong
    }

    suspend fun clear() {
        items.clear()
        currentIndex = -1
        emitQueueChanged()
    }

    suspend fun reset() {
        currentIndex = -1
        emitQueueChanged()
    }

    private suspend fun emitQueueChanged() {
        eventBus.emit(QueueChanged(queueSize = size, currentIndex = currentIndexValue))
    }
}
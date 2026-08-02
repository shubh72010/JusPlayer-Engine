package org.jusplayer.engine.queue

import org.jusplayer.engine.model.QueueItem
import org.jusplayer.engine.model.Song

class QueueEngine {
    private val _items = mutableListOf<QueueItem>()
    private var currentIndex: Int = -1

    val items: List<QueueItem>
        get() = _items.toList()

    val size: Int
        get() = _items.size

    val currentIndexValue: Int
        get() = currentIndex

    val currentSong: Song?
        get() = if (currentIndex in _items.indices) _items[currentIndex].song else null

    val isEmpty: Boolean
        get() = _items.isEmpty()

    fun add(song: Song) {
        _items.add(QueueItem(song))
    }

    fun addAll(songs: List<Song>) {
        _items.addAll(songs.map { QueueItem(it) })
    }

    fun remove(index: Int) {
        if (index !in _items.indices) return
        _items.removeAt(index)
        if (currentIndex > index) {
            currentIndex--
        } else if (currentIndex == index && currentIndex >= _items.size) {
            currentIndex = _items.size - 1
        }
    }

    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in _items.indices) return
        if (toIndex !in _items.indices) return
        val item = _items.removeAt(fromIndex)
        _items.add(toIndex, item)
        if (currentIndex == fromIndex) {
            currentIndex = toIndex
        } else if (fromIndex < currentIndex && toIndex >= currentIndex) {
            currentIndex--
        } else if (fromIndex > currentIndex && toIndex <= currentIndex) {
            currentIndex++
        }
    }

    fun next(): Song? {
        if (_items.isEmpty()) return null
        currentIndex = (currentIndex + 1).coerceIn(0, _items.size - 1)
        return currentSong
    }

    fun previous(): Song? {
        if (_items.isEmpty()) return null
        currentIndex = (currentIndex - 1).coerceIn(0, _items.size - 1)
        return currentSong
    }

    fun shuffle() {
        if (_items.size <= 1) return
        val current = if (currentIndex in _items.indices) _items[currentIndex] else null
        _items.shuffle()
        if (current != null) {
            currentIndex = _items.indexOfFirst { it.song.id == current.song.id }
        }
    }

    fun repeat(): Song? {
        if (_items.isEmpty()) return null
        return currentSong
    }

    fun clear() {
        _items.clear()
        currentIndex = -1
    }

    fun reset() {
        currentIndex = -1
    }
}
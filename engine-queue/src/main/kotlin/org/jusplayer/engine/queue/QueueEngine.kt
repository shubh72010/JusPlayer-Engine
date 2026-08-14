package org.jusplayer.engine.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jusplayer.engine.model.QueueItem
import org.jusplayer.engine.model.RepeatMode
import org.jusplayer.engine.model.Song

/**
 * The single source of truth for what is queued.
 *
 * Owns the queue contents, the current cursor, the shuffle state, and the repeat
 * state. Every mutation is applied under an internal lock and re-published
 * atomically as a [QueueSnapshot] on [state] — playback, the DSL, observers, and
 * autoplay all read and mutate this one object.
 *
 * Semantics:
 * - [next] returns `null` at end-of-queue under [RepeatMode.NONE] (exhaustion
 *   is representable and explicit).
 * - [RepeatMode.ONE] re-selects the current track; [RepeatMode.ALL] wraps.
 * - [shuffle] keeps the current track first and remembers the pre-shuffle order
 *   so [restore] can recover it.
 * - [addNext] inserts immediately after the current track.
 */
class QueueEngine {

    private val lock = Any()

    private val entries = mutableListOf<QueueItem>()
    private var cursor: Int = -1
    private var repeat: RepeatMode = RepeatMode.NONE
    private var shuffled: Boolean = false

    /** Pre-shuffle order, kept in sync (by object identity) while shuffled. */
    private var originalEntries: MutableList<QueueItem>? = null

    private val _state = MutableStateFlow(snapshotUnsafe())
    val state: StateFlow<QueueSnapshot> = _state.asStateFlow()

    // ── Convenience accessors (always from the latest snapshot) ──

    val size: Int get() = _state.value.size
    val isEmpty: Boolean get() = _state.value.isEmpty
    val currentIndexValue: Int get() = _state.value.currentIndex
    val currentSong: Song? get() = _state.value.currentSong
    val items: List<Song> get() = _state.value.items
    val hasNext: Boolean get() = _state.value.hasNext
    val hasPrevious: Boolean get() = _state.value.hasPrevious
    val repeatMode: RepeatMode get() = _state.value.repeatMode
    val shuffleEnabled: Boolean get() = _state.value.shuffleEnabled

    // ── Mutations ──

    fun add(song: Song) = synchronized(lock) {
        val item = QueueItem(song)
        entries.add(item)
        originalEntries?.add(item)
        publish()
    }

    fun addAll(songs: List<Song>) = synchronized(lock) {
        val newItems = songs.map { QueueItem(it) }
        entries.addAll(newItems)
        originalEntries?.addAll(newItems)
        publish()
    }

    /**
     * Inserts [song] immediately after the current track. With an empty queue
     * the song becomes the first item (the cursor stays unstarted). When the
     * queue is unstarted but non-empty (cursor `-1`) the song is inserted at
     * the front — the position "play next" means when nothing is current.
     * The insertion is mirrored into the pre-shuffle order so [restore] never
     * loses entries.
     */
    fun addNext(song: Song) = synchronized(lock) {
        val item = QueueItem(song)
        if (entries.isEmpty()) {
            entries.add(item)
            originalEntries?.add(item)
        } else if (cursor !in entries.indices) {
            entries.add(0, item)
            originalEntries?.add(0, item)
        } else {
            val at = cursor + 1
            entries.add(at, item)
            val current = entries[cursor]
            val original = originalEntries
            if (original != null) {
                val oi = original.indexOfFirst { it === current }
                if (oi >= 0) original.add((oi + 1).coerceIn(0, original.size), item)
            }
        }
        publish()
    }

    fun remove(index: Int) = synchronized(lock) {
        if (index !in entries.indices) return
        val removed = entries.removeAt(index)
        removeByIdentity(originalEntries, removed)
        cursor = QueueOrder.indexAfterRemove(entries.size, cursor, index)
        publish()
    }

    /** Removes the first item matching [song] (by id). `false` when not present. */
    fun removeMatching(song: Song): Boolean = synchronized(lock) {
        val index = entries.indexOfFirst { it.song.id == song.id }
        if (index < 0) {
            false
        } else {
            val removed = entries.removeAt(index)
            removeByIdentity(originalEntries, removed)
            cursor = QueueOrder.indexAfterRemove(entries.size, cursor, index)
            publish()
            true
        }
    }

    fun move(fromIndex: Int, toIndex: Int) = synchronized(lock) {
        if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) return
        val moved = entries.removeAt(fromIndex)
        entries.add(toIndex, moved)
        originalEntries?.let { original ->
            val oi = original.indexOfFirst { it === moved }
            if (oi >= 0) {
                original.removeAt(oi)
                original.add(toIndex.coerceIn(0, original.size), moved)
            }
        }
        cursor = QueueOrder.indexAfterMove(entries.size, cursor, fromIndex, toIndex)
        publish()
    }

    /**
     * Enables shuffle: the current track stays first, the rest are randomized,
     * and the pre-shuffle order is remembered for [restore].
     */
    fun shuffle() = synchronized(lock) {
        if (entries.isEmpty()) {
            shuffled = true
            publish()
            return
        }
        if (!shuffled) {
            originalEntries = entries.toMutableList()
        }
        val reordered = QueueOrder.shuffleFromCurrent(entries, cursor)
        entries.clear()
        entries.addAll(reordered.items)
        cursor = reordered.currentIndex
        shuffled = true
        publish()
    }

    /** Restores the pre-shuffle order, keeping the current track in focus. */
    fun restore() = synchronized(lock) {
        if (!shuffled) {
            publish()
            return
        }
        val original = originalEntries
        val current = entries.getOrNull(cursor)
        val reordered = QueueOrder.restoreOriginal(original ?: emptyList(), current) { it.song }
        entries.clear()
        entries.addAll(reordered.items)
        cursor = reordered.currentIndex
        originalEntries = null
        shuffled = false
        publish()
    }

    /** Toggles shuffle on/off without discarding anything. */
    fun setShuffle(enabled: Boolean) {
        if (enabled) shuffle() else restore()
    }

    fun setRepeatMode(mode: RepeatMode) = synchronized(lock) {
        repeat = mode
        publish()
    }

    /**
     * Advances the cursor to the next track and returns it, respecting
     * [RepeatMode.ONE]/[RepeatMode.ALL]. Returns `null` — and leaves the cursor
     * in place — when the queue is exhausted under [RepeatMode.NONE].
     */
    fun next(): Song? = synchronized(lock) {
        val index = QueueOrder.nextIndex(entries.size, cursor, repeat)
        if (index == null) {
            publish()
            null
        } else {
            cursor = index
            publish()
            entries[cursor].song
        }
    }

    /** Moves the cursor to the previous track. Never wraps forward under NONE. */
    fun previous(): Song? = synchronized(lock) {
        val index = QueueOrder.previousIndex(entries.size, cursor, repeat)
        if (index == null) {
            publish()
            null
        } else {
            cursor = index
            publish()
            entries[cursor].song
        }
    }

    /** Points the cursor at [index]. `null` (and unchanged) when out of range. */
    fun jumpTo(index: Int): Song? = synchronized(lock) {
        if (index !in entries.indices) {
            publish()
            null
        } else {
            cursor = index
            publish()
            entries[cursor].song
        }
    }

    /**
     * Moves the cursor to the next track as if [RepeatMode.NONE], ignoring a
     * [RepeatMode.ONE] re-selection. Lets a user skip a track that failed to
     * play instead of being stuck on it forever. Returns `null` when exhausted
     * under a non-wrapping mode.
     */
    fun nextIgnoringRepeatOne(): Song? = synchronized(lock) {
        val mode = if (repeat == RepeatMode.ONE) RepeatMode.NONE else repeat
        val index = QueueOrder.nextIndex(entries.size, cursor, mode)
        if (index == null) {
            publish()
            null
        } else {
            cursor = index
            publish()
            entries[cursor].song
        }
    }

    /**
     * Removes every item strictly before the current cursor (tracks already
     * consumed), so autoplay replenishment cannot grow the queue without bound.
     * No-op when unstarted or when nothing precedes the cursor. The pre-shuffle
     * order is mirrored so [restore] stays consistent.
     */
    fun pruneConsumed() = synchronized(lock) {
        if (cursor <= 0) return
        val removed = entries.take(cursor)
        repeat(cursor) { entries.removeAt(0) }
        originalEntries?.let { original ->
            removed.forEach { item ->
                val idx = original.indexOfFirst { it === item }
                if (idx >= 0) original.removeAt(idx)
            }
        }
        cursor = 0
        publish()
    }

    /** Index of the first item equal to [song] (by id), or `-1`. */
    fun indexOf(song: Song): Int = QueueOrder.indexOfMatch(_state.value.items, song) { it }

    fun clear() = synchronized(lock) {
        entries.clear()
        originalEntries = null
        cursor = -1
        publish()
    }

    // ── Snapshot plumbing ──

    private fun publish() {
        _state.value = snapshotUnsafe()
    }

    private fun snapshotUnsafe(): QueueSnapshot {
        val songs = entries.map { it.song }
        return QueueSnapshot(
            items = songs,
            currentIndex = cursor,
            currentSong = cursor.takeIf { it in entries.indices }?.let { entries[it].song },
            size = entries.size,
            isEmpty = entries.isEmpty(),
            repeatMode = repeat,
            shuffleEnabled = shuffled,
            hasNext = QueueOrder.nextIndex(entries.size, cursor, repeat) != null,
            hasPrevious = QueueOrder.previousIndex(entries.size, cursor, repeat) != null,
        )
    }

    private fun removeByIdentity(list: MutableList<QueueItem>?, item: QueueItem) {
        val index = list?.indexOfFirst { it === item } ?: return
        if (index >= 0) list.removeAt(index)
    }
}
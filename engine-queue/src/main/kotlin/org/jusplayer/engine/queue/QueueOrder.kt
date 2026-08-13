package org.jusplayer.engine.queue

import kotlin.random.Random
import org.jusplayer.engine.model.RepeatMode

/**
 * Pure, deterministic queue navigation helpers.
 *
 * These functions only transform data — they hold no queue state themselves —
 * which keeps them trivial to unit-test and independent of playback, network,
 * or UI. The [QueueEngine] orchestrates them inside a lock; each helper here is
 * intentionally side-effect-free.
 *
 * An ordered result is returned as a [ReorderedQueue] (list + resolved cursor).
 */
internal data class ReorderedQueue<T>(
    val items: List<T>,
    val currentIndex: Int,
)

internal object QueueOrder {

    /**
     * Index of the next song to play, or `null` when there is genuinely no next
     * track (`RepeatMode.NONE` at the end of the queue). A `null` result is how
     * queue exhaustion is detected — callers must NOT clamp to the last index.
     *
     * - [RepeatMode.ONE] stays on the current track.
     * - [RepeatMode.ALL] wraps to the front after the last track.
     * - A cursor pointing before the queue (`-1`) but a non-empty queue yields
     *   index 0 (play the first queued track).
     */
    fun nextIndex(itemCount: Int, currentIndex: Int, repeatMode: RepeatMode): Int? {
        if (itemCount <= 0) return null
        return when (repeatMode) {
            RepeatMode.ONE ->
                if (currentIndex in 0 until itemCount) currentIndex else 0
            RepeatMode.ALL ->
                when {
                    currentIndex < 0 -> 0
                    currentIndex < itemCount - 1 -> currentIndex + 1
                    else -> 0
                }
            RepeatMode.NONE ->
                when {
                    currentIndex < 0 -> 0
                    currentIndex < itemCount - 1 -> currentIndex + 1
                    else -> null
                }
        }
    }

    /**
     * Index of the previous song. Never moves before the front.
     * - [RepeatMode.ONE] stays on the current track.
     * - [RepeatMode.ALL] wraps from the front to the last track.
     */
    fun previousIndex(itemCount: Int, currentIndex: Int, repeatMode: RepeatMode): Int? {
        if (itemCount <= 0) return null
        return when (repeatMode) {
            RepeatMode.ONE ->
                if (currentIndex in 0 until itemCount) currentIndex else 0
            RepeatMode.ALL ->
                when {
                    currentIndex <= 0 -> itemCount - 1
                    else -> currentIndex - 1
                }
            RepeatMode.NONE ->
                if (currentIndex <= 0) 0 else currentIndex - 1
        }
    }

    /**
     * Shuffles the remaining tracks while keeping the currently playing item
     * first: `[current, rest shuffled ...]`. If nothing is current, the whole
     * list is shuffled. Returns a reordered list with the cursor at the current
     * item (index 0) when it was preserved.
     */
    fun <T> shuffleFromCurrent(
        items: List<T>,
        currentIndex: Int,
        random: Random = Random.Default,
    ): ReorderedQueue<T> {
        if (items.isEmpty()) return ReorderedQueue(emptyList(), -1)
        if (items.size == 1) return ReorderedQueue(items, 0)
        if (currentIndex !in items.indices) {
            return ReorderedQueue(items.shuffled(random), -1)
        }
        val current = items[currentIndex]
        val rest = items.filterIndexed { index, _ -> index != currentIndex }.shuffled(random)
        return ReorderedQueue(listOf(current) + rest, currentIndex = 0)
    }

    /**
     * Restores the pre-shuffle [original] ordering and re-points the cursor at
     * [current] (matched by [keySelector]) so playback continues on the same
     * track. Falls back to the front when the current item is no longer present.
     */
    fun <T, K> restoreOriginal(
        original: List<T>,
        current: T?,
        keySelector: (T) -> K,
    ): ReorderedQueue<T> {
        if (original.isEmpty()) return ReorderedQueue(emptyList(), -1)
        if (current == null) return ReorderedQueue(original, currentIndex = if (original.isNotEmpty()) 0 else -1)
        val key = keySelector(current)
        val index = original.indexOfFirst { keySelector(it) == key }
        return ReorderedQueue(original, if (index >= 0) index else 0)
    }

    /** Index at which a "play next" song should land: immediately after the current one. */
    fun insertAfterIndex(itemCount: Int, currentIndex: Int): Int =
        (currentIndex + 1).coerceIn(0, itemCount)

    /**
     * Resolves [currentIndex] after [removedIndex] was removed from a list that
     * now has [newItemCount] items. Removing the current track falls forward to
     * the item that took its place (or the new last item).
     */
    fun indexAfterRemove(newItemCount: Int, currentIndex: Int, removedIndex: Int): Int {
        if (newItemCount <= 0) return -1
        return when {
            removedIndex == currentIndex -> currentIndex.coerceAtMost(newItemCount - 1)
            removedIndex < currentIndex -> currentIndex - 1
            else -> currentIndex
        }
    }

    /**
     * Resolves [currentIndex] after an item moved from [fromIndex] to [toIndex]
     * in a list that now has [newItemCount] items.
     */
    fun indexAfterMove(newItemCount: Int, currentIndex: Int, fromIndex: Int, toIndex: Int): Int {
        if (newItemCount <= 0) return -1
        return when {
            fromIndex == currentIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
    }

    /**
     * Index of the first item equal to [target] (or matching its [keySelector]);
     * `-1` when absent.
     */
    fun <T, K> indexOfMatch(items: List<T>, target: T, keySelector: (T) -> K): Int {
        items.indexOf(target).takeIf { it >= 0 }?.let { return it }
        val key = keySelector(target)
        return items.indexOfFirst { keySelector(it) == key }
    }
}
package org.jusplayer.engine.queue

import org.jusplayer.engine.model.RepeatMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueOrderTest {

    @Test
    fun `next NONE advances to the next index`() {
        assertEquals(1, QueueOrder.nextIndex(3, 0, RepeatMode.NONE))
        assertEquals(2, QueueOrder.nextIndex(3, 1, RepeatMode.NONE))
    }

    @Test
    fun `next NONE is null at end of queue`() {
        val result = QueueOrder.nextIndex(3, 2, RepeatMode.NONE)
        assertNull(result)
    }

    @Test
    fun `next NONE from unstarted cursor picks the first track`() {
        assertEquals(0, QueueOrder.nextIndex(3, -1, RepeatMode.NONE))
    }

    @Test
    fun `next NONE on empty queue is null at any cursor`() {
        assertNull(QueueOrder.nextIndex(0, -1, RepeatMode.NONE))
        assertNull(QueueOrder.nextIndex(0, 3, RepeatMode.NONE))
    }

    @Test
    fun `next ONE stays on the current track`() {
        assertEquals(2, QueueOrder.nextIndex(5, 2, RepeatMode.ONE))
        assertEquals(0, QueueOrder.nextIndex(1, 0, RepeatMode.ONE))
    }

    @Test
    fun `next ONE from an unstarted cursor picks the first track`() {
        assertEquals(0, QueueOrder.nextIndex(3, -1, RepeatMode.ONE))
    }

    @Test
    fun `next ALL wraps to the front after the last track`() {
        assertEquals(0, QueueOrder.nextIndex(3, 2, RepeatMode.ALL))
    }

    @Test
    fun `next ALL never ends`() {
        assertTrue(QueueOrder.nextIndex(2, 1, RepeatMode.ALL) != null)
    }

    @Test
    fun `previous NONE never leaves the front`() {
        assertEquals(0, QueueOrder.previousIndex(3, 0, RepeatMode.NONE))
        assertEquals(1, QueueOrder.previousIndex(3, 2, RepeatMode.NONE))
    }

    @Test
    fun `previous ONE stays on the current track`() {
        assertEquals(1, QueueOrder.previousIndex(3, 1, RepeatMode.ONE))
    }

    @Test
    fun `previous ALL wraps from the front to the last track`() {
        assertEquals(2, QueueOrder.previousIndex(3, 0, RepeatMode.ALL))
    }

    @Test
    fun `previous empty queue is null`() {
        assertNull(QueueOrder.previousIndex(0, -1, RepeatMode.ALL))
    }

    @Test
    fun `shuffleFromCurrent keeps the current track first`() {
        val items = listOf('A', 'B', 'C', 'D')
        val result = QueueOrder.shuffleFromCurrent(items, 2, Random(42))
        assertEquals('C', result.items.first())
        assertEquals(0, result.currentIndex)
        assertEquals(items.toSet(), result.items.toSet())
    }

    @Test
    fun `shuffleFromCurrent shuffles everything without a current track`() {
        val items = listOf('A', 'B', 'C')
        val result = QueueOrder.shuffleFromCurrent(items, -1, Random(7))
        assertEquals(items.toSet(), result.items.toSet())
        assertEquals(-1, result.currentIndex)
    }

    @Test
    fun `shuffleFromCurrent single item stays put`() {
        val result = QueueOrder.shuffleFromCurrent(listOf('A'), 0, Random(1))
        assertEquals(listOf('A'), result.items)
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `restoreOriginal re-points cursor at current`() {
        val original = listOf('A', 'B', 'C', 'D')
        val result = QueueOrder.restoreOriginal(original, 'C') { it }
        assertEquals(original, result.items)
        assertEquals(2, result.currentIndex)
    }

    @Test
    fun `restoreOriginal falls to front when current is gone`() {
        val result = QueueOrder.restoreOriginal(listOf('A', 'B'), 'Z') { it }
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `restoreOriginal empty stays unstarted`() {
        assertEquals(-1, QueueOrder.restoreOriginal(emptyList(), null) { it }.currentIndex)
    }

    @Test
    fun `insertAfterIndex places after current or at front`() {
        assertEquals(2, QueueOrder.insertAfterIndex(4, 1))
        assertEquals(0, QueueOrder.insertAfterIndex(1, -1))
    }

    @Test
    fun `indexAfterRemove falls forward when the current track is removed`() {
        assertEquals(1, QueueOrder.indexAfterRemove(2, 1, 1))
        assertEquals(0, QueueOrder.indexAfterRemove(1, 1, 1))
        assertEquals(-1, QueueOrder.indexAfterRemove(0, 0, 0))
    }

    @Test
    fun `indexAfterMove follows the standard adjust rules`() {
        // current at index 1, moving 1 -> 3 recomputes to 3
        assertEquals(3, QueueOrder.indexAfterMove(4, 1, 1, 3))
        // item moved from before the cursor past it shifts the cursor left
        assertEquals(1, QueueOrder.indexAfterMove(4, 2, 0, 2))
        assertEquals(1, QueueOrder.indexAfterMove(4, 2, 0, 3))
    }

    @Test
    fun `indexOfMatch finds by equality then by key`() {
        assertEquals(1, QueueOrder.indexOfMatch(listOf('A', 'B', 'C'), 'B') { it })
        assertEquals(1, QueueOrder.indexOfMatch(listOf('Q', 'R', 'S'), 'r') { it.uppercaseChar() })
        assertEquals(-1, QueueOrder.indexOfMatch(listOf('A', 'B'), 'D') { it })
    }
}
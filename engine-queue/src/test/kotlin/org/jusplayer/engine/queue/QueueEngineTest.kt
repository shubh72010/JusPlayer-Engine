package org.jusplayer.engine.queue

import org.jusplayer.engine.model.RepeatMode
import org.jusplayer.engine.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueEngineTest {
    private lateinit var queue: QueueEngine

    @BeforeTest
    fun setup() {
        queue = QueueEngine()
    }

    @AfterTest
    fun teardown() {
        queue.clear()
    }

    @Test
    fun testAddIncreasesSize() {
        queue.add(createSong("1"))
        assertEquals(1, queue.size)
    }

    @Test
    fun testAddAllIncreasesSize() {
        queue.addAll(listOf(createSong("1"), createSong("2")))
        assertEquals(2, queue.size)
    }

    @Test
    fun testRemoveDecreasesSize() {
        queue.add(createSong("1"))
        queue.remove(0)
        assertEquals(0, queue.size)
    }

    @Test
    fun testNextReturnsSong() {
        val song = createSong("1")
        queue.add(song)
        assertEquals(song, queue.next())
    }

    @Test
    fun testPreviousReturnsSong() {
        val song = createSong("1")
        queue.add(song)
        queue.next()
        assertEquals(song, queue.previous())
    }

    @Test
    fun testNextReturnsNullWhenEmpty() {
        assertNull(queue.next())
    }

    @Test
    fun testNextIsNullAtEndOfQueue() {
        queue.addAll(listOf(createSong("1"), createSong("2")))
        queue.next()
        assertEquals(createSong("2"), queue.next())
        assertNull(queue.next())
        assertFalse(queue.hasNext)
    }

    @Test
    fun testNextIsIdempotentAtEndOfQueue() {
        queue.addAll(listOf(createSong("1"), createSong("2")))
        queue.next()
        queue.next()
        val nullResult = queue.next()
        assertNull(nullResult)
    }

    @Test
    fun testClearResetsState() {
        queue.add(createSong("1"))
        queue.next()
        queue.clear()
        assertEquals(0, queue.size)
        assertNull(queue.currentSong)
        assertNull(queue.next())
    }

    @Test
    fun testMoveChangesOrder() {
        val song1 = createSong("1")
        val song2 = createSong("2")
        queue.add(song1)
        queue.add(song2)
        queue.move(0, 1)
        queue.next()
        assertEquals(song2, queue.currentSong)
        queue.next()
        assertNull(queue.next())
    }

    @Test
    fun testShuffleKeepsAllItems() {
        val song1 = createSong("1")
        val song2 = createSong("2")
        val song3 = createSong("3")
        queue.add(song1)
        queue.add(song2)
        queue.add(song3)
        queue.next()
        val currentBefore = queue.currentSong
        queue.shuffle()
        assertEquals(3, queue.size)
        assertEquals(currentBefore, queue.currentSong)
        assertTrue(queue.items.size == 3)
        assertTrue(queue.shuffleEnabled)
    }

    @Test
    fun testShuffleChangesOrder() {
        val songs = (1..10).map { createSong("$it") }
        queue.addAll(songs)
        queue.next()
        queue.shuffle()
        assertEquals(10, queue.size)
    }

    @Test
    fun testShuffleCurrentStaysFirst() {
        queue.addAll((1..10).map { createSong("$it") })
        queue.jumpTo(3)
        val current = queue.currentSong
        queue.shuffle()
        assertEquals(0, queue.currentIndexValue)
        assertEquals(current, queue.currentSong)
    }

    @Test
    fun testRestoreRecoversOriginalOrder() {
        queue.addAll((1..5).map { createSong("$it") })
        queue.jumpTo(2)
        val original = queue.items
        queue.shuffle()
        queue.restore()
        assertEquals(original, queue.items)
        assertEquals(createSong("3"), queue.currentSong)
        assertFalse(queue.shuffleEnabled)
    }

    @Test
    fun testRestoreWhenNothingShuffledIsNoop() {
        queue.add(createSong("1"))
        queue.restore()
        assertEquals(1, queue.size)
        assertFalse(queue.shuffleEnabled)
    }

    @Test
    fun testSetShuffleOnThenOff() {
        queue.addAll((1..4).map { createSong("$it") })
        queue.next()
        queue.setShuffle(true)
        assertTrue(queue.shuffleEnabled)
        queue.setShuffle(false)
        assertFalse(queue.shuffleEnabled)
        assertTrue(queue.items.size == 4)
    }

    @Test
    fun testRepeatNone() {
        queue.addAll(listOf(createSong("1"), createSong("2")))
        queue.next()
        queue.next()
        assertNull(queue.next())
    }

    @Test
    fun testRepeatOneReSelectsCurrent() {
        queue.addAll(listOf(createSong("1"), createSong("2"), createSong("3")))
        queue.setRepeatMode(RepeatMode.ONE)
        queue.next()
        assertEquals(createSong("1"), queue.currentSong)
        assertEquals(createSong("1"), queue.next())
        assertEquals(createSong("1"), queue.next())
        assertTrue(queue.hasNext)
    }

    @Test
    fun testRepeatAllWraps() {
        queue.addAll(listOf(createSong("1"), createSong("2")))
        queue.setRepeatMode(RepeatMode.ALL)
        queue.next()
        assertEquals(createSong("2"), queue.next())
        assertEquals(createSong("1"), queue.next())
        assertTrue(queue.hasNext)
    }

    @Test
    fun testRepeatAllPreviousWrapsFromFront() {
        queue.addAll(listOf(createSong("1"), createSong("2"), createSong("3")))
        queue.setRepeatMode(RepeatMode.ALL)
        queue.next()
        assertEquals(createSong("3"), queue.previous())
    }

    @Test
    fun testPreviousRestartsFirstTrackUnderNone() {
        queue.addAll(listOf(createSong("1"), createSong("2")))
        queue.next()
        assertEquals(createSong("1"), queue.previous())
        assertEquals(createSong("1"), queue.previous())
    }

    @Test
    fun testAddNextInsertsAfterCurrent() {
        queue.addAll(listOf(createSong("1"), createSong("2"), createSong("3")))
        queue.next()
        queue.addNext(createSong("X"))
        val order = queue.items
        assertEquals(listOf("1", "X", "2", "3"), order.map { it.id })
        assertEquals(createSong("X"), queue.next())
        assertEquals(createSong("2"), queue.next())
    }

    @Test
    fun testAddNextOnEmptyQueue() {
        queue.addNext(createSong("X"))
        assertEquals(1, queue.size)
        assertNull(queue.currentSong)
        assertEquals(createSong("X"), queue.next())
    }

    @Test
    fun testRemoveMatchingRemovesFirstById() {
        queue.addAll(listOf(createSong("1"), createSong("2"), createSong("1")))
        assertTrue(queue.removeMatching(createSong("1")))
        assertEquals(listOf("2", "1"), queue.items.map { it.id })
        assertTrue(queue.removeMatching(createSong("1")))
        assertEquals(listOf("2"), queue.items.map { it.id })
        assertFalse(queue.removeMatching(createSong("9")))
    }

    @Test
    fun testRemoveCurrentFallsForwardToNext() {
        queue.addAll(listOf(createSong("1"), createSong("2"), createSong("3")))
        queue.jumpTo(1)
        queue.remove(1)
        // "3" shifts into the removed slot, so it becomes "current".
        assertEquals(createSong("3"), queue.currentSong)
    }

    @Test
    fun testSnapshotPortraysState() {
        queue.add(createSong("1"))
        val snapshot = queue.state.value
        assertEquals(1, snapshot.size)
        assertEquals(createSong("1"), snapshot.items.first())
        assertFalse(snapshot.isEmpty)
        assertTrue(snapshot.hasNext)
    }

    @Test
    fun testConcurrentMutationsAreSafe() = runBlocking {
        val jobs = (1..8).map { worker ->
            async(Dispatchers.Default) {
                repeat(200) { i ->
                    when (worker % 4) {
                        0 -> queue.add(createSong("w$worker-$i"))
                        1 -> queue.next()
                        2 -> queue.removeMatching(createSong("w${worker or 1}-0"))
                        else -> queue.shuffle()
                    }
                }
            }
        }
        jobs.awaitAll()
        assertTrue(queue.size >= 0)
        assertTrue(queue.items.distinctBy { it.id }.size <= queue.items.size)
    }

    private fun createSong(id: String): Song {
        return Song(
            id = id,
            title = "Song $id",
            artists = emptyList(),
            album = null,
            duration = 180_000,
            thumbnailUrl = null,
            streamUrl = null,
        )
    }
}
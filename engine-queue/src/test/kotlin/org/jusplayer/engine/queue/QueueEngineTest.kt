package org.jusplayer.engine.queue

import org.jusplayer.engine.model.Song
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val result = queue.next()
        assertEquals(song, result)
    }

    @Test
    fun testPreviousReturnsSong() {
        val song = createSong("1")
        queue.add(song)
        queue.next()
        val result = queue.previous()
        assertEquals(song, result)
    }

    @Test
    fun testNextReturnsNullWhenEmpty() {
        assertNull(queue.next())
    }

    @Test
    fun testClearResetsState() {
        queue.add(createSong("1"))
        queue.next()
        queue.clear()
        assertEquals(0, queue.size)
        assertNull(queue.currentSong)
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
        assertEquals(song1, queue.currentSong)
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
    fun testIsEmptyInitially() {
        assertTrue(queue.isEmpty)
    }

    @Test
    fun testRemoveDoesNotCrashOnInvalidIndex() {
        queue.remove(0)
        assertEquals(0, queue.size)
    }

    @Test
    fun testMoveDoesNotCrashOnInvalidIndices() {
        queue.add(createSong("1"))
        queue.move(0, 5)
        queue.move(5, 0)
        assertEquals(1, queue.size)
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
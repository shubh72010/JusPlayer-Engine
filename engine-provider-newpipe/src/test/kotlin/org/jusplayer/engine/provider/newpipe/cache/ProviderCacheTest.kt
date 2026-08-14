package org.jusplayer.engine.provider.newpipe.cache

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderCacheTest {

    @AfterTest
    fun tearDown() {
        // nothing persistent to clean
    }

    @Test
    fun putAndGetReturnsValue() {
        val cache = ProviderCache(defaultTtlMillis = 60_000)
        cache.put("key", "value")
        assertEquals("value", cache.get<String>("key"))
    }

    @Test
    fun getMissingKeyReturnsNull() {
        val cache = ProviderCache()
        assertNull(cache.get<String>("missing"))
    }

    @Test
    fun expiredEntryIsEvicted() {
        val cache = ProviderCache(defaultTtlMillis = 1)
        cache.put("key", "value")
        Thread.sleep(5)
        assertNull(cache.get<String>("key"))
    }

    @Test
    fun removeDeletesEntry() {
        val cache = ProviderCache()
        cache.put("key", "value")
        cache.remove("key")
        assertNull(cache.get<String>("key"))
    }

    @Test
    fun clearEmptiesCache() {
        val cache = ProviderCache()
        cache.put("a", 1)
        cache.put("b", 2)
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun sizeTracksEntries() {
        val cache = ProviderCache()
        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(2, cache.size)
    }

    @Test
    fun evictsOldestBeyondMaxSize() {
        val cache = ProviderCache(maxSize = 3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        cache.put("d", 4)
        assertEquals(3, cache.size)
        // Insertion-order eviction: "a" was the first inserted and is gone.
        assertNull(cache.get<String>("a"))
        assertEquals(2, cache.get<Int>("b"))
        assertEquals(4, cache.get<Int>("d"))
    }

    @Test
    fun overwritingSameKeyDoesNotCountTwice() {
        val cache = ProviderCache(maxSize = 2)
        cache.put("a", 1)
        cache.put("a", 2)
        cache.put("b", 3)
        assertEquals(2, cache.size)
        assertEquals(2, cache.get<Int>("a"))
        assertEquals(3, cache.get<Int>("b"))
    }

    @Test
    fun maxSizeOfOneKeepsOnlyLatest() {
        val cache = ProviderCache(maxSize = 1)
        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(1, cache.size)
        assertNull(cache.get<Int>("a"))
        assertEquals(2, cache.get<Int>("b"))
    }
}

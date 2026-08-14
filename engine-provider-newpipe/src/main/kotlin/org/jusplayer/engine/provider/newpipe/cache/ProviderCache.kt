package org.jusplayer.engine.provider.newpipe.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * A simple thread-safe, in-memory cache with a time-to-live per entry and a
 * bounded size. When the cache exceeds [maxSize], the least-recently inserted
 * entry is evicted (insertion-order eviction) so the cache cannot grow without
 * bound.
 *
 * Used to cache provider responses that are cheap to store and expensive to
 * re-extract (search results, song metadata, lyrics). Audio streams must never
 * be cached here — they are too large and have short-lived URLs.
 */
class ProviderCache(
    private val defaultTtlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) {
    private data class Entry(val value: Any, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val insertionOrder = ArrayDeque<String>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            entries.remove(key)
            return null
        }
        return entry.value as T
    }

    fun put(key: String, value: Any, ttlMillis: Long = defaultTtlMillis) {
        synchronized(entries) {
            val isNew = entries.put(key, Entry(value, System.currentTimeMillis() + ttlMillis)) == null
            if (isNew) insertionOrder.addLast(key)
            while (entries.size > maxSize && insertionOrder.isNotEmpty()) {
                entries.remove(insertionOrder.removeFirst())
            }
        }
    }

    fun remove(key: String) {
        synchronized(entries) {
            entries.remove(key)
            insertionOrder.remove(key)
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            insertionOrder.clear()
        }
    }

    val size: Int
        get() = entries.size

    companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_MAX_SIZE = 256
    }
}

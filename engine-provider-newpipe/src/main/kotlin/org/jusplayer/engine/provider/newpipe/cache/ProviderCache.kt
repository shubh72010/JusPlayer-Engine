package org.jusplayer.engine.provider.newpipe.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * A simple thread-safe, in-memory cache with a time-to-live per entry.
 *
 * Used to cache provider responses that are cheap to store and expensive to
 * re-extract (search results, song metadata, lyrics). Audio streams must never
 * be cached here — they are too large and have short-lived URLs.
 */
class ProviderCache(
    private val defaultTtlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private data class Entry(val value: Any, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

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
        entries[key] = Entry(value, System.currentTimeMillis() + ttlMillis)
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }

    val size: Int
        get() = entries.size

    companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
    }
}

package org.jusplayer.engine.core

import org.jusplayer.engine.provider.MusicProvider
import org.jusplayer.engine.provider.ProviderCapabilities
import org.jusplayer.engine.model.Song
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeProvider : MusicProvider {
    override val name: String = "FakeProvider"
    override val capabilities: ProviderCapabilities = ProviderCapabilities()

    override suspend fun search(query: String): org.jusplayer.engine.model.SearchResult {
        return org.jusplayer.engine.model.SearchResult(emptyList(), emptyList(), emptyList(), emptyList())
    }

    override suspend fun getSong(id: String): Song {
        return Song(id = id, title = "Test", artists = emptyList(), album = null, duration = 0, thumbnailUrl = null)
    }

    override suspend fun getStream(songId: String): org.jusplayer.engine.model.Stream {
        return org.jusplayer.engine.model.Stream("", "mp3", bitrate = null, sampleRate = null, isLive = false, duration = 0)
    }
}

class ProviderRegistryTest {
    private lateinit var registry: ProviderRegistry

    @BeforeTest
    fun setup() {
        registry = ProviderRegistry()
    }

    @AfterTest
    fun teardown() {
        registry.names().forEach { registry.unregister(it) }
    }

    @Test
    fun testRegisterAddsProvider() {
        val provider = FakeProvider()
        registry.register(provider)
        assertEquals(1, registry.names().size)
        assertTrue(registry.hasProvider("FakeProvider"))
    }

    @Test
    fun testUnregisterRemovesProvider() {
        val provider = FakeProvider()
        registry.register(provider)
        registry.unregister("FakeProvider")
        assertEquals(0, registry.names().size)
    }

    @Test
    fun testGetProviderReturnsCorrectInstance() {
        val provider = FakeProvider()
        registry.register(provider)
        assertEquals(provider, registry.getProvider("FakeProvider"))
    }

    @Test
    fun testGetProviderReturnsNullForUnknown() {
        assertNull(registry.getProvider("Unknown"))
    }

    @Test
    fun testGetAllProvidersReturnsAllRegistered() {
        val provider = FakeProvider()
        registry.register(provider)
        assertEquals(1, registry.getAllProviders().size)
        assertEquals(provider, registry.getAllProviders().first())
    }

    @Test
    fun testGetDefaultReturnsFirstRegistered() {
        val provider = FakeProvider()
        registry.register(provider)
        assertEquals(provider, registry.getDefault())
    }

    @Test
    fun testGetDefaultReturnsNullWhenEmpty() {
        assertNull(registry.getDefault())
    }

    @Test
    fun testHasProviderReturnsFalseForUnregistered() {
        assertTrue(!registry.hasProvider("NonExistent"))
    }

    @Test
    fun testMultipleProvidersCanBeRegistered() {
        registry.register(FakeProvider())
        assertEquals(1, registry.names().size)
    }
}
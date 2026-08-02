package org.jusplayer.engine.core

import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.ProviderChanged
import org.jusplayer.engine.provider.MusicProvider

class ProviderRegistry {
    private val providers = mutableMapOf<String, MusicProvider>()

    fun register(provider: MusicProvider) {
        providers[provider.name] = provider
    }

    fun unregister(name: String) {
        providers.remove(name)
    }

    fun getProvider(name: String): MusicProvider? {
        return providers[name]
    }

    fun getAllProviders(): List<MusicProvider> {
        return providers.values.toList()
    }

    fun getDefault(): MusicProvider? {
        return providers.values.firstOrNull()
    }

    fun hasProvider(name: String): Boolean {
        return providers.containsKey(name)
    }

    fun names(): Set<String> {
        return providers.keys.toSet()
    }
}
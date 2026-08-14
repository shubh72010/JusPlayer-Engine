package org.jusplayer.engine.core

import org.jusplayer.engine.events.EventBus
import org.jusplayer.engine.events.ProviderChanged
import org.jusplayer.engine.provider.MusicProvider

/**
 * A registry of [MusicProvider]s. When an [EventBus] is supplied, registering or
 * unregistering a provider announces a [ProviderChanged] so subscribers (e.g.
 * the diagnostics layer) stay in sync.
 */
class ProviderRegistry(
    private val eventBus: EventBus? = null,
) {
    private val providers = mutableMapOf<String, MusicProvider>()

    fun register(provider: MusicProvider) {
        synchronized(providers) {
            providers[provider.name] = provider
        }
        eventBus?.tryEmit(ProviderChanged(provider.name))
    }

    fun unregister(name: String) {
        synchronized(providers) {
            providers.remove(name)
        }
        eventBus?.tryEmit(ProviderChanged(name))
    }

    fun getProvider(name: String): MusicProvider? {
        return synchronized(providers) { providers[name] }
    }

    fun getAllProviders(): List<MusicProvider> {
        return synchronized(providers) { providers.values.toList() }
    }

    fun getDefault(): MusicProvider? {
        return synchronized(providers) { providers.values.firstOrNull() }
    }

    fun hasProvider(name: String): Boolean {
        return synchronized(providers) { providers.containsKey(name) }
    }

    fun names(): Set<String> {
        return synchronized(providers) { providers.keys.toSet() }
    }
}

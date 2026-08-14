package org.jusplayer.engine.autoplay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jusplayer.engine.model.Song

/**
 * The default [AutoplayEngine]: a provider-agnostic pipeline that aggregates any
 * registered [RecommendationProvider]s and passes their output through
 * [AutoplayPipeline].
 *
 * Provider failures are isolated — one provider throwing (or timing out) only
 * drops that provider's contribution, never the whole recommendation pass.
 */
class DefaultAutoplayEngine(
    private val providers: List<RecommendationProvider>,
    private val history: AutoplayHistory,
    override val config: AutoplayConfig = AutoplayConfig(),
) : AutoplayEngine {

    override suspend fun recommend(context: AutoplayContext): List<Song> {
        if (providers.isEmpty()) return emptyList()

        val candidates = coroutineScope {
            providers.map { provider ->
                async {
                    // A per-provider timeout or failure only drops that
                    // provider's contribution; real cancellation (parent scope
                    // torn down) must propagate, so CancellationException (other
                    // than the timeout's own) is rethrown.
                    try {
                        withTimeout(config.providerTimeoutMs) {
                            provider.recommend(context, config.bufferSize * 2)
                        }
                    } catch (e: TimeoutCancellationException) {
                        emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        return AutoplayPipeline.process(
            candidates = candidates,
            currentSong = context.currentSong,
            queueSongs = context.queueSongs,
            history = history,
            config = config,
        )
    }
}
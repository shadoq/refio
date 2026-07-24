package pl.jclab.refio.core.services

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Caps the number of simultaneous outbound LLM requests to a single provider.
 *
 * When many subagents run in parallel each opens its own stream to the same provider (all N
 * hitting `LLMClient.complete` at once), which overruns provider rate limits (e.g. OpenRouter
 * 429s). This gate — a per-provider [Semaphore], mirroring [OllamaRequestGate] but keyed by the
 * provider name instead of the endpoint — bounds that concurrency. It composes with the existing
 * retry-on-429 in `LLMRetryHandler`: the gate prevents most bursts, the retry handles any that
 * still slip through.
 *
 * The permit is held for the *entire* request (including a streamed body), so a slow stream keeps
 * a slot for its full duration — size [maxConcurrentPerProvider] accordingly.
 *
 * `ollama` is intentionally NOT gated here: it manages its own concurrency at the adapter level via
 * [OllamaRequestGate], keyed per endpoint (a machine can run several Ollama endpoints). Gating it
 * again here — by the single provider name — would collapse all endpoints into one slot and stack
 * two semaphores on the same call.
 */
object ProviderRequestGate {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    /** Providers that throttle themselves at the adapter level; bypassed here. */
    private val selfGatedProviders = setOf("ollama")

    /** Sensible default ceiling; matches [pl.jclab.refio.core.config.ConfigKeys.PROVIDER_MAX_CONCURRENT]. */
    const val DEFAULT_MAX_CONCURRENT = 4

    /**
     * Max concurrent requests per provider. Changes take effect only for providers whose semaphore
     * has not been built yet — call [resetProvider] to force-rebuild one. A value `<= 0` is ignored
     * by [pl.jclab.refio.core.api.modules.CoreApiRouterBootstrap]; treat this as always positive.
     */
    @Volatile
    var maxConcurrentPerProvider: Int = DEFAULT_MAX_CONCURRENT

    suspend fun <T> withPermit(provider: String, block: suspend () -> T): T {
        val key = provider.trim().lowercase()
        if (key.isEmpty() || key in selfGatedProviders) {
            return block()
        }
        val semaphore = semaphores.computeIfAbsent(key) {
            Semaphore(maxConcurrentPerProvider.coerceAtLeast(1))
        }
        return semaphore.withPermit {
            block()
        }
    }

    /**
     * Force-rebuild the semaphore for a provider with the current [maxConcurrentPerProvider].
     * Only call when no requests are in-flight for this provider.
     */
    fun resetProvider(provider: String) {
        val key = provider.trim().lowercase()
        semaphores[key] = Semaphore(maxConcurrentPerProvider.coerceAtLeast(1))
    }
}

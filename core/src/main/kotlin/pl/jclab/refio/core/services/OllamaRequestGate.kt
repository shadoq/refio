package pl.jclab.refio.core.services

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object OllamaRequestGate {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    /**
     * Max concurrent requests per endpoint. Default 1 (sequential). Increase for multi-agent.
     *
     * Changes take effect only for NEW endpoints — existing semaphores are not rebuilt
     * to avoid invalidating semaphores held by active coroutines.
     * Call [resetEndpoint] to force-rebuild a specific endpoint's semaphore.
     */
    @Volatile
    var maxConcurrentPerEndpoint: Int = 1

    suspend fun <T> withPermit(endpoint: String, block: suspend () -> T): T {
        val normalizedEndpoint = endpoint.trim().removeSuffix("/")
        val semaphore = semaphores.computeIfAbsent(normalizedEndpoint) {
            Semaphore(maxConcurrentPerEndpoint)
        }
        return semaphore.withPermit {
            block()
        }
    }

    /**
     * Force-rebuild semaphore for a specific endpoint with current [maxConcurrentPerEndpoint].
     * Only call when no requests are in-flight for this endpoint.
     */
    fun resetEndpoint(endpoint: String) {
        val normalizedEndpoint = endpoint.trim().removeSuffix("/")
        semaphores[normalizedEndpoint] = Semaphore(maxConcurrentPerEndpoint)
    }
}

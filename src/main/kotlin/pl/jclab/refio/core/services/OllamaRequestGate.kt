package pl.jclab.refio.core.services

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object OllamaRequestGate {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    /** Max concurrent requests per endpoint. Default 1 (sequential). Increase for multi-agent. */
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
}

package pl.jclab.refio.core.services

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object OllamaRequestGate {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    suspend fun <T> withPermit(endpoint: String, block: suspend () -> T): T {
        val normalizedEndpoint = endpoint.trim().removeSuffix("/")
        val semaphore = semaphores.computeIfAbsent(normalizedEndpoint) { Semaphore(1) }
        return semaphore.withPermit {
            block()
        }
    }
}

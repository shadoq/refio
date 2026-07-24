package pl.jclab.refio.core.services

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderRequestGateTest {

    @AfterEach
    fun restoreDefault() {
        ProviderRequestGate.maxConcurrentPerProvider = ProviderRequestGate.DEFAULT_MAX_CONCURRENT
    }

    @Test
    fun `executes the block and returns its result`() = runTest {
        val result = ProviderRequestGate.withPermit("openrouter") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `caps concurrent requests to one provider at maxConcurrentPerProvider`() = runTest {
        ProviderRequestGate.maxConcurrentPerProvider = 2
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        // Unique key so the semaphore is built with the value set above (cached per key).
        val jobs = (1..6).map {
            async {
                ProviderRequestGate.withPermit("cap-provider") {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }
                    delay(20)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals(2, maxConcurrent.get(), "no more than maxConcurrentPerProvider streams may run at once")
    }

    @Test
    fun `ollama is not gated here - it self-throttles per endpoint in its adapter`() = runTest {
        ProviderRequestGate.maxConcurrentPerProvider = 1
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (1..4).map {
            async {
                ProviderRequestGate.withPermit("ollama") {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }
                    delay(20)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals(4, maxConcurrent.get(), "ollama must bypass this gate, not be serialized by it")
    }

    @Test
    fun `each provider has an independent semaphore`() = runTest {
        ProviderRequestGate.maxConcurrentPerProvider = 1
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = listOf("independent-a", "independent-b").map { providerKey ->
            async {
                ProviderRequestGate.withPermit(providerKey) {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }
                    delay(50)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }

        assertTrue(maxConcurrent.get() == 2, "two different providers must not block each other")
    }

    @Test
    fun `default ceiling is 4`() {
        assertEquals(4, ProviderRequestGate.DEFAULT_MAX_CONCURRENT)
    }
}

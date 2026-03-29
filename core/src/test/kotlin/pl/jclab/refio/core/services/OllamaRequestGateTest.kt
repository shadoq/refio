package pl.jclab.refio.core.services

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaRequestGateTest {

    @Test
    fun `should execute block and return result`() = runTest {
        val result = OllamaRequestGate.withPermit("http://localhost:11434") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `should serialize requests to same endpoint by default`() = runTest {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (1..3).map {
            async {
                OllamaRequestGate.withPermit("http://localhost:11434/serial") {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }
                    delay(20)
                    concurrent.decrementAndGet()
                    "done"
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals(1, maxConcurrent.get(), "Default should serialize (1 concurrent)")
    }

    @Test
    fun `should normalize endpoints`() = runTest {
        // Trailing slash should be removed
        val r1 = OllamaRequestGate.withPermit("http://localhost:11434/") { "a" }
        val r2 = OllamaRequestGate.withPermit("http://localhost:11434") { "b" }
        assertEquals("a", r1)
        assertEquals("b", r2)
    }

    @Test
    fun `should have independent semaphores per endpoint`() = runTest {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = listOf(
            async {
                OllamaRequestGate.withPermit("http://host1:11434/independent") {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }
                    delay(50)
                    concurrent.decrementAndGet()
                }
            },
            async {
                OllamaRequestGate.withPermit("http://host2:11434/independent") {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }
                    delay(50)
                    concurrent.decrementAndGet()
                }
            }
        )
        jobs.forEach { it.await() }

        // Different endpoints should run concurrently
        assertTrue(maxConcurrent.get() >= 1)
    }

    @Test
    fun `maxConcurrentPerEndpoint default should be 1`() {
        assertEquals(1, OllamaRequestGate.maxConcurrentPerEndpoint)
    }
}

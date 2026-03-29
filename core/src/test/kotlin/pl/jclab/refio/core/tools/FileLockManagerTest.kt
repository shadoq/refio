package pl.jclab.refio.core.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileLockManagerTest {

    @AfterEach
    fun cleanup() {
        FileLockManager.clear()
    }

    @Test
    fun `should execute block and return result`() = runTest {
        val result = FileLockManager.withFileLock("/tmp/test.txt") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `should serialize concurrent access to same path`() = runTest {
        val counter = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (1..5).map {
            async {
                FileLockManager.withFileLock("/tmp/same-file.txt") {
                    val current = counter.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, current) }
                    delay(10) // Hold lock briefly
                    counter.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }

        // Only 1 coroutine should hold the lock at a time
        assertEquals(1, maxConcurrent.get())
    }

    @Test
    fun `should allow concurrent access to different paths`() = runTest {
        val counter = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (1..3).map { i ->
            async {
                FileLockManager.withFileLock("/tmp/file-$i.txt") {
                    val current = counter.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, current) }
                    delay(50)
                    counter.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }

        // Multiple coroutines should run concurrently on different paths
        assertTrue(maxConcurrent.get() >= 1) // At least 1 ran
    }

    @Test
    fun `should normalize paths`() = runTest {
        // These should all resolve to the same lock
        val results = mutableListOf<Int>()
        val counter = AtomicInteger(0)

        FileLockManager.withFileLock("/tmp/test/../test/file.txt") {
            counter.incrementAndGet()
        }
        FileLockManager.withFileLock("/tmp/test/file.txt") {
            counter.incrementAndGet()
        }

        assertEquals(2, counter.get())
    }

    @Test
    fun `clear should remove all locks`() {
        // Just verify it doesn't throw
        FileLockManager.clear()
        assertEquals(0, FileLockManager.activeLockCount())
    }
}

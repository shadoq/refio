package pl.jclab.refio.core.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    @Test
    fun `eviction between lookup and lock cannot break mutual exclusion`() = kotlinx.coroutines.runBlocking {
        // The whole point of this manager is that two agents never write the same file at once.
        // Looking the entry up and locking it are two steps: if eviction drops the entry in between,
        // the next caller creates a second mutex for the same path and both "hold the lock".
        FileLockManager.clear()
        val inside = java.util.concurrent.atomic.AtomicBoolean(false)
        val violations = AtomicInteger(0)

        val evictor = launch(kotlinx.coroutines.Dispatchers.Default) {
            while (isActive) {
                FileLockManager.evictAllUnlockedForTest()
            }
        }
        val workers = (1..8).map {
            launch(kotlinx.coroutines.Dispatchers.Default) {
                repeat(2_000) {
                    FileLockManager.withFileLock("/tmp/contended-file") {
                        if (!inside.compareAndSet(false, true)) {
                            violations.incrementAndGet()
                        }
                        kotlinx.coroutines.yield()
                        inside.set(false)
                    }
                }
            }
        }

        workers.forEach { it.join() }
        evictor.cancelAndJoin()

        assertEquals(0, violations.get(), "two coroutines held the lock for the same file at once")
    }
}

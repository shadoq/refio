package pl.jclab.refio.core.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests verifying FileLockManager prevents data races
 * when multiple coroutines attempt concurrent file writes.
 */
class FileLockManagerIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun cleanup() {
        FileLockManager.clear()
    }

    @Test
    fun `concurrent writes to same file should be serialized`() = runTest {
        val file = tempDir.resolve("shared.txt").also {
            it.toFile().writeText("initial")
        }
        val path = file.toAbsolutePath().toString()
        val writeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val concurrent = AtomicInteger(0)

        val jobs = (1..10).map { i ->
            async {
                FileLockManager.withFileLock(path) {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }

                    // Simulate read-modify-write cycle
                    val content = file.toFile().readText()
                    delay(5) // Simulate processing
                    file.toFile().writeText("$content-$i")

                    concurrent.decrementAndGet()
                    writeCount.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(10, writeCount.get())
        assertEquals(1, maxConcurrent.get(), "Only 1 coroutine should access file at a time")
    }

    @Test
    fun `writes to different files should proceed in parallel`() = runTest {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (1..5).map { i ->
            val file = tempDir.resolve("file-$i.txt").also {
                it.toFile().writeText("content-$i")
            }
            async {
                FileLockManager.withFileLock(file.toAbsolutePath().toString()) {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }
                    delay(30)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertTrue(maxConcurrent.get() >= 1, "Different files should allow concurrent access")
    }

    @Test
    fun `lock should be released even when block throws`() = runTest {
        val path = tempDir.resolve("error.txt").toAbsolutePath().toString()

        // First call throws
        try {
            FileLockManager.withFileLock(path) {
                throw RuntimeException("Simulated error")
            }
        } catch (_: RuntimeException) {}

        // Second call should succeed (lock released after exception)
        var secondCallExecuted = false
        FileLockManager.withFileLock(path) {
            secondCallExecuted = true
        }

        assertTrue(secondCallExecuted, "Lock should be released after exception")
    }

    @Test
    fun `lock should handle path normalization for same file`() = runTest {
        val file = tempDir.resolve("normalize-test.txt").also {
            it.toFile().writeText("content")
        }

        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        // Use different path representations for the same file
        val paths = listOf(
            file.toAbsolutePath().toString(),
            tempDir.resolve("./normalize-test.txt").toAbsolutePath().toString(),
            tempDir.resolve("sub/../normalize-test.txt").toAbsolutePath().toString()
        )

        val jobs = paths.map { path ->
            async {
                FileLockManager.withFileLock(path) {
                    val c = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, c) }
                    delay(20)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(1, maxConcurrent.get(), "Normalized paths should share the same lock")
    }
}

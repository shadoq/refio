package pl.jclab.refio.core.services

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.util.concurrent.TimeUnit

class ProcessManagerTest {

    private val manager = ProcessManager()
    private val workDir = File(System.getProperty("java.io.tmpdir"))

    @Test
    @Timeout(30)
    @DisabledOnOs(OS.WINDOWS)
    fun `chatty process is not blocked by a full pipe buffer`() {
        // Emits far more than the ~64KB OS pipe capacity; without a continuous drain
        // thread the child would block on write() and never exit.
        val command = "i=0; while [ \$i -lt 20000 ]; do echo line\$i; i=\$((i+1)); done"
        val managed = manager.start(command, workDir)

        assertTrue(
            managed.process.waitFor(20, TimeUnit.SECONDS),
            "process should exit because its output is continuously drained"
        )

        val (lines, _) = runBlocking { manager.readOutput(managed.processId, maxLines = 100000) }
        assertTrue(lines.isNotEmpty(), "drained output should be readable")
        // The buffer keeps the newest output when the cap is exceeded.
        assertTrue(lines.last() == "line19999", "newest output must be kept, got: ${lines.lastOrNull()}")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `readOutput returns accumulated new output and running state`() = runBlocking {
        val managed = manager.start("echo hello", workDir)
        managed.process.waitFor(10, TimeUnit.SECONDS)
        // Give the drain thread a moment to flush.
        Thread.sleep(200)

        val (lines, isRunning) = manager.readOutput(managed.processId)
        assertTrue(lines.contains("hello"))
        assertFalse(isRunning)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `stop kills the process`() {
        val managed = manager.start("sleep 60", workDir)
        assertTrue(managed.process.isAlive)

        manager.stop(managed.processId)

        assertTrue(managed.process.waitFor(10, TimeUnit.SECONDS), "process should be killed by stop()")
    }

    @Test
    fun `buffer drops oldest lines beyond the cap`() {
        val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        val managed = ProcessManager.ManagedProcess(
            "test",
            "noop",
            ProcessBuilder(javaExecutable, "-version").start()
        )
        val line = "x".repeat(1000)
        repeat(ProcessManager.MAX_BUFFERED_CHARS / 1000 + 100) { managed.append(line) }
        managed.append("newest")

        var chars = 0
        synchronized(managed.pendingLines) {
            chars = managed.pendingLines.sumOf { it.length }
        }
        assertTrue(chars <= ProcessManager.MAX_BUFFERED_CHARS, "buffer must stay bounded, was $chars")

        val taken = managed.take(Int.MAX_VALUE)
        assertTrue(taken.first().startsWith("[output truncated:"), "truncation must be surfaced")
        assertTrue(taken.last() == "newest", "newest output must be kept")
    }

    @Test
    @Timeout(10)
    fun `completed process is evicted after retention period`() {
        val shortLivedManager = ProcessManager(completedRetentionMs = 50, cleanupIntervalMs = 10)
        try {
            val managed = shortLivedManager.start("echo done", workDir)
            assertTrue(managed.process.waitFor(5, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 5000
            while (shortLivedManager.get(managed.processId) != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(shortLivedManager.get(managed.processId) == null)
        } finally {
            shortLivedManager.close()
        }
    }
}

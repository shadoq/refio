package pl.jclab.refio.core.services.hooks

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class HookExecutorTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `runCommand executes shell command with variable substitution`(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("hook.log")
        val executor = HookExecutor()

        val result = executor.runCommand(
            command = "echo tool={toolName} >> \"${logFile.toAbsolutePath()}\"",
            variables = mapOf("toolName" to "code_editing"),
            timeoutMs = 5000
        )

        assertTrue(result.success, "Command should succeed: ${result.error}")
        val content = logFile.toFile().readText()
        assertTrue(content.contains("tool=code_editing"), "Variable should be substituted. Got: $content")
    }

    @Test
    fun `runCommand returns failure on timeout`() {
        val executor = HookExecutor()

        val result = executor.runCommand(
            command = if (isWindows) "ping -n 10 127.0.0.1" else "sleep 10",
            variables = emptyMap(),
            timeoutMs = 500
        )

        assertFalse(result.success)
        assertTrue(result.error?.contains("Timeout") == true)
    }

    @Test
    fun `runCommand substitutes multiple variables`() {
        val executor = HookExecutor()

        val result = executor.runCommand(
            command = "echo {taskId}-{mode}-{toolName}",
            variables = mapOf("taskId" to "t1", "mode" to "AGENT", "toolName" to "read_file"),
            timeoutMs = 5000
        )

        assertTrue(result.success)
        assertTrue(result.output?.contains("t1-AGENT-read_file") == true, "Got: ${result.output}")
    }

    @Test
    fun `notify calls callback with substituted message`() {
        val executor = HookExecutor()
        var received: String? = null

        executor.notify(
            message = "Agent {agentName} completed with {iterations} iterations",
            variables = mapOf("agentName" to "code-reviewer", "iterations" to "5"),
            callback = { received = it }
        )

        assertEquals("Agent code-reviewer completed with 5 iterations", received)
    }
}

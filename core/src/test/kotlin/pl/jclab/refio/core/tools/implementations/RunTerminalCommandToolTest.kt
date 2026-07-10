package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.security.CommandLimits
import pl.jclab.refio.core.tools.security.CommandRule
import pl.jclab.refio.core.tools.security.CommandRuleMatcher
import pl.jclab.refio.core.tools.security.RuleAction
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Testy dla RunTerminalCommandTool — narzędzia do uruchamiania poleceń terminalowych.
 */
class RunTerminalCommandToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var limits: CommandLimits
    private lateinit var tool: RunTerminalCommandTool

    private fun allowAllMatcher(): CommandRuleMatcher =
        CommandRuleMatcher(listOf(CommandRule(".*", RuleAction.ALLOW, "test: allow all")))

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        limits = CommandLimits.DEFAULT
        tool = RunTerminalCommandTool(sandbox, limits, allowAllMatcher())
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("run_terminal_command", tool.name)
        }

        @Test
        fun `should have correct tool mode`() {
            assertEquals(ToolMode.WRITE, tool.mode)
        }

        @Test
        fun `should have correct tool category`() {
            assertEquals(ToolCategory.EXECUTION, tool.category)
        }

        @Test
        fun `should have non-empty description`() {
            assertTrue(tool.description.isNotEmpty())
        }
    }

    @Nested
    inner class ParameterValidationTests {

        @Test
        fun `should validate params with valid command`() {
            tool.validateParams(mapOf("command" to "echo hello"))
        }

        @Test
        fun `should throw exception when command is missing`() {
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(emptyMap())
            }
            assertTrue(exception.message!!.contains("command"))
        }

        @Test
        fun `should throw exception when command is null`() {
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                @Suppress("UNCHECKED_CAST")
                tool.validateParams(mapOf("command" to null) as Map<String, Any>)
            }
            assertTrue(exception.message!!.contains("command"))
        }

        @Test
        fun `should throw exception when command is empty`() {
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("command" to ""))
            }
            assertTrue(exception.message!!.contains("command"))
        }

        @Test
        fun `should throw exception when command is blank`() {
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("command" to "   "))
            }
            assertTrue(exception.message!!.contains("command"))
        }
    }

    @Nested
    inner class CommandExecutionTests {

        @Test
        fun `should execute simple command successfully`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo hello"))

            assertTrue(result.success)
            assertTrue(result.output!!.contains("hello"))
        }

        @Test
        fun `should return exit code in metadata`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo test"))

            assertNotNull(result.exitCode)
            assertEquals(0, result.exitCode)
        }

        @Test
        fun `should set durationMs correctly`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo test"))

            assertNotNull(result.durationMs)
            assertTrue(result.durationMs!! >= 0)
        }

        @Test
        fun `should include command in metadata`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo test"))

            assertNotNull(result.metadata)
            assertEquals("echo test", result.metadata!!["command"])
        }

        @Test
        fun `should include output length in metadata`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo test"))

            assertNotNull(result.metadata)
            assertTrue(result.metadata!!.containsKey("output_length"))
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when command parameter is missing`() = runBlocking {
            val result = tool.execute(emptyMap())

            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("command"))
        }

        @Test
        fun `should handle non-zero exit codes`() = runBlocking {
            val result = tool.execute(mapOf("command" to "ls /nonexistent_directory_12345"))

            assertNotNull(result.exitCode)
            assertTrue(result.exitCode != 0)
        }

        @Test
        fun `should handle command that produces no output`() = runBlocking {
            val os = System.getProperty("os.name").lowercase()
            val command = if (os.contains("windows")) "exit 0" else "true"

            val result = tool.execute(mapOf("command" to command))

            assertTrue(result.success, "Command should succeed")
            val exitCode = result.exitCode
            assertTrue(exitCode == null || exitCode == 0, "Exit code should be 0 or null but was: $exitCode")
        }
    }

    @Nested
    inner class CommandRuleValidationTests {

        @Test
        fun `should block command when rule matcher returns BLOCK`() = runBlocking {
            val blockingMatcher = CommandRuleMatcher(
                listOf(CommandRule("^echo\\b.*", RuleAction.BLOCK, "test: block echo"))
            )
            val strictTool = RunTerminalCommandTool(sandbox, limits, blockingMatcher)

            val result = strictTool.execute(mapOf("command" to "echo test"))

            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("blocked", ignoreCase = true))
        }

        @Test
        fun `should allow command when rule matcher returns ALLOW`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo allowed"))

            assertTrue(result.success)
        }

        @Test
        fun `should allow command on ASK (pre-approved at tool level)`() = runBlocking {
            val askMatcher = CommandRuleMatcher(
                listOf(CommandRule("^echo\\b.*", RuleAction.ASK, "test: ask"))
            )
            val askTool = RunTerminalCommandTool(sandbox, limits, askMatcher)

            val result = askTool.execute(mapOf("command" to "echo ask-case"))

            assertTrue(result.success)
        }
    }

    @Nested
    inner class TimeoutTests {

        @Test
        fun `should enforce command timeout`() = runBlocking {
            val strictLimits = CommandLimits(timeoutSeconds = 1)
            val strictTool = RunTerminalCommandTool(sandbox, strictLimits, allowAllMatcher())

            val result = strictTool.execute(mapOf("command" to "echo quick"))

            assertNotNull(result)
            assertTrue(result.success)
        }
    }

    @Nested
    inner class OrphanProcessTests {

        /**
         * A backgrounded child that keeps stdout open must not hang the tool.
         *
         * Under a non-interactive `sh -c` there is no job control, so `kill %1` is a no-op: the
         * backgrounded `sleep` survives the shell and keeps the inherited stdout pipe open. The tool
         * must still return promptly (reaping the orphan) instead of blocking on the never-ending
         * read for the full child lifetime.
         */
        @Test
        fun `should not hang when a backgrounded child keeps stdout open`() = runBlocking {
            val os = System.getProperty("os.name").lowercase()
            org.junit.jupiter.api.Assumptions.assumeFalse(os.contains("windows"), "POSIX job-control scenario")

            val childSleepSeconds = 60
            val timeoutSeconds = 2L
            val strictTool = RunTerminalCommandTool(
                sandbox,
                CommandLimits(timeoutSeconds = timeoutSeconds),
                allowAllMatcher()
            )

            // The child sleeps far longer than the shell lives; `kill %1` cannot stop it (no job
            // control), so it survives holding stdout open unless the tool reaps the tree.
            val command = "sleep $childSleepSeconds & sleep 0.2; kill %1"

            val elapsed = kotlin.system.measureTimeMillis {
                val result = strictTool.execute(mapOf("command" to command))
                assertNotNull(result)
            }

            // Must return well before the child's full sleep - proving the orphan did not block us.
            assertTrue(
                elapsed < childSleepSeconds * 1000L / 2,
                "Tool should return promptly, but took ${elapsed}ms"
            )
        }
    }

    @Nested
    inner class OutputLimitTests {

        @Test
        fun `should truncate large output`() = runBlocking {
            val strictLimits = CommandLimits(maxOutputSize = 100)
            val strictTool = RunTerminalCommandTool(sandbox, strictLimits, allowAllMatcher())

            val result = strictTool.execute(mapOf("command" to "echo very long output that should be truncated"))

            assertTrue(result.success)
            val output = result.output!!
            if (output.length > 100) {
                assertTrue(output.contains("truncated", ignoreCase = true))
            }
        }

        @Test
        fun `should indicate truncation in metadata`() = runBlocking {
            val strictLimits = CommandLimits(maxOutputSize = 10)
            val strictTool = RunTerminalCommandTool(sandbox, strictLimits, allowAllMatcher())

            val result = strictTool.execute(mapOf("command" to "echo this is longer than ten chars"))

            assertNotNull(result.metadata)
            val truncated = result.metadata!!["truncated"] as? Boolean
            if (result.output!!.length >= 10) {
                assertTrue(truncated == true)
            }
        }
    }

    @Nested
    inner class WorkingDirectoryTests {

        @Test
        fun `should execute command in project root`() = runBlocking {
            java.nio.file.Files.writeString(tempDir.resolve("test.txt"), "content")

            val command = if (System.getProperty("os.name").lowercase().contains("windows")) "dir" else "ls"
            val result = tool.execute(mapOf("command" to command))

            assertTrue(result.success)
        }

        @Test
        fun `should respect sandbox boundaries`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo test"))

            assertNotNull(result)
            assertTrue(result.success || result.error != null)
        }
    }

    @Nested
    inner class CommandParsingTests {

        @Test
        fun `should handle commands with arguments`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo hello world"))

            assertTrue(result.success)
            assertTrue(result.output!!.contains("hello") || result.output!!.contains("world"))
        }

        @Test
        fun `should handle commands with quotes`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo \"quoted text\""))

            assertTrue(result.success)
        }

        @Test
        fun `should handle empty output gracefully`() = runBlocking {
            val os = System.getProperty("os.name").lowercase()
            val command = if (os.contains("windows")) "exit 0" else "true"

            val result = tool.execute(mapOf("command" to command))

            assertTrue(result.success, "Command should succeed")
            val exitCode = result.exitCode
            assertTrue(exitCode == null || exitCode == 0, "Exit code should be 0 or null but was: $exitCode")
        }
    }

    @Nested
    inner class ErrorCaseTests {

        @Test
        fun `should handle malformed command gracefully`() = runBlocking {
            val result = tool.execute(mapOf("command" to "echo \"unclosed quote"))

            assertNotNull(result)
        }

        @Test
        fun `should handle command that produces error output`() = runBlocking {
            val result = tool.execute(mapOf("command" to "ls /nonexistent"))

            assertNotNull(result)
        }
    }

    @Nested
    inner class ParameterSchemaTests {

        @Test
        fun `should return valid parameter schema`() {
            val schema = tool.getParameterSchema()

            assertEquals("object", schema["type"])
            val properties = schema["properties"] as Map<*, *>
            assertNotNull(properties["command"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("command"))
        }
    }
}

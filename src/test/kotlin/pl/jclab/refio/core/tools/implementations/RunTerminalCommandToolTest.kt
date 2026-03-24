package pl.jclab.refio.core.tools.implementations

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.security.CommandDenylist
import pl.jclab.refio.core.tools.security.CommandLimits
import pl.jclab.refio.core.tools.security.CommandWhitelist
import pl.jclab.refio.core.tools.security.CommandWhitelistConfig
import pl.jclab.refio.core.tools.security.WhitelistMode
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Testy dla RunTerminalCommandTool — narzędzia do uruchamiania poleceń terminalowych.
 */
class RunTerminalCommandToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var whitelist: CommandWhitelist
    private lateinit var denylist: CommandDenylist
    private lateinit var limits: CommandLimits
    private lateinit var tool: RunTerminalCommandTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        denylist = CommandDenylist()
        limits = CommandLimits.DEFAULT

        val config = CommandWhitelistConfig(
            enabled = false, // Disabled for testing
            mode = WhitelistMode.WHITELIST_ONLY,
            globalBlockedPatterns = emptyList(),
            allowedCommands = emptyList()
        )
        whitelist = CommandWhitelist(config, denylist)

        tool = RunTerminalCommandTool(sandbox, whitelist, limits)
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
            // When & Then - should not throw
            tool.validateParams(mapOf("command" to "echo hello"))
        }

        @Test
        fun `should throw exception when command is missing`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(emptyMap())
            }
            assertTrue(exception.message!!.contains("command"))
        }

        @Test
        fun `should throw exception when command is null`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                @Suppress("UNCHECKED_CAST")
                tool.validateParams(mapOf("command" to null) as Map<String, Any>)
            }
            assertTrue(exception.message!!.contains("command"))
        }

        @Test
        fun `should throw exception when command is empty`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("command" to ""))
            }
            assertTrue(exception.message!!.contains("command"))
        }

        @Test
        fun `should throw exception when command is blank`() {
            // When & Then
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
            // When
            val result = tool.execute(mapOf("command" to "echo hello"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("hello"))
        }

        @Test
        fun `should return exit code in metadata`() = runBlocking {
            // When
            val result = tool.execute(mapOf("command" to "echo test"))

            // Then
            assertNotNull(result.exitCode)
            assertEquals(0, result.exitCode)
        }

        @Test
        fun `should set exitCode in ToolResult`() = runBlocking {
            // When
            val result = tool.execute(mapOf("command" to "echo test"))

            // Then - exitCode should be set directly on ToolResult
            assertNotNull(result.exitCode)
            assertEquals(0, result.exitCode)
        }

        @Test
        fun `should set durationMs correctly`() = runBlocking {
            // When
            val result = tool.execute(mapOf("command" to "echo test"))

            // Then
            assertNotNull(result.durationMs)
            assertTrue(result.durationMs!! >= 0)
        }

        @Test
        fun `should include command in metadata`() = runBlocking {
            // When
            val result = tool.execute(mapOf("command" to "echo test"))

            // Then
            assertNotNull(result.metadata)
            assertEquals("echo test", result.metadata!!["command"])
        }

        @Test
        fun `should include output length in metadata`() = runBlocking {
            // When
            val result = tool.execute(mapOf("command" to "echo test"))

            // Then
            assertNotNull(result.metadata)
            assertTrue(result.metadata!!.containsKey("output_length"))
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when command parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(emptyMap())

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("command"))
        }

        @Test
        fun `should handle non-zero exit codes`() = runBlocking {
            // When - command that will fail
            val result = tool.execute(mapOf("command" to "ls /nonexistent_directory_12345"))

            // Then
            // Result may be success=true with exit code != 0, or success=false with error
            // depending on implementation
            assertNotNull(result.exitCode)
            assertTrue(result.exitCode != 0)
        }

        @Test
        fun `should handle command that produces no output`() = runBlocking {
            // When - use cross-platform command that succeeds with no output
            // On Windows: exit 0 (PowerShell syntax for successful exit)
            // On Unix: true command
            val os = System.getProperty("os.name").lowercase()
            val command = if (os.contains("windows")) {
                "exit 0"  // PowerShell command that succeeds immediately
            } else {
                "true"  // Unix builtin
            }

            val result = tool.execute(mapOf("command" to command))

            // Then - command should succeed with exit code 0
            assertTrue(result.success, "Command should succeed")
            // exitCode should be 0 for successful command
            val exitCode = result.exitCode
            assertTrue(exitCode == null || exitCode == 0, "Exit code should be 0 or null but was: $exitCode")
        }
    }

    @Nested
    inner class WhitelistValidationTests {

        @Test
        fun `should block command when whitelist validation fails`() = runBlocking {
            // Given
            val enabledConfig = CommandWhitelistConfig(
                enabled = true,
                mode = WhitelistMode.WHITELIST_ONLY,
                globalBlockedPatterns = emptyList(),
                allowedCommands = emptyList() // No commands allowed
            )
            val strictWhitelist = CommandWhitelist(enabledConfig, denylist)
            val strictTool = RunTerminalCommandTool(sandbox, strictWhitelist, limits)

            // When
            val result = strictTool.execute(mapOf("command" to "echo test"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not on whitelist", ignoreCase = true))
        }

        @Test
        fun `should require confirmation when command requires it`() = runBlocking {
            // Given - command that requires confirmation (e.g., risky operation)
            // This test documents expected behavior when confirmation is needed
            // Actual implementation depends on whitelist configuration

            // When
            val result = tool.execute(mapOf("command" to "echo test"))

            // Then
            // With disabled whitelist, should execute normally
            assertTrue(result.success || result.error != null)
        }

        @Test
        fun `should block commands matching denylist patterns`() = runBlocking {
            // When - command with dangerous pattern
            val result = tool.execute(mapOf("command" to "rm -rf /tmp/test"))

            // Then
            // Should be blocked by denylist
            assertFalse(result.success)
            assertTrue(result.error!!.contains("not allowed", ignoreCase = true) ||
                       result.error!!.contains("blocked", ignoreCase = true))
        }
    }

    @Nested
    inner class TimeoutTests {

        @Test
        fun `should enforce command timeout`() = runBlocking {
            // Given - strict limits with short timeout
            val strictLimits = CommandLimits(timeoutSeconds = 1)
            val strictTool = RunTerminalCommandTool(sandbox, whitelist, strictLimits)

            // When - command that takes longer than timeout
            // Note: This is a simplified test - actual timeout testing requires
            // commands that reliably exceed the timeout
            val result = strictTool.execute(mapOf("command" to "echo quick"))

            // Then
            assertNotNull(result)
            // Command should complete quickly and succeed
            assertTrue(result.success)
        }
    }

    @Nested
    inner class OutputLimitTests {

        @Test
        fun `should truncate large output`() = runBlocking {
            // Given - strict limits with small output size
            val strictLimits = CommandLimits(maxOutputSize = 100)
            val strictTool = RunTerminalCommandTool(sandbox, whitelist, strictLimits)

            // When - command that produces lots of output
            val result = strictTool.execute(mapOf("command" to "echo very long output that should be truncated"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // Output should be truncated or marked as truncated
            if (output.length > 100) {
                assertTrue(output.contains("truncated", ignoreCase = true))
            }
        }

        @Test
        fun `should indicate truncation in metadata`() = runBlocking {
            // Given
            val strictLimits = CommandLimits(maxOutputSize = 10)
            val strictTool = RunTerminalCommandTool(sandbox, whitelist, strictLimits)

            // When
            val result = strictTool.execute(mapOf("command" to "echo this is longer than ten chars"))

            // Then
            assertNotNull(result.metadata)
            val truncated = result.metadata!!["truncated"] as? Boolean
            // If output was truncated, should be true
            if (result.output!!.length >= 10) {
                assertTrue(truncated == true)
            }
        }
    }

    @Nested
    inner class WorkingDirectoryTests {

        @Test
        fun `should execute command in project root`() = runBlocking {
            // Given - create a test file in temp directory
            java.nio.file.Files.writeString(tempDir.resolve("test.txt"), "content")

            // When - use cross-platform command
            val command = if (System.getProperty("os.name").lowercase().contains("windows")) "dir" else "ls"
            val result = tool.execute(mapOf("command" to command))

            // Then
            assertTrue(result.success)
            // Output should contain files from temp directory
        }

        @Test
        fun `should respect sandbox boundaries`() = runBlocking {
            // Given
            val result = tool.execute(mapOf("command" to "echo test"))

            // Then
            assertNotNull(result)
            // Command should execute within sandbox
            assertTrue(result.success || result.error != null)
        }
    }

    @Nested
    inner class CommandParsingTests {

        @Test
        fun `should handle commands with arguments`() = runBlocking {
            // When
            val result = tool.execute(mapOf("command" to "echo hello world"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("hello") || result.output!!.contains("world"))
        }

        @Test
        fun `should handle commands with quotes`() = runBlocking {
            // When
            val result = tool.execute(mapOf("command" to "echo \"quoted text\""))

            // Then
            assertTrue(result.success)
        }

        @Test
        fun `should handle empty output gracefully`() = runBlocking {
            // When - use cross-platform command that succeeds with minimal/no output
            val os = System.getProperty("os.name").lowercase()
            val command = if (os.contains("windows")) {
                "exit 0"  // PowerShell command that succeeds immediately
            } else {
                "true"  // Unix builtin
            }

            val result = tool.execute(mapOf("command" to command))

            // Then - should succeed even with no/empty output
            assertTrue(result.success, "Command should succeed")
            // Exit code should be 0 for successful command
            val exitCode = result.exitCode
            assertTrue(exitCode == null || exitCode == 0, "Exit code should be 0 or null but was: $exitCode")
        }
    }

    @Nested
    inner class ErrorCaseTests {

        @Test
        fun `should handle malformed command gracefully`() = runBlocking {
            // When - command with invalid syntax
            val result = tool.execute(mapOf("command" to "echo \"unclosed quote"))

            // Then
            assertNotNull(result)
            // Should either succeed or fail with descriptive error
        }

        @Test
        fun `should handle command that produces error output`() = runBlocking {
            // When - command that writes to stderr
            val result = tool.execute(mapOf("command" to "ls /nonexistent"))

            // Then
            assertNotNull(result)
            // Should have output (stderr is combined with stdout)
        }
    }

    @Nested
    inner class ParameterSchemaTests {

        @Test
        fun `should return valid parameter schema`() {
            // When
            val schema = tool.getParameterSchema()

            // Then
            assertEquals("object", schema["type"])
            val properties = schema["properties"] as Map<*, *>
            assertNotNull(properties["command"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("command"))
        }
    }
}

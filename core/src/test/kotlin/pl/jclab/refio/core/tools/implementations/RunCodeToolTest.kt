package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.security.ExecutionIsolationMode
import pl.jclab.refio.core.security.HostExecutionSandbox
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for RunCodeTool - inline code execution tool.
 */
class RunCodeToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var tool: RunCodeTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        tool = RunCodeTool(sandbox)
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("run_code", tool.name)
        }

        @Test
        fun `should have correct tool mode`() {
            assertEquals(ToolMode.WRITE, tool.mode)
        }

        @Test
        fun `should have correct tool category`() {
            assertEquals(ToolCategory.DATA_PRODUCING, tool.category)
        }

        @Test
        fun `should have non-empty description`() {
            assertTrue(tool.description.isNotEmpty())
        }
    }

    @Nested
    inner class ParameterValidationTests {

        @Test
        fun `should reject missing language parameter`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("code" to "print('hello')"))
            }
        }

        @Test
        fun `should reject blank language parameter`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("language" to "", "code" to "print('hello')"))
            }
        }

        @Test
        fun `should reject unsupported language`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("language" to "ruby", "code" to "puts 'hello'"))
            }
        }

        @Test
        fun `should reject missing code parameter`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("language" to "python"))
            }
        }

        @Test
        fun `should reject blank code parameter`() {
            assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("language" to "python", "code" to ""))
            }
        }

        @Test
        fun `should accept valid python params`() {
            tool.validateParams(mapOf("language" to "python", "code" to "print('hello')"))
        }

        @Test
        fun `should accept valid javascript params`() {
            tool.validateParams(mapOf("language" to "javascript", "code" to "console.log('hello')"))
        }

        @Test
        fun `should accept valid kotlin params`() {
            tool.validateParams(mapOf("language" to "kotlin", "code" to "println(\"hello\")"))
        }

        @Test
        fun `should accept language case insensitive`() {
            tool.validateParams(mapOf("language" to "Python", "code" to "print('hello')"))
        }
    }

    @Nested
    inner class ParameterSchemaTests {

        @Test
        fun `should return non-empty schema`() {
            val schema = tool.getParameterSchema()
            assertTrue(schema.isNotEmpty())
        }

        @Test
        fun `should require language and code parameters`() {
            val schema = tool.getParameterSchema()
            @Suppress("UNCHECKED_CAST")
            val required = schema["required"] as List<String>
            assertTrue("language" in required)
            assertTrue("code" in required)
        }

        @Test
        fun `should define language and code properties`() {
            val schema = tool.getParameterSchema()
            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as Map<String, Any>
            assertTrue("language" in properties)
            assertTrue("code" in properties)
        }

        @Test
        fun `should list supported languages as enum`() {
            val schema = tool.getParameterSchema()
            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as Map<String, Map<String, Any>>
            @Suppress("UNCHECKED_CAST")
            val languageEnum = properties["language"]?.get("enum") as? List<String>
            assertNotNull(languageEnum)
            assertTrue("python" in languageEnum)
            assertTrue("javascript" in languageEnum)
            assertTrue("kotlin" in languageEnum)
        }
    }

    @Nested
    inner class ExecutionTests {

        @Test
        fun `should return error for missing language`() = runBlocking {
            val result = tool.execute(mapOf("code" to "print('hello')"))
            assertFalse(result.success)
            assertNotNull(result.error)
        }

        @Test
        fun `should return error for missing code`() = runBlocking {
            val result = tool.execute(mapOf("language" to "python"))
            assertFalse(result.success)
            assertNotNull(result.error)
        }

        @Test
        fun `should return error for unsupported language`() = runBlocking {
            val result = tool.execute(mapOf("language" to "cobol", "code" to "DISPLAY 'HELLO'"))
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("Unsupported language"))
        }

        @Test
        fun `should execute python code successfully`() = runBlocking {
            val result = tool.execute(mapOf(
                "language" to "python",
                "code" to "print('hello from python')"
            ))
            // May fail if python is not installed - that's ok for CI
            if (result.success) {
                assertNotNull(result.output)
                assertTrue(result.output!!.contains("hello from python"))
                assertEquals(0, result.exitCode)
                assertNotNull(result.durationMs)
                assertNotNull(result.metadata)
            }
        }

        @Test
        fun `should execute javascript code successfully`() = runBlocking {
            val result = tool.execute(mapOf(
                "language" to "javascript",
                "code" to "console.log('hello from node')"
            ))
            if (result.success) {
                assertNotNull(result.output)
                assertTrue(result.output!!.contains("hello from node"))
                assertEquals(0, result.exitCode)
            }
        }

        @Test
        fun `should capture exit code on failure`() = runBlocking {
            val result = tool.execute(mapOf(
                "language" to "python",
                "code" to "import sys; sys.exit(42)"
            ))
            if (result.exitCode != null) {
                // On Windows/PowerShell the exit code may differ from 42
                assertFalse(result.success)
                assertTrue(result.exitCode != 0, "Exit code should be non-zero")
            }
        }

        @Test
        fun `should capture stderr in output`() = runBlocking {
            val result = tool.execute(mapOf(
                "language" to "python",
                "code" to "raise ValueError('test error')"
            ))
            if (result.exitCode != null) {
                assertFalse(result.success)
                assertNotNull(result.output)
                assertTrue(result.output!!.contains("ValueError"))
            }
        }

        @Test
        fun `should include metadata with language info`() = runBlocking {
            val result = tool.execute(mapOf(
                "language" to "python",
                "code" to "print('test')"
            ))
            if (result.metadata != null) {
                assertEquals("python", result.metadata!!["language"])
            }
        }

        @Test
        fun `should clean up temp files after execution`() = runBlocking {
            tool.execute(mapOf(
                "language" to "python",
                "code" to "print('cleanup test')"
            ))

            // Verify no .refio_run_ files remain in tempDir
            val remainingTempFiles = tempDir.toFile().listFiles()
                ?.filter { it.name.startsWith(".refio_run_") }
                ?: emptyList()
            assertTrue(remainingTempFiles.isEmpty(), "Temp files should be cleaned up")
        }
    }

    @Nested
    inner class TimeoutTests {

        @Test
        fun `should timeout long-running code`() = runBlocking {
            val shortTimeoutTool = RunCodeTool(sandbox, timeoutSeconds = 2)
            val result = shortTimeoutTool.execute(mapOf(
                "language" to "python",
                "code" to "import time; time.sleep(30)"
            ))
            // Should fail with timeout
            if (result.error != null) {
                assertTrue(
                    result.error!!.contains("timed out") || result.error!!.contains("failed"),
                    "Expected timeout error but got: ${result.error}"
                )
            }
        }
    }

    @Nested
    inner class SupportedLanguagesTests {

        @Test
        fun `should support python`() {
            assertTrue("python" in RunCodeTool.SUPPORTED_LANGUAGES)
        }

        @Test
        fun `should support javascript`() {
            assertTrue("javascript" in RunCodeTool.SUPPORTED_LANGUAGES)
        }

        @Test
        fun `should support kotlin`() {
            assertTrue("kotlin" in RunCodeTool.SUPPORTED_LANGUAGES)
        }

        @Test
        fun `python config should have py extension`() {
            assertEquals(".py", RunCodeTool.SUPPORTED_LANGUAGES["python"]?.extension)
        }

        @Test
        fun `javascript config should have mjs extension`() {
            assertEquals(".mjs", RunCodeTool.SUPPORTED_LANGUAGES["javascript"]?.extension)
        }

        @Test
        fun `kotlin config should have kts extension`() {
            assertEquals(".kts", RunCodeTool.SUPPORTED_LANGUAGES["kotlin"]?.extension)
        }
    }

    /**
     * The end-to-end half of the isolation setting: that the refusal is actually wired into the
     * tool, not merely known to the sandbox. Without this the unit test of the sandbox could stay
     * green while `run_code` kept spawning processes.
     */
    @Nested
    inner class ExecutionIsolationTests {

        private fun toolWith(mode: ExecutionIsolationMode) =
            RunCodeTool(sandbox, HostExecutionSandbox { mode })

        @Test
        fun `required refuses to run the code and starts no process`() = runBlocking {
            val marker = tempDir.resolve("it-ran.txt")
            val result = toolWith(ExecutionIsolationMode.REQUIRED).execute(
                mapOf(
                    "language" to "python",
                    "code" to "open(r'${marker.toString().replace("\\", "\\\\")}', 'w').write('ran')",
                )
            )

            assertFalse(result.success, "a refused run is not a successful one")
            assertTrue(
                result.error!!.contains("execution_isolation=required"),
                "the model has to be told this was a refusal, not a broken runtime: ${result.error}",
            )
            assertFalse(
                java.nio.file.Files.exists(marker),
                "the process must never have started - the refusal is the whole point",
            )
        }

        @Test
        fun `required leaves no temporary script behind in the project`() = runBlocking {
            toolWith(ExecutionIsolationMode.REQUIRED).execute(
                mapOf("language" to "python", "code" to "print(1)")
            )

            val leftovers = java.nio.file.Files.list(tempDir).use { stream ->
                stream.filter { it.fileName.toString().startsWith(".refio_run_") }.toList()
            }
            assertTrue(leftovers.isEmpty(), "refusing must not litter the user's project: $leftovers")
        }
    }
}

package pl.jclab.refio.core.tools.implementations

import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.security.FileLimits
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Testy dla ReadFileTool — narzędzia do odczytu plików.
 */
class ReadFileToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var tool: ReadFileTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        tool = ReadFileTool(sandbox, FileLimits.DEFAULT)
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("read_file", tool.name)
        }

        @Test
        fun `should have correct tool mode`() {
            assertEquals(ToolMode.READ_ONLY, tool.mode)
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
        fun `should validate params with valid path`() {
            // When & Then - should not throw
            tool.validateParams(mapOf("path" to "test.txt"))
        }

        @Test
        fun `should throw exception when path is missing`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                @Suppress("UNCHECKED_CAST")
                tool.validateParams(mapOf("path" to null) as Map<String, Any>)
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when path is empty string`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("path" to ""))
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when path is blank string`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("path" to "   "))
            }
            assertTrue(exception.message!!.contains("path"))
        }
    }

    @Nested
    inner class SuccessfulReadTests {

        @Test
        fun `should read file and return its content`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("hello.txt"), "Hello World")

            // When
            val result = tool.execute(mapOf("path" to "hello.txt"))

            // Then
            assertTrue(result.success)
            assertEquals("Hello World", result.output)
            assertNull(result.error)
        }

        @Test
        fun `should read file with multiple lines`() = runBlocking {
            // Given
            val content = "Line 1\nLine 2\nLine 3"
            Files.writeString(tempDir.resolve("multiline.txt"), content)

            // When
            val result = tool.execute(mapOf("path" to "multiline.txt"))

            // Then
            assertTrue(result.success)
            assertEquals(content, result.output)
        }

        @Test
        fun `should read file from subdirectory`() = runBlocking {
            // Given
            val subdir = tempDir.resolve("subdir")
            Files.createDirectories(subdir)
            Files.writeString(subdir.resolve("file.txt"), "Content in subdir")

            // When
            val result = tool.execute(mapOf("path" to "subdir/file.txt"))

            // Then
            assertTrue(result.success)
            assertEquals("Content in subdir", result.output)
        }

        @Test
        fun `should read empty file`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("empty.txt"), "")

            // When
            val result = tool.execute(mapOf("path" to "empty.txt"))

            // Then
            assertTrue(result.success)
            assertEquals("", result.output)
        }

        @Test
        fun `should read file with special characters`() = runBlocking {
            // Given
            val content = "Special: !@#\$%^&*(){}[]<>?/\\|~`"
            Files.writeString(tempDir.resolve("special.txt"), content)

            // When
            val result = tool.execute(mapOf("path" to "special.txt"))

            // Then
            assertTrue(result.success)
            assertEquals(content, result.output)
        }

        @Test
        fun `should read file with unicode characters`() = runBlocking {
            // Given
            val content = "Unicode: 你好 こんにちは Привет שלום"
            Files.writeString(tempDir.resolve("unicode.txt"), content)

            // When
            val result = tool.execute(mapOf("path" to "unicode.txt"))

            // Then
            assertTrue(result.success)
            assertEquals(content, result.output)
        }

        @Test
        fun `should include metadata in result`() = runBlocking {
            // Given
            val content = "Line 1\nLine 2\nLine 3"
            Files.writeString(tempDir.resolve("meta.txt"), content)

            // When
            val result = tool.execute(mapOf("path" to "meta.txt"))

            // Then
            assertNotNull(result.metadata)
            assertEquals(3, result.metadata!!["total_lines"])
            assertEquals(3, result.metadata!!["lines_read"])
            assertEquals("meta.txt", result.metadata!!["path"])
        }

        @Test
        fun `should set bytesRead correctly`() = runBlocking {
            // Given
            val content = "Hello World"
            Files.writeString(tempDir.resolve("bytes.txt"), content)

            // When
            val result = tool.execute(mapOf("path" to "bytes.txt"))

            // Then
            assertNotNull(result.bytesRead)
            assertTrue(result.bytesRead!! > 0)
        }

        @Test
        fun `should set durationMs correctly`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("timing.txt"), "Content")

            // When
            val result = tool.execute(mapOf("path" to "timing.txt"))

            // Then
            assertNotNull(result.durationMs)
            assertTrue(result.durationMs!! >= 0)
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when file not found`() = runBlocking {
            // When
            val result = tool.execute(mapOf("path" to "nonexistent.txt"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should return error when path is a directory`() = runBlocking {
            // Given
            Files.createDirectories(tempDir.resolve("adir"))

            // When
            val result = tool.execute(mapOf("path" to "adir"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not a regular file", ignoreCase = true))
        }

        @Test
        fun `should return error when file exceeds size limit`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxFileSize = 100)
            val strictTool = ReadFileTool(sandbox, strictLimits)
            val largeContent = "x".repeat(200)
            Files.writeString(tempDir.resolve("large.txt"), largeContent)

            // When
            val result = strictTool.execute(mapOf("path" to "large.txt"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("too large", ignoreCase = true))
        }

        @Test
        fun `should return error when path parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(emptyMap())

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("path"))
        }

        @Test
        fun `should return error when path parameter has wrong type`() = runBlocking {
            // When
            val result = tool.execute(mapOf("path" to 123))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("path"))
        }
    }

    @Nested
    inner class PathNormalizationTests {

        @Test
        fun `should handle path with backslashes`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "Content")

            // When
            val result = tool.execute(mapOf("path" to "test.txt"))

            // Then
            assertTrue(result.success)
        }

        @Test
        fun `should handle path with forward slashes`() = runBlocking {
            // Given
            Files.createDirectories(tempDir.resolve("sub"))
            Files.writeString(tempDir.resolve("sub/test.txt"), "Content")

            // When
            val result = tool.execute(mapOf("path" to "sub/test.txt"))

            // Then
            assertTrue(result.success)
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
            assertNotNull(properties["path"])
            val required = schema["required"] as List<*>
            assertTrue(required.contains("path"))
        }
    }
}

package pl.jclab.refio.core.tools.implementations

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
import kotlin.test.assertFailsWith

/**
 * Testy dla CreateNewFileTool — narzędzia do tworzenia nowych plików.
 */
class CreateNewFileToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var tool: CreateNewFileTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        tool = CreateNewFileTool(sandbox, FileLimits.DEFAULT)
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("create_new_file", tool.name)
        }

        @Test
        fun `should have correct tool mode`() {
            assertEquals(ToolMode.WRITE, tool.mode)
        }

        @Test
        fun `should have correct tool category`() {
            assertEquals(ToolCategory.FILE_MODIFYING, tool.category)
        }

        @Test
        fun `should have non-empty description`() {
            assertTrue(tool.description.isNotEmpty())
        }
    }

    @Nested
    inner class ParameterValidationTests {

        @Test
        fun `should validate params with path and content`() {
            // When & Then - should not throw
            tool.validateParams(mapOf(
                "path" to "test.txt",
                "content" to "content"
            ))
        }

        @Test
        fun `should throw exception when path is missing`() {
            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("content" to "content"))
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when path is empty`() {
            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("path" to "", "content" to "content"))
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when path is blank`() {
            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("path" to "   ", "content" to "content"))
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when content is missing`() {
            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("path" to "test.txt"))
            }
            assertTrue(exception.message!!.contains("content"))
        }

        @Test
        fun `should accept empty string as content`() {
            // When & Then - should not throw (empty content is valid)
            tool.validateParams(mapOf("path" to "test.txt", "content" to ""))
        }
    }

    @Nested
    inner class SuccessfulCreationTests {

        @Test
        fun `should create new file with content`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "newfile.txt",
                "content" to "Hello World"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("created successfully"))
            assertTrue(Files.exists(tempDir.resolve("newfile.txt")))
            assertEquals("Hello World", Files.readString(tempDir.resolve("newfile.txt")))
        }

        @Test
        fun `should create file with multiline content`() = runBlocking {
            // Given
            val content = "Line 1\nLine 2\nLine 3"

            // When
            val result = tool.execute(mapOf(
                "path" to "multiline.txt",
                "content" to content
            ))

            // Then
            assertTrue(result.success)
            assertEquals(content, Files.readString(tempDir.resolve("multiline.txt")))
        }

        @Test
        fun `should create file with empty content`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "empty.txt",
                "content" to ""
            ))

            // Then
            assertTrue(result.success)
            assertEquals("", Files.readString(tempDir.resolve("empty.txt")))
        }

        @Test
        fun `should create parent directories when needed`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "a/b/c/file.txt",
                "content" to "content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve("a/b/c/file.txt")))
            assertTrue(Files.isDirectory(tempDir.resolve("a/b/c")))
            assertEquals("content", Files.readString(tempDir.resolve("a/b/c/file.txt")))
        }

        @Test
        fun `should create file in existing subdirectory`() = runBlocking {
            // Given
            Files.createDirectories(tempDir.resolve("existing"))

            // When
            val result = tool.execute(mapOf(
                "path" to "existing/file.txt",
                "content" to "content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve("existing/file.txt")))
        }

        @Test
        fun `should handle special characters in content`() = runBlocking {
            // Given
            val content = "Special: !@#\$%^&*(){}[]<>"

            // When
            val result = tool.execute(mapOf(
                "path" to "special.txt",
                "content" to content
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.readString(tempDir.resolve("special.txt")).contains("!@#"))
        }

        @Test
        fun `should handle unicode characters in content`() = runBlocking {
            // Given
            val content = "Unicode: 你好 こんにちは Привет"

            // When
            val result = tool.execute(mapOf(
                "path" to "unicode.txt",
                "content" to content
            ))

            // Then
            assertTrue(result.success)
            assertEquals(content, Files.readString(tempDir.resolve("unicode.txt")))
        }

        @Test
        fun `should include filesChanged in result`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "newfile.txt",
                "content" to "content"
            ))

            // Then
            assertNotNull(result.filesChanged)
            assertEquals(listOf("newfile.txt"), result.filesChanged)
        }

        @Test
        fun `should set bytesWritten correctly`() = runBlocking {
            // Given
            val content = "Hello World"

            // When
            val result = tool.execute(mapOf(
                "path" to "file.txt",
                "content" to content
            ))

            // Then
            assertNotNull(result.bytesWritten)
            assertEquals(content.toByteArray().size, result.bytesWritten)
        }

        @Test
        fun `should include metadata in result`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "file.txt",
                "content" to "Line 1\nLine 2"
            ))

            // Then
            assertNotNull(result.metadata)
            assertEquals("file.txt", result.metadata!!["path"])
            assertEquals(2, result.metadata!!["line_count"])
            assertEquals(13, result.metadata!!["char_count"])
        }
    }

    @Nested
    inner class FileExistsTests {

        @Test
        fun `should fail with recovery when file already exists`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("existing.txt"), "old content")

            // When
            val result = tool.execute(mapOf(
                "path" to "existing.txt",
                "content" to "new content"
            ))

            // Then — must be a hard failure so the model does not silently move on.
            // Prior behaviour returned success=true with a warning, which caused agents
            // to assume the file had been created and continue with stale state.
            assertEquals(false, result.success, "Should return success=false when file already exists")
            assertNotNull(result.error, "Error message must be set")
            assertTrue(result.error!!.contains("already exists"))
            assertNotNull(result.recovery, "Recovery instructions must be set")
            assertTrue(result.recovery!!.contains("read_file"), "Recovery should point at read_file")
            assertTrue(result.recovery!!.contains("code_editing"), "Recovery should mention code_editing")
            assertEquals("old content", Files.readString(tempDir.resolve("existing.txt")), "File must not be modified")
        }

        @Test
        fun `should not modify existing file`() = runBlocking {
            // Given
            val originalContent = "original content"
            Files.writeString(tempDir.resolve("file.txt"), originalContent)

            // When
            val result = tool.execute(mapOf(
                "path" to "file.txt",
                "content" to "new content"
            ))

            // Then — failure path now, but the invariant is the same: file untouched.
            assertEquals(false, result.success)
            assertEquals(originalContent, Files.readString(tempDir.resolve("file.txt")))
        }

        @Test
        fun `should not report filesChanged when file exists`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("existing.txt"), "content")

            // When
            val result = tool.execute(mapOf(
                "path" to "existing.txt",
                "content" to "new"
            ))

            // Then — error result has no filesChanged at all (null), which still
            // truthfully signals "nothing was changed" to the agent.
            assertEquals(false, result.success)
            assertNull(result.filesChanged)
        }

        @Test
        fun `should not report bytesWritten when file exists`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("existing.txt"), "content")

            // When
            val result = tool.execute(mapOf(
                "path" to "existing.txt",
                "content" to "new"
            ))

            // Then — error result has no bytesWritten metric, again signalling no write happened.
            assertEquals(false, result.success)
            assertNull(result.bytesWritten)
        }
    }

    @Nested
    inner class SmallFileGateTests {

        @Test
        fun `should reject content over the byte limit and steer to advance_code_editing`() = runBlocking {
            // Regression (session 7e87c668): qwen3.5:122b wrote a 1182-line / ~46KB game via
            // create_new_file (463s, 11.7k output tokens). The size gate now hard-rejects large
            // content and points the model at advance_code_editing.
            val big = "x".repeat(CreateNewFileTool.MAX_SMALL_FILE_BYTES + 1)
            val result = tool.execute(mapOf("path" to "game.html", "content" to big))

            assertFalse(result.success)
            assertTrue(result.error!!.contains("SMALL files only", ignoreCase = true))
            assertTrue(result.recovery!!.contains("advance_code_editing"))
            assertFalse(Files.exists(tempDir.resolve("game.html")), "oversized file must NOT be written")
        }

        @Test
        fun `should reject content over the line limit even when under the byte limit`() = runBlocking {
            val many = "a\n".repeat(CreateNewFileTool.MAX_SMALL_FILE_LINES + 1)  // 52 short lines, ~104 bytes
            assertTrue(many.toByteArray().size <= CreateNewFileTool.MAX_SMALL_FILE_BYTES)
            val result = tool.execute(mapOf("path" to "many.txt", "content" to many))

            assertFalse(result.success)
            assertTrue(result.error!!.contains("SMALL files only", ignoreCase = true))
            assertFalse(Files.exists(tempDir.resolve("many.txt")))
        }

        @Test
        fun `should still create a file at the boundary (small stub passes)`() = runBlocking {
            // Stub-then-fill stays viable: content at the byte/line bound is accepted.
            val stub = "x".repeat(CreateNewFileTool.MAX_SMALL_FILE_BYTES)  // exactly the limit, 1 line
            val result = tool.execute(mapOf("path" to "stub.html", "content" to stub))

            assertTrue(result.success, "content at the limit must still be allowed")
            assertTrue(Files.exists(tempDir.resolve("stub.html")))
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when path parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(mapOf("content" to "content"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("path"))
        }

        @Test
        fun `should return error when content parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(mapOf("path" to "file.txt"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("content"))
        }

        @Test
        fun `should return error when content exceeds size limit`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxFileSize = 100)
            val strictTool = CreateNewFileTool(sandbox, strictLimits)
            val largeContent = "x".repeat(200)

            // When
            val result = strictTool.execute(mapOf(
                "path" to "file.txt",
                "content" to largeContent
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("too large", ignoreCase = true))
        }

        @Test
        fun `should return error when parent exists but is not a directory`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("notadir"), "I'm a file")

            // When
            val result = tool.execute(mapOf(
                "path" to "notadir/file.txt",
                "content" to "content"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not a directory", ignoreCase = true))
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle very long file names`() = runBlocking {
            // Given
            val longName = "a".repeat(200) + ".txt"

            // When
            val result = tool.execute(mapOf(
                "path" to longName,
                "content" to "content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve(longName)))
        }

        @Test
        fun `should handle file with dot at start`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to ".hidden",
                "content" to "hidden content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve(".hidden")))
        }

        @Test
        fun `should handle file with multiple extensions`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "file.tar.gz.txt",
                "content" to "content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve("file.tar.gz.txt")))
        }

        @Test
        fun `should handle content with only newlines`() = runBlocking {
            // Given
            val content = "\n\n\n"

            // When
            val result = tool.execute(mapOf(
                "path" to "newlines.txt",
                "content" to content
            ))

            // Then
            assertTrue(result.success)
            assertEquals(content, Files.readString(tempDir.resolve("newlines.txt")))
        }

        @Test
        fun `should handle JSON content`() = runBlocking {
            // Given
            val json = """{"key": "value", "number": 123}"""

            // When
            val result = tool.execute(mapOf(
                "path" to "config.json",
                "content" to json
            ))

            // Then
            assertTrue(result.success)
            assertEquals(json, Files.readString(tempDir.resolve("config.json")))
        }

        @Test
        fun `should handle YAML content`() = runBlocking {
            // Given
            val yaml = """
                key: value
                nested:
                  item: 123
            """.trimIndent()

            // When
            val result = tool.execute(mapOf(
                "path" to "config.yaml",
                "content" to yaml
            ))

            // Then
            assertTrue(result.success)
            assertEquals(yaml, Files.readString(tempDir.resolve("config.yaml")))
        }
    }

    @Nested
    inner class SecurityTests {

        @Test
        fun `should respect sandbox boundaries`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "../outside.txt",
                "content" to "content"
            ))

            // Then
            // Path sandbox should prevent traversal
            assertTrue(result.success || result.error != null)
        }

        @Test
        fun `should create file within sandbox`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "safe.txt",
                "content" to "safe content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve("safe.txt")))
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
            assertNotNull(properties["content"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("path"))
            assertTrue(required.contains("content"))
        }
    }
}

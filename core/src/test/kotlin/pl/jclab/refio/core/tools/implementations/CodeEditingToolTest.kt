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
 * Testy dla CodeEditingTool — narzędzia do edycji plików metodą search-and-replace.
 */
class CodeEditingToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var tool: CodeEditingTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        tool = CodeEditingTool(sandbox, FileLimits.DEFAULT)
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("code_editing", tool.name)
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
        fun `should validate params with all required parameters`() {
            // When & Then - should not throw
            tool.validateParams(mapOf(
                "path" to "test.txt",
                "old_string" to "old",
                "new_string" to "new"
            ))
        }

        @Test
        fun `should throw exception when path is missing`() {
            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf(
                    "old_string" to "old",
                    "new_string" to "new"
                ))
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when path is empty`() {
            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf(
                    "path" to "",
                    "old_string" to "old",
                    "new_string" to "new"
                ))
            }
            assertTrue(exception.message!!.contains("path"))
        }

        @Test
        fun `should throw exception when old_string is missing`() {
            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf(
                    "path" to "test.txt",
                    "new_string" to "new"
                ))
            }
            assertTrue(exception.message!!.contains("old_string"))
        }

        @Test
        fun `should throw exception when new_string is missing`() {
            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf(
                    "path" to "test.txt",
                    "old_string" to "old"
                ))
            }
            assertTrue(exception.message!!.contains("new_string"))
        }
    }

    @Nested
    inner class SuccessfulEditTests {

        @Test
        fun `should replace single occurrence of string`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "Hello World\nHello Universe")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "Hello World",
                "new_string" to "Hi World"
            ))

            // Then
            assertTrue(result.success)
            assertNotNull(result.output)
            assertTrue(result.output!!.contains("Hi World"))

            val content = Files.readString(tempDir.resolve("test.txt"))
            assertEquals("Hi World\nHello Universe", content)
        }

        @Test
        fun `should replace all occurrences when replace_all is true`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "foo bar foo baz foo")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "foo",
                "new_string" to "qux",
                "replace_all" to true
            ))

            // Then
            assertTrue(result.success)

            val content = Files.readString(tempDir.resolve("test.txt"))
            assertEquals("qux bar qux baz qux", content)
        }

        @Test
        fun `should return error when string appears multiple times without replace_all`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "foo bar foo baz foo")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "foo",
                "new_string" to "qux",
                "replace_all" to false
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("appears", ignoreCase = true))
            assertTrue(result.error!!.contains("times", ignoreCase = true))
        }

        @Test
        fun `should replace only first occurrence when replace_all is false and string is unique`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "foo bar baz qux")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "foo",
                "new_string" to "quux",
                "replace_all" to false
            ))

            // Then
            assertTrue(result.success)

            val content = Files.readString(tempDir.resolve("test.txt"))
            assertEquals("quux bar baz qux", content)
        }

        @Test
        fun `should create new file when old_string is empty`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "newfile.txt",
                "old_string" to "",
                "new_string" to "New content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("created successfully"))
            assertTrue(Files.exists(tempDir.resolve("newfile.txt")))

            val content = Files.readString(tempDir.resolve("newfile.txt"))
            assertEquals("New content", content)
        }

        @Test
        fun `should create parent directories when creating new file`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "subdir/nested/file.txt",
                "old_string" to "",
                "new_string" to "Content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.exists(tempDir.resolve("subdir/nested/file.txt")))
            assertTrue(Files.isDirectory(tempDir.resolve("subdir/nested")))
        }

        @Test
        fun `should include diff in output`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "old content")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "old",
                "new_string" to "new"
            ))

            // Then
            assertTrue(result.success)
            assertNotNull(result.output)
            assertTrue(result.output!!.contains("```diff"))
            // The diff shows only the replaced parts, not full lines
            assertTrue(result.output!!.contains("- old"))
            assertTrue(result.output!!.contains("+ new"))
        }

        @Test
        fun `should include metadata in result`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "old content")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "old",
                "new_string" to "new"
            ))

            // Then
            assertNotNull(result.metadata)
            assertEquals("test.txt", result.metadata!!["path"])
            // mode is only set for new file creation, not for edits
            assertEquals(1, result.metadata!!["replacements"])
        }

        @Test
        fun `should set bytesWritten correctly`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "old content")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "old",
                "new_string" to "new longer content"
            ))

            // Then
            assertNotNull(result.bytesWritten)
            assertTrue(result.bytesWritten!! > 0)
        }

        @Test
        fun `should include filesChanged in result`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "old content")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "old",
                "new_string" to "new"
            ))

            // Then
            assertNotNull(result.filesChanged)
            assertEquals(listOf("test.txt"), result.filesChanged)
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when file not found with non-empty old_string`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "nonexistent.txt",
                "old_string" to "old",
                "new_string" to "new"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should return error when string not found in file`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "Hello World")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "Goodbye",
                "new_string" to "Hello"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found in file", ignoreCase = true))
        }

        @Test
        fun `should return error when old_string appears multiple times without replace_all`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "foo foo foo")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "foo",
                "new_string" to "bar"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("appears", ignoreCase = true))
            assertTrue(result.error!!.contains("times", ignoreCase = true))
        }

        @Test
        fun `should return error when path is a directory`() = runBlocking {
            // Given
            Files.createDirectories(tempDir.resolve("adir"))

            // When
            val result = tool.execute(mapOf(
                "path" to "adir",
                "old_string" to "",
                "new_string" to "content"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not a regular file", ignoreCase = true))
        }

        @Test
        fun `should return error when file exceeds size limit`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxFileSize = 100)
            val strictTool = CodeEditingTool(sandbox, strictLimits)
            val largeContent = "x".repeat(200)
            Files.writeString(tempDir.resolve("large.txt"), largeContent)

            // When
            val result = strictTool.execute(mapOf(
                "path" to "large.txt",
                "old_string" to "x",
                "new_string" to "y"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("too large", ignoreCase = true))
        }

        @Test
        fun `should return error when path parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "old_string" to "old",
                "new_string" to "new"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("path"))
        }

        @Test
        fun `should return error when old_string parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "new_string" to "new"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("old_string"))
        }

        @Test
        fun `should return error when new_string parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "old"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("new_string"))
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle empty file`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("empty.txt"), "")

            // When
            val result = tool.execute(mapOf(
                "path" to "empty.txt",
                "old_string" to "",
                "new_string" to "new content"
            ))

            // Then
            assertTrue(result.success)
            assertEquals("new content", Files.readString(tempDir.resolve("empty.txt")))
        }

        @Test
        fun `should handle multiline replacement`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "line 1\nline 2\nline 3")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "line 1\nline 2",
                "new_string" to "new line 1\nnew line 2"
            ))

            // Then
            assertTrue(result.success)
            assertEquals("new line 1\nnew line 2\nline 3", Files.readString(tempDir.resolve("test.txt")))
        }

        @Test
        fun `should handle special characters in replacement`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "old content")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "old",
                "new_string" to "new: !@#\$%^&*()"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(Files.readString(tempDir.resolve("test.txt")).contains("new: !@#"))
        }

        @Test
        fun `should handle unicode characters in replacement`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "old 你好")

            // When
            val result = tool.execute(mapOf(
                "path" to "test.txt",
                "old_string" to "old",
                "new_string" to "new こんにちは"
            ))

            // Then
            assertTrue(result.success)
            assertEquals("new こんにちは 你好", Files.readString(tempDir.resolve("test.txt")))
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
            assertNotNull(properties["old_string"])
            assertNotNull(properties["new_string"])
            assertNotNull(properties["replace_all"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("path"))
            assertTrue(required.contains("old_string"))
            assertTrue(required.contains("new_string"))
        }
    }

    /**
     * On a Windows checkout (core.autocrlf) tracked files materialize as CRLF, but read_file always
     * hands the model LF, so the model's old_string carries LF. The edit must still land - and the
     * file must keep its CRLF - otherwise edits silently fail for any model on any CRLF file.
     */
    @Nested
    inner class CrlfLineEndingTests {

        @Test
        fun `multi-line edit on a CRLF file matches an LF old_string and keeps CRLF`() = runBlocking {
            // File on disk is CRLF (as a Windows working-tree checkout would be).
            Files.writeString(
                tempDir.resolve("Main.kt"),
                "fun describe(x: String?): String {\r\n    return \"length=\" + x.length\r\n}\r\n"
            )

            // The model emits old_string/new_string with LF (read_file fed it LF), spanning a line break.
            val result = tool.execute(mapOf(
                "path" to "Main.kt",
                "old_string" to "    return \"length=\" + x.length\n",
                "new_string" to "    return \"length=\" + (x?.length ?: 0)\n"
            ))

            assertTrue(result.success, "edit should succeed despite the LF/CRLF mismatch")
            val content = Files.readString(tempDir.resolve("Main.kt"))
            assertTrue(content.contains("x?.length ?: 0"), "replacement should be applied")
            assertTrue(content.contains("\r\n"), "file must keep CRLF line endings")
            assertFalse(content.contains("\n\n"), "no stray LF-only lines introduced")
            // Exactly the edited region changed; surrounding CRLF lines stay intact.
            assertEquals(
                "fun describe(x: String?): String {\r\n    return \"length=\" + (x?.length ?: 0)\r\n}\r\n",
                content
            )
        }

        @Test
        fun `edit on an LF file with an LF old_string keeps LF`() = runBlocking {
            Files.writeString(tempDir.resolve("Main.kt"), "line1\nline2\nline3\n")

            val result = tool.execute(mapOf(
                "path" to "Main.kt",
                "old_string" to "line2\n",
                "new_string" to "edited\n"
            ))

            assertTrue(result.success)
            val content = Files.readString(tempDir.resolve("Main.kt"))
            assertEquals("line1\nedited\nline3\n", content)
            assertFalse(content.contains("\r\n"), "must not introduce CRLF on an LF file")
        }
    }
}

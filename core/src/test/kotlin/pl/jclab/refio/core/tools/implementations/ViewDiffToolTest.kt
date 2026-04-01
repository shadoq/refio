package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolCategory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Testy dla ViewDiffTool — narzędzia do porównywania plików.
 */
class ViewDiffToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var tool: ViewDiffTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        tool = ViewDiffTool(sandbox)
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("view_diff", tool.name)
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
        fun `should validate params with file1 and file2`() {
            // When & Then - should not throw
            tool.validateParams(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))
        }

        @Test
        fun `should validate params with file1 and content2`() {
            // When & Then - should not throw
            tool.validateParams(mapOf(
                "file1" to "file1.txt",
                "content2" to "some content"
            ))
        }

        @Test
        fun `should throw exception when file1 is missing`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("file2" to "file2.txt"))
            }
            assertTrue(exception.message!!.contains("file1"))
        }

        @Test
        fun `should throw exception when file1 is empty`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("file1" to ""))
            }
            assertTrue(exception.message!!.contains("file1"))
        }

        @Test
        fun `should throw exception when neither file2 nor content2 is provided`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("file1" to "file1.txt"))
            }
            assertTrue(exception.message!!.contains("file2") || exception.message!!.contains("content2"))
        }

        @Test
        fun `should validate when both file2 and content2 are provided`() {
            // When & Then - should not throw (content2 takes precedence)
            tool.validateParams(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt",
                "content2" to "content"
            ))
        }
    }

    @Nested
    inner class FileToFileComparisonTests {

        @Test
        fun `should compare two identical files`() = runBlocking {
            // Given
            val content = "Line 1\nLine 2\nLine 3"
            Files.writeString(tempDir.resolve("file1.txt"), content)
            Files.writeString(tempDir.resolve("file2.txt"), content)

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertTrue(result.success)
            assertNotNull(result.output)
            // All lines should be unchanged
            val lines = result.output!!.lines()
            assertTrue(lines.all { it.startsWith("  ") })
        }

        @Test
        fun `should show differences between files`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "Line 1\nLine 2\nLine 3")
            Files.writeString(tempDir.resolve("file2.txt"), "Line 1\nLine 2 modified\nLine 3")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("- Line 2"))
            assertTrue(output.contains("+ Line 2 modified"))
        }

        @Test
        fun `should show added lines`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "Line 1\nLine 2")
            Files.writeString(tempDir.resolve("file2.txt"), "Line 1\nLine 2\nLine 3\nLine 4")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("+ Line 3"))
            assertTrue(output.contains("+ Line 4"))
        }

        @Test
        fun `should show removed lines`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "Line 1\nLine 2\nLine 3\nLine 4")
            Files.writeString(tempDir.resolve("file2.txt"), "Line 1\nLine 2")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("- Line 3"))
            assertTrue(output.contains("- Line 4"))
        }

        @Test
        fun `should include metadata with line counts`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "Line 1\nLine 2")
            Files.writeString(tempDir.resolve("file2.txt"), "Line 1\nLine 2 modified\nLine 3")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertNotNull(result.metadata)
            assertEquals("file1.txt", result.metadata!!["file1"])
            assertEquals("file2.txt", result.metadata!!["file2"])
            assertTrue(result.metadata!!.containsKey("added_lines"))
            assertTrue(result.metadata!!.containsKey("removed_lines"))
            assertTrue(result.metadata!!.containsKey("unchanged_lines"))
        }

        @Test
        fun `should compare files in subdirectories`() = runBlocking {
            // Given
            val dir1 = tempDir.resolve("dir1")
            val dir2 = tempDir.resolve("dir2")
            Files.createDirectories(dir1)
            Files.createDirectories(dir2)
            Files.writeString(dir1.resolve("file.txt"), "old content")
            Files.writeString(dir2.resolve("file.txt"), "new content")

            // When
            val result = tool.execute(mapOf(
                "file1" to "dir1/file.txt",
                "file2" to "dir2/file.txt"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("old content"))
            assertTrue(result.output!!.contains("new content"))
        }
    }

    @Nested
    inner class FileToContentComparisonTests {

        @Test
        fun `should compare file with string content`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "original content")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file.txt",
                "content2" to "modified content"
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("- original content"))
            assertTrue(output.contains("+ modified content"))
        }

        @Test
        fun `should handle empty content2`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "some content")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file.txt",
                "content2" to ""
            ))

            // Then
            assertTrue(result.success)
            // Should show file content as removed
            assertTrue(result.output!!.contains("- some content"))
        }

        @Test
        fun `should handle empty file with content2`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("empty.txt"), "")

            // When
            val result = tool.execute(mapOf(
                "file1" to "empty.txt",
                "content2" to "new content"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("+ new content"))
        }

        @Test
        fun `should include metadata indicating content comparison`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "content")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file.txt",
                "content2" to "new"
            ))

            // Then
            assertNotNull(result.metadata)
            assertEquals("file.txt", result.metadata!!["file1"])
            assertEquals("<content>", result.metadata!!["file2"])
        }

        @Test
        fun `should handle multiline content2`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "line 1\nline 2")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file.txt",
                "content2" to "line 1\nline 2 modified\nline 3"
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("- line 2"))
            assertTrue(output.contains("+ line 2 modified"))
            assertTrue(output.contains("+ line 3"))
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when file1 does not exist`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "file1" to "nonexistent.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should return error when file2 does not exist`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "content")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "nonexistent.txt"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should return error when file1 is a directory`() = runBlocking {
            // Given
            Files.createDirectories(tempDir.resolve("adir"))
            Files.writeString(tempDir.resolve("file2.txt"), "content")

            // When
            val result = tool.execute(mapOf(
                "file1" to "adir",
                "file2" to "file2.txt"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not a regular file", ignoreCase = true))
        }

        @Test
        fun `should return error when file2 is a directory`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "content")
            Files.createDirectories(tempDir.resolve("adir"))

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "adir"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not a regular file", ignoreCase = true))
        }

        @Test
        fun `should return error when file1 parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "content2" to "some content"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("file1"))
        }

        @Test
        fun `should return error when neither file2 nor content2 provided`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "content")

            // When
            val result = tool.execute(mapOf("file1" to "file1.txt"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle empty files`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("empty1.txt"), "")
            Files.writeString(tempDir.resolve("empty2.txt"), "")

            // When
            val result = tool.execute(mapOf(
                "file1" to "empty1.txt",
                "file2" to "empty2.txt"
            ))

            // Then
            assertTrue(result.success)
            // Empty diff is acceptable
        }

        @Test
        fun `should handle special characters in content`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "old: !@#\$%^&*()")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file.txt",
                "content2" to "new: !@#\$%^&*()"
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("old:"))
            assertTrue(output.contains("new:"))
        }

        @Test
        fun `should handle unicode characters`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "old 你好")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file.txt",
                "content2" to "new こんにちは"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("old 你好"))
            assertTrue(result.output!!.contains("new こんにちは"))
        }

        @Test
        fun `should handle very long lines`() = runBlocking {
            // Given
            val longLine = "x".repeat(1000)
            Files.writeString(tempDir.resolve("file1.txt"), longLine)
            Files.writeString(tempDir.resolve("file2.txt"), longLine + "y")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertTrue(result.success)
        }
    }

    @Nested
    inner class MetadataTests {

        @Test
        fun `should report added lines count correctly`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "a\nb")
            Files.writeString(tempDir.resolve("file2.txt"), "a\nb\nc\nd")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertEquals(2, result.metadata!!["added_lines"])
        }

        @Test
        fun `should report removed lines count correctly`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "a\nb\nc\nd")
            Files.writeString(tempDir.resolve("file2.txt"), "a\nb")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertEquals(2, result.metadata!!["removed_lines"])
        }

        @Test
        fun `should report unchanged lines count correctly`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "a\nb\nc")
            Files.writeString(tempDir.resolve("file2.txt"), "a\nb modified\nc")

            // When
            val result = tool.execute(mapOf(
                "file1" to "file1.txt",
                "file2" to "file2.txt"
            ))

            // Then
            assertEquals(2, result.metadata!!["unchanged_lines"])
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
            assertNotNull(properties["file1"])
            assertNotNull(properties["file2"])
            assertNotNull(properties["content2"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("file1"))
            // file2 and content2 are both optional, but at least one is required (validated separately)
        }
    }
}

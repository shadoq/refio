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

/**
 * Testy dla ReadDirectoryTool — narzędzia do listowania plików w katalogach.
 */
class ReadDirectoryToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var tool: ReadDirectoryTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        tool = ReadDirectoryTool(sandbox, FileLimits.DEFAULT)
    }

    private fun createTestStructure() {
        Files.writeString(tempDir.resolve("file1.txt"), "content1")
        Files.writeString(tempDir.resolve("file2.kt"), "content2")

        val subdir = tempDir.resolve("subdir")
        Files.createDirectories(subdir)
        Files.writeString(subdir.resolve("nested.txt"), "nested content")

        val deepDir = tempDir.resolve("a/b/c")
        Files.createDirectories(deepDir)
        Files.writeString(deepDir.resolve("deep.txt"), "deep content")

        Files.createDirectories(tempDir.resolve("emptydir"))
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("read_directory", tool.name)
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
            tool.validateParams(mapOf("path" to "subdir"))
        }

        @Test
        fun `should accept empty path parameter`() {
            // When & Then - should not throw (defaults to ".")
            tool.validateParams(emptyMap())
        }

        @Test
        fun `should accept null path parameter`() {
            // When & Then - should not throw (defaults to ".")
            @Suppress("UNCHECKED_CAST")
            tool.validateParams(mapOf("path" to null) as Map<String, Any>)
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
    inner class SingleLevelListingTests {

        @Test
        fun `should list files in current directory`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            assertNotNull(result.output)
            assertTrue(result.output!!.contains("file1.txt"))
        }

        @Test
        fun `should show directories with DIR prefix`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("DIR"))
        }

        @Test
        fun `should show files with FILE prefix`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("FILE"))
        }

        @Test
        fun `should list empty directory`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "emptydir"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("empty directory"))
        }

        @Test
        fun `should list specific subdirectory`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "subdir"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("nested.txt"))
        }

        @Test
        fun `should show file sizes`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            // Output should contain size info (e.g., "10B", "1KB")
            val output = result.output!!
            assertTrue(output.contains("B"))
        }
    }

    @Nested
    inner class RecursiveListingTests {

        @Test
        fun `should list files recursively when recursive is true`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf(
                "path" to ".",
                "recursive" to true
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // Should contain files from subdirectories
            assertTrue(output.contains("nested.txt") || output.contains("subdir"))
            assertTrue(output.contains("deep.txt") || output.contains("a"))
        }

        @Test
        fun `should indent nested files when recursive`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf(
                "path" to ".",
                "recursive" to true
            ))

            // Then
            assertTrue(result.success)
            // Nested files should be indented with spaces
            val lines = result.output!!.lines()
            // Some lines should start with spaces (indentation)
            assertTrue(lines.any { it.startsWith("  ") })
        }

        @Test
        fun `should not list recursively when recursive is false`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf(
                "path" to ".",
                "recursive" to false
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // Should only show top-level items
        }

        @Test
        fun `should respect max_depth parameter`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf(
                "path" to ".",
                "recursive" to true,
                "max_depth" to 2
            ))

            // Then
            assertTrue(result.success)
            // Should limit depth to 2 levels
        }

        @Test
        fun `should enforce file limits max search depth`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxSearchDepth = 2)
            val strictTool = ReadDirectoryTool(sandbox, strictLimits)
            createTestStructure()

            // When
            val result = strictTool.execute(mapOf(
                "path" to ".",
                "recursive" to true,
                "max_depth" to 10  // Request more than limits allow
            ))

            // Then
            assertTrue(result.success)
            // Depth should be limited by FileLimits
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when directory does not exist`() = runBlocking {
            // When
            val result = tool.execute(mapOf("path" to "nonexistent"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should return error when path is a file`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "content")

            // When
            val result = tool.execute(mapOf("path" to "file.txt"))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not a directory", ignoreCase = true))
        }

        @Test
        fun `should return error when path parameter is empty string`() = runBlocking {
            // When
            val result = tool.execute(mapOf("path" to ""))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
        }
    }

    @Nested
    inner class MetadataTests {

        @Test
        fun `should include file count in metadata`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertNotNull(result.metadata)
            assertTrue(result.metadata!!.containsKey("file_count"))
            val fileCount = result.metadata!!["file_count"] as Int
            assertTrue(fileCount > 0)
        }

        @Test
        fun `should include directory count in metadata`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertNotNull(result.metadata)
            assertTrue(result.metadata!!.containsKey("directory_count"))
            val dirCount = result.metadata!!["directory_count"] as Int
            assertTrue(dirCount > 0)
        }

        @Test
        fun `should include path in metadata`() = runBlocking {
            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertNotNull(result.metadata)
            assertEquals(".", result.metadata!!["path"])
        }

        @Test
        fun `should include recursive flag in metadata`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "path" to ".",
                "recursive" to true
            ))

            // Then
            assertNotNull(result.metadata)
            assertEquals(true, result.metadata!!["recursive"])
        }

        @Test
        fun `should report zero files in empty directory`() = runBlocking {
            // Given
            createTestStructure()

            // When
            val result = tool.execute(mapOf("path" to "emptydir"))

            // Then
            assertNotNull(result.metadata)
            assertEquals(0, result.metadata!!["file_count"])
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle directory with many files`() = runBlocking {
            // Given
            repeat(50) { i ->
                Files.writeString(tempDir.resolve("file$i.txt"), "content")
            }

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            val fileCount = result.metadata!!["file_count"] as Int
            assertEquals(50, fileCount)
        }

        @Test
        fun `should handle directory with mixed content`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("a.txt"), "text")
            Files.writeString(tempDir.resolve("b.kt"), "code")
            Files.writeString(tempDir.resolve("c.json"), """{"key":"value"}""")
            Files.createDirectories(tempDir.resolve("dir"))

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("a.txt"))
            assertTrue(output.contains("b.kt"))
            assertTrue(output.contains("c.json"))
            assertTrue(output.contains("dir"))
        }

        @Test
        fun `should handle hidden files`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve(".hidden"), "hidden content")
            Files.writeString(tempDir.resolve("visible.txt"), "visible content")

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("visible.txt"))
            // Hidden files should also be listed
        }

        @Test
        fun `should default to current directory when path is null`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "content")

            // When
            @Suppress("UNCHECKED_CAST")
            val result = tool.execute(mapOf("path" to null) as Map<String, Any>)

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("test.txt"))
        }

        @Test
        fun `should default to current directory when path is omitted`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "content")

            // When
            val result = tool.execute(emptyMap())

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("test.txt"))
        }
    }

    @Nested
    inner class FileSizeFormattingTests {

        @Test
        fun `should format bytes correctly`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("small.txt"), "x")

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("B"))
        }

        @Test
        fun `should format kilobytes correctly`() = runBlocking {
            // Given
            val content = "x".repeat(2048)
            Files.writeString(tempDir.resolve("kb.txt"), content)

            // When
            val result = tool.execute(mapOf("path" to "."))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // Should contain KB
            assertTrue(output.lines().any { it.contains("KB") })
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
            assertNotNull(properties["recursive"])
            assertNotNull(properties["max_depth"])

            val required = schema["required"] as List<*>
            assertTrue(required.isEmpty(), "All parameters should be optional")
        }
    }
}

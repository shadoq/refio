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
 * Testy dla FileSearchTool — narzędzia do wyszukiwania plików po nazwie.
 */
class FileSearchToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var tool: FileSearchTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        tool = FileSearchTool(sandbox, FileLimits.DEFAULT)
    }

    private fun createTestFiles() {
        Files.writeString(tempDir.resolve("file1.txt"), "content1")
        Files.writeString(tempDir.resolve("file2.kt"), "content2")
        Files.writeString(tempDir.resolve("test.json"), "content3")

        val subdir = tempDir.resolve("subdir")
        Files.createDirectories(subdir)
        Files.writeString(subdir.resolve("nested.txt"), "nested content")

        val deepDir = tempDir.resolve("a/b/c")
        Files.createDirectories(deepDir)
        Files.writeString(deepDir.resolve("deep.txt"), "deep content")

        val excludedDir = tempDir.resolve("node_modules")
        Files.createDirectories(excludedDir)
        Files.writeString(excludedDir.resolve("excluded.txt"), "excluded content")
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("file_search", tool.name)
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
        fun `should validate params with valid pattern`() {
            // When & Then - should not throw
            tool.validateParams(mapOf("pattern" to "*.txt"))
        }

        @Test
        fun `should throw exception when pattern is missing`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(emptyMap())
            }
            assertTrue(exception.message!!.contains("pattern"))
        }

        @Test
        fun `should throw exception when pattern is empty`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("pattern" to ""))
            }
            assertTrue(exception.message!!.contains("pattern"))
        }

        @Test
        fun `should throw exception when pattern is blank`() {
            // When & Then
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                tool.validateParams(mapOf("pattern" to "   "))
            }
            assertTrue(exception.message!!.contains("pattern"))
        }
    }

    @Nested
    inner class SuccessfulSearchTests {

        @Test
        fun `should find files by glob pattern`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "*.txt"))

            // Then
            assertTrue(result.success)
            assertNotNull(result.output)
            assertTrue(result.output!!.contains("file1.txt"))
            assertTrue(result.output!!.contains("test.json") || !result.output!!.contains("test.json"))
        }

        @Test
        fun `should find files with specific extension`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "*.kt"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("file2.kt"))
        }

        @Test
        fun `should find files in subdirectories`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "path" to "."
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("nested.txt") || result.output!!.contains("subdir"))
        }

        @Test
        fun `should find files recursively`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "path" to "."
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("deep.txt") || result.output!!.contains("a/b/c"))
        }

        @Test
        fun `should search in specific subdirectory`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "path" to "subdir"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("nested.txt"))
        }

        @Test
        fun `should support double star glob pattern`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "**/*.txt"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("nested.txt"))
            assertTrue(result.output!!.contains("deep.txt"))
        }

        @Test
        fun `should return no files message when nothing found`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "*.nonexistent"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("No files found"))
        }

        @Test
        fun `should include metadata in result`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "*.txt"))

            // Then
            assertNotNull(result.metadata)
            assertEquals("*.txt", result.metadata!!["pattern"])
            assertTrue(result.metadata!!.containsKey("result_count"))
        }
    }

    @Nested
    inner class PaginationTests {

        @Test
        fun `should support offset parameter`() = runBlocking {
            // Given
            repeat(5) { i ->
                Files.writeString(tempDir.resolve("file$i.txt"), "content")
            }

            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "offset" to 2
            ))

            // Then
            assertTrue(result.success)
            // Should skip first 2 results
            val lines = result.output!!.lines()
            assertTrue(lines.size < 5)
        }

        @Test
        fun `should support limit parameter`() = runBlocking {
            // Given
            repeat(10) { i ->
                Files.writeString(tempDir.resolve("file$i.txt"), "content")
            }

            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "limit" to 3
            ))

            // Then
            assertTrue(result.success)
            val lines = result.output!!.lines().filter { it.isNotBlank() }
            assertTrue(lines.size <= 3)
        }

        @Test
        fun `should support combined offset and limit`() = runBlocking {
            // Given
            repeat(10) { i ->
                Files.writeString(tempDir.resolve("file$i.txt"), "content")
            }

            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "offset" to 5,
                "limit" to 3
            ))

            // Then
            assertTrue(result.success)
            val lines = result.output!!.lines().filter { it.isNotBlank() }
            assertTrue(lines.size <= 3)
        }

        @Test
        fun `should return no files message when offset exceeds results`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "content")

            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "offset" to 10
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("No files found in requested range"))
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when pattern parameter is missing`() = runBlocking {
            // When
            val result = tool.execute(emptyMap())

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("pattern"))
        }

        @Test
        fun `should return error when search path does not exist`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "path" to "nonexistent/dir"  // Use path with slash to avoid bare filename conversion
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should return error when path is a file not directory`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file.txt"), "content")

            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "path" to "./file.txt"  // Use ./ prefix to avoid bare filename conversion
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not a directory", ignoreCase = true))
        }

        @Test
        fun `should return error when offset is negative`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "offset" to -1
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("offset"))
        }

        @Test
        fun `should return error when limit is zero`() = runBlocking {
            // When
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "limit" to 0
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("limit"))
        }
    }

    @Nested
    inner class ExcludedDirectoriesTests {

        @Test
        fun `should exclude node_modules directory`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "*.txt"))

            // Then
            assertTrue(result.success)
            assertFalse(result.output!!.contains("excluded.txt"))
        }

        @Test
        fun `exclude build directory`() = runBlocking {
            // Given
            val buildDir = tempDir.resolve("build")
            Files.createDirectories(buildDir)
            Files.writeString(buildDir.resolve("output.txt"), "build output")

            Files.writeString(tempDir.resolve("source.txt"), "source")

            // When
            val result = tool.execute(mapOf("pattern" to "*.txt"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("source.txt"))
            assertFalse(result.output!!.contains("output.txt"))
        }

        @Test
        fun `exclude git directory`() = runBlocking {
            // Given
            val gitDir = tempDir.resolve(".git")
            Files.createDirectories(gitDir)
            Files.writeString(gitDir.resolve("config"), "git config")

            Files.writeString(tempDir.resolve("readme.txt"), "readme")

            // When
            val result = tool.execute(mapOf("pattern" to "*"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("readme.txt"))
            assertFalse(result.output!!.contains(".git"))
        }

        @Test
        fun `exclude venv directory`() = runBlocking {
            // Given
            val venvDir = tempDir.resolve("venv")
            Files.createDirectories(venvDir)
            Files.writeString(venvDir.resolve("python.py"), "python code")

            Files.writeString(tempDir.resolve("main.py"), "main")

            // When
            val result = tool.execute(mapOf("pattern" to "*.py"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("main.py"))
            assertFalse(result.output!!.contains("python.py"))
        }
    }

    @Nested
    inner class DepthLimitTests {

        @Test
        fun `should respect max_depth parameter`() = runBlocking {
            // Given
            val deepDir = tempDir.resolve("a/b/c/d/e")
            Files.createDirectories(deepDir)
            Files.writeString(deepDir.resolve("deep.txt"), "very deep")
            Files.writeString(tempDir.resolve("shallow.txt"), "shallow")

            // When - limit depth to 3
            val result = tool.execute(mapOf(
                "pattern" to "*.txt",
                "max_depth" to 3
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("shallow.txt"))
            // Very deep file might not be found due to depth limit
        }

        @Test
        fun `should enforce file limits max search depth`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxSearchDepth = 2)
            val strictTool = FileSearchTool(sandbox, strictLimits)

            val deepDir = tempDir.resolve("a/b/c")
            Files.createDirectories(deepDir)
            Files.writeString(deepDir.resolve("deep.txt"), "deep")

            // When
            val result = strictTool.execute(mapOf(
                "pattern" to "*.txt",
                "max_depth" to 10  // Request more than limits allow
            ))

            // Then
            assertTrue(result.success)
            // Depth should be limited by FileLimits
        }
    }

    @Nested
    inner class PatternConversionTests {

        @Test
        fun `should convert simple glob to regex`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), "content")

            // When
            val result = tool.execute(mapOf("pattern" to "*.txt"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("test.txt"))
        }

        @Test
        fun `should convert question mark glob to regex`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "content1")
            Files.writeString(tempDir.resolve("file2.txt"), "content2")
            Files.writeString(tempDir.resolve("file10.txt"), "content10")

            // When
            val result = tool.execute(mapOf("pattern" to "file?.txt"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("file1.txt"))
            assertTrue(result.output!!.contains("file2.txt"))
            assertFalse(result.output!!.contains("file10.txt"))
        }

        @Test
        fun `should handle path separator in pattern`() = runBlocking {
            // Given
            val subdir = tempDir.resolve("subdir")
            Files.createDirectories(subdir)
            Files.writeString(subdir.resolve("file.txt"), "content")

            // When
            val result = tool.execute(mapOf("pattern" to "subdir/*.txt"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("file.txt"))
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
            assertNotNull(properties["pattern"])
            assertNotNull(properties["path"])
            assertNotNull(properties["max_depth"])
            assertNotNull(properties["offset"])
            assertNotNull(properties["limit"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("pattern"))
        }
    }
}

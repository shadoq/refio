package pl.jclab.refio.testutil

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.FileLimits
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Helper functions for tool tests.
 * Provides common setup and assertion methods.
 */
object ToolTestHelpers {

    /**
     * Creates a test file with given content.
     */
    fun createTestFile(parent: Path, name: String, content: String = ""): Path {
        val file = parent.resolve(name)
        Files.writeString(file, content)
        return file
    }

    /**
     * Creates a nested directory structure.
     */
    fun createDirectoryStructure(root: Path, vararg paths: String): Path {
        paths.forEach { pathStr ->
            val fullPath = root.resolve(pathStr)
            if (pathStr.endsWith("/")) {
                Files.createDirectories(fullPath)
            } else {
                fullPath.parent?.let { Files.createDirectories(it) }
                Files.writeString(fullPath, "content of $pathStr")
            }
        }
        return root
    }

    /**
     * Creates a standard sandbox for testing.
     */
    fun createTestSandbox(tempDir: Path): PathSandbox {
        return PathSandbox(tempDir)
    }

    /**
     * Creates strict file limits for testing edge cases.
     */
    fun createStrictLimits(maxFileSize: Long = 1024): FileLimits {
        return FileLimits(maxFileSize = maxFileSize)
    }

    /**
     * Asserts that a tool result indicates success.
     */
    fun assertToolSuccess(result: ToolResult) {
        assertTrue(result.success, "Expected success but got error: ${result.error}")
    }

    /**
     * Asserts that a tool result indicates failure.
     */
    fun assertToolError(result: ToolResult, expectedErrorMessageContains: String? = null) {
        assertTrue(!result.success, "Expected error but got success")
        assertNotNull(result.error, "Error message should not be null")
        if (expectedErrorMessageContains != null) {
            assertTrue(
                result.error!!.contains(expectedErrorMessageContains, ignoreCase = true),
                "Error message should contain '$expectedErrorMessageContains' but was: ${result.error}"
            )
        }
    }

    /**
     * Asserts that a file exists with expected content.
     */
    fun assertFileExists(root: Path, relativePath: String, expectedContent: String? = null) {
        val file = root.resolve(relativePath)
        assertTrue(file.exists(), "File should exist: $relativePath")
        if (expectedContent != null) {
            val actualContent = Files.readString(file)
            assertTrue(
                actualContent == expectedContent,
                "File content mismatch. Expected: '$expectedContent', Actual: '$actualContent'"
            )
        }
    }

    /**
     * Asserts that a file does not exist.
     */
    fun assertFileNotExists(root: Path, relativePath: String) {
        val file = root.resolve(relativePath)
        assertTrue(!file.exists(), "File should not exist: $relativePath")
    }

    /**
     * Creates a set of test files for comprehensive testing.
     */
    fun createTestFiles(root: Path): Map<String, Path> {
        val files = mutableMapOf<String, Path>()

        // Create various file types
        files["text.txt"] = createTestFile(root, "text.txt", "Hello World\nLine 2")
        files["empty.txt"] = createTestFile(root, "empty.txt", "")
        files["json.json"] = createTestFile(root, "config.json", """{"key": "value"}""")
        files["multiline.md"] = createTestFile(root, "readme.md", "# Title\n\nContent here")

        // Create nested structure
        val srcDir = root.resolve("src")
        Files.createDirectories(srcDir)
        files["src/main.kt"] = createTestFile(srcDir, "main.kt", "fun main() = println()")

        val testDir = root.resolve("tests")
        Files.createDirectories(testDir)
        files["tests/test.kt"] = createTestFile(testDir, "test.kt", "fun test() = assert()")

        return files
    }

    /**
     * Reads file content safely.
     */
    fun readFileContent(root: Path, relativePath: String): String {
        val file = root.resolve(relativePath)
        return Files.readString(file)
    }

    /**
     * Counts lines in a file.
     */
    fun countLines(root: Path, relativePath: String): Int {
        val content = readFileContent(root, relativePath)
        return content.lines().size
    }

    /**
     * Creates a large file for testing size limits.
     */
    fun createLargeFile(root: Path, name: String, sizeInBytes: Long): Path {
        val file = root.resolve(name)
        // Write in chunks for efficiency
        file.toFile().outputStream().use { output ->
            val chunk = "x".repeat(1024).toByteArray()
            var remaining = sizeInBytes
            while (remaining > 0) {
                val toWrite = minOf(chunk.size.toLong(), remaining).toInt()
                output.write(chunk, 0, toWrite)
                remaining -= toWrite
            }
        }
        return file
    }

    /**
     * Standard test content for various scenarios.
     */
    object TestContent {
        val SIMPLE_TEXT = "Hello, World!"
        val MULTILINE_TEXT = """
            Line 1
            Line 2
            Line 3
        """.trimIndent()

        val CODE_SAMPLE = """
            fun main() {
                println("Hello")
            }
        """.trimIndent()

        val JSON_SAMPLE = """{"name": "test", "value": 123}"""

        val REPEATED_PATTERN = "foo bar foo baz foo qux"

        val UNICODE_TEXT = "English 日本語 Polski 中文"

        val SPECIAL_CHARS = "!@#\$%^&*(){}[]<>?/\\|~`"
    }
}

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
 * Testy dla GrepSearchTool — narzędzia do wyszukiwania tekstu w plikach.
 */
class GrepSearchToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PathSandbox
    private lateinit var tool: GrepSearchTool

    @BeforeEach
    fun setup() {
        sandbox = PathSandbox(tempDir)
        tool = GrepSearchTool(sandbox, FileLimits.DEFAULT)
    }

    private fun createTestFiles() {
        Files.writeString(tempDir.resolve("file1.txt"), """
            line one
            line two
            line three
            hello world
        """.trimIndent())

        Files.writeString(tempDir.resolve("file2.kt"), """
            fun main() {
                println("Hello")
                val test = "test"
            }
        """.trimIndent())

        val subdir = tempDir.resolve("subdir")
        Files.createDirectories(subdir)
        Files.writeString(subdir.resolve("nested.txt"), """
            nested content
            hello again
            more lines
        """.trimIndent())

        val excludedDir = tempDir.resolve("node_modules")
        Files.createDirectories(excludedDir)
        Files.writeString(excludedDir.resolve("excluded.js"), "hello from excluded")
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("grep_search", tool.name)
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
            tool.validateParams(mapOf("pattern" to "hello"))
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
        fun `should find text pattern in files`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "hello"))

            // Then
            assertTrue(result.success)
            assertNotNull(result.output)
            assertTrue(result.output!!.contains("hello"), "Should contain 'hello'")
        }

        @Test
        fun `should show line numbers in results`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "hello"))

            // Then
            assertTrue(result.success)
            // Results are formatted as "file:line: content"
            assertTrue(result.output!!.contains(":"))
        }

        @Test
        fun `should search in subdirectories by default`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf(
                "pattern" to "nested",
                "path" to "."
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("nested"))
        }

        @Test
        fun `should search in specific subdirectory`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf(
                "pattern" to "hello",
                "path" to "subdir"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("hello"))
        }

        @Test
        fun `should find multiple matches in same file`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), """
                hello world
                hello again
                hello there
            """.trimIndent())

            // When
            val result = tool.execute(mapOf("pattern" to "hello"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            val helloCount = output.lines().count { it.contains("hello") }
            assertTrue(helloCount >= 3, "Should find at least 3 'hello' occurrences")
        }

        @Test
        fun `should return no matches message when pattern not found`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "nonexistent_pattern_xyz"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("No matches found"))
        }

        @Test
        fun `should include metadata in result`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "hello"))

            // Then
            assertNotNull(result.metadata)
            assertEquals("hello", result.metadata!!["pattern"])
            assertTrue(result.metadata!!.containsKey("match_count"))
            assertTrue(result.metadata!!.containsKey("files_searched"))
        }
    }

    @Nested
    inner class CaseSensitivityTests {

        @Test
        fun `should be case insensitive by default`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), """
                Hello World
                HELLO again
                hello there
            """.trimIndent())

            // When
            val result = tool.execute(mapOf("pattern" to "hello"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // Should match all variations
            assertTrue(output.lines().count { it.contains("hello", ignoreCase = true) } >= 3)
        }

        @Test
        fun `should be case sensitive when parameter is true`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), """
                Hello World
                hello there
            """.trimIndent())

            // When
            val result = tool.execute(mapOf(
                "pattern" to "hello",
                "case_sensitive" to true
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // Should only match lowercase "hello"
            assertTrue(output.contains("hello there"))
        }

        @Test
        fun `should only match exact case when case sensitive`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), """
                Hello World
                hello there
            """.trimIndent())

            // When
            val result = tool.execute(mapOf(
                "pattern" to "Hello",
                "case_sensitive" to true
            ))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("Hello World"))
        }
    }

    @Nested
    inner class FilePatternTests {

        @Test
        fun `should filter files by file_pattern`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf(
                "pattern" to "hello",
                "file_pattern" to "*.txt"
            ))

            // Then
            assertTrue(result.success)
            // Should only search .txt files
            val output = result.output!!
            assertTrue(output.lines().none { it.contains(".kt:") })
        }

        @Test
        fun `should search only kotlin files with kt pattern`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf(
                "pattern" to "println",
                "file_pattern" to "*.kt"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("println"))
        }

        @Test
        fun `should treat path-anchored file_pattern as a name glob`() = runBlocking {
            // Regression (session 35f562ed, 2026-05): models pass a full path glob in
            // file_pattern (e.g. "subdir/*.txt") instead of a bare name glob. The regex
            // was anchored (^...$) and matched against the bare file name, so any '/'
            // silently yielded 0 hits. The directory is already scoped by `path`, so a
            // path-like pattern must be reduced to its last segment ("*.txt").
            createTestFiles()

            val result = tool.execute(mapOf(
                "pattern" to "println",
                "file_pattern" to "core/src/main/kotlin/**/*.kt"
            ))

            assertTrue(result.success)
            assertFalse(
                result.output!!.contains("No matches found"),
                "Path-anchored file_pattern should match by name segment, not yield 0 hits"
            )
            assertTrue(result.output!!.contains("println"))
        }

        @Test
        fun `should support question mark in file pattern`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "hello")
            Files.writeString(tempDir.resolve("file2.txt"), "world")

            // When
            val result = tool.execute(mapOf(
                "pattern" to "hello",
                "file_pattern" to "file?.txt"
            ))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("hello"))
        }
    }

    @Nested
    inner class RegexPatternTests {

        @Test
        fun `should support simple regex patterns`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), """
                test123
                test456
                test789
            """.trimIndent())

            // When
            val result = tool.execute(mapOf("pattern" to "test\\d+"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.lines().count { it.contains("test") } >= 3)
        }

        @Test
        fun `should support character classes in regex`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), """
                abc
                axc
                ayc
                a1c
            """.trimIndent())

            // When
            val result = tool.execute(mapOf("pattern" to "a[xyz]c"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // a[xyz]c matches axc and ayc, but NOT abc (b is not in [xyz]) or a1c (1 is not in [xyz])
            assertFalse(output.contains("abc"), "abc should not match pattern a[xyz]c")
            assertTrue(output.contains("axc"))
            assertTrue(output.contains("ayc"))
            assertFalse(output.contains("a1c"), "a1c should not match pattern a[xyz]c")
        }

        @Test
        fun `should support alternation in regex`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), """
                hello
                world
                foo
                bar
            """.trimIndent())

            // When
            val result = tool.execute(mapOf("pattern" to "hello|world"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("hello"))
            assertTrue(output.contains("world"))
        }

        @Test
        fun `should support anchors in regex`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("test.txt"), """
                test
                pretest
                testpost
            """.trimIndent())

            // When
            val result = tool.execute(mapOf("pattern" to "^test\$"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // Should only match "test" on its own line (line 1)
            // The output format is "test.txt:1: test" which contains "test.txt:1: test"
            assertTrue(output.contains("test.txt"))
            // Should NOT contain "pretest" or "testpost" since they don't match ^test$
            assertFalse(output.contains("pretest"))
            assertFalse(output.contains("testpost"))
        }
    }

    @Nested
    inner class ResultLimitTests {

        @Test
        fun `should respect max_results parameter`() = runBlocking {
            // Given
            repeat(20) { i ->
                Files.writeString(tempDir.resolve("file$i.txt"), "hello world $i")
            }

            // When
            val result = tool.execute(mapOf(
                "pattern" to "hello",
                "max_results" to 5
            ))

            // Then
            assertTrue(result.success)
            assertNotNull(result.metadata)
            val matchCount = result.metadata!!["match_count"] as Int
            assertTrue(matchCount <= 5, "Should return at most 5 results, got $matchCount")
        }

        @Test
        fun `should enforce file limits max grep results`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxGrepResults = 10)
            val strictTool = GrepSearchTool(sandbox, strictLimits)

            repeat(50) { i ->
                Files.writeString(tempDir.resolve("file$i.txt"), "hello world $i")
            }

            // When
            val result = strictTool.execute(mapOf(
                "pattern" to "hello",
                "max_results" to 100  // Request more than limits allow
            ))

            // Then
            assertTrue(result.success)
            val matchCount = result.metadata!!["match_count"] as Int
            assertTrue(matchCount <= 10, "Should be limited by FileLimits")
        }

        @Test
        fun `should stop searching when limit is reached`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("file1.txt"), "hello\nhello\nhello")
            Files.writeString(tempDir.resolve("file2.txt"), "hello\nhello\nhello")

            // When
            val result = tool.execute(mapOf(
                "pattern" to "hello",
                "max_results" to 3
            ))

            // Then
            assertTrue(result.success)
            val matchCount = result.metadata!!["match_count"] as Int
            assertTrue(matchCount <= 3)
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
            // When - use path with slash to avoid bare filename conversion
            val result = tool.execute(mapOf(
                "pattern" to "test",
                "path" to "nonexistent/dir"
            ))

            // Then
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should search file directly when path is a regular file`() = runBlocking {
            // B3: grep_search now accepts a single file path — agents commonly pass a
            // concrete file when they want to scan one file's contents. Previously this
            // returned "Not a directory" and cost a wasted turn.
            Files.writeString(tempDir.resolve("file.txt"), "line one\nmatch me\nline three")

            val result = tool.execute(mapOf(
                "pattern" to "match",
                "path" to "./file.txt"
            ))

            assertTrue(result.success, "Expected single-file grep to succeed: ${result.error}")
            assertNotNull(result.output)
            assertTrue(result.output!!.contains("match me"), "Output should contain matched line: ${result.output}")
        }
    }

    @Nested
    inner class ExcludedFilesTests {

        @Test
        fun `should exclude binary files by default`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("source.txt"), "hello world")
            Files.writeString(tempDir.resolve("image.jpg"), "hello")
            Files.writeString(tempDir.resolve("archive.zip"), "hello")

            // When
            val result = tool.execute(mapOf("pattern" to "hello"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("source.txt"))
            assertFalse(output.contains(".jpg"))
            assertFalse(output.contains(".zip"))
        }

        @Test
        fun `should exclude compiled code files`() = runBlocking {
            // Given
            Files.writeString(tempDir.resolve("source.txt"), "hello")
            Files.writeString(tempDir.resolve("program.class"), "hello")

            // When
            val result = tool.execute(mapOf("pattern" to "hello"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            assertTrue(output.contains("source.txt"))
            assertFalse(output.contains(".class"))
        }

        @Test
        fun `should exclude excluded directories`() = runBlocking {
            // Given
            createTestFiles()

            // When
            val result = tool.execute(mapOf("pattern" to "hello"))

            // Then
            assertTrue(result.success)
            val output = result.output!!
            // Should not search in node_modules
            assertFalse(output.contains("excluded.js"))
        }
    }

    @Nested
    inner class FileSizeLimitTests {

        @Test
        fun `should skip files that exceed size limit`() = runBlocking {
            // Given
            val strictLimits = FileLimits(maxFileSize = 100)
            val strictTool = GrepSearchTool(sandbox, strictLimits)

            Files.writeString(tempDir.resolve("small.txt"), "hello world")
            val largeContent = "x".repeat(200)
            Files.writeString(tempDir.resolve("large.txt"), largeContent)

            // When
            val result = strictTool.execute(mapOf("pattern" to "hello"))

            // Then
            assertTrue(result.success)
            assertTrue(result.output!!.contains("small.txt"))
        }
    }

    @Nested
    inner class ResultRankingTests {

        @Test
        fun `declaration hits rank above usage hits`() = runBlocking {
            // Rule 9 (docs/0060 Faza 2): when an agent greps for a symbol, the line that DECLARES
            // it is the high-value hit. Surfacing the definition above call sites cuts the
            // follow-up reads a model needs on cold-start navigation. A regression here (usages
            // first) would silently send weak models down call chains before they ever see the
            // definition — that is WHY ranking matters, not just THAT the order changed.
            Files.writeString(tempDir.resolve("a.kt"), """
                fun caller() {
                    val x = Widget()
                    doSomething(Widget())
                }
                class Widget {
                }
            """.trimIndent())

            val result = tool.execute(mapOf("pattern" to "Widget", "file_pattern" to "*.kt"))

            assertTrue(result.success)
            val hitLines = result.output!!.lines().filter { it.contains("Widget") }
            val declIdx = hitLines.indexOfFirst { it.contains("class Widget") }
            val usageIdx = hitLines.indexOfFirst { it.contains("Widget()") }
            assertTrue(declIdx >= 0, "declaration line missing: $hitLines")
            assertTrue(usageIdx >= 0, "usage line missing: $hitLines")
            assertTrue(declIdx < usageIdx, "Declaration 'class Widget' must rank before usage 'Widget()': $hitLines")
        }

        @Test
        fun `preserves file order within the same rank group`() = runBlocking {
            // Within one rank tier (two plain usages, no declaration keyword) the original
            // file-walk order must survive — ranking is a stable reorder, not a shuffle.
            Files.writeString(tempDir.resolve("b.kt"), """
                val first = Gadget()
                val second = Gadget()
            """.trimIndent())

            val result = tool.execute(mapOf("pattern" to "Gadget\\(\\)", "file_pattern" to "*.kt"))

            assertTrue(result.success)
            val hitLines = result.output!!.lines().filter { it.contains("Gadget()") }
            val firstIdx = hitLines.indexOfFirst { it.contains("first") }
            val secondIdx = hitLines.indexOfFirst { it.contains("second") }
            assertTrue(firstIdx >= 0 && secondIdx > firstIdx, "file order must be preserved: $hitLines")
        }
    }

    @Nested
    inner class LongLineTests {

        /**
         * Matching is scanned from every start offset, so an unanchored pattern over a single very
         * long line is quadratic: measured ~4 s at 50k characters and ~8 s at 100k, and a line may
         * legally reach the 2 MB file limit. java.util.regex answers neither cancellation nor
         * Thread.interrupt() while it runs, so such a line has to be refused before it is matched.
         */
        @Test
        fun `a very long line is not matched against and the skip is reported`() = runBlocking {
            Files.writeString(
                tempDir.resolve("bundle.min.js"),
                "needle " + "abcdefghij".repeat(200_000) + "!"
            )

            val started = System.currentTimeMillis()
            val result = tool.execute(mapOf("pattern" to "[a-z]*!", "path" to "."))
            val elapsed = System.currentTimeMillis() - started

            assertTrue(result.success)
            assertTrue(elapsed < 5_000, "a 2 MB single-line file must not stall the call, took ${elapsed}ms")
            assertEquals(1, result.metadata?.get("long_lines_skipped"))
            assertTrue(
                result.output!!.contains("were not searched"),
                "skipping must be visible, not silent: ${result.output}"
            )
        }

        @Test
        fun `an ordinary long-ish line is still searched`() {
            // The cap must not quietly shrink normal search: only absurd machine-generated lines go.
            runBlocking {
                Files.writeString(tempDir.resolve("wide.txt"), "x".repeat(5_000) + " needle")

                val result = tool.execute(mapOf("pattern" to "needle", "path" to "."))

                assertTrue(result.success)
                assertTrue(result.output!!.contains("wide.txt"), "got: ${result.output}")
                assertEquals(0, result.metadata?.get("long_lines_skipped"))
            }
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
            assertNotNull(properties["file_pattern"])
            assertNotNull(properties["case_sensitive"])
            assertNotNull(properties["max_results"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("pattern"))
        }
    }
}

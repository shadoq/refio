package pl.jclab.refio.core.tools.refactor

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.security.FileLimits
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Contract tests for the text fallback engine. The business rule under test: a rename is
 * identifier-boundary-aware, so renaming `count` must never touch `counter` or `myCount`.
 */
class TextStructuralRefactorerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var refactorer: TextStructuralRefactorer

    @BeforeEach
    fun setup() {
        refactorer = TextStructuralRefactorer(PathSandbox(tempDir), FileLimits.DEFAULT)

        tempDir.resolve("src").createDirectories()
        tempDir.resolve("Main.kt").writeText(
            """
            fun main() {
                var count = 0
                count += 1
                println(count)
            }
            """.trimIndent()
        )
        tempDir.resolve("src/Helper.kt").writeText(
            """
            fun helper(count: Int): Int {
                return count * 2
            }
            """.trimIndent()
        )
        // False-positive fixture: contains `count` only as a substring of other identifiers.
        tempDir.resolve("src/Counter.kt").writeText(
            """
            class Counter {
                var counter = 0
                var myCount = 0
                var count_total = 0
            }
            """.trimIndent()
        )
    }

    @Test
    fun `rename replaces whole-word occurrences across files`() = runBlocking<Unit> {
        val result = refactorer.renameSymbol("Main.kt", 2, "count", "total")

        assertEquals(listOf("Main.kt", "src/Helper.kt"), result.filesChanged.sorted())
        assertEquals(5, result.replacements)
        assertTrue(tempDir.resolve("Main.kt").readText().contains("var total = 0"))
        assertTrue(tempDir.resolve("src/Helper.kt").readText().contains("fun helper(total: Int)"))
        assertFalse(tempDir.resolve("Main.kt").readText().contains("count"))
    }

    @Test
    fun `rename does not touch identifiers that merely contain the name as substring`() = runBlocking<Unit> {
        val before = tempDir.resolve("src/Counter.kt").readText()

        val result = refactorer.renameSymbol("Main.kt", 2, "count", "total")

        assertEquals(before, tempDir.resolve("src/Counter.kt").readText())
        assertFalse(result.filesChanged.contains("src/Counter.kt"))
    }

    @Test
    fun `rename of unknown symbol changes nothing`() = runBlocking<Unit> {
        val result = refactorer.renameSymbol("Main.kt", 1, "doesNotExist", "whatever")

        assertEquals(0, result.replacements)
        assertTrue(result.filesChanged.isEmpty())
    }

    @Test
    fun `rename rejects non-identifier names to prevent regex injection`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { refactorer.renameSymbol("Main.kt", 1, "a.*b", "x") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { refactorer.renameSymbol("Main.kt", 1, "count", "new name") }
        }
    }

    @Test
    fun `findUsages returns whole-word locations only`() = runBlocking<Unit> {
        val usages = refactorer.findUsages("count")

        val byFile = usages.groupBy { it.file }
        assertEquals(setOf("Main.kt", "src/Helper.kt"), byFile.keys)
        assertEquals(listOf(2, 3, 4), byFile.getValue("Main.kt").map { it.line })
        assertEquals(listOf(1, 2), byFile.getValue("src/Helper.kt").map { it.line })
        assertTrue(usages.first { it.file == "Main.kt" && it.line == 2 }.snippet.contains("var count = 0"))
    }

    @Test
    fun `findUsages of unknown symbol returns empty list`() = runBlocking<Unit> {
        assertTrue(refactorer.findUsages("doesNotExist").isEmpty())
    }
}

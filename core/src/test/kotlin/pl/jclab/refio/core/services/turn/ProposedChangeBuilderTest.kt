package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProposedChangeBuilderTest {

    @TempDir
    lateinit var projectRoot: Path

    private val builder by lazy { ProposedChangeBuilder(projectRoot) }

    private fun writeFile(relative: String, content: String): Path {
        val path = projectRoot.resolve(relative)
        Files.createDirectories(path.parent ?: projectRoot)
        Files.writeString(path, content)
        return path
    }

    @Test
    fun `advance_code_editing with old and new string produces unified diff of the replacement`() {
        writeFile("src/Main.kt", "fun main() {\n    println(\"old\")\n}\n")

        val change = builder.build(
            "advance_code_editing",
            mapOf(
                "path" to "src/Main.kt",
                "old_string" to "println(\"old\")",
                "new_string" to "println(\"new\")"
            )
        )

        assertNotNull(change)
        assertEquals("src/Main.kt", change.filePath)
        assertTrue(change.oldContent!!.contains("println(\"old\")"))
        assertTrue(change.newContent!!.contains("println(\"new\")"))
        assertFalse(change.newContent!!.contains("println(\"old\")"))
        assertTrue(change.unifiedDiff.contains("- ") && change.unifiedDiff.contains("println(\"old\")"))
        assertTrue(change.unifiedDiff.contains("+ ") && change.unifiedDiff.contains("println(\"new\")"))
        assertFalse(change.diffTruncated)
    }

    @Test
    fun `multi_edit applies all edits of a single-file batch before diffing`() {
        writeFile("app.py", "alpha\nbeta\ngamma\n")

        val change = builder.build(
            "multi_edit",
            mapOf(
                "edits" to listOf(
                    mapOf("path" to "app.py", "old_string" to "alpha", "new_string" to "ALPHA"),
                    mapOf("path" to "app.py", "old_string" to "gamma", "new_string" to "GAMMA")
                )
            )
        )

        assertNotNull(change)
        assertEquals("app.py", change.filePath)
        assertEquals("ALPHA\nbeta\nGAMMA\n", change.newContent)
        assertTrue(change.unifiedDiff.contains("+ ALPHA"))
        assertTrue(change.unifiedDiff.contains("+ GAMMA"))
    }

    @Test
    fun `multi_edit spanning multiple files yields no preview`() {
        writeFile("a.txt", "one\n")
        writeFile("b.txt", "two\n")

        val change = builder.build(
            "multi_edit",
            mapOf(
                "edits" to listOf(
                    mapOf("path" to "a.txt", "old_string" to "one", "new_string" to "1"),
                    mapOf("path" to "b.txt", "old_string" to "two", "new_string" to "2")
                )
            )
        )

        assertNull(change)
    }

    @Test
    fun `large file carries only the diff, not both full contents`() {
        val bigContent = (1..300).joinToString("\n") { "line $it" } + "\n"
        writeFile("big.txt", bigContent)

        val change = builder.build(
            "code_editing",
            mapOf(
                "path" to "big.txt",
                "old_string" to "line 150",
                "new_string" to "line 150 changed"
            )
        )

        assertNotNull(change)
        assertNull(change.oldContent)
        assertNull(change.newContent)
        assertTrue(change.unifiedDiff.contains("+ line 150 changed"))
    }

    @Test
    fun `huge diff is capped with a truncation marker`() {
        val oldContent = (1..600).joinToString("\n") { "old $it" }
        writeFile("huge.txt", oldContent)
        val newContent = (1..600).joinToString("\n") { "new $it" }

        val change = builder.build(
            "create_new_file",
            mapOf("path" to "huge.txt", "content" to newContent)
        )

        assertNotNull(change)
        assertTrue(change.diffTruncated)
        assertTrue(change.unifiedDiff.endsWith(ProposedChangeBuilder.TRUNCATION_MARKER))
        assertTrue(change.unifiedDiff.lines().size <= ProposedChangeBuilder.MAX_DIFF_LINES + 1)
    }

    @Test
    fun `create_new_file previews full content as additions`() {
        val change = builder.build(
            "create_new_file",
            mapOf("path" to "fresh.txt", "content" to "hello\nworld\n")
        )

        assertNotNull(change)
        assertEquals("", change.oldContent)
        assertEquals("hello\nworld\n", change.newContent)
        assertTrue(change.unifiedDiff.contains("+ hello"))
    }

    @Test
    fun `path escaping the project root yields no preview`() {
        val change = builder.build(
            "create_new_file",
            mapOf("path" to "../outside.txt", "content" to "x")
        )

        assertNull(change)
    }

    @Test
    fun `advance_code_editing with only edit_description yields no preview`() {
        writeFile("gen.kt", "content\n")

        val change = builder.build(
            "advance_code_editing",
            mapOf("path" to "gen.kt", "edit_description" to "refactor everything")
        )

        assertNull(change)
    }
}

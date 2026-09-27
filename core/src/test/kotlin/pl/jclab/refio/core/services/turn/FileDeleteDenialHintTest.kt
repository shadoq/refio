package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A model that wants to start a file over tries to delete it first, because creating a file that
 * exists is refused. When the gate denies the delete, the denial has to tell it the way that works,
 * otherwise it cycles through rm, rm -f and unlink until the turn is aborted for repeated denials.
 */
class FileDeleteDenialHintTest {

    private val root = Paths.get("/work/project").toAbsolutePath().normalize()

    @Test
    fun `deleting a project file points the model at the whole-file editing tool`() {
        val hint = FileDeleteDenialHint.forCommand("rm index.html", root)

        assertNotNull(hint)
        assertTrue(hint.contains("advance_code_editing"), hint)
    }

    @Test
    fun `the forced and unlink variants a model tries next get the same hint`() {
        listOf(
            "rm -f index.html",
            "unlink ./src/app.js",
            "del /f /q src\\app.js",
            "Remove-Item -Path index.html -Force",
            "rm \"${root.resolve("index.html")}\"",
        ).forEach { command ->
            assertNotNull(FileDeleteDenialHint.forCommand(command, root), command)
        }
    }

    @Test
    fun `deleting a file outside the project gets no hint`() {
        assertNull(FileDeleteDenialHint.forCommand("rm ../other/index.html", root))
        assertNull(FileDeleteDenialHint.forCommand("rm ${root.resolveSibling("elsewhere").resolve("a.txt")}", root))
    }

    @Test
    fun `a command that deletes nothing gets no hint`() {
        assertNull(FileDeleteDenialHint.forCommand("npm test", root))
        assertNull(FileDeleteDenialHint.forCommand("rm", root))
    }

    @Test
    fun `without a known project root there is nothing to compare against`() {
        assertNull(FileDeleteDenialHint.forCommand("rm index.html", null))
    }

    @Test
    fun `the hint is appended to the denial only when it applies`() {
        val base = "Error: not permitted."

        assertEquals(base, FileDeleteDenialHint.appendTo(base, "npm test", root))
        assertTrue(FileDeleteDenialHint.appendTo(base, "rm index.html", root).startsWith("$base "))
    }
}

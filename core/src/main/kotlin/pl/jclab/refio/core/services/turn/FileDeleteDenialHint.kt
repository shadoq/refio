package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.tools.security.ShellCommandAnalyzer
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Extra guidance for a denied command that deletes a file inside the project.
 *
 * A model that wants to start a file over cannot create it again (creating an existing file is
 * refused), so it tries to delete it first. When the approval gate denies that, a bare denial leaves
 * it cycling through rm, rm -f and unlink until the turn aborts. The hint names the way that works:
 * rewriting the whole file in place.
 */
object FileDeleteDenialHint {

    const val HINT =
        "To replace a file's content, rewrite the whole file with the advance_code_editing tool instead of deleting it."

    private val DELETE_PROGRAMS = setOf("rm", "unlink", "del", "erase", "remove-item", "ri")

    // cmd.exe switches such as /f or /q; a POSIX path like /tmp/x has more than one letter after the slash.
    private val CMD_SWITCH = Regex("^/[a-zA-Z]$")

    /** [base] with the hint appended when [command] deletes a file inside [projectRoot]. */
    fun appendTo(base: String, command: String?, projectRoot: Path?): String {
        val hint = command?.let { forCommand(it, projectRoot) } ?: return base
        return "$base $hint"
    }

    /** The hint when [command] deletes at least one file inside [projectRoot], else null. */
    fun forCommand(command: String, projectRoot: Path?): String? {
        val root = projectRoot?.toAbsolutePath()?.normalize() ?: return null
        val deletesProjectFile = ShellCommandAnalyzer.commandUnits(command).any { unit ->
            deleteTargets(unit).any { isInside(it, root) }
        }
        return if (deletesProjectFile) HINT else null
    }

    private fun deleteTargets(unit: String): List<String> {
        val tokens = unit.trim().split(Regex("\\s+")).map { it.trim('"', '\'') }.filter { it.isNotEmpty() }
        val program = tokens.firstOrNull()
            ?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.lowercase()?.removeSuffix(".exe")
            ?: return emptyList()
        if (program !in DELETE_PROGRAMS) return emptyList()
        return tokens.drop(1).filterNot { it.startsWith("-") || CMD_SWITCH.matches(it) }
    }

    private fun isInside(target: String, root: Path): Boolean = try {
        root.resolve(Paths.get(target)).normalize().startsWith(root)
    } catch (_: Exception) {
        false
    }
}

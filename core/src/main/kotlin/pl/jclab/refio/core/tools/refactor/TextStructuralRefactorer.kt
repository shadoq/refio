package pl.jclab.refio.core.tools.refactor

import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.FileLockManager
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.security.FileLimits
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

private val logger = dualLogger("TextStructuralRefactorer")

/**
 * Text-based fallback implementation of [StructuralRefactorer].
 *
 * Not semantic: it performs identifier-boundary-aware search/replace across project text files.
 * Substrings are never touched (renaming `count` leaves `counter` and `myCount` intact), but it
 * cannot distinguish scopes, comments, or string literals - every whole-word occurrence changes.
 *
 * File writes go through the same safety path as the editing tools: per-file lock via
 * [FileLockManager] plus sandbox re-validation inside the lock (TOCTOU window).
 */
class TextStructuralRefactorer(
    private val sandbox: PathSandbox,
    private val limits: FileLimits
) : StructuralRefactorer {

    override val engineDescription =
        "text fallback: word-boundary search/replace across project files (no scope awareness; " +
        "comments and string literals with the same whole word are also affected)"

    override suspend fun renameSymbol(file: String, line: Int, oldName: String, newName: String): RenameResult {
        requireIdentifier(oldName, "oldName")
        requireIdentifier(newName, "newName")

        val regex = wordBoundaryRegex(oldName)
        val changedFiles = mutableListOf<String>()
        var totalReplacements = 0

        for (path in walkProjectFiles()) {
            val replaced = FileLockManager.withFileLock(path.toAbsolutePath().toString()) {
                sandbox.revalidateBeforeIO(path)
                val content = try {
                    Files.readString(path)
                } catch (e: Exception) {
                    logger.debug { "Skipping unreadable file: $path - ${e.message}" }
                    return@withFileLock 0
                }
                val matches = regex.findAll(content).count()
                if (matches == 0) {
                    return@withFileLock 0
                }
                Files.writeString(path, regex.replace(content, Regex.escapeReplacement(newName)))
                matches
            }
            if (replaced > 0) {
                changedFiles.add(relativize(path))
                totalReplacements += replaced
            }
        }

        logger.info { "Text rename '$oldName' -> '$newName': $totalReplacements replacements in ${changedFiles.size} files" }
        return RenameResult(filesChanged = changedFiles, replacements = totalReplacements)
    }

    override suspend fun findUsages(symbolName: String): List<UsageLocation> {
        requireIdentifier(symbolName, "symbolName")

        val regex = wordBoundaryRegex(symbolName)
        val usages = mutableListOf<UsageLocation>()

        for (path in walkProjectFiles()) {
            val content = try {
                Files.readString(path)
            } catch (e: Exception) {
                logger.debug { "Skipping unreadable file: $path - ${e.message}" }
                continue
            }
            content.lines().forEachIndexed { index, lineText ->
                if (regex.containsMatchIn(lineText)) {
                    usages.add(UsageLocation(file = relativize(path), line = index + 1, snippet = lineText.trim()))
                }
            }
        }
        return usages
    }

    /**
     * Project text files eligible for refactoring: excluded directories (.git, build, ...),
     * excluded extensions (binaries), and oversized files are skipped - same policy as grep_search.
     */
    private fun walkProjectFiles(): List<Path> {
        val root = sandbox.getProjectRoot()
        return Files.walk(root, limits.maxSearchDepth).use { stream ->
            stream
                .filter { path ->
                    val relative = try {
                        root.relativize(path)
                    } catch (e: Exception) {
                        path
                    }
                    relative.none { segment -> limits.shouldExcludeDirectory(segment.toString()) }
                }
                .filter { it.isRegularFile() }
                .filter { !limits.shouldExcludeFile(it.fileName.toString()) }
                .filter { Files.size(it) <= limits.maxFileSize }
                .toList()
        }
    }

    private fun relativize(path: Path): String {
        return sandbox.getProjectRoot().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
    }

    private fun requireIdentifier(name: String, paramName: String) {
        if (!IDENTIFIER_REGEX.matches(name)) {
            throw IllegalArgumentException("Parameter '$paramName' must be a plain identifier, got: '$name'")
        }
    }

    /**
     * Matches [name] only as a whole identifier: no identifier character (letter, digit,
     * underscore, dollar) directly before or after, so substrings like `counter` for `count`
     * are never matched.
     */
    private fun wordBoundaryRegex(name: String): Regex {
        return Regex("(?<![A-Za-z0-9_$])${Regex.escape(name)}(?![A-Za-z0-9_$])")
    }

    companion object {
        private val IDENTIFIER_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

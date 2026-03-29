package pl.jclab.refio.core.context.providers.standalone

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val logger = dualLogger("StandaloneGitDiffContextProvider")

/**
 * Standalone version of GitDiffContextProvider — uses git CLI instead of IntelliJ VCS API.
 */
class StandaloneGitDiffContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "diff",
        displayTitle = "diff",
        description = "Uncommitted changes (git diff)",
        type = ProviderType.NORMAL,
        icon = "🔀"
    )

    override suspend fun getContextItems(query: String, extras: ContextProviderExtras): List<ContextItem> = withContext(Dispatchers.IO) {
        val workspacePath = extras.workspacePath
        if (workspacePath.isEmpty()) return@withContext emptyList()

        val workingDir = File(workspacePath)
        if (!workingDir.exists()) return@withContext emptyList()

        val diff = getGitDiff(workingDir)

        listOf(ContextItem(
            description = "Uncommitted Changes",
            content = "```diff\n$diff\n```",
            name = "Git Diff",
            uri = ContextUri(type = "git-diff", value = "working-tree")
        ))
    }

    private fun getGitDiff(workingDir: File): String {
        return try {
            // Check if git repo
            val check = executeGit(workingDir, listOf("rev-parse", "--git-dir"))
            if (check.isFailure) return "[Not a git repository]"

            // Get staged + unstaged diff
            val staged = executeGit(workingDir, listOf("diff", "--cached", "--stat"))
                .getOrDefault("")
            val unstaged = executeGit(workingDir, listOf("diff", "--stat"))
                .getOrDefault("")
            val untracked = executeGit(workingDir, listOf("ls-files", "--others", "--exclude-standard"))
                .getOrDefault("")

            // Get full diff (limited)
            val fullDiff = executeGit(workingDir, listOf("diff", "HEAD"))
                .getOrDefault("")

            buildString {
                if (staged.isNotBlank()) {
                    appendLine("Staged changes:")
                    appendLine(staged.trim())
                    appendLine()
                }
                if (unstaged.isNotBlank()) {
                    appendLine("Unstaged changes:")
                    appendLine(unstaged.trim())
                    appendLine()
                }
                if (untracked.isNotBlank()) {
                    appendLine("Untracked files:")
                    untracked.trim().lines().take(20).forEach { appendLine("  $it") }
                    appendLine()
                }
                if (fullDiff.isNotBlank()) {
                    appendLine("Full diff:")
                    // Limit to avoid huge context
                    val lines = fullDiff.lines()
                    if (lines.size > 200) {
                        lines.take(200).forEach { appendLine(it) }
                        appendLine("... (${lines.size - 200} more lines)")
                    } else {
                        appendLine(fullDiff.trim())
                    }
                }
                if (isEmpty()) append("[No uncommitted changes]")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get git diff" }
            "[Error getting git diff: ${e.message}]"
        }
    }

    private fun executeGit(workingDir: File, args: List<String>): Result<String> {
        return try {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) Result.success(output) else Result.failure(Exception("git exit $exitCode"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

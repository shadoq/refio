package pl.jclab.refio.core.context.providers

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val logger = dualLogger("GitCommitContextProvider")

/**
 * Provider for git commit information.
 *
 * Usage: @commit:hash
 * Shows details of a specific git commit.
 *
 * Note: Uses git4idea API for git integration.
 */
class GitCommitContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "commit",
        displayTitle = "commit",
        description = "Specific git commit details",
        type = ProviderType.QUERY,
        icon = "📝"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> = withContext(Dispatchers.IO) {
        val commitHash = query.trim()
        if (commitHash.isEmpty()) {
            logger.warn { "Empty commit hash provided" }
            return@withContext emptyList()
        }

        logger.debug { "Git commit query: $commitHash" }

        try {
            val projectPath = extras.workspacePath.ifEmpty {
                extras.project?.basePath ?: ""
            }

            if (projectPath.isEmpty()) {
                logger.warn { "No project path found" }
                return@withContext listOf(
                    ContextItem(
                        description = "No Project Path",
                        content = "Could not determine project directory",
                        name = "Git Error",
                        uri = ContextUri(type = "error", value = "no-project")
                    )
                )
            }

            val workingDir = File(projectPath)
            if (!workingDir.exists()) {
                logger.warn { "Project directory does not exist: $projectPath" }
                return@withContext listOf(
                    ContextItem(
                        description = "Invalid Project Path",
                        content = "Project directory does not exist: $projectPath",
                        name = "Git Error",
                        uri = ContextUri(type = "error", value = "invalid-path")
                    )
                )
            }

            // Check if git is available and this is a git repository
            val gitCheck = executeGitCommand(workingDir, listOf("rev-parse", "--git-dir"))
            if (gitCheck.isFailure) {
                logger.warn { "Not a git repository: $projectPath" }
                return@withContext listOf(
                    ContextItem(
                        description = "No Git Repository",
                        content = "No Git repository found in project. Make sure the project is under version control.",
                        name = "Git Error",
                        uri = ContextUri(type = "error", value = "no-repo")
                    )
                )
            }

            val resolvedHash = resolveCommitHash(workingDir, commitHash)
            if (resolvedHash != null) {
                val details = fetchCommitDetails(workingDir, resolvedHash)
                if (details != null) {
                    logger.info { "Found commit: ${details.shortHash}" }
                    return@withContext listOf(buildCommitContextItem(details))
                }
            }

            val matches = searchCommitMessages(workingDir, commitHash)
            if (matches.isEmpty()) {
                logger.warn { "Commit $commitHash not found" }
                return@withContext listOf(
                    ContextItem(
                        description = "Commit Not Found",
                        content = buildString {
                            appendLine("Commit $commitHash not found in repository.")
                            appendLine()
                            appendLine("Try one of:")
                            appendLine("- Full commit hash")
                            appendLine("- Short hash prefix")
                            appendLine("- Branch or tag name")
                            appendLine("- Commit message keyword")
                        },
                        name = "Git Error",
                        uri = ContextUri(type = "error", value = "not-found")
                    )
                )
            }

            val detailedMatches = matches.mapNotNull { match ->
                fetchCommitDetails(workingDir, match.fullHash)
            }

            if (detailedMatches.isEmpty()) {
                return@withContext listOf(
                    ContextItem(
                        description = "Git Error",
                        content = "Failed to resolve commit details for matches of '$commitHash'",
                        name = "Error",
                        uri = ContextUri(type = "error", value = commitHash)
                    )
                )
            }

            return@withContext detailedMatches.map { buildCommitContextItem(it) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get commit details" }
            return@withContext listOf(
                ContextItem(
                    description = "Git Error",
                    content = buildString {
                        appendLine("Failed to get commit details for: $commitHash")
                        appendLine()
                        appendLine("Error: ${e.message}")
                        appendLine()
                        appendLine("Please verify:")
                        appendLine("- Commit hash is correct")
                        appendLine("- Repository is accessible")
                        appendLine("- Git is properly configured")
                    },
                    name = "Error",
                    uri = ContextUri(type = "error", value = commitHash)
                )
            )
        }
    }

    /**
     * Execute a git command and return the output
     */
    private fun executeGitCommand(workingDir: File, args: List<String>): Result<String> {
        return try {
            val command = listOf("git") + args
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Result.success(output)
            } else {
                Result.failure(Exception("Git command failed with exit code $exitCode: $output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class CommitDetails(
        val fullHash: String,
        val shortHash: String,
        val authorName: String,
        val authorEmail: String,
        val authorDate: String,
        val message: String,
        val fileChanges: String
    )

    private data class CommitMatch(
        val fullHash: String,
        val shortHash: String,
        val subject: String
    )

    private fun resolveCommitHash(workingDir: File, input: String): String? {
        val resolved = executeGitCommand(workingDir, listOf("rev-parse", "--verify", "${input}^{commit}"))
        return if (resolved.isSuccess) resolved.getOrNull()?.trim() else null
    }

    private fun searchCommitMessages(workingDir: File, query: String, limit: Int = 5): List<CommitMatch> {
        val result = executeGitCommand(
            workingDir,
            listOf(
                "log",
                "--all",
                "--max-count=$limit",
                "--regexp-ignore-case",
                "--format=%H|%h|%s",
                "--grep=$query"
            )
        )

        val output = result.getOrNull()?.trim().orEmpty()
        if (output.isBlank()) return emptyList()

        return output.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            CommitMatch(
                fullHash = parts[0],
                shortHash = parts[1],
                subject = parts[2]
            )
        }
    }

    private fun fetchCommitDetails(workingDir: File, commitHash: String): CommitDetails? {
        val showResult = executeGitCommand(
            workingDir,
            listOf("show", "--no-patch", "--format=format:%H%n%h%n%an%n%ae%n%ai%n%B", commitHash)
        )
        if (showResult.isFailure) return null

        val commitInfo = showResult.getOrNull()?.split("\n") ?: return null
        if (commitInfo.size < 6) return null

        val fullHash = commitInfo[0]
        val shortHash = commitInfo[1]
        val authorName = commitInfo[2]
        val authorEmail = commitInfo[3]
        val authorDate = commitInfo[4]
        val message = commitInfo.drop(5).joinToString("\n")

        val statResult = executeGitCommand(
            workingDir,
            listOf("show", "--stat", "--format=", commitHash)
        )
        val fileChanges = statResult.getOrNull() ?: ""

        return CommitDetails(
            fullHash = fullHash,
            shortHash = shortHash,
            authorName = authorName,
            authorEmail = authorEmail,
            authorDate = authorDate,
            message = message,
            fileChanges = fileChanges
        )
    }

    private fun buildCommitContextItem(details: CommitDetails): ContextItem {
        return ContextItem(
            description = "Commit: ${details.shortHash}",
            content = buildString {
                appendLine("Commit: ${details.fullHash}")
                appendLine("Short: ${details.shortHash}")
                appendLine("Author: ${details.authorName} <${details.authorEmail}>")
                appendLine("Date: ${details.authorDate}")
                appendLine()
                appendLine("Message:")
                appendLine(details.message.trim())
                appendLine()
                appendLine("Changes:")
                if (details.fileChanges.isNotBlank()) {
                    appendLine(details.fileChanges.trim())
                } else {
                    appendLine("  (no changes)")
                }
            },
            name = "Commit ${details.shortHash}",
            uri = ContextUri(type = "git-commit", value = details.fullHash)
        )
    }
}

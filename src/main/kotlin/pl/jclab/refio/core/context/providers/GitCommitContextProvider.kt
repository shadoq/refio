package pl.jclab.refio.core.context.providers

import pl.jclab.refio.core.context.*
import pl.jclab.refio.services.logging.dualLogger
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

            // Get commit details using git show
            val showResult = executeGitCommand(
                workingDir,
                listOf("show", "--no-patch", "--format=format:%H%n%h%n%an%n%ae%n%ai%n%B", commitHash)
            )

            if (showResult.isFailure) {
                logger.warn { "Commit $commitHash not found" }
                return@withContext listOf(
                    ContextItem(
                        description = "Commit Not Found",
                        content = "Commit $commitHash not found in repository. Please verify the commit hash.\n\nError: ${showResult.exceptionOrNull()?.message}",
                        name = "Git Error",
                        uri = ContextUri(type = "error", value = "not-found")
                    )
                )
            }

            val commitInfo = showResult.getOrNull()?.split("\n") ?: emptyList()
            if (commitInfo.size < 6) {
                logger.error { "Invalid git show output format" }
                return@withContext listOf(
                    ContextItem(
                        description = "Git Error",
                        content = "Failed to parse git commit information",
                        name = "Error",
                        uri = ContextUri(type = "error", value = commitHash)
                    )
                )
            }

            val fullHash = commitInfo[0]
            val shortHash = commitInfo[1]
            val authorName = commitInfo[2]
            val authorEmail = commitInfo[3]
            val authorDate = commitInfo[4]
            val message = commitInfo.drop(5).joinToString("\n")

            // Get file changes
            val statResult = executeGitCommand(
                workingDir,
                listOf("show", "--stat", "--format=", commitHash)
            )
            val fileChanges = statResult.getOrNull() ?: ""

            logger.info { "Found commit: $shortHash" }

            return@withContext listOf(
                ContextItem(
                    description = "Commit: $shortHash",
                    content = buildString {
                        appendLine("Commit: $fullHash")
                        appendLine("Short: $shortHash")
                        appendLine("Author: $authorName <$authorEmail>")
                        appendLine("Date: $authorDate")
                        appendLine()
                        appendLine("Message:")
                        appendLine(message.trim())
                        appendLine()
                        appendLine("Changes:")
                        if (fileChanges.isNotBlank()) {
                            appendLine(fileChanges.trim())
                        } else {
                            appendLine("  (no changes)")
                        }
                    },
                    name = "Commit $shortHash",
                    uri = ContextUri(type = "git-commit", value = fullHash)
                )
            )
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
}

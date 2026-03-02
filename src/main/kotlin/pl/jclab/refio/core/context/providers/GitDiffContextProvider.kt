package pl.jclab.refio.core.context.providers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import pl.jclab.refio.core.context.*
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("GitDiffContextProvider")

/**
 * Provider for git diff of current changes.
 *
 * Usage: @diff
 * Returns uncommitted changes (git diff).
 */
class GitDiffContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "diff",
        displayTitle = "diff",
        description = "Uncommitted changes (git diff)",
        type = ProviderType.NORMAL,
        icon = "🔀"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val project = extras.project ?: return emptyList()
        val workspacePath = extras.workspacePath.ifEmpty { project.basePath ?: "" }

        logger.debug { "Getting git diff for project: ${project.name}" }

        val diff = getGitDiff(project, workspacePath)

        return listOf(
            ContextItem(
                description = "Uncommitted Changes",
                content = "```diff\n$diff\n```",
                name = "Git Diff",
                uri = ContextUri(
                    type = "git-diff",
                    value = "working-tree"
                )
            )
        )
    }

    private fun getGitDiff(
        project: com.intellij.openapi.project.Project,
        workspacePath: String
    ): String {
        return try {
            // Get changes and extract data (requires read action)
            data class ChangeInfo(val changeType: String, val filePath: String)

            val changeInfos = ApplicationManager.getApplication().runReadAction<List<ChangeInfo>> {
                val changeListManager = ChangeListManager.getInstance(project)
                val changes = changeListManager.allChanges

                changes.take(20).map { change ->
                    ChangeInfo(
                        changeType = change.type.name,
                        filePath = change.virtualFile?.path ?: "unknown"
                    )
                }.toList()
            }

            if (changeInfos.isEmpty()) {
                logger.debug { "No git changes found" }
                return "[No uncommitted changes]"
            }

            logger.debug { "Found ${changeInfos.size} changed files" }

            // Build diff summary (outside read action)
            val diff = buildString {
                appendLine("Uncommitted Changes (${changeInfos.size} files):")
                appendLine()

                changeInfos.forEach { info ->
                    val relativePath = getRelativePath(workspacePath, info.filePath)
                    appendLine("${info.changeType}: $relativePath")
                }

                if (changeInfos.size >= 20) {
                    appendLine()
                    appendLine("Note: Showing first 20 changed files")
                }

                appendLine()
                appendLine("Use IDE's Version Control panel for detailed diffs")
            }

            diff

        } catch (e: Exception) {
            logger.error(e) { "Failed to get git diff" }
            "[Error getting git diff: ${e.message}]"
        }
    }

    private fun getRelativePath(basePath: String, filePath: String): String {
        return if (basePath.isNotEmpty() && filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
        } else {
            filePath
        }
    }
}

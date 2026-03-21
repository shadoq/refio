package pl.jclab.refio.core.context.providers

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import pl.jclab.refio.core.context.*
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("ProblemsContextProvider")

/**
 * Provider for compilation errors and warnings.
 *
 * Usage: @problems
 * Returns current problems from IDE (errors, warnings from compiler/inspections).
 */
class ProblemsContextProvider : BaseContextProvider() {

    override val environment = ContextProviderEnvironment.IDE_ONLY

    override val description = ContextProviderDescription(
        title = "problems",
        displayTitle = "Problems",
        description = "Current compilation errors and warnings",
        type = ProviderType.NORMAL,
        icon = "⚠️"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val project = extras.project ?: return emptyList()
        val workspacePath = extras.workspacePath.ifEmpty { project.basePath ?: "" }

        logger.debug { "Getting problems for project: ${project.name}" }

        val problems = collectProblems(project, workspacePath)

        if (problems.isEmpty()) {
            return listOf(
                ContextItem(
                    description = "No Problems",
                    content = "No compilation errors or warnings found.",
                    name = "Problems",
                    uri = ContextUri(type = "problems", value = "none")
                )
            )
        }

        val problemsContent = buildString {
            appendLine("Current Problems (${problems.size} issues):")
            appendLine()

            problems.forEachIndexed { index, problem ->
                appendLine("${index + 1}. [${problem.severity}] ${problem.file}:${problem.line}")
                appendLine("   ${problem.message}")
                appendLine()
            }
        }

        logger.debug { "Found ${problems.size} problems" }

        return listOf(
            ContextItem(
                description = "Problems (${problems.size} issues)",
                content = problemsContent,
                name = "Problems",
                uri = ContextUri(type = "problems", value = "current")
            )
        )
    }

    private fun collectProblems(
        project: com.intellij.openapi.project.Project,
        workspacePath: String
    ): List<ProblemInfo> {
        val problems = mutableListOf<ProblemInfo>()

        try {
            // Get problems from WolfTheProblemSolver (IntelliJ's problem tracker)
            val problemSolver = WolfTheProblemSolver.getInstance(project)
            val fileIndex = ProjectFileIndex.getInstance(project)

            // Iterate through project files and find those with problems
            val problemFiles = mutableListOf<VirtualFile>()
            fileIndex.iterateContent { virtualFile ->
                if (!virtualFile.isDirectory && problemSolver.isProblemFile(virtualFile)) {
                    problemFiles.add(virtualFile)
                }
                true // Continue iteration
            }

            logger.debug { "Found ${problemFiles.size} files with problems" }

            problemFiles.forEach { virtualFile ->
                try {
                    val relativePath = getRelativePath(workspacePath, virtualFile.path)

                    // Add problem info (WolfTheProblemSolver doesn't provide detailed problem messages)
                    // We just know the file has problems
                    problems.add(
                        ProblemInfo(
                            file = relativePath,
                            line = 0, // Line info not available from Wolf
                            severity = "ERROR",
                            message = "File contains errors (check IDE Problems panel for details)"
                        )
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to process problem file: ${virtualFile.path}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to collect problems" }
        }

        return problems.take(20) // Limit to 20 most relevant
    }

    private fun getRelativePath(basePath: String, filePath: String): String {
        return if (basePath.isNotEmpty() && filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
        } else {
            filePath
        }
    }

    data class ProblemInfo(
        val file: String,
        val line: Int,
        val severity: String,
        val message: String
    )
}

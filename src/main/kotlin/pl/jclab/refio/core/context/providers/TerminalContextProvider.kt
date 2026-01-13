package pl.jclab.refio.core.context.providers

import com.intellij.openapi.wm.ToolWindowManager
import pl.jclab.refio.core.context.*
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("TerminalContextProvider")

/**
 * Provider for terminal output.
 *
 * Usage: @terminal
 * Returns recent terminal output from active terminal session.
 *
 * Note: IntelliJ Terminal API is complex and not fully public.
 * This is a simplified implementation that may need platform-specific adjustments.
 */
class TerminalContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "terminal",
        displayTitle = "Terminal",
        description = "Recent terminal output",
        type = ProviderType.NORMAL,
        icon = "💻"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        val project = extras.project ?: return emptyList()

        logger.debug { "Getting terminal output for project: ${project.name}" }

        val terminalOutput = getTerminalOutput(project)

        return listOf(
            ContextItem(
                description = "Terminal Output",
                content = "```shell\n$terminalOutput\n```",
                name = "Terminal",
                uri = ContextUri(
                    type = "terminal",
                    value = "last-output"
                )
            )
        )
    }

    private fun getTerminalOutput(project: com.intellij.openapi.project.Project): String {
        return try {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")

            if (toolWindow == null) {
                logger.debug { "Terminal tool window not found" }
                return "[No terminal window available]"
            }

            // Note: Accessing terminal content is complex in IntelliJ Platform
            // This is a placeholder implementation
            // Full implementation would require:
            // 1. Getting TerminalView from tool window content
            // 2. Accessing terminal widget
            // 3. Reading terminal buffer content

            // For now, return a message indicating limitation
            val message = buildString {
                appendLine("Terminal content access is limited in this version.")
                appendLine("To include terminal output:")
                appendLine("1. Copy terminal output manually")
                appendLine("2. Use @clipboard to include copied content")
                appendLine()
                appendLine("Advanced terminal integration coming in future version.")
            }

            logger.debug { "Terminal output requested but not fully implemented" }
            message

        } catch (e: Exception) {
            logger.error(e) { "Failed to get terminal output" }
            "[Error accessing terminal: ${e.message}]"
        }
    }
}

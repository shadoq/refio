package pl.jclab.refio.core.context.providers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.logging.dualLogger
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

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

    private companion object {
        const val MAX_TERMINAL_LINES = 200
    }

    override val environment = ContextProviderEnvironment.IDE_ONLY

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
        val project = extras.project as? Project ?: return emptyList()

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
            val widgetRef = AtomicReference<Any?>(null)
            val statusRef = AtomicReference<String?>(null)
            val findWidgetOnEdt = {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                if (toolWindow == null) {
                    logger.debug { "Terminal tool window not found" }
                    statusRef.set("[No terminal window available]")
                } else {
                    val content = toolWindow.contentManager.selectedContent
                    val widget = content?.let { findTerminalWidget(it.component) }
                    if (widget == null) {
                        statusRef.set("[No active terminal sessions]")
                    } else {
                        widgetRef.set(widget)
                    }
                }
            }

            if (SwingUtilities.isEventDispatchThread()) {
                findWidgetOnEdt()
            } else {
                ApplicationManager.getApplication().invokeAndWait(findWidgetOnEdt, ModalityState.any())
            }

            statusRef.get()?.let { return it }
            val widget = widgetRef.get() ?: return "[No active terminal sessions]"

            val output = readWidgetOutput(widget)
            if (output.isBlank()) {
                "[Terminal output empty]"
            } else {
                output
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to get terminal output" }
            "[Error accessing terminal: ${e.message}]"
        }
    }

    private fun readWidgetOutput(widget: Any): String {
        val buffer = runCatching {
            widget.javaClass.methods.firstOrNull { it.name == "getTerminalTextBuffer" && it.parameterCount == 0 }
                ?.invoke(widget)
        }.getOrNull() ?: return ""

        val lines = mutableListOf<String>()
        lines += extractLinesFromBuffer(buffer, "getHistoryBuffer")
        lines += extractLinesFromBuffer(buffer, "getScreenBuffer")
        lines += extractLinesFromBuffer(buffer, "getScreenLines")

        if (lines.isEmpty()) {
            return buffer.toString().trim()
        }

        return lines.takeLast(MAX_TERMINAL_LINES).joinToString("\n").trim()
    }

    private fun extractLinesFromBuffer(buffer: Any, getterName: String): List<String> {
        val getter = buffer.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
            ?: return emptyList()
        val target = runCatching { getter.invoke(buffer) }.getOrNull() ?: return emptyList()
        return extractLinesFromContainer(target)
    }

    private fun extractLinesFromContainer(container: Any): List<String> {
        val linesMethod = container.javaClass.methods.firstOrNull { it.name == "getLines" && it.parameterCount == 0 }
        val linesObj = runCatching { linesMethod?.invoke(container) }.getOrNull()
        if (linesObj is List<*>) {
            return linesObj.mapNotNull { lineToString(it) }
        }

        val countMethod = container.javaClass.methods.firstOrNull { it.name == "getLineCount" && it.parameterCount == 0 }
        val getLineMethod = container.javaClass.methods.firstOrNull { it.name == "getLine" && it.parameterCount == 1 }
        val count = (runCatching { countMethod?.invoke(container) }.getOrNull() as? Int) ?: return emptyList()

        val result = ArrayList<String>(count)
        for (i in 0 until count) {
            val line = runCatching { getLineMethod?.invoke(container, i) }.getOrNull()
            lineToString(line)?.let { result.add(it) }
        }
        return result
    }

    private fun lineToString(line: Any?): String? {
        if (line == null) return null
        val textMethod = line.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
        val textObj = runCatching { textMethod?.invoke(line) }.getOrNull()
        return when (textObj) {
            is CharArray -> String(textObj).trimEnd()
            is String -> textObj.trimEnd()
            else -> line.toString().trimEnd()
        }
    }

    private fun findTerminalWidget(component: Component): Any? {
        if (isTerminalWidget(component)) return component
        if (component is Container) {
            component.components.forEach { child ->
                val found = findTerminalWidget(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun isTerminalWidget(component: Component): Boolean {
        val className = component.javaClass.name
        return className == "com.jediterm.terminal.ui.JediTermWidget"
    }
}

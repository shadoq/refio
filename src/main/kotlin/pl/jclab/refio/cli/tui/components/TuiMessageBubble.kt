package pl.jclab.refio.cli.tui.components

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiContentParser
import pl.jclab.refio.cli.tui.rendering.TuiContentSegment
import pl.jclab.refio.cli.tui.rendering.TuiMarkdown
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiMessageType
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Renders chat messages with role-based routing and content segment parsing.
 * Adapted from plugin's ChatMessageBubbleRouter + 5 specialized renderers.
 *
 * Routing:
 * - user → green header + plain text
 * - assistant → blue header + parsed segments (thinking, code blocks, markdown)
 * - system → red header + plain text
 * - agent_event → colored header with status icon + compact text
 */
object TuiMessageBubble {

    private val timeFormat = SimpleDateFormat("HH:mm:ss")

    fun render(terminal: Terminal, msg: TuiChatMessage) {
        for (line in renderToLines(terminal, msg)) {
            terminal.println(line)
        }
    }

    /** Render message to a list of lines (for buffer-based rendering). */
    fun renderToLines(terminal: Terminal, msg: TuiChatMessage): List<String> {
        return when (msg.role) {
            "user" -> renderUserMessage(msg)
            "assistant" -> renderAssistantMessage(terminal, msg)
            "system" -> renderSystemMessage(msg)
            "agent_event" -> renderAgentEvent(msg)
            else -> renderGenericMessage(msg)
        }
    }

    // --- User messages ---

    private fun renderUserMessage(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val time = timeFormat.format(Date(msg.timestamp))
        result.add(TuiColors.user("[User] $time"))
        result.addAll(msg.content.lines())
        return result
    }

    // --- Assistant messages (with content segment parsing) ---

    private fun renderAssistantMessage(terminal: Terminal, msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val name = msg.agentName ?: "Refio"
        val time = timeFormat.format(Date(msg.timestamp))
        val roleColor = if (msg.agentColorIndex != null && msg.agentColorIndex > 0) {
            TuiColors.forAgent(msg.agentColorIndex)
        } else {
            TuiColors.assistant
        }
        result.add(roleColor("[$name] $time"))

        // Parse content into segments (thinking, code, markdown)
        val segments = TuiContentParser.parse(msg.content, isStreaming = msg.isStreaming)

        if (segments.isEmpty()) {
            result.addAll(msg.content.lines())
        } else {
            for (segment in segments) {
                result.addAll(renderSegment(terminal, segment))
            }
        }

        if (msg.isStreaming) {
            result.add(TuiColors.streaming("  ... streaming ..."))
        }

        // Per-message metrics (tokens/cost) — shown after content completes
        if (!msg.isStreaming && (msg.tokensIn > 0 || msg.tokensOut > 0)) {
            val tokIn = msg.tokensIn
            val tokOut = msg.tokensOut
            val cost = String.format("%.4f", msg.costUsd)
            result.add(TuiColors.muted("  [$tokIn in / $tokOut out, \$$cost]"))
        }

        return result
    }

    private fun renderSegment(terminal: Terminal, segment: TuiContentSegment): List<String> {
        return when (segment) {
            is TuiContentSegment.Thinking -> renderThinkingSegment(segment)
            is TuiContentSegment.Code -> renderCodeSegment(segment)
            is TuiContentSegment.Json -> renderJsonSegment(segment)
            is TuiContentSegment.Markdown -> renderMarkdownSegment(terminal, segment)
        }
    }

    private fun renderThinkingSegment(segment: TuiContentSegment.Thinking): List<String> {
        val result = mutableListOf<String>()
        result.add(TuiColors.muted("  [thinking]"))
        for (line in segment.content.lines()) {
            result.add(TuiColors.muted("  $line"))
        }
        return result
    }

    private fun renderCodeSegment(segment: TuiContentSegment.Code): List<String> {
        val result = mutableListOf<String>()
        val lines = segment.content.lines()
        val langLabel = segment.language
        val pathLabel = segment.filePath?.let { ": $it" } ?: ""

        // Top border with language/path
        result.add(TuiColors.border("  ┌─ $langLabel$pathLabel ${"─".repeat(40)}"))

        // Code lines with line numbers
        for ((i, line) in lines.withIndex()) {
            val lineNum = (i + 1).toString().padStart(3)
            result.add(TuiColors.border("  │") + TuiColors.muted("$lineNum ") + line)
        }

        // Bottom border
        result.add(TuiColors.border("  └${"─".repeat(50)}"))

        return result
    }

    private fun renderJsonSegment(segment: TuiContentSegment.Json): List<String> {
        val result = mutableListOf<String>()
        result.add(TuiColors.border("  ┌─ json ${"─".repeat(43)}"))
        for (line in segment.content.lines()) {
            result.add(TuiColors.border("  │ ") + TuiColors.accent(line))
        }
        result.add(TuiColors.border("  └${"─".repeat(50)}"))
        return result
    }

    private fun renderMarkdownSegment(terminal: Terminal, segment: TuiContentSegment.Markdown): List<String> {
        val text = segment.content.trim()
        if (text.isEmpty()) return emptyList()

        // Use Mordant for markdown rendering if content has formatting
        return if (text.contains("**") || text.contains("- ") || text.contains("# ") || text.contains("| ")) {
            TuiMarkdown.renderToString(terminal, text).lines()
        } else {
            text.lines()
        }
    }

    // --- System messages ---

    private fun renderSystemMessage(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val time = timeFormat.format(Date(msg.timestamp))
        result.add(TuiColors.system("[System] $time"))
        result.addAll(msg.content.lines().map { TuiColors.system(it) })
        return result
    }

    // --- Agent events (compact, single-line when possible) ---

    private fun renderAgentEvent(msg: TuiChatMessage): List<String> {
        val roleColor = msg.agentColorIndex?.let { TuiColors.forAgent(it) } ?: TuiColors.tool
        val name = msg.agentName ?: msg.agentId ?: "agent"
        val time = timeFormat.format(Date(msg.timestamp))

        val (icon, statusColor) = when (msg.messageType) {
            TuiMessageType.AGENT_STARTED -> "▶" to TuiColors.statusRunning
            TuiMessageType.AGENT_COMPLETED -> "✓" to TuiColors.statusSuccess
            TuiMessageType.AGENT_FAILED -> "✗" to TuiColors.statusFailed
            TuiMessageType.APPROVAL_REQUEST -> "?" to TuiColors.statusPending
            TuiMessageType.ARTIFACT -> "★" to TuiColors.accent
            TuiMessageType.DATA_EXCHANGE -> "↔" to TuiColors.muted
            TuiMessageType.TEXT -> "·" to roleColor
        }

        return listOf(
            statusColor("$icon ") + roleColor("[$name] ") + TuiColors.muted(time) + " " + msg.content
        )
    }

    // --- Generic fallback ---

    private fun renderGenericMessage(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val time = timeFormat.format(Date(msg.timestamp))
        result.add(TuiColors.tool("[${msg.role}] $time"))
        result.addAll(msg.content.lines())
        return result
    }
}

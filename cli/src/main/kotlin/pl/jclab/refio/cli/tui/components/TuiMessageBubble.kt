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
        // Special message types get dedicated rendering
        return when (msg.messageType) {
            TuiMessageType.TOOL_CALL -> renderToolCallMessage(msg)
            TuiMessageType.EXECUTION_SUMMARY -> renderExecutionSummary(msg)
            TuiMessageType.ORCHESTRATOR_QUESTION -> renderOrchestratorQuestion(msg)
            TuiMessageType.PLAN -> renderPlanMessage(msg)
            else -> when (msg.role) {
                "user" -> renderUserMessage(msg)
                "assistant" -> renderAssistantMessage(terminal, msg)
                "system" -> renderSystemMessage(msg)
                "agent_event" -> renderAgentEvent(msg)
                else -> renderGenericMessage(msg)
            }
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

    /** Track which thinking blocks are expanded (by content hash). */
    private val expandedThinkingBlocks = mutableSetOf<Int>()

    fun toggleThinkingBlock(contentHash: Int) {
        if (contentHash in expandedThinkingBlocks) {
            expandedThinkingBlocks.remove(contentHash)
        } else {
            expandedThinkingBlocks.add(contentHash)
        }
    }

    private fun renderThinkingSegment(segment: TuiContentSegment.Thinking): List<String> {
        val result = mutableListOf<String>()
        val lineCount = segment.content.lines().size
        val hash = segment.content.hashCode()
        val isExpanded = hash in expandedThinkingBlocks

        if (isExpanded) {
            result.add(TuiColors.muted("  ▼ [thinking] $lineCount lines"))
            for (line in segment.content.lines()) {
                result.add(TuiColors.muted("    $line"))
            }
        } else {
            // Collapsed: show first line preview
            val preview = segment.content.lines().firstOrNull()?.take(60) ?: ""
            result.add(TuiColors.muted("  ▶ [thinking] $lineCount lines — ${preview}..."))
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

        // Code lines with line numbers and basic syntax highlighting
        for ((i, line) in lines.withIndex()) {
            val lineNum = (i + 1).toString().padStart(3)
            val highlighted = highlightCodeLine(line, langLabel)
            result.add(TuiColors.border("  │") + TuiColors.muted("$lineNum ") + highlighted)
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
        // Approval requests get a richer inline rendering
        if (msg.messageType == TuiMessageType.APPROVAL_REQUEST) {
            return renderApprovalInline(msg)
        }

        val roleColor = msg.agentColorIndex?.let { TuiColors.forAgent(it) } ?: TuiColors.tool
        val name = msg.agentName ?: msg.agentId ?: "agent"
        val time = timeFormat.format(Date(msg.timestamp))

        val (icon, statusColor) = when (msg.messageType) {
            TuiMessageType.AGENT_STARTED -> "▶" to TuiColors.statusRunning
            TuiMessageType.AGENT_COMPLETED -> "✓" to TuiColors.statusSuccess
            TuiMessageType.AGENT_FAILED -> "✗" to TuiColors.statusFailed
            TuiMessageType.ARTIFACT -> "★" to TuiColors.accent
            TuiMessageType.DATA_EXCHANGE -> "↔" to TuiColors.muted
            else -> "·" to roleColor
        }

        return listOf(
            statusColor("$icon ") + roleColor("[$name] ") + TuiColors.muted(time) + " " + msg.content
        )
    }

    private fun renderApprovalInline(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val roleColor = msg.agentColorIndex?.let { TuiColors.forAgent(it) } ?: TuiColors.tool
        val name = msg.agentName ?: msg.agentId ?: "agent"
        val time = timeFormat.format(Date(msg.timestamp))

        result.add(TuiColors.statusPending("  ? ") + roleColor("[$name] ") + TuiColors.muted(time) + " " + TuiColors.statusPending("Approval Required"))

        // Show action details from content
        for (line in msg.content.lines()) {
            if (line.isNotBlank()) {
                result.add(TuiColors.highlight("    $line"))
            }
        }

        // Show risk level from metadata if available
        val risk = msg.metadata["risk"] as? String
        if (risk != null) {
            result.add(TuiColors.muted("    Risk: ") + when (risk.lowercase()) {
                "high" -> TuiColors.statusFailed(risk)
                "medium" -> TuiColors.statusPending(risk)
                else -> TuiColors.statusSuccess(risk)
            })
        }

        // Inline action hint
        result.add(TuiColors.muted("    ") + TuiColors.statusPending("[y]") + TuiColors.muted("es  ") +
                TuiColors.statusFailed("[n]") + TuiColors.muted("o  ") +
                TuiColors.statusSuccess("[t]") + TuiColors.muted("rust agent"))

        return result
    }

    // --- Tool call messages ---

    private fun renderToolCallMessage(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val time = timeFormat.format(Date(msg.timestamp))
        val toolLabel = msg.toolName ?: "tool"
        result.add(TuiColors.tool("  ⚙ [$toolLabel] ") + TuiColors.muted(time))

        val content = msg.content.trim()
        if (content.isNotEmpty()) {
            // Show truncated result (first 5 lines max)
            val lines = content.lines()
            val displayLines = if (lines.size > 5) lines.take(5) + listOf("... (${lines.size - 5} more lines)") else lines
            for (line in displayLines) {
                result.add(TuiColors.muted("    $line"))
            }
        }
        return result
    }

    // --- Execution summary ---

    private fun renderExecutionSummary(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val time = timeFormat.format(Date(msg.timestamp))
        result.add(TuiColors.statusSuccess("  ✓ [Execution Summary] ") + TuiColors.muted(time))
        for (line in msg.content.lines()) {
            result.add("    $line")
        }
        if (msg.tokensIn > 0 || msg.tokensOut > 0) {
            val cost = String.format("%.4f", msg.costUsd)
            result.add(TuiColors.muted("    [${msg.tokensIn} in / ${msg.tokensOut} out, \$$cost]"))
        }
        return result
    }

    // --- Orchestrator question ---

    private fun renderOrchestratorQuestion(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val time = timeFormat.format(Date(msg.timestamp))
        result.add(TuiColors.statusPending("  ? [Question] ") + TuiColors.muted(time))
        for (line in msg.content.lines()) {
            result.add(TuiColors.highlight("    $line"))
        }
        result.add(TuiColors.muted("    (Type your answer in the prompt below)"))
        return result
    }

    // --- Plan messages ---

    private fun renderPlanMessage(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val time = timeFormat.format(Date(msg.timestamp))
        result.add(TuiColors.accent("  📋 [Plan] ") + TuiColors.muted(time))
        for (line in msg.content.lines()) {
            result.add("    $line")
        }
        return result
    }

    // --- Generic fallback ---

    private fun renderGenericMessage(msg: TuiChatMessage): List<String> {
        val result = mutableListOf<String>()
        val time = timeFormat.format(Date(msg.timestamp))
        result.add(TuiColors.tool("[${msg.role}] $time"))
        result.addAll(msg.content.lines())
        return result
    }

    // --- Basic syntax highlighting ---

    private val KEYWORDS_JVM = setOf(
        "fun", "val", "var", "class", "interface", "object", "data", "sealed", "enum",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "try", "catch", "finally", "throw", "import", "package", "private", "public",
        "protected", "internal", "abstract", "override", "open", "suspend", "inline",
        "companion", "init", "constructor", "this", "super", "is", "as", "in", "out",
        "null", "true", "false", "const", "lateinit", "by", "lazy",
        // Java additions
        "static", "final", "void", "new", "extends", "implements", "throws",
        "instanceof", "synchronized", "volatile", "transient"
    )

    private val KEYWORDS_JS = setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "do",
        "return", "break", "continue", "try", "catch", "finally", "throw",
        "import", "export", "default", "from", "class", "extends", "new",
        "this", "super", "typeof", "instanceof", "null", "undefined",
        "true", "false", "async", "await", "yield", "of", "in",
        "interface", "type", "enum", "readonly", "as", "implements"
    )

    private val KEYWORDS_PY = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return",
        "import", "from", "as", "try", "except", "finally", "raise", "with",
        "pass", "break", "continue", "yield", "lambda", "and", "or", "not",
        "in", "is", "None", "True", "False", "self", "async", "await",
        "global", "nonlocal", "del", "assert"
    )

    private fun getKeywords(lang: String): Set<String> = when (lang.lowercase()) {
        "kotlin", "kt", "java" -> KEYWORDS_JVM
        "javascript", "js", "typescript", "ts", "tsx", "jsx" -> KEYWORDS_JS
        "python", "py" -> KEYWORDS_PY
        else -> KEYWORDS_JVM + KEYWORDS_JS // fallback: combined
    }

    /** Basic keyword + string + comment highlighting using ANSI colors. */
    private fun highlightCodeLine(line: String, language: String): String {
        if (line.isBlank()) return line

        val keywords = getKeywords(language)
        val sb = StringBuilder()
        var i = 0
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length

        // Preserve leading whitespace
        if (indent > 0) sb.append(line.substring(0, indent))

        // Single-line comment detection
        val commentStart = when {
            trimmed.startsWith("//") -> 0
            trimmed.startsWith("#") && language.lowercase() in listOf("python", "py", "bash", "sh", "yaml", "yml") -> 0
            else -> -1
        }
        if (commentStart == 0) {
            sb.append(TuiColors.muted(trimmed))
            return sb.toString()
        }

        // Token-based highlighting
        val text = trimmed
        var j = 0
        while (j < text.length) {
            val ch = text[j]

            // String literals
            if (ch == '"' || ch == '\'') {
                val end = text.indexOf(ch, j + 1)
                val strEnd = if (end >= 0) end + 1 else text.length
                sb.append(TuiColors.statusSuccess(text.substring(j, strEnd)))
                j = strEnd
                continue
            }

            // Numbers
            if (ch.isDigit() && (j == 0 || !text[j - 1].isLetterOrDigit())) {
                var numEnd = j
                while (numEnd < text.length && (text[numEnd].isDigit() || text[numEnd] == '.' || text[numEnd] == 'x' || text[numEnd] == 'L' || text[numEnd] == 'f')) numEnd++
                sb.append(TuiColors.statusPending(text.substring(j, numEnd)))
                j = numEnd
                continue
            }

            // Words (potential keywords)
            if (ch.isLetter() || ch == '_') {
                var wordEnd = j
                while (wordEnd < text.length && (text[wordEnd].isLetterOrDigit() || text[wordEnd] == '_')) wordEnd++
                val word = text.substring(j, wordEnd)
                if (word in keywords) {
                    sb.append(TuiColors.accent(word))
                } else if (word[0].isUpperCase()) {
                    sb.append(TuiColors.highlight(word)) // Type names
                } else {
                    sb.append(word)
                }
                j = wordEnd
                continue
            }

            // Inline comment
            if (ch == '/' && j + 1 < text.length && text[j + 1] == '/') {
                sb.append(TuiColors.muted(text.substring(j)))
                break
            }

            sb.append(ch)
            j++
        }
        return sb.toString()
    }
}

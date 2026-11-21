package pl.jclab.refio.cli.tui.components

import pl.jclab.refio.cli.tui.rendering.Frame
import pl.jclab.refio.cli.tui.rendering.FrameState
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.FolderTrustRule
import pl.jclab.refio.cli.tui.state.ToolRowStatus
import pl.jclab.refio.cli.tui.state.TranscriptBuilder
import pl.jclab.refio.cli.tui.state.TranscriptItem
import pl.jclab.refio.cli.tui.state.TuiPendingApproval
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.state.TuiToolApprovalRequest
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor
import pl.jclab.refio.core.services.turn.ProposedChange

/**
 * Approval asked for inside the conversation (at its end), not in a window over it: what the tool will do,
 * the diff of a file change with long unchanged runs folded, and the choices as keys. The same shape serves
 * a tool set to ASK and an action of an agent in a multi-agent run.
 */
object ApprovalFrame {

    /** Text put in the prompt by `n`, so the user can say what to do instead. */
    const val CHANGE_REQUEST_PREFIX = "Change request: "

    private const val DIFF_MAX_LINES = 12
    private const val FOLD_RUN = 4

    data class Choice(val key: Char, val label: String)

    private const val INACTIVE_HINT = "The prompt has text: send or clear it to choose here."

    fun isPending(state: TuiState): Boolean = state.pendingToolApproval != null || state.pendingApprovals.isNotEmpty()

    /** The choice keys act only while the prompt is empty and the chat, not a panel, has the keys. */
    fun keysActive(state: TuiState): Boolean =
        state.inputBuffer.isEmpty() && !state.modelSelectorVisible && !(state.panelFocused && state.activeTab != TuiTab.CHAT)

    fun choices(state: TuiState): List<Choice> {
        val agent = state.pendingApprovals.firstOrNull()
        if (agent != null) {
            return listOf(
                Choice('y', "yes"),
                Choice('a', "yes, and trust ${agent.agentName} for this session"),
                Choice('n', "no"),
            )
        }
        val req = state.pendingToolApproval ?: return emptyList()
        val path = req.arguments["path"] as? String
        val allow = when {
            path == null -> "yes, and allow ${req.toolName} for this session"
            else -> FolderTrustRule.folderOf(path)?.let { "yes, and allow ${req.toolName} in $it for this session" }
                ?: "yes (this path cannot become a folder rule, so only this once)"
        }
        return listOf(
            Choice('y', "yes"),
            Choice('a', allow),
            Choice('d', if (req.proposedChange != null) "open the full diff first" else "open the whole call first"),
            Choice('n', "no, tell refio what to change"),
        )
    }

    /** Every argument of the call as `name: value`, for the full view behind `d`. */
    fun callText(req: TuiToolApprovalRequest): String =
        TuiRenderBuffer.cleanText(req.arguments.entries.joinToString("\n") { (name, value) -> "$name: $value" })

    /**
     * The frame, at most [maxRows] rows high (the chat area), so its title and the target of the call stay in view:
     * the diff or the argument list gives way first and says how to see the rest.
     */
    fun render(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs, maxRows: Int = Int.MAX_VALUE): List<String> {
        val frame = Frame(theme, glyphs)
        val inner = frame.innerSize(width, 5).first
        val agent = state.pendingApprovals.firstOrNull()
        val req = state.pendingToolApproval
        val active = keysActive(state)
        val all = choices(state)
        // Borders, the rule above the choices, the choices and the hint when the keys are inactive.
        val contentRows = (maxRows - 3 - all.size - (if (active) 0 else 1)).coerceAtLeast(2)
        val (title, meta, content) = when {
            agent != null -> Triple("${glyphs.attention} approve agent action", "${agent.agentName} · risk ${agent.risk}", agentContent(agent, inner, theme))
            req != null -> {
                val kind = if (req.proposedChange != null) "write" else "tool"
                Triple("${glyphs.attention} approve $kind", "${req.toolName} is set to ASK in ${state.mode}", toolContent(req, inner, theme, glyphs, contentRows))
            }
            else -> return emptyList()
        }
        val rule = theme.style(glyphs.horizontal.repeat(inner.coerceAtMost(40)), fg = ThemeColor.FRAME)
        val choiceRows = all.mapIndexed { i, choice ->
            val selected = active && i == state.approvalChoice.coerceIn(0, all.lastIndex)
            val marker = if (selected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
            val text = TextFit.truncateEnd(choice.label, (inner - 5).coerceAtLeast(1), glyphs.ellipsis)
            marker + " " + theme.style(choice.key.toString(), fg = if (active) ThemeColor.WARN else ThemeColor.DIM, bold = active) + "  " +
                theme.style(text, fg = if (selected) ThemeColor.ACCENT else if (active) ThemeColor.TEXT else ThemeColor.DIM, bold = selected)
        }
        val hint = if (active) null else theme.style(TextFit.truncateEnd(INACTIVE_HINT, inner, glyphs.ellipsis), fg = ThemeColor.DIM)
        val body = content + rule + choiceRows + listOfNotNull(hint)
        return frame.render(width, body.size + 2, title, meta, FrameState.WARNING, body)
    }

    /** Tool calls since the last prompt, for the right column while an approval waits. */
    fun thisTurnLines(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs): List<String> {
        val visible = TranscriptBuilder.visibleMessages(state.messages, state.agentFilter)
        val turn = visible.drop(visible.indexOfLast { it.role == "user" } + 1)
        val rows = TranscriptBuilder.build(turn).filterIsInstance<TranscriptItem.Tools>().flatMap { it.rows }
        if (rows.isEmpty()) return listOf(theme.style("no tool calls yet", fg = ThemeColor.DIM))
        val nameWidth = rows.maxOf { it.toolName.length }.coerceAtMost((width / 2).coerceAtLeast(4))
        return rows.map { row ->
            val (glyph, colour) = when (row.status) {
                ToolRowStatus.RUNNING -> glyphs.running to ThemeColor.INFO
                ToolRowStatus.DONE -> glyphs.done to ThemeColor.OK
                ToolRowStatus.FAILED -> glyphs.failed to ThemeColor.ERR
                ToolRowStatus.RECOVERED -> glyphs.failed to ThemeColor.DIM
                ToolRowStatus.NO_RESULT -> glyphs.pending to ThemeColor.DIM
            }
            val name = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(row.toolName, nameWidth, glyphs.ellipsis), nameWidth)
            val argument = TextFit.truncateMiddle(row.argument, (width - nameWidth - 3).coerceAtLeast(0), glyphs.ellipsis)
            theme.style(glyph, fg = colour) + " " + theme.style(name, fg = ThemeColor.WARN) + " " + theme.style(argument, fg = ThemeColor.DIM)
        }
    }

    /** Session rules made with `a`: tool and the folder it may work in, or "everywhere". */
    fun allowedLines(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs): List<String> {
        if (state.trustRules.isEmpty()) return listOf(theme.style(TextFit.truncateEnd("nothing yet: a adds a rule", width, glyphs.ellipsis), fg = ThemeColor.DIM))
        val nameWidth = state.trustRules.maxOf { it.toolName.length }.coerceAtMost((width / 2).coerceAtLeast(4))
        return state.trustRules.map { rule ->
            val scope = when (val pattern = rule.argsPattern) {
                null -> "everywhere"
                else -> FolderTrustRule.describe(pattern) ?: "matching ${pattern}"
            }
            val name = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(rule.toolName, nameWidth, glyphs.ellipsis), nameWidth)
            theme.style(glyphs.done, fg = ThemeColor.OK) + " " + theme.style(name, fg = ThemeColor.TEXT) + " " +
                theme.style(TextFit.truncateMiddle(scope, (width - nameWidth - 3).coerceAtLeast(0), glyphs.ellipsis), fg = ThemeColor.DIM)
        }
    }

    private fun agentContent(agent: TuiPendingApproval, inner: Int, theme: Theme): List<String> {
        val details = agent.details.entries.take(3).map { (k, v) -> theme.style(TextFit.truncateEnd("$k: $v", inner), fg = ThemeColor.DIM) }
        return listOf(theme.style(TextFit.truncateEnd(agent.action, inner), fg = ThemeColor.TEXT, bold = true)) + details
    }

    /** Head line, then the diff of a write or every argument of any other call, in at most [maxRows] rows. */
    private fun toolContent(req: TuiToolApprovalRequest, inner: Int, theme: Theme, glyphs: Glyphs, maxRows: Int): List<String> {
        val change = req.proposedChange
        val target = TuiRenderBuffer.cleanText(change?.filePath ?: TranscriptBuilder.mainArgument(argumentsJson(req.arguments)))
        val head = mutableListOf(theme.style(req.toolName, fg = ThemeColor.WARN, bold = true))
        if (target.isNotBlank()) head += theme.style(TextFit.truncateMiddle(target, (inner / 2).coerceAtLeast(8), glyphs.ellipsis), fg = ThemeColor.TEXT)
        if (change != null) {
            val (added, removed) = counts(change)
            // The core gives a new file an empty old content; null means the old file was too large to carry.
            head += theme.style(if (change.oldContent?.isEmpty() == true) "new file" else "edit", fg = ThemeColor.DIM)
            head += theme.style("+$added", fg = ThemeColor.OK) + " " + theme.style("−$removed", fg = ThemeColor.ERR)
        }
        val lines = mutableListOf(TuiRenderBuffer.fitToWidth(head.joinToString("  "), inner).trimEnd())
        if (change == null && req.description.isNotBlank() && maxRows > 3) {
            lines += theme.style(TextFit.truncateEnd(TuiRenderBuffer.cleanText(req.description).replace('\n', ' '), inner, glyphs.ellipsis), fg = ThemeColor.DIM)
        }
        lines += theme.style(glyphs.horizontal.repeat(inner.coerceAtMost(40)), fg = ThemeColor.FRAME)
        val room = (maxRows - lines.size).coerceAtLeast(1)
        lines += if (change != null) diffLines(change, inner, theme, glyphs, room) else argumentLines(req, inner, theme, glyphs, room)
        return lines
    }

    /** `name: value` for every argument, wrapped, so a long or multi-line command is read in full before it runs. */
    private fun argumentLines(req: TuiToolApprovalRequest, inner: Int, theme: Theme, glyphs: Glyphs, maxRows: Int): List<String> {
        val all = callText(req).lines().flatMap { line -> TextFit.wrapWords(line, inner).ifEmpty { listOf("") } }
        if (all.size <= maxRows) return all.map { theme.style(it, fg = ThemeColor.TEXT) }
        val shown = (maxRows - 1).coerceAtLeast(0)
        val tail = "${glyphs.ellipsis} ${all.size - shown} more lines · d open the whole call"
        return all.take(shown).map { theme.style(it, fg = ThemeColor.TEXT) } + theme.style(TextFit.truncateEnd(tail, inner, glyphs.ellipsis), fg = ThemeColor.DIM)
    }

    private fun argumentsJson(arguments: Map<String, Any>): String =
        "{" + arguments.entries.joinToString(",") { (k, v) -> "\"$k\":\"${v.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\"" } + "}"

    private fun counts(change: ProposedChange): Pair<Int, Int> {
        val lines = change.unifiedDiff.lines()
        val added = lines.count { it.startsWith("+") && !it.startsWith("+++") }
        val removed = lines.count { it.startsWith("-") && !it.startsWith("---") }
        return added to removed
    }

    /**
     * Diff body with new-file line numbers in at most [maxRows] rows (and never more than [DIFF_MAX_LINES] diff
     * lines); unchanged runs longer than four lines fold into one line.
     */
    internal fun diffLines(change: ProposedChange, inner: Int, theme: Theme, glyphs: Glyphs, maxRows: Int = DIFF_MAX_LINES + 1): List<String> {
        data class Row(val number: Int?, val sign: Char, val text: String)
        val rows = mutableListOf<Row>()
        var next = 1
        for (line in TuiRenderBuffer.cleanText(change.unifiedDiff).trimEnd('\n').lines()) {
            when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("\\") -> {}
                line.startsWith("@@") -> next = Regex("""\+(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: next
                // The core writes a sign and a space before each line ("+ x", "- x", "  x").
                line.startsWith("+") -> rows += Row(next++, '+', line.drop(1).removePrefix(" "))
                line.startsWith("-") -> rows += Row(null, '-', line.drop(1).removePrefix(" "))
                line.isNotEmpty() || rows.isNotEmpty() -> rows += Row(next++, ' ', line.drop(1).removePrefix(" "))
            }
        }
        // Fold long unchanged runs.
        val folded = mutableListOf<Any>() // Row or Int (number of folded lines)
        var i = 0
        while (i < rows.size) {
            if (rows[i].sign != ' ') { folded += rows[i]; i++; continue }
            var j = i
            while (j < rows.size && rows[j].sign == ' ') j++
            val run = j - i
            if (run > FOLD_RUN) {
                folded += rows[i]
                folded += run - 2
                folded += rows[j - 1]
            } else {
                for (k in i until j) folded += rows[k]
            }
            i = j
        }
        val numberWidth = (rows.mapNotNull { it.number }.maxOrNull() ?: 1).toString().length
        val fits = folded.size <= minOf(DIFF_MAX_LINES, maxRows) && !change.diffTruncated
        val shown = if (fits) folded.size else minOf(DIFF_MAX_LINES, maxRows - 1).coerceAtLeast(0)
        val out = folded.take(shown).map { item ->
            if (item is Int) return@map theme.style("${" ".repeat(numberWidth)}   ${glyphs.ellipsis} $item lines folded", fg = ThemeColor.DIM)
            val row = item as Row
            val number = (row.number?.toString() ?: "").padStart(numberWidth)
            val colour = when (row.sign) {
                '+' -> ThemeColor.OK
                '-' -> ThemeColor.ERR
                else -> ThemeColor.DIM
            }
            theme.style(number, fg = ThemeColor.DIM) + " " + theme.style(TextFit.truncateEnd("${row.sign} ${row.text.replace('\t', ' ')}", (inner - numberWidth - 1).coerceAtLeast(1), glyphs.ellipsis), fg = colour)
        }
        val hidden = folded.size - shown
        val tail = when {
            hidden > 0 -> "${glyphs.ellipsis} $hidden more lines · d open the full diff"
            change.diffTruncated -> "${glyphs.ellipsis} diff shortened · d open the full diff"
            else -> null
        }
        return out + listOfNotNull(tail?.let { theme.style(TextFit.truncateEnd(it, inner, glyphs.ellipsis), fg = ThemeColor.DIM) })
    }
}

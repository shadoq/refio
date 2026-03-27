package pl.jclab.refio.cli.tui.rendering

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.components.TuiPromptInput
import pl.jclab.refio.cli.tui.screens.TuiHelpScreen
import pl.jclab.refio.cli.tui.screens.TuiHistoryScreen
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.views.*
import java.io.Writer

/**
 * Main rendering engine for split-pane TUI.
 *
 * ALL rendering goes through [TuiScreenBuffer] — a full-screen framebuffer.
 * Base content is rendered first, then overlays (autocomplete, modals) are
 * painted on top. The entire composed frame is flushed to the terminal in
 * one atomic write — no partial draws, no cursor repositioning between draws.
 *
 * All output goes through the JLine terminal's writer to ensure ANSI control
 * sequences are sent through the same channel as the terminal's input handling.
 * Mordant Terminal is kept only for text styling (render Markdown widgets).
 *
 * Layout:
 * ```
 * ┌─F1:Chat│F2:Steps│...│F8:Set  [CHAT|default] streaming... $0.02│5K tok [Ctrl+Q]─┐
 * ├────────────────────────────────────────────────────────────────────────────────────┤
 * │ Chat messages              │ Right panel (active tab)                              │
 * │ (scrollable)               │ Steps / Context / RAG / ...                           │
 * ├────────────────────────────┤                                                       │
 * │ [CHAT] [model]             │                                                       │
 * │ > input here_              │                                                       │
 * └────────────────────────────┴───────────────────────────────────────────────────────┘
 * ```
 */
class TuiRenderer(
    val terminal: Terminal,
    private val jlineTerminal: org.jline.terminal.Terminal
) {

    /** Single output channel — all ANSI goes through JLine's writer. */
    private val output: Writer = jlineTerminal.writer()

    private var lastRenderedHash: Int = 0
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0

    private fun rawWrite(s: String) {
        output.write(s)
        output.flush()
    }

    fun showLoading(message: String) {
        val text = TuiColors.accent(message)
        rawWrite("\u001b[H${text}\u001b[K")
    }

    fun showError(message: String) {
        val text = TuiColors.system("Error: $message")
        rawWrite("\u001b[H${text}\u001b[K")
    }

    fun enterFullScreen() {
        rawWrite(
            "\u001b[?1049h" + // alternate screen buffer
            "\u001b[?7l" +    // disable auto-wrap — prevents line wrapping from creating scroll
            "\u001b[2J\u001b[H" // clear screen + home
        )
    }

    fun exitFullScreen() {
        rawWrite(
            "\u001b[?25h" +    // show cursor
            "\u001b[?7h" +     // re-enable auto-wrap
            "\u001b[?1049l"    // leave alternate screen buffer
        )
    }

    /**
     * Force a full re-render (e.g. on terminal resize).
     */
    fun forceRender(state: TuiState) {
        lastRenderedHash = 0
        lastWidth = 0
        lastHeight = 0
        render(state)
    }

    fun render(state: TuiState) {
        val width = jlineTerminal.width
        val height = jlineTerminal.height
        val resized = width != lastWidth || height != lastHeight
        lastWidth = width
        lastHeight = height

        val hash = state.hashCode()
        if (hash == lastRenderedHash && !resized) return
        lastRenderedHash = hash

        val isSplit = state.screen == TuiScreen.MAIN && state.activeTab != TuiTab.CHAT
        val effectiveHeight = height.coerceAtLeast(TuiLayoutRegions.MIN_HEIGHT)
        val layout = TuiLayoutRegions.fromTerminal(width, effectiveHeight, isSplitMode = isSplit)

        // === 1. Build framebuffer ===
        val screen = TuiScreenBuffer(layout.width, layout.height)

        // Row 0: tab bar
        screen.setRow(0, buildTabBarLine(state, layout))
        // Row 1: separator
        screen.setRow(1, TuiColors.border("─".repeat(layout.width)))

        // Rows 2..height-1: content area (exactly contentHeight lines)
        val contentStartRow = layout.tabBarHeight + layout.separatorHeight
        val contentLines = when (state.screen) {
            TuiScreen.MAIN -> buildMainScreenLines(state, layout)
            TuiScreen.HISTORY -> TuiHistoryScreen.renderToLines(state, layout.width, layout.contentHeight)
            TuiScreen.SETTINGS -> TuiSettingsScreen.renderToLines(state, layout.width, layout.contentHeight)
            TuiScreen.HELP -> TuiHelpScreen.renderToLines(state, layout.width, layout.contentHeight)
        }
        screen.setRows(contentStartRow, contentLines)

        // === 2. Apply overlays ===
        if (state.screen == TuiScreen.MAIN && state.autocompleteVisible && state.autocompleteCandidates.isNotEmpty()) {
            applyAutocompleteOverlay(screen, state, layout)
        }

        // Model selector overlay
        if (state.modelSelectorVisible && state.modelSelectorCandidates.isNotEmpty()) {
            applyModelSelectorOverlay(screen, state, layout)
        }

        // Approval dialog overlay (takes priority over autocomplete)
        if (state.pendingApprovals.isNotEmpty()) {
            applyApprovalOverlay(screen, state, layout)
        }

        // === 3. Flush to terminal (through JLine writer) ===
        screen.flush(output, clearScreen = resized)

        // === 4. Position cursor ===
        if (state.screen == TuiScreen.MAIN) {
            // Calculate cursor position within multi-line input
            val prefixLen = 2 // "> "
            val editableWidth = (if (layout.isSplitMode) layout.leftPanelWidth else layout.contentWidth) - prefixLen
            val (cursorRow, cursorCol) = TuiPromptInput.getCursorRowCol(
                state.inputBuffer, state.cursorPosition, editableWidth.coerceAtLeast(10)
            )
            // The input lines start at (contentHeight - promptLines + status/separator lines)
            // The last N lines of contentHeight are the prompt, and input starts after separator+status
            val promptLines = TuiPromptInput.renderToLines(state, editableWidth + prefixLen)
            val inputStartLine = promptLines.size - // total prompt lines
                (promptLines.size - 2).coerceAtLeast(1) // lines that are input (subtract separator+status)
            val inputRow = layout.tabBarHeight + layout.separatorHeight + layout.contentHeight -
                promptLines.size + 2 + cursorRow + inputStartLine
            val inputCol = (if (cursorRow == 0) prefixLen + 1 else 2 + 1) + cursorCol // prefix width + col
            screen.positionCursorAndShow(output, inputRow, inputCol)
        } else {
            screen.showCursor(output)
        }
    }

    // === Content builders ===

    private fun buildMainScreenLines(state: TuiState, layout: TuiLayoutRegions): List<String> {
        return if (!layout.isSplitMode) {
            buildFullWidthChatLines(state, layout)
        } else {
            buildSplitPaneLines(state, layout)
        }
    }

    private fun buildFullWidthChatLines(state: TuiState, layout: TuiLayoutRegions): List<String> {
        val promptBuf = TuiChatView.renderPrompt(state, layout.contentWidth)
        val actualPromptHeight = promptBuf.lineCount
        val chatHeight = (layout.contentHeight - actualPromptHeight).coerceAtLeast(3)
        val chatBuf = TuiChatView.renderMessages(terminal, state, layout.contentWidth, chatHeight)
        return chatBuf.getVisibleLines() + promptBuf.getLines()
    }

    private fun buildSplitPaneLines(state: TuiState, layout: TuiLayoutRegions): List<String> {
        val leftW = layout.leftPanelWidth
        val rightW = layout.rightPanelWidth

        val promptBuf = TuiChatView.renderPrompt(state, leftW)
        val actualPromptHeight = promptBuf.lineCount
        val chatHeight = (layout.contentHeight - actualPromptHeight).coerceAtLeast(3)
        val chatBuf = TuiChatView.renderMessages(terminal, state, leftW, chatHeight)
        val leftLines = chatBuf.getVisibleLines() + promptBuf.getLines()

        val rightBuf = renderRightPanel(state, rightW, layout.contentHeight)
        val rightLines = rightBuf.getVisibleLines()

        return TuiRenderBuffer.mergeSideBySide(
            left = leftLines, leftWidth = leftW,
            right = rightLines, rightWidth = rightW,
            separator = TuiColors.border("│")
        )
    }

    private fun renderRightPanel(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        return when (state.activeTab) {
            TuiTab.CHAT -> TuiRenderBuffer(width, height)
            TuiTab.STEPS -> TuiStepsView.renderToBuffer(state, width, height)
            TuiTab.CONTEXT -> TuiContextView.renderToBuffer(state, width, height)
            TuiTab.RAG -> TuiRagView.renderToBuffer(state, width, height)
            TuiTab.LOGS -> TuiLogsView.renderToBuffer(state, width, height)
            TuiTab.DEBUG -> TuiDebugView.renderToBuffer(state, width, height)
            TuiTab.API_LOGS -> TuiApiLogsView.renderToBuffer(state, width, height)
            TuiTab.FILES -> TuiFilesView.renderToBuffer(state, width, height)
        }
    }

    // === Tab bar ===

    private fun buildTabBarLine(state: TuiState, layout: TuiLayoutRegions): String {
        val sep = TuiColors.border("│")
        val helpLabel = if (state.screen == TuiScreen.HELP) TuiColors.tabActive(" F1:Help ") else TuiColors.tabInactive(" F1:Help ")
        val tabLabels = TuiTab.entries.filter { it != TuiTab.CHAT }.map { tab ->
            val fKeyNum = tab.fKey ?: (tab.ordinal + 1)
            val label = " F${fKeyNum}:${tab.label} "
            if (tab == state.activeTab && state.screen == TuiScreen.MAIN) TuiColors.tabActive(label) else TuiColors.tabInactive(label)
        }
        val settingsLabel = if (state.screen == TuiScreen.SETTINGS) TuiColors.tabActive(" F9:Set ") else TuiColors.tabInactive(" F9:Set ")
        val tabsPart = helpLabel + sep + tabLabels.joinToString(sep) + sep + settingsLabel
        return tabsPart
    }

    // === Overlays ===

    /**
     * Render autocomplete popup into the screen buffer as an overlay.
     * Paints a bordered popup with candidates above the prompt area.
     */
    private fun applyAutocompleteOverlay(screen: TuiScreenBuffer, state: TuiState, layout: TuiLayoutRegions) {
        val candidates = state.autocompleteCandidates
        val selected = state.autocompleteSelectedIndex
        val maxVisible = 8.coerceAtMost(candidates.size)
        val popupWidth = candidates.maxOf { it.length + 4 }.coerceAtMost(40)

        val popupBottom = layout.height - 2 // above prompt line
        val popupTop = popupBottom - maxVisible - 1
        val col = 3 // after "> " prompt (0-based)

        val overlayLines = mutableListOf<String>()

        // Top border
        overlayLines.add(TuiColors.border("┌${"─".repeat(popupWidth - 2)}┐"))

        // Candidates
        val startIdx = if (selected >= maxVisible) selected - maxVisible + 1 else 0
        for (i in 0 until maxVisible) {
            val idx = startIdx + i
            if (idx >= candidates.size) break
            val candidate = candidates[idx]
            val line = candidate.padEnd(popupWidth - 4)
            if (idx == selected) {
                overlayLines.add(TuiColors.border("│") + TuiColors.tabActive(" $line ") + TuiColors.border("│"))
            } else {
                overlayLines.add(TuiColors.border("│") + " $line " + TuiColors.border("│"))
            }
        }

        // Bottom border
        overlayLines.add(TuiColors.border("└${"─".repeat(popupWidth - 2)}┘"))

        screen.overlay(popupTop, col, overlayLines)
    }

    /**
     * Render plan approval dialog as a centered overlay.
     * Shows action, risk level, and y/n prompt.
     */
    private fun applyApprovalOverlay(screen: TuiScreenBuffer, state: TuiState, layout: TuiLayoutRegions) {
        val approval = state.pendingApprovals.first()
        val boxWidth = 60.coerceAtMost(layout.width - 4)
        val innerWidth = boxWidth - 4

        val lines = mutableListOf<String>()

        // Title
        lines.add(TuiColors.border("┌") + TuiColors.border("─".repeat(boxWidth - 2)) + TuiColors.border("┐"))
        lines.add(TuiColors.border("│") + TuiColors.highlight(" Approval Required".padEnd(boxWidth - 2)) + TuiColors.border("│"))
        lines.add(TuiColors.border("├") + TuiColors.border("─".repeat(boxWidth - 2)) + TuiColors.border("┤"))

        // Agent
        val agentLine = " Agent: ${approval.agentName}".take(innerWidth).padEnd(innerWidth)
        lines.add(TuiColors.border("│") + " $agentLine " + TuiColors.border("│"))

        // Action
        val actionLine = " Action: ${approval.action}".take(innerWidth).padEnd(innerWidth)
        lines.add(TuiColors.border("│") + " $actionLine " + TuiColors.border("│"))

        // Risk
        val riskColor = when (approval.risk.lowercase()) {
            "high" -> TuiColors.statusFailed
            "medium" -> TuiColors.statusPending
            else -> TuiColors.statusSuccess
        }
        val riskText = riskColor("Risk: ${approval.risk}")
        val riskPadded = " $riskText".padEnd(innerWidth + (riskText.length - approval.risk.length - 6))
        lines.add(TuiColors.border("│") + " $riskPadded " + TuiColors.border("│"))

        // Details (if any)
        for ((key, value) in approval.details.entries.take(3)) {
            val detailLine = " $key: $value".take(innerWidth).padEnd(innerWidth)
            lines.add(TuiColors.border("│") + " $detailLine " + TuiColors.border("│"))
        }

        // Separator + prompt
        lines.add(TuiColors.border("├") + TuiColors.border("─".repeat(boxWidth - 2)) + TuiColors.border("┤"))
        val promptText = " [y] Approve  [n] Reject  [t] Trust  [Esc] Skip"
        lines.add(TuiColors.border("│") + TuiColors.accent(promptText.padEnd(boxWidth - 2)) + TuiColors.border("│"))
        lines.add(TuiColors.border("└") + TuiColors.border("─".repeat(boxWidth - 2)) + TuiColors.border("┘"))

        // Center the dialog
        val startRow = ((layout.height - lines.size) / 2).coerceAtLeast(2)
        val startCol = ((layout.width - boxWidth) / 2).coerceAtLeast(0)

        screen.overlay(startRow, startCol, lines)
    }

    /**
     * Render model selector popup as a centered overlay.
     */
    private fun applyModelSelectorOverlay(screen: TuiScreenBuffer, state: TuiState, layout: TuiLayoutRegions) {
        val candidates = state.modelSelectorCandidates
        val selected = state.modelSelectorIndex
        val maxVisible = 10.coerceAtMost(candidates.size)
        val longestModel = candidates.maxOfOrNull { it.length } ?: 20
        val boxWidth = (longestModel + 6).coerceIn(30, layout.width - 4)
        val innerWidth = boxWidth - 4

        val lines = mutableListOf<String>()

        // Title
        lines.add(TuiColors.border("┌─") + TuiColors.highlight(" Select Model ") + TuiColors.border("─".repeat((boxWidth - 18).coerceAtLeast(0)) + "┐"))

        // Candidates
        val startIdx = if (selected >= maxVisible) selected - maxVisible + 1 else 0
        for (i in 0 until maxVisible) {
            val idx = startIdx + i
            if (idx >= candidates.size) break
            val candidate = candidates[idx]
            val display = candidate.take(innerWidth).padEnd(innerWidth)
            val marker = if (candidate == state.model) TuiColors.statusSuccess("● ") else "  "
            if (idx == selected) {
                lines.add(TuiColors.border("│") + TuiColors.tabActive(" $marker$display ") + TuiColors.border("│"))
            } else {
                lines.add(TuiColors.border("│") + " $marker$display " + TuiColors.border("│"))
            }
        }

        if (candidates.size > maxVisible) {
            val scrollInfo = " (${startIdx + 1}-${(startIdx + maxVisible).coerceAtMost(candidates.size)} of ${candidates.size})"
            lines.add(TuiColors.border("│") + TuiColors.muted(scrollInfo.padEnd(boxWidth - 2)) + TuiColors.border("│"))
        }

        // Footer
        lines.add(TuiColors.border("├") + TuiColors.border("─".repeat(boxWidth - 2)) + TuiColors.border("┤"))
        val hint = " [↑↓] Navigate  [Enter] Select  [Esc] Cancel"
        lines.add(TuiColors.border("│") + TuiColors.muted(hint.take(boxWidth - 2).padEnd(boxWidth - 2)) + TuiColors.border("│"))
        lines.add(TuiColors.border("└") + TuiColors.border("─".repeat(boxWidth - 2)) + TuiColors.border("┘"))

        // Center the dialog
        val startRow = ((layout.height - lines.size) / 2).coerceAtLeast(2)
        val startCol = ((layout.width - boxWidth) / 2).coerceAtLeast(0)

        screen.overlay(startRow, startCol, lines)
    }

}

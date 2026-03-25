package pl.jclab.refio.cli.tui.rendering

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.screens.TuiHistoryScreen
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.views.*

/**
 * Main rendering engine for split-pane TUI.
 *
 * ALL rendering goes through [TuiScreenBuffer] — a full-screen framebuffer.
 * Base content is rendered first, then overlays (autocomplete, modals) are
 * painted on top. The entire composed frame is flushed to the terminal in
 * one atomic write — no partial draws, no cursor repositioning between draws.
 *
 * This architecture supports arbitrary overlays: autocomplete popups,
 * confirmation dialogs, file pickers, etc.
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
class TuiRenderer(val terminal: Terminal) {

    private var lastRenderedHash: Int = 0
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0

    fun showLoading(message: String) {
        terminal.println(TuiColors.accent(message))
    }

    fun showError(message: String) {
        terminal.println(TuiColors.system("Error: $message"))
    }

    fun enterFullScreen() {
        terminal.print("\u001b[?1049h") // alternate screen buffer
    }

    fun exitFullScreen() {
        terminal.print("\u001b[?25h")    // show cursor
        terminal.print("\u001b[?1049l")  // leave alternate screen buffer
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
        val size = terminal.size
        val resized = size.width != lastWidth || size.height != lastHeight
        lastWidth = size.width
        lastHeight = size.height

        val hash = state.hashCode()
        if (hash == lastRenderedHash && !resized) return
        lastRenderedHash = hash

        val isSplit = state.screen == TuiScreen.MAIN && state.activeTab != TuiTab.CHAT
        val layout = TuiLayoutRegions.fromTerminal(size.width, size.height, isSplitMode = isSplit)

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
        }
        screen.setRows(contentStartRow, contentLines)

        // === 2. Apply overlays ===
        if (state.screen == TuiScreen.MAIN && state.autocompleteVisible && state.autocompleteCandidates.isNotEmpty()) {
            applyAutocompleteOverlay(screen, state, layout)
        }

        // Approval dialog overlay (takes priority over autocomplete)
        if (state.pendingApprovals.isNotEmpty()) {
            applyApprovalOverlay(screen, state, layout)
        }

        // === 3. Flush to terminal ===
        screen.flush(terminal, clearScreen = resized)

        // === 4. Position cursor ===
        if (state.screen == TuiScreen.MAIN) {
            val inputRow = layout.tabBarHeight + layout.separatorHeight + layout.contentHeight
            val inputCol = 3 + TuiRenderBuffer.visibleLength(state.inputBuffer) // "> " + input
            screen.positionCursorAndShow(terminal, inputRow, inputCol)
        } else {
            screen.showCursor(terminal)
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
        val chatBuf = TuiChatView.renderMessages(terminal, state, layout.contentWidth, layout.chatHeight)
        val promptBuf = TuiChatView.renderPrompt(state, layout.contentWidth)
        return chatBuf.getVisibleLines() + promptBuf.getLines()
    }

    private fun buildSplitPaneLines(state: TuiState, layout: TuiLayoutRegions): List<String> {
        val leftW = layout.leftPanelWidth
        val rightW = layout.rightPanelWidth

        val chatBuf = TuiChatView.renderMessages(terminal, state, leftW, layout.chatHeight)
        val promptBuf = TuiChatView.renderPrompt(state, leftW)
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
        }
    }

    // === Tab bar ===

    private fun buildTabBarLine(state: TuiState, layout: TuiLayoutRegions): String {
        val tabs = TuiTab.entries.mapIndexed { i, tab ->
            val label = " F${i + 1}:${tab.label} "
            if (tab == state.activeTab) TuiColors.tabActive(label) else TuiColors.tabInactive(label)
        }
        val settingsLabel = if (state.screen == TuiScreen.SETTINGS) TuiColors.tabActive(" F8:Set ") else TuiColors.tabInactive(" F8:Set ")
        val sep = TuiColors.border("│")
        val leftPart = tabs.joinToString(sep) + sep + settingsLabel

        val mode = TuiColors.accent("[${state.mode}|${state.model ?: "default"}]")
        val streaming = if (state.isStreaming) TuiColors.statusRunning(" streaming...") else ""
        val cost = TuiColors.muted(" \$${String.format("%.4f", state.totalCostUsd)}|${formatTokens(state.totalTokens)}tok")
        val quit = TuiColors.muted(" [Ctrl+Q]")
        val rightPart = "$mode$streaming$cost$quit"

        val leftVisible = TuiRenderBuffer.visibleLength(leftPart)
        val rightVisible = TuiRenderBuffer.visibleLength(rightPart)
        val gap = (layout.width - leftVisible - rightVisible).coerceAtLeast(1)

        return "$leftPart${" ".repeat(gap)}$rightPart"
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
        val promptText = " [y] Approve  [n] Reject  [Esc] Skip"
        lines.add(TuiColors.border("│") + TuiColors.accent(promptText.padEnd(boxWidth - 2)) + TuiColors.border("│"))
        lines.add(TuiColors.border("└") + TuiColors.border("─".repeat(boxWidth - 2)) + TuiColors.border("┘"))

        // Center the dialog
        val startRow = ((layout.height - lines.size) / 2).coerceAtLeast(2)
        val startCol = ((layout.width - boxWidth) / 2).coerceAtLeast(0)

        screen.overlay(startRow, startCol, lines)
    }

    private fun formatTokens(tokens: Long): String {
        return if (tokens > 1000) "${String.format("%.1f", tokens / 1000.0)}K" else tokens.toString()
    }
}

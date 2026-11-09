package pl.jclab.refio.cli.tui.rendering

import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.components.ApprovalFrame
import pl.jclab.refio.cli.tui.components.CommandPopup
import pl.jclab.refio.cli.tui.components.Sidebar
import pl.jclab.refio.cli.tui.components.StatusBar
import pl.jclab.refio.cli.tui.components.TuiPromptInput
import pl.jclab.refio.cli.tui.screens.TuiHelpScreen
import pl.jclab.refio.cli.tui.screens.TuiHistoryScreen
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor
import pl.jclab.refio.cli.tui.views.*
import java.io.Writer
import java.nio.charset.Charset

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
 * Layout (columns depend on the terminal width, see [LayoutMode]):
 * ```
 * ╭─ REFIO ─╮ ╭─ CHAT ──────────── meta ─╮ ╭─ STEPS ───╮
 * │ nav     │ │ messages                 │ │           │
 * ╭─ ...  ──╮ ╰──────────────────────────╯ ╭─ CONTEXT ─╮
 * │         │ ╭─ PROMPT ─────────────────╮ │           │
 * ╰─────────╯ ╰──────────────────────────╯ ╰───────────╯
 *  MODE  model │ state │ ctx ... │ cost                 keys
 * ```
 */
class TuiRenderer(
    val terminal: Terminal,
    private val jlineTerminal: org.jline.terminal.Terminal,
    theme: Theme? = null,
    glyphs: Glyphs? = null
) {

    companion object {
        /** The chat frame gets wider side margins than other frames. */
        private const val CHAT_PADDING = 2

        private const val PROMPT_META = "Enter send · @ file · / command · ! agent"
    }

    private val theme: Theme = theme ?: Theme(ColorDepth.fromAnsiLevel(terminal.terminalInfo.ansiLevel))
    private val glyphs: Glyphs = glyphs ?: Glyphs.forEnvironment(System.getenv(), jlineTerminal.encoding() ?: Charset.defaultCharset())
    private val frame = Frame(this.theme, this.glyphs)

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
    @Synchronized
    fun forceRender(state: TuiState) {
        lastRenderedHash = 0
        lastWidth = 0
        lastHeight = 0
        render(state)
    }

    // Synchronized: frames may be triggered concurrently by the state-collect
    // loop, the resize poller and the WINCH signal handler; interleaved writes
    // of two frames corrupt the screen and can kill the writer mid-resize.
    @Synchronized
    fun render(state: TuiState) {
        val width = jlineTerminal.width
        val height = jlineTerminal.height
        val resized = width != lastWidth || height != lastHeight
        lastWidth = width
        lastHeight = height

        val hash = state.hashCode()
        if (hash == lastRenderedHash && !resized) return
        lastRenderedHash = hash

        val composed = compose(state, width, height)

        // Flush to terminal (through JLine writer), then park the cursor in the prompt when it has focus
        composed.screen.flush(output, clearScreen = resized)
        val cursor = composed.cursor
        if (cursor != null) {
            composed.screen.positionCursorAndShow(output, cursor.first, cursor.second)
        } else {
            composed.screen.showCursor(output)
        }
    }

    /** Builds the whole frame for [state] at the given terminal size without writing anything to the terminal. */
    fun composeFrame(state: TuiState, width: Int, height: Int): TuiScreenBuffer = compose(state, width, height).screen

    /** A composed screen plus where the terminal cursor goes (1-based row and column), if anywhere. */
    private class ComposedFrame(val screen: TuiScreenBuffer, val cursor: Pair<Int, Int>?)

    /** Rows of one screen area plus the prompt cursor inside it (1-based), if the prompt has focus. */
    private class Area(val rows: List<String>, val cursor: Pair<Int, Int>?)

    private fun compose(state: TuiState, width: Int, height: Int): ComposedFrame {
        // Views that style through the shared palette draw with this renderer's colour depth.
        TuiColors.theme = theme
        // Some terminals report 0x0 until the first resize; draw at the minimum size meanwhile.
        val w = if (width > 0) width else LayoutMode.MIN_WIDTH
        val h = if (height > 0) height else LayoutMode.MIN_HEIGHT
        val mode = LayoutMode.of(w, h)
        val screen = TuiScreenBuffer(w, h)

        if (mode == LayoutMode.TooSmall) {
            val message = TextFit.truncateEnd("Refio needs at least ${LayoutMode.MIN_WIDTH}×${LayoutMode.MIN_HEIGHT} (now ${w}×$h)", w)
            val pad = ((w - TuiRenderBuffer.visibleLength(message)) / 2).coerceAtLeast(0)
            screen.setRow(h / 2, " ".repeat(pad) + theme.style(message, fg = ThemeColor.WARN))
            return ComposedFrame(screen, null)
        }

        val bodyHeight = h - 1
        // Overlays position themselves in the area above the status bar.
        val layout = TuiLayoutRegions(width = w, height = bodyHeight, tabBarHeight = 0, separatorHeight = 0, isSplitMode = false)
        var cursor: Pair<Int, Int>? = null
        when (state.screen) {
            TuiScreen.MAIN -> {
                val main = composeMain(state, mode, w, bodyHeight)
                screen.setRows(0, main.rows)
                cursor = main.cursor
            }
            TuiScreen.HISTORY -> screen.setRows(0, TuiHistoryScreen.renderToLines(state, w, bodyHeight))
            TuiScreen.SETTINGS -> screen.setRows(0, TuiSettingsScreen.renderToLines(state, w, bodyHeight, theme, glyphs))
            TuiScreen.HELP -> screen.setRows(0, TuiHelpScreen.renderToLines(state, w, bodyHeight, theme, glyphs))
        }
        screen.setRow(h - 1, StatusBar.render(state, w, theme, glyphs))

        if (state.screen == TuiScreen.MAIN && state.autocompleteVisible && state.autocompleteCandidates.isNotEmpty()) {
            applyCommandPopup(screen, state, mode, w, bodyHeight)
        }
        if (state.modelSelectorVisible && state.modelSelectorCandidates.isNotEmpty()) {
            applyModelSelectorOverlay(screen, state, layout)
        }
        // File viewer overlay (full-screen, takes priority over autocomplete)
        if (state.fileViewerVisible) {
            applyFileViewerOverlay(screen, state, layout)
            cursor = null
        }
        return ComposedFrame(screen, cursor)
    }

    /**
     * Main screen: wide = sidebar | chat + prompt | right column; medium = chat + prompt | right column;
     * narrow = chat + prompt, or the open panel on its own. Columns are separated by one blank column.
     * Without an open panel or a waiting approval there is no right column and the chat takes its width.
     */
    private fun composeMain(state: TuiState, mode: LayoutMode, width: Int, height: Int): Area {
        val panelOpen = state.activeTab != TuiTab.CHAT
        if (mode == LayoutMode.Narrow && panelOpen) {
            return Area(panelFrame(state, width, height, alone = true), null)
        }
        val cols = LayoutMode.columns(mode, width, rightColumnOpen(state))
        val columns = mutableListOf<List<String>>()
        var chatStart = 0
        if (cols.sidebar > 0) {
            columns += Sidebar.render(state, cols.sidebar, height, theme, glyphs)
            chatStart = cols.sidebar + 1
        }
        val chat = chatColumn(state, mode, cols.chat, height)
        columns += chat.rows
        if (cols.right > 0) columns += rightColumn(state, cols.right, height)
        val rows = (0 until height).map { r -> columns.joinToString(" ") { it.getOrElse(r) { "" } } }
        return Area(rows, chat.cursor?.let { (row, col) -> row to col + chatStart })
    }

    private fun promptHeight(state: TuiState, width: Int, height: Int): Int =
        (TuiPromptInput.renderToLines(state, frame.innerSize(width, 3).first).size + 2).coerceIn(3, (height / 2).coerceAtLeast(3))

    private fun chatColumn(state: TuiState, mode: LayoutMode, width: Int, height: Int): Area {
        val promptInner = frame.innerSize(width, 3).first
        val promptBody = TuiPromptInput.renderToLines(state, promptInner)
        val promptHeight = promptHeight(state, width, height)
        val chatHeight = height - promptHeight
        val (chatInnerWidth, chatInnerHeight) = frame.innerSize(width, chatHeight, padding = CHAT_PADDING)
        val messages = TuiChatView.renderMessages(terminal, state, chatInnerWidth, chatInnerHeight, theme, glyphs, compact = mode == LayoutMode.Narrow).getVisibleLines()

        val panelHasFocus = state.panelFocused && state.activeTab != TuiTab.CHAT
        val chatHasFocus = !panelHasFocus && state.selectedMessageIndex >= 0
        val promptHasFocus = !panelHasFocus && !chatHasFocus
        val rows = frame.render(width, chatHeight, "chat", chatMeta(state, mode), if (chatHasFocus) FrameState.FOCUSED else FrameState.INACTIVE, messages, padding = CHAT_PADDING) +
            frame.render(width, promptHeight, "prompt", PROMPT_META, if (promptHasFocus) FrameState.FOCUSED else FrameState.INACTIVE, promptBody)
        if (!promptHasFocus || state.fileViewerVisible) return Area(rows, null)

        val prefixLen = 2 // "> "
        val (cursorRow, cursorCol) = TuiPromptInput.getCursorRowCol(
            state.inputBuffer, state.cursorPosition, (promptInner - prefixLen).coerceAtLeast(10)
        )
        val lastInputRow = chatHeight + promptHeight - 2
        val row = (chatHeight + 1 + TuiPromptInput.inputLineOffset(state) + cursorRow).coerceAtMost(lastInputRow)
        val col = 2 + prefixLen + cursorCol // left border + padding + "> "
        return Area(rows, (row + 1) to (col + 1))
    }

    private fun chatMeta(state: TuiState, mode: LayoutMode): String {
        val activity = when {
            state.pendingToolApproval != null || state.pendingApprovals.isNotEmpty() -> "waiting for approval"
            state.isStreaming -> "streaming"
            else -> "idle"
        }
        val agent = state.agentFilter?.let { " · agent $it" } ?: ""
        return when (mode) {
            LayoutMode.Wide -> "${state.model ?: "default model"} · $activity$agent"
            LayoutMode.Medium -> listOfNotNull(
                Sidebar.stepsBadge(state).takeIf { it.isNotEmpty() }?.let { "steps $it" },
                "ctx ${Sidebar.contextBadge(state)}",
                activity
            ).joinToString(" · ") + agent
            else -> "F2 steps · F3 ctx · F4 rag · F5 logs · F9 settings"
        }
    }

    private fun rightColumnOpen(state: TuiState): Boolean = state.activeTab != TuiTab.CHAT || ApprovalFrame.isPending(state)

    /** Right column: the panel opened with F2-F8, or what an approval needs while it waits. */
    private fun rightColumn(state: TuiState, width: Int, height: Int): List<String> =
        if (state.activeTab != TuiTab.CHAT) panelFrame(state, width, height, alone = false) else approvalColumn(state, width, height)

    /** While an approval waits: what this turn has done so far over what is already allowed for the session. */
    private fun approvalColumn(state: TuiState, width: Int, height: Int): List<String> {
        val turnHeight = (height * 3 / 5).coerceAtLeast(3)
        val allowedHeight = height - turnHeight
        val turnInner = frame.innerSize(width, turnHeight)
        val allowedInner = frame.innerSize(width, allowedHeight)
        val turn = ApprovalFrame.thisTurnLines(state, turnInner.first, theme, glyphs).takeLast(turnInner.second)
        val allowed = ApprovalFrame.allowedLines(state, allowedInner.first, theme, glyphs).take(allowedInner.second)
        return frame.render(width, turnHeight, "this turn", null, FrameState.INACTIVE, turn) +
            frame.render(width, allowedHeight, "allowed this session", null, FrameState.INACTIVE, allowed)
    }

    /** One frame holding the panel picked with F2-F8; [alone] = it fills the screen (narrow layout). */
    private fun panelFrame(state: TuiState, width: Int, height: Int, alone: Boolean): List<String> {
        val (innerWidth, innerHeight) = frame.innerSize(width, height)
        val body = renderRightPanel(state, innerWidth, innerHeight).getVisibleLines()
        val focused = alone || state.panelFocused
        val meta = when {
            alone -> "Esc chat · F2-F8 panels"
            state.panelFocused -> "Tab prompt"
            else -> "Tab focus"
        }
        return frame.render(width, height, state.activeTab.label, meta, if (focused) FrameState.FOCUSED else FrameState.INACTIVE, body)
    }

    private fun renderRightPanel(state: TuiState, width: Int, height: Int): TuiRenderBuffer {
        return when (state.activeTab) {
            TuiTab.CHAT -> TuiRenderBuffer(width, height)
            TuiTab.STEPS -> TuiStepsView.renderToBuffer(state, width, height, theme, glyphs)
            TuiTab.CONTEXT -> TuiContextView.renderToBuffer(state, width, height, theme, glyphs)
            TuiTab.RAG -> TuiRagView.renderToBuffer(state, width, height, theme, glyphs)
            TuiTab.LOGS -> TuiLogsView.renderToBuffer(state, width, height)
            TuiTab.DEBUG -> TuiDebugView.renderToBuffer(state, width, height)
            TuiTab.API_LOGS -> TuiApiLogsView.renderToBuffer(state, width, height)
            TuiTab.FILES -> TuiFilesView.renderToBuffer(state, width, height)
        }
    }

    // === Overlays ===

    /** Suggestions for `/ @ !` in a frame right above the prompt, as wide as the chat column. */
    private fun applyCommandPopup(screen: TuiScreenBuffer, state: TuiState, mode: LayoutMode, width: Int, height: Int) {
        if (mode == LayoutMode.Narrow && state.activeTab != TuiTab.CHAT) return
        val cols = LayoutMode.columns(mode, width, rightColumnOpen(state))
        val chatStart = if (cols.sidebar > 0) cols.sidebar + 1 else 0
        val lines = CommandPopup.render(state, cols.chat, theme, glyphs)
        val top = (height - promptHeight(state, cols.chat, height) - lines.size).coerceAtLeast(0)
        screen.overlay(top, chatStart, lines)
    }

    /**
     * Render file viewer as a full-screen overlay replacing the content area.
     */
    private fun applyFileViewerOverlay(screen: TuiScreenBuffer, state: TuiState, layout: TuiLayoutRegions) {
        val contentStartRow = layout.tabBarHeight + layout.separatorHeight
        val buf = TuiFileViewerOverlay.renderToBuffer(state, layout.width, layout.contentHeight)
        val lines = buf.getLines()
        screen.setRows(contentStartRow, lines)
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
        // Every row must be exactly boxWidth visible columns so the right border
        // stays vertical: 1 border + 1 space + 2 marker + innerWidth + 1 space + 1 border.
        val innerWidth = boxWidth - 6

        val lines = mutableListOf<String>()

        // Title: 2 + 14 + fill + 1 = boxWidth
        lines.add(TuiColors.border("┌─") + TuiColors.highlight(" Select Model ") + TuiColors.border("─".repeat((boxWidth - 17).coerceAtLeast(0)) + "┐"))

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

package pl.jclab.refio.cli.tui.golden

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import pl.jclab.refio.cli.tui.rendering.TuiRenderer
import pl.jclab.refio.cli.tui.state.TranscriptBuilder
import pl.jclab.refio.cli.tui.state.TuiChatMessage
import pl.jclab.refio.cli.tui.state.TuiContextSection
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.core.api.SubtaskResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Fixed, made-up session data for screen pattern tests; times are UTC so patterns match on any machine. */
object TuiFixtures {

    /** 2026-01-15 12:04:00 UTC */
    const val T0 = 1_768_478_640_000L

    fun renderer(depth: ColorDepth = ColorDepth.TRUECOLOR, glyphs: Glyphs = Glyphs.UNICODE): TuiRenderer {
        val terminal = Terminal(ansiLevel = AnsiLevel.TRUECOLOR, width = 200, height = 50, terminalInterface = TerminalRecorder())
        val jline = org.jline.terminal.TerminalBuilder.builder()
            .streams(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
            .type("dumb")
            .build()
        return TuiRenderer(terminal, jline, Theme(depth), glyphs)
    }

    fun step(order: Int, description: String, status: String) = SubtaskResponse(
        id = "s$order", taskId = "t1", orderIndex = order, kind = "TOOL", status = status, approvalStatus = "APPROVED",
        requiresApproval = false, approvedByUser = true, description = description, paramsJson = null, stepPlanJson = null,
        summary = null, result = null, startedAt = null, finishedAt = null, errorCode = null, errorMessage = null,
        tokensIn = 0, tokensOut = 0, costUsd = 0.0, latencyMs = 0, model = null, provider = null, createdAt = T0, updatedAt = T0
    )

    fun chatState(): TuiState = TuiState(
        mode = "AGENT",
        model = "ollama/qwen3.8:27b-mlx",
        activeSessionId = "t1",
        contextUsedTokens = 16_700,
        contextMaxTokens = 32_768,
        sessionTokensIn = 21_700,
        sessionTokensOut = 154,
        messages = listOf(
            TuiChatMessage(id = "m1", timestamp = T0, role = "user", content = "Make the status bar show the context window the engine really uses."),
            // Built the same way the view model builds them, so the pattern shows a real tool row.
            TranscriptBuilder.toolCallMessage(
                "m2", "c1", "read_file", """{"path":"cli/src/main/kotlin/TuiViewModel.kt"}""", T0 + 4_000, running = false
            ),
            TranscriptBuilder.toolResultMessage("m2r", "c1", "lines 1-40 of 296", T0 + 4_300),
            TuiChatMessage(
                id = "m3", timestamp = T0 + 9_000, role = "assistant",
                content = "The TUI computes its own window and falls back to 128K. I will read it from the same place as the plugin."
            )
        ),
        subtasks = listOf(
            step(1, "Read TuiViewModel", "SUCCESS"),
            step(2, "Use the core context window", "RUNNING"),
            step(3, "Run the CLI tests", "PENDING")
        ),
        contextSections = listOf(
            TuiContextSection("System prompt", "project", 2_100, 32_768, 6.4),
            TuiContextSection("Conversation", "conversation", 9_800, 32_768, 29.9),
            TuiContextSection("Tool results", "tools", 4_800, 32_768, 14.6)
        )
    )
}

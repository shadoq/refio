package pl.jclab.refio.cli.tui

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.*
import mu.KotlinLogging
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.cli.tui.input.TuiInputHandler
import pl.jclab.refio.cli.tui.rendering.TuiRenderer
import pl.jclab.refio.cli.tui.screens.TuiSettingsScreen
import pl.jclab.refio.cli.tui.state.TuiViewModel
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * TUI application entry point.
 * Replaces Compose Desktop launchComposeApp().
 *
 * Two modes based on terminal capabilities:
 * - **Full-screen TUI** (real TTY): Alternate screen buffer, raw input, F-key navigation
 * - **Simple TUI** (no TTY / IDE runner): Inline rendering, line-based input, /commands
 *
 * A single JLine terminal is created for interactive mode and shared between
 * the renderer (output via JLine's writer) and the input handler (input via
 * JLine's reader). This ensures all I/O goes through one coordinated channel.
 */
fun launchTuiApp(
    projectPath: Path,
    mode: TaskMode?,
    model: String?,
    noEgress: Boolean,
    runConfigOverrides: Map<String, String> = emptyMap()
) {
    val mordantTerminal = Terminal()
    val viewModel = TuiViewModel(projectPath, mode, model, noEgress, runConfigOverrides)
    val inputHandler = TuiInputHandler(mordantTerminal)

    // --- Create JLine terminal for interactive mode ---
    var jlineTerminal: org.jline.terminal.Terminal? = null
    val interactive = System.console() != null

    if (interactive) {
        try {
            java.util.logging.Logger.getLogger("org.jline").level = java.util.logging.Level.OFF

            val jt = org.jline.terminal.TerminalBuilder.builder()
                .system(true)
                .jansi(true)
                .build()

            if (jt.type != "dumb" && isRealTerminal(jt)) {
                jlineTerminal = jt
            } else {
                logger.info { "Dumb terminal detected, falling back to simple TUI mode" }
                jt.close()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to create JLine terminal, falling back to simple TUI mode" }
        }
    }

    val jt = jlineTerminal
    // Renderer requires JLine terminal for interactive mode
    val renderer = if (jt != null) TuiRenderer(mordantTerminal, jt) else null

    runBlocking {
        if (renderer != null) {
            renderer.enterFullScreen()
            renderer.showLoading("Initializing Refio for ${projectPath.toAbsolutePath().fileName}...")
        }

        viewModel.initialize()
        TuiSettingsScreen.setViewModel(viewModel)

        val error = viewModel.error.value
        if (error != null) {
            renderer?.exitFullScreen()
            renderer?.showError(error) ?: mordantTerminal.println("Error: $error")
            jlineTerminal?.close()
            return@runBlocking
        }

        if (renderer == null) {
            logger.info { "Non-interactive terminal detected — using simple TUI mode (line input)" }
            mordantTerminal.println("Refio TUI ready. Type messages or /help for commands. /quit to exit.")
        }

        try {
            // Initial full render — read terminal size and draw before starting collect loop.
            // Without this, the first frame from stateFlow.collect may render with stale
            // or zero dimensions (especially on Windows where alternate screen buffer
            // size may not be available immediately).
            if (renderer != null) {
                renderer.forceRender(viewModel.stateFlow.value)
            }

            // Render loop: re-render on state change. A failed frame is logged
            // and skipped; it must never kill the loop (a dead render loop
            // looks like a silent app crash to the user).
            val renderJob = if (renderer != null) launch {
                viewModel.stateFlow.collect { state ->
                    try {
                        renderer.render(state)
                    } catch (e: Exception) {
                        logger.error(e) { "Render failed; frame skipped" }
                    }
                }
            } else null

            // Immediate resize reaction: SIGWINCH forces a full re-render with
            // a clean comparison buffer. Not supported on all platforms; the
            // polling watcher below stays as fallback.
            if (renderer != null && jt != null) {
                try {
                    jt.handle(org.jline.terminal.Terminal.Signal.WINCH) {
                        try {
                            renderer.forceRender(viewModel.stateFlow.value)
                        } catch (e: Exception) {
                            logger.error(e) { "Render after resize failed; frame skipped" }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "WINCH signal handler not available on this platform" }
                }
            }

            // Resize watcher: poll terminal size and force re-render on change
            val resizeJob = if (renderer != null && jt != null) launch {
                var prevW = jt.width
                var prevH = jt.height
                while (isActive) {
                    delay(300)
                    val w = jt.width
                    val h = jt.height
                    if (w != prevW || h != prevH) {
                        prevW = w
                        prevH = h
                        try {
                            renderer.forceRender(viewModel.stateFlow.value)
                        } catch (e: Exception) {
                            logger.error(e) { "Render after resize failed; frame skipped" }
                        }
                    }
                }
            } else null

            // Input loop: raw keys (interactive) or line input (non-interactive)
            val inputJob = launch(Dispatchers.IO) {
                inputHandler.startInputLoop(viewModel, jlineTerminal)
            }

            // Wait for input loop to finish (user pressed Ctrl+Q or /quit)
            inputJob.join()
            resizeJob?.cancelAndJoin()
            renderJob?.cancelAndJoin()
        } finally {
            renderer?.exitFullScreen()
            jlineTerminal?.close()
            viewModel.shutdown()
        }
    }
}

private fun isRealTerminal(jlineTerminal: org.jline.terminal.Terminal): Boolean {
    return try {
        val size = jlineTerminal.size
        size.columns > 0 && size.rows > 0
    } catch (_: Exception) {
        false
    }
}

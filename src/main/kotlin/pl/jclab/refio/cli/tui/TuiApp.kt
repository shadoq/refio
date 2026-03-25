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
 */
fun launchTuiApp(projectPath: Path, mode: TaskMode, model: String?, noEgress: Boolean) {
    val terminal = Terminal()
    val renderer = TuiRenderer(terminal)
    val viewModel = TuiViewModel(projectPath, mode, model, noEgress)
    val inputHandler = TuiInputHandler(terminal)

    val interactive = System.console() != null

    runBlocking {
        if (interactive) {
            // Enter alternate screen buffer FIRST — before any output,
            // so the normal buffer stays clean (no scroll-back artifacts).
            renderer.enterFullScreen()
            renderer.showLoading("Initializing Refio for ${projectPath.toAbsolutePath().fileName}...")
        }

        viewModel.initialize()
        TuiSettingsScreen.setViewModel(viewModel)

        val error = viewModel.error.value
        if (error != null) {
            if (interactive) renderer.exitFullScreen()
            renderer.showError(error)
            return@runBlocking
        }

        if (!interactive) {
            logger.info { "Non-interactive terminal detected — using simple TUI mode (line input)" }
            terminal.println("Refio TUI ready. Type messages or /help for commands. /quit to exit.")
        }

        try {
            // Render loop: re-render on state change
            val renderJob = launch {
                viewModel.stateFlow.collect { state ->
                    if (interactive) {
                        renderer.render(state)
                    }
                }
            }

            // Resize watcher: poll terminal size and force re-render on change
            val resizeJob = if (interactive) launch {
                var prevW = terminal.size.width
                var prevH = terminal.size.height
                while (isActive) {
                    delay(300)
                    val s = terminal.size
                    if (s.width != prevW || s.height != prevH) {
                        prevW = s.width
                        prevH = s.height
                        renderer.forceRender(viewModel.stateFlow.value)
                    }
                }
            } else null

            // Input loop: raw keys (interactive) or line input (non-interactive)
            val inputJob = launch(Dispatchers.IO) {
                inputHandler.startInputLoop(viewModel)
            }

            // Wait for input loop to finish (user pressed Ctrl+Q or /quit)
            inputJob.join()
            resizeJob?.cancelAndJoin()
            renderJob.cancelAndJoin()
        } finally {
            if (interactive) {
                renderer.exitFullScreen()
            }
            viewModel.shutdown()
        }
    }
}

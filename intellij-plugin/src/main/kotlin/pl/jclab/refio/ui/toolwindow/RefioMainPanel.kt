package pl.jclab.refio.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import javax.swing.SwingConstants

/**
 * Thin wrapper around [RefioContentPanel] that keeps the EDT free while
 * [SessionManager] initializes. The previous implementation constructed the
 * full UI inline, which pulled in project-level service creation (DB init,
 * prompt seeding, migrations) on the EDT and produced multi-second freezes.
 *
 * Flow:
 *  1. On EDT: show a lightweight "Initializing..." placeholder.
 *  2. Off EDT: create [SessionManager] and [StepExecutionService].
 *  3. Back on EDT: swap placeholder for [RefioContentPanel].
 *
 * Public action methods queue their work: if the content panel isn't ready
 * yet, the action is replayed once initialization completes.
 */
class RefioMainPanel(private val project: Project) : JBPanel<RefioMainPanel>(BorderLayout()), Disposable {

    private val logger = dualLogger("RefioMainPanel")

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var content: RefioContentPanel? = null

    private val pendingActions = mutableListOf<(RefioContentPanel) -> Unit>()

    private val loader = JBLabel("Initializing Refio…", SwingConstants.CENTER).apply {
        background = LCATheme.backgroundColor
        isOpaque = true
    }

    init {
        background = LCATheme.backgroundColor
        add(loader, BorderLayout.CENTER)

        cs.launch {
            try {
                val sessionManager = withContext(Dispatchers.IO) {
                    SessionManager.getInstance(project)
                }
                val stepExecutionService = withContext(Dispatchers.IO) {
                    StepExecutionService.getInstance(project)
                }

                ApplicationManager.getApplication().invokeLater({
                    installContent(sessionManager, stepExecutionService)
                }, ModalityState.any())
            } catch (e: Throwable) {
                logger.error(e) { "Failed to initialize Refio tool window" }
                ApplicationManager.getApplication().invokeLater({
                    loader.text = "Failed to initialize Refio: ${e.message}"
                }, ModalityState.any())
            }
        }
    }

    private fun installContent(
        sessionManager: SessionManager,
        stepExecutionService: StepExecutionService
    ) {
        logger.info { "Installing Refio content panel on EDT" }

        val panel = RefioContentPanel(project, sessionManager, stepExecutionService)

        remove(loader)
        add(panel, BorderLayout.CENTER)
        revalidate()
        repaint()

        val actionsToReplay: List<(RefioContentPanel) -> Unit>
        synchronized(pendingActions) {
            content = panel
            actionsToReplay = pendingActions.toList()
            pendingActions.clear()
        }
        actionsToReplay.forEach { it(panel) }
    }

    private fun run(action: (RefioContentPanel) -> Unit) {
        val ready = content
        if (ready != null) {
            action(ready)
            return
        }
        synchronized(pendingActions) {
            val current = content
            if (current != null) {
                current.let(action)
            } else {
                pendingActions.add(action)
            }
        }
    }

    fun cycleMode() = run { it.cycleMode() }

    fun createNewSession() = run { it.createNewSession() }

    fun showHistory() = run { it.showHistory() }

    fun showSettings() = run { it.showSettings() }

    fun showHelp() {
        // Help is independent of content initialization — open browser directly.
        logger.info { "Show help requested" }
        com.intellij.ide.BrowserUtil.browse("https://github.com/jclab-joseph/refio")
    }

    fun setAdvancedViewEnabled(enabled: Boolean) = run { it.setAdvancedViewEnabled(enabled) }

    override fun dispose() {
        cs.cancel()
        content?.dispose()
    }
}

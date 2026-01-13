package pl.jclab.refio.ui.components.plan

import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pl.jclab.refio.services.session.SessionManager
import javax.swing.JButton
import javax.swing.JPanel
import java.awt.FlowLayout

/**
 * Toolbar for plan actions (Finalize, Execute)
 * TODO: Full implementation with proper button states and execute dialog
 *
 * This is a stub for compilation purposes.
 * Full implementation requires:
 * - Button state management based on plan status
 * - Execute dialog with orchestration options
 * - Confirmation dialogs
 */
class PlanToolbar(
    private val project: Project,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope
) : JPanel(FlowLayout(FlowLayout.RIGHT)) {

    private val finalizeButton = JButton("Finalize Plan")
    private val executeButton = JButton("▶ Execute Plan")

    init {
        add(finalizeButton)
        add(executeButton)

        // TODO: Add action listeners and button state management

        finalizeButton.addActionListener {
            scope.launch {
                sessionManager.finalizePlan()
            }
        }

        executeButton.addActionListener {
            // TODO: Show execute dialog with options
            showExecutePlanDialog()
        }

        // Observe plan status to enable/disable buttons
        scope.launch {
            sessionManager.activePlan.collect { plan ->
                // TODO: Update button states based on plan.status
                // DRAFT: finalize enabled, execute enabled
                // READY: finalize disabled, execute enabled
                // EXECUTING: both disabled
                // EXECUTED: finalize disabled, execute enabled (re-execute)
            }
        }
    }

    /**
     * Placeholder for execute plan dialog
     */
    private fun showExecutePlanDialog() {
        // TODO: Implement dialog with:
        // - Session name input
        // - Orchestration enabled checkbox
        // - Write operations summary
        // - Confirmation
        // For now, just execute with defaults
        scope.launch {
            try {
                sessionManager.executePlan(orchestrationEnabled = false)
            } catch (e: Exception) {
                // TODO: Show error dialog
                e.printStackTrace()
            }
        }
    }
}

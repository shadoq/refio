package pl.jclab.refio.ui.components.plan

import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pl.jclab.refio.services.session.SessionManager
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Panel displaying editable list of plan steps
 * TODO: Full implementation with drag-and-drop, edit buttons, etc.
 *
 * This is a stub for compilation purposes.
 * Full implementation requires:
 * - JBList with custom cell renderer
 * - Drag-and-drop reordering
 * - Edit/Delete buttons per step
 * - Add step dialog
 */
class PlanStepsPanel(
    private val project: Project,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope
) : JPanel(BorderLayout()) {

    init {
        // TODO: Implement UI components
        // - List of steps with custom renderer
        // - Toolbar with Add/Edit/Delete buttons
        // - Observe sessionManager.planSteps StateFlow
        // - Update list when planSteps changes

        // Stub: Observe planSteps for future implementation
        scope.launch {
            sessionManager.planSteps.collect { steps ->
                // TODO: Update UI list with steps
                // For now, just a placeholder
            }
        }
    }

    /**
     * Placeholder for showing add step dialog
     */
    private fun showAddStepDialog() {
        // TODO: Implement dialog with:
        // - Tool type dropdown
        // - Description text field
        // - Parameters JSON editor
        // - Is write operation checkbox
    }

    /**
     * Placeholder for editing selected step
     */
    private fun editSelectedStep() {
        // TODO: Implement edit dialog
    }

    /**
     * Placeholder for deleting selected step
     */
    private fun deleteSelectedStep() {
        // TODO: Implement delete confirmation and call sessionManager.deletePlanStep()
    }
}

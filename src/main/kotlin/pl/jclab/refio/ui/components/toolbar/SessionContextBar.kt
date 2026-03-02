package pl.jclab.refio.ui.components.toolbar

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.services.execution.StepExecutionService
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JToggleButton

/**
 * Session Context Bar implementing UI-plan.md section 2.3
 * Shows:
 * - Session name (editable)
 * - Mode badge
 * - Status badge
 * - Metadata (duration, cost)
 * - Session tabs
 * - Content tabs
 */
class SessionContextBar(private val project: Project, private val chatView: pl.jclab.refio.ui.components.chat.ChatView? = null) : JBPanel<SessionContextBar>(FlowLayout(FlowLayout.LEFT)) {

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val stepExecutionService = StepExecutionService.getInstance(project)
    private val logger = dualLogger("SessionContextBar")

    private val executionModeToggle: JToggleButton
    private val thinkingToggle: JToggleButton
    private val noEgressToggle: JToggleButton
    private val historyButton: JButton
    private val settingsButton: JButton

    // Flag to prevent triggering actionListener during programmatic updates
    private var isUpdatingToggleProgrammatically = false

    init {
        // Initialize execution mode toggle button moved from ToolbarComponent
        executionModeToggle = JToggleButton("🤚").apply {
            toolTipText = "Interactive mode (🤚) / Auto mode (⚡)"
            preferredSize = Dimension(32, 28)
            isSelected = true // Default: INTERACTIVE

            addActionListener {
                // Skip if this is a programmatic update (to prevent infinite loops)
                if (isUpdatingToggleProgrammatically) {
                    return@addActionListener
                }

                val newMode = if (isSelected) {
                    toolTipText = "Interactive mode"
                    text = "🤚"
                    ExecutionMode.INTERACTIVE
                } else {
                    toolTipText = "Auto mode"
                    text = "⚡"
                    ExecutionMode.AUTO
                }

                onExecutionModeChanged(newMode)
                updateBadge()
            }
        }

        // Initialize buttons moved from ToolbarComponent
        thinkingToggle = JToggleButton("🧠").apply {
            toolTipText = "Enable thinking mode"
            preferredSize = Dimension(32, 28)
            
            addActionListener {
                updateBadge()
            }
        }

        noEgressToggle = JToggleButton("🛡️").apply {
            toolTipText = "Enable no-egress mode (local only)"
            preferredSize = Dimension(32, 28)
            
            addActionListener {
                updateBadge()
            }
        }

        historyButton = JButton("⏱️").apply {
            toolTipText = "History"
            preferredSize = Dimension(32, 28)
        }

        settingsButton = JButton("⚙️").apply {
            toolTipText = "Settings"
            preferredSize = Dimension(32, 28)
        }

        // Add buttons to the layout
        add(executionModeToggle)
        add(thinkingToggle)
        add(noEgressToggle)
        add(historyButton)
        add(settingsButton)

        // TODO: Add session tabs
        // TODO: Add content tabs

        // Listen to session changes
        cs.launch {
            sessionManager.activeSession.collect { session ->
                session?.let { updateSession(it) }
            }
        }
    }

    /**
     * Handle execution mode change
     * @param mode ExecutionMode.INTERACTIVE or ExecutionMode.AUTO
     */
    private fun onExecutionModeChanged(mode: ExecutionMode) {
        logger.info { "Execution mode changed to: $mode" }

        // Update session in SessionManager
        cs.launch {
            val currentSession = sessionManager.activeSession.value
            if (currentSession != null) {
                try {
                    // Update local session (embedded core, no HTTP)
                    val updatedSession = currentSession.copy(executionMode = mode)
                    sessionManager.updateSession(updatedSession)

                    // Immediately switch execution mode if execution is active
                    stepExecutionService.switchExecutionMode(currentSession.id, mode)

                } catch (e: Exception) {
                    logger.error(e) { "Failed to set execution mode" }
                }
            }
        }

        // Fire property change for listeners
        firePropertyChange("executionMode", null, mode)
    }

    /**
     * Update UI with session data
     */
    private fun updateSession(session: Session) {

        // Synchronize execution mode toggle with session state
        val isInteractiveMode = session.executionMode == ExecutionMode.INTERACTIVE
        if (executionModeToggle.isSelected != isInteractiveMode) {
            // Set flag to prevent actionListener from firing
            isUpdatingToggleProgrammatically = true

            executionModeToggle.isSelected = isInteractiveMode

            // Update toggle appearance without triggering action listener
            if (isInteractiveMode) {
                executionModeToggle.text = "🤚"
                executionModeToggle.toolTipText = "Interactive mode"
            } else {
                executionModeToggle.text = "⚡"
                executionModeToggle.toolTipText = "Auto mode"
            }

            // Reset flag
            isUpdatingToggleProgrammatically = false
        }

        updateBadge()
    }

    /**
     * Get current execution mode from toggle button state
     */
    fun getCurrentExecutionMode(): ExecutionMode {
        return if (executionModeToggle.isSelected) {
            ExecutionMode.INTERACTIVE
        } else {
            ExecutionMode.AUTO
        }
    }

    /**
     * Update badge with current session and toggle states
     * NOTE: Mode badge removed - this method no longer does anything
     */
    private fun updateBadge() {
        // Mode badge functionality removed
    }

    /**
     * Dispose resources
     */
    fun dispose() {
        cs.cancel()
    }
}

package pl.jclab.refio.ui.components.toolbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import pl.jclab.refio.ui.theme.LCATheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Custom rounded button matching landing page .tool-button style
 */
private class RoundedButton(text: String) : JButton(text) {
    init {
        isOpaque = false
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        background = LCATheme.newSessionButtonBackground
        foreground = LCATheme.newSessionButtonForeground
        border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Background
        val radius = LCATheme.buttonRadius.toDouble()
        val shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), radius * 2, radius * 2)

        g2.color = LCATheme.newSessionButtonBackground
        g2.fill(shape)

        // Border
        g2.color = LCATheme.newSessionButtonBorder
        g2.draw(shape)

        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * Toolbar component with direct button access
 * Layout: New Session Button | [spacer] | History, Settings, Help
 *
 * Note: Mode/Model selectors, execution toggles, and no-egress toggle moved to PromptInputPanel
 */
class ToolbarComponent(
    private val project: Project,
    private val promptInputPanel: pl.jclab.refio.ui.components.chat.PromptInputPanel? = null,
    private val onSettingsRequested: (() -> Unit)? = null
) : JBPanel<ToolbarComponent>(BorderLayout()) {

    // Use EDT dispatcher for UI updates in IntelliJ
    private val cs = CoroutineScope(SupervisorJob())
    private val sessionManager = SessionManager.getInstance(project)
    private val logger = dualLogger("ToolbarComponent")

    private val newSessionButton: JButton
    private val historyButton: JButton
    private val settingsButton: JButton
    private val helpButton: JButton

    private val leftPanel: JPanel
    private val rightPanel: JPanel

    init {
        // Initialize new session button
        newSessionButton = JButton("New Session").apply {
            toolTipText = "Create new session"
            preferredSize = Dimension(100, 28)
            addActionListener {
                cs.launch {
                    // Cancel any running operations before creating new session
                    try {
                        sessionManager.cancelStreaming()
                        sessionManager.cancelExecution()
                        pl.jclab.refio.services.execution.StepExecutionService.getInstance(project).stopExecution()
                        logger.info { "Canceled running operations before creating new session" }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to cancel operations (may not be running)" }
                    }

                    val currentMode = promptInputPanel?.getSelectedMode() ?: pl.jclab.refio.api.models.TaskMode.CHAT
                    val executionMode = promptInputPanel?.getCurrentExecutionMode() ?: pl.jclab.refio.api.models.ExecutionMode.INTERACTIVE
                    sessionManager.createSession("Session (${currentMode.name})", currentMode, executionMode)

                    // Notify RefioMainPanel to switch to Chat view
                    SwingUtilities.invokeLater {
                        firePropertyChange("newSessionCreated", false, true)
                    }
                }
            }
        }

        // Left panel with new session button
        leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2)).apply {
            add(newSessionButton)
        }

        // Initialize history button with native icon
        historyButton = JButton(AllIcons.Vcs.History).apply {
            toolTipText = "History"
            preferredSize = Dimension(32, 28)
            addActionListener {
                onHistoryClicked()
            }
        }

        // Initialize settings button with native icon
        settingsButton = JButton(AllIcons.General.Settings).apply {
            toolTipText = "Settings"
            preferredSize = Dimension(32, 28)
            addActionListener {
                onSettingsClicked()
            }
        }

        // Initialize help button with native icon
        helpButton = JButton(AllIcons.Actions.Help).apply {
            toolTipText = "Help"
            preferredSize = Dimension(32, 28)
            addActionListener {
                onHelpClicked()
            }
        }

        // Right panel with all utility buttons
        rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2)).apply {
            add(historyButton)
            add(settingsButton)
            add(helpButton)
        }

        // Assemble layout
        add(leftPanel, BorderLayout.WEST)
        add(Box.createHorizontalGlue(), BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
    }

    private fun onHistoryClicked() {
        logger.info { "History button clicked" }

        // Fire property change event to notify RefioMainPanel
        firePropertyChange("showHistory", false, true)
    }

    private fun onSettingsClicked() {
        logger.info { "Settings button clicked" }
        onSettingsRequested?.invoke()
    }

    private fun onHelpClicked() {
        logger.info { "Help button clicked" }
        com.intellij.ide.BrowserUtil.browse("https://github.com/jclab-joseph/refio")
    }

    /**
     * Cycle to next mode (triggered by Alt+M action) - delegates to PromptInputPanel
     */
    fun cycleMode() {
        promptInputPanel?.cycleMode()
    }
}

package pl.jclab.refio.ui.execution

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import pl.jclab.refio.core.services.turn.TurnPhase
import pl.jclab.refio.core.services.turn.TurnStateSnapshot
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Status bar showing the current turn execution state.
 * Displays: phase, iteration count, token usage, and active tool name.
 *
 * Example: [EXECUTING_TOOLS] | Iteration 3/25 | Tokens: 12,450 | Tool: grep_search
 */
class TurnStateStatusBar : JPanel() {

    private val phaseLabel = JBLabel("IDLE")
    private val iterationLabel = JBLabel("")
    private val tokensLabel = JBLabel("")
    private val toolLabel = JBLabel("")

    init {
        layout = FlowLayout(FlowLayout.LEFT, 8, 4)
        isOpaque = false
        add(phaseLabel)
        add(JSeparator(JSeparator.VERTICAL).apply { preferredSize = Dimension(1, 16) })
        add(iterationLabel)
        add(JSeparator(JSeparator.VERTICAL).apply { preferredSize = Dimension(1, 16) })
        add(tokensLabel)
        add(toolLabel)
    }

    fun update(snapshot: TurnStateSnapshot) {
        phaseLabel.text = snapshot.phase.name
        phaseLabel.foreground = when (snapshot.phase) {
            TurnPhase.FAILED -> JBColor.RED
            TurnPhase.EXECUTING_TOOLS -> JBColor.BLUE
            TurnPhase.CALLING_MODEL -> JBColor.ORANGE
            TurnPhase.WAITING_FOR_PERMISSION, TurnPhase.WAITING_FOR_USER -> JBColor.YELLOW
            TurnPhase.COMPLETED -> JBColor(0x4CAF50, 0x81C784)
            else -> JBColor.foreground()
        }
        iterationLabel.text = if (snapshot.maxIterations > 0)
            "Iteration ${snapshot.iteration}/${snapshot.maxIterations}" else ""
        tokensLabel.text = if (snapshot.tokensUsed > 0)
            "Tokens: ${snapshot.tokensUsed}" else ""
        toolLabel.text = snapshot.activeToolName?.let { "Tool: $it" } ?: ""
    }
}

package pl.jclab.refio.ui.components.chat

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.core.services.SessionStats
import pl.jclab.refio.core.services.SessionStatsCalculator
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.FlowLayout
import javax.swing.JComponent

/**
 * Compact one-line stats summary shown under the conversation toolbar.
 *
 * Format: ⏱ 7h 15m · ⚙ 4s · ↓1409 ↑165 · $0.0023
 *
 * Stateless: builds from the current message snapshot. Re-create on each refresh
 * to reflect live updates (ChatView recreates toolbar row on every messages change).
 */
internal object SessionStatsBar {

    fun create(messages: List<Message>): JComponent {
        val stats = SessionStatsCalculator.compute(messages)
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            background = LCATheme.backgroundColor
            isOpaque = false
            border = LCATheme.paddedBorder(0, 0, 4, 4)
        }

        if (stats.isEmpty) {
            panel.isVisible = false
            return panel
        }

        panel.add(label("⏱ ${formatDuration(stats.durationMs)}", "Total session duration (first → last message)"))
        panel.add(separator())
        panel.add(label("⚙ ${formatDuration(stats.generationMs)}", "Generation time (LLM latency + tool execution)"))
        panel.add(separator())
        panel.add(label("↓${stats.tokensIn} ↑${stats.tokensOut}", "Tokens in / out"))
        if (stats.costUsd > 0) {
            panel.add(separator())
            panel.add(label("$" + String.format("%.4f", stats.costUsd), "Total cost (USD)"))
        }
        return panel
    }

    private fun label(text: String, tooltip: String): JBLabel = JBLabel(text).apply {
        foreground = LCATheme.grayColor
        font = font.deriveFont(11f)
        toolTipText = tooltip
    }

    private fun separator(): JBLabel = JBLabel("·").apply {
        foreground = LCATheme.grayColor
        font = font.deriveFont(11f)
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "—"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    @Suppress("unused")
    fun statsOf(messages: List<Message>): SessionStats = SessionStatsCalculator.compute(messages)
}

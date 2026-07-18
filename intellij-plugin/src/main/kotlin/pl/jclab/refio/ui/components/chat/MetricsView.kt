package pl.jclab.refio.ui.components.chat

import com.intellij.ui.components.JBPanel
import pl.jclab.refio.core.db.MessageMetrics
import pl.jclab.refio.core.db.ToolUsageMetric
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.*
import javax.swing.*

/**
 * Component wyświetlający metryki wykonania pod wiadomością
 */
class MetricsView(private val metrics: MessageMetrics) : JBPanel<MetricsView>(GridBagLayout()) {

    init {
        border = BorderFactory.createCompoundBorder(
            LCATheme.paddedBorder(8, 0, 4, 0),
            BorderFactory.createLineBorder(LCATheme.grayColor.darker(), 1)
        )
        background = LCATheme.darkenedBackground

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = LCATheme.insets(4, 8, 0, 0)
        }

        // Model row
        if (metrics.model != null && metrics.provider != null) {
            add(createMetricLabel("Model:", formatModel(metrics.provider ?: "", metrics.model ?: "")), gbc)
            gbc.gridy++
        }

        // Time row
        if (metrics.latencyMs > 0) {
            add(createMetricLabel("Time:", formatTime(metrics.latencyMs)), gbc)
            gbc.gridy++
        }

        // Tokens row
        if (metrics.totalTokens > 0) {
            add(createMetricLabel("Tokens:", formatTokens(metrics.inputTokens, metrics.outputTokens)), gbc)
            gbc.gridy++
        }

        // Cost row
        if (metrics.costUsd > 0.0) {
            add(createMetricLabel("Cost:", formatCost(metrics.costUsd)), gbc)
            gbc.gridy++
        }

        // Tools row (dla Agent mode)
        if (metrics.toolsUsed.isNotEmpty()) {
            add(createMetricLabel("Tools:", formatTools(metrics.toolsUsed)), gbc)
            gbc.gridy++
        }
    }

    private fun createMetricLabel(label: String, value: String): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = this@MetricsView.background
            isOpaque = false

            add(JLabel(label).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = LCATheme.grayColor
            })

            add(Box.createHorizontalStrut(4))

            add(JLabel(value).apply {
                font = font.deriveFont(11f)
                foreground = LCATheme.labelForeground
            })
        }
    }

    private fun formatModel(provider: String, model: String): String {
        return "$provider/$model"
    }

    private fun formatTime(latencyMs: Int): String {
        return when {
            latencyMs < 1000 -> "${latencyMs}ms"
            latencyMs < 60000 -> String.format("%.2fs", latencyMs / 1000.0)
            else -> {
                val minutes = latencyMs / 60000
                val seconds = (latencyMs % 60000) / 1000
                "${minutes}m ${seconds}s"
            }
        }
    }

    private fun formatTokens(inputTokens: Int, outputTokens: Int): String {
        return "${formatNumber(inputTokens)} in / ${formatNumber(outputTokens)} out"
    }

    private fun formatCost(costUsd: Double): String {
        return when {
            costUsd < 0.0001 -> String.format("$%.6f", costUsd)
            costUsd < 0.01 -> String.format("$%.4f", costUsd)
            else -> String.format("$%.2f", costUsd)
        }
    }

    private fun formatTools(toolsUsed: List<ToolUsageMetric>): String {
        val toolNames = toolsUsed.map { tool ->
            val status = if (tool.success) "✓" else "✗"
            "$status ${tool.toolName}"
        }
        return toolNames.joinToString(", ")
    }

    private fun formatNumber(num: Int): String {
        return when {
            num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
            num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
            else -> num.toString()
        }
    }
}

/**
 * Extension dla MessageMetrics
 */
fun MessageMetrics.toMetricsView(): MetricsView {
    return MetricsView(this)
}

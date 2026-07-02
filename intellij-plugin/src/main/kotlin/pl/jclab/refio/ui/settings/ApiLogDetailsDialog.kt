package pl.jclab.refio.ui.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import pl.jclab.refio.core.db.ApiLog
import pl.jclab.refio.core.security.SecureLogger
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Dialog displaying detailed information about a single API log entry
 */
class ApiLogDetailsDialog(
    parent: Component,
    private val log: ApiLog
) : DialogWrapper(parent, true) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    init {
        title = "API Log Details"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(LCATheme.margin)
            preferredSize = Dimension(800, 600)
        }

        // Main scroll pane
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Header section
        contentPanel.add(createHeaderSection())
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))

        // Metadata section
        contentPanel.add(createMetadataSection())
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))

        // Metrics section
        contentPanel.add(createMetricsSection())
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))

        // Error section (if applicable)
        if (log.errorMessage != null || log.errorType != null) {
            contentPanel.add(createErrorSection())
            contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))
        }

        // Request payload section
        contentPanel.add(createPayloadSection("Request Payload", log.requestPayload))
        contentPanel.add(Box.createVerticalStrut(LCATheme.spacingLg))

        // Response payload section
        val responsePayload = log.responsePayload
        if (responsePayload != null) {
            contentPanel.add(createPayloadSection("Response Payload", responsePayload))
        }

        panel.add(JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)

        return panel
    }

    private fun createHeaderSection(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = LCATheme.createTitledBorder("Log Information")

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = LCATheme.insetsMedium
                fill = GridBagConstraints.HORIZONTAL
            }

            // ID
            addField(this, gbc, "ID:", log.id)
            gbc.gridy++

            // Timestamp
            addField(this, gbc, "Timestamp:", dateFormat.format(Date(log.createdAt)))
            gbc.gridy++

            // Task ID
            addField(this, gbc, "Task ID:", log.taskId ?: "-")
            gbc.gridy++

            // Subtask ID
            addField(this, gbc, "Subtask ID:", log.subtaskId ?: "-")
        }
    }

    private fun createMetadataSection(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = LCATheme.createTitledBorder("Provider & Model")

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = LCATheme.insetsMedium
                fill = GridBagConstraints.HORIZONTAL
            }

            // Provider
            addField(this, gbc, "Provider:", log.provider)
            gbc.gridy++

            // Model
            addField(this, gbc, "Model:", log.model)
            gbc.gridy++

            // Endpoint
            addField(this, gbc, "Endpoint:", log.endpoint)
            gbc.gridy++

            // Source
            addField(this, gbc, "Source:", log.requestSource ?: "-")
            gbc.gridy++

            // HTTP Status
            val httpStatus = log.httpStatus
            val statusColor = if (httpStatus != null) {
                when {
                    httpStatus in 200..299 -> LCATheme.successColor
                    httpStatus in 400..499 -> LCATheme.warningColor
                    httpStatus >= 500 -> LCATheme.errorColor
                    else -> LCATheme.labelForeground
                }
            } else {
                null
            }
            addField(this, gbc, "HTTP Status:", httpStatus?.toString() ?: "-", statusColor)
        }
    }

    private fun createMetricsSection(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = LCATheme.createTitledBorder("Metrics")

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = LCATheme.insetsMedium
                fill = GridBagConstraints.HORIZONTAL
            }

            // Input tokens
            addField(this, gbc, "Input Tokens:", formatNumber(log.inputTokens))
            gbc.gridy++

            // Output tokens
            addField(this, gbc, "Output Tokens:", formatNumber(log.outputTokens))
            gbc.gridy++

            // Total tokens
            val totalTokens = log.inputTokens + log.outputTokens
            addField(this, gbc, "Total Tokens:", formatNumber(totalTokens))
            gbc.gridy++

            // Cost
            addField(this, gbc, "Cost (USD):", String.format("$%.6f", log.costUsd))
            gbc.gridy++

            // Latency
            addField(this, gbc, "Latency:", "${formatNumber(log.latencyMs)} ms")
        }
    }

    private fun createErrorSection(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = LCATheme.createTitledBorder("Error Information")

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = LCATheme.insetsMedium
                fill = GridBagConstraints.HORIZONTAL
            }

            // Error type
            val errorType = log.errorType
            if (errorType != null) {
                addField(this, gbc, "Error Type:", errorType)
                gbc.gridy++
            }

            // Error message
            if (log.errorMessage != null) {
                gbc.gridwidth = 2
                gbc.weightx = 1.0
                add(JBLabel("Error Message:").apply {
                    font = LCATheme.headerFont
                }, gbc)
                gbc.gridy++

                add(JBTextArea(SecureLogger.redact(log.errorMessage ?: "")).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    rows = 3
                    background = LCATheme.editorBackground
                    foreground = LCATheme.errorColor
                    border = JBUI.Borders.customLine(LCATheme.borderColor, 1)
                }, gbc)
            }
        }
    }

    private fun createPayloadSection(title: String, payload: String): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.createTitledBorder(title)

            // API keys / tokens must never be shown or exported from this dialog
            val textArea = JBTextArea(formatJson(SecureLogger.redact(payload))).apply {
                isEditable = false
                lineWrap = false
                font = LCATheme.monoFont
                background = LCATheme.editorBackground
                border = JBUI.Borders.empty(LCATheme.padding)
            }

            val scrollPane = JBScrollPane(textArea).apply {
                preferredSize = Dimension(750, 150)
                border = JBUI.Borders.customLine(LCATheme.borderColor, 1)
            }

            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun addField(panel: JPanel, gbc: GridBagConstraints, label: String, value: String, valueColor: Color? = null) {
        // Label
        gbc.gridx = 0
        gbc.weightx = 0.3
        panel.add(JBLabel(label).apply {
            font = LCATheme.headerFont
        }, gbc)

        // Value
        gbc.gridx = 1
        gbc.weightx = 0.7
        panel.add(JBLabel(value).apply {
            font = LCATheme.monoFont
            valueColor?.let { foreground = it }
        }, gbc)
    }

    private fun formatNumber(number: Int): String {
        return String.format("%,d", number)
    }

    private fun formatJson(json: String): String {
        // Simple JSON formatting - add indentation
        // This is a basic implementation; for production, consider using a proper JSON library
        return try {
            json.replace(",", ",\n  ")
                .replace("{", "{\n  ")
                .replace("}", "\n}")
                .replace("[", "[\n  ")
                .replace("]", "\n]")
        } catch (e: Exception) {
            json
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            object : DialogWrapperAction("Copy to Clipboard") {
                override fun doAction(e: ActionEvent?) {
                    copyToClipboard()
                }
            },
            object : DialogWrapperAction("Save to File") {
                override fun doAction(e: ActionEvent?) {
                    saveToFile()
                }
            },
            okAction
        )
    }

    private fun copyToClipboard() {
        val jsonContent = buildJsonContent()
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(jsonContent)
        clipboard.setContents(selection, null)
    }

    private fun saveToFile() {
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Save API Log"
            selectedFile = File("api-log-${log.id}.json")
            fileFilter = FileNameExtensionFilter("JSON Files", "json")
        }

        if (fileChooser.showSaveDialog(contentPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                val jsonContent = buildJsonContent()
                fileChooser.selectedFile.writeText(jsonContent)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Failed to save file:\n${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    private fun buildJsonContent(): String {
        return """
{
    "id": "${log.id}",
    "taskId": ${if (log.taskId != null) "\"${log.taskId}\"" else "null"},
    "subtaskId": ${if (log.subtaskId != null) "\"${log.subtaskId}\"" else "null"},
    "provider": "${log.provider}",
    "model": "${log.model}",
    "endpoint": "${log.endpoint}",
    "requestSource": ${if (log.requestSource != null) "\"${log.requestSource}\"" else "null"},
    "httpStatus": ${log.httpStatus ?: "null"},
    "inputTokens": ${log.inputTokens},
    "outputTokens": ${log.outputTokens},
    "costUsd": ${log.costUsd},
    "latencyMs": ${log.latencyMs},
    "errorType": ${if (log.errorType != null) "\"${log.errorType}\"" else "null"},
    "errorMessage": ${log.errorMessage?.let { "\"${escapeJson(SecureLogger.redact(it))}\"" } ?: "null"},
    "createdAt": ${log.createdAt},
    "requestPayload": ${formatPayloadForJson(log.requestPayload)},
    "responsePayload": ${log.responsePayload?.let { formatPayloadForJson(it) } ?: "null"}
}
        """.trimIndent()
    }

    private fun formatPayloadForJson(payload: String): String {
        // Redact secrets before escaping so exported JSON never carries keys/tokens
        return "\"${escapeJson(SecureLogger.redact(payload))}\""
    }

    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

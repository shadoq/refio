package pl.jclab.refio.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.jclab.refio.core.context.mcp.MCPAccessMode
import pl.jclab.refio.core.context.mcp.MCPAuthConfig
import pl.jclab.refio.core.context.mcp.MCPAuthType
import pl.jclab.refio.core.context.mcp.MCPEnvVariable
import pl.jclab.refio.core.context.mcp.MCPHttpHeader
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.context.mcp.MCPServerPresets
import pl.jclab.refio.core.context.mcp.MCPServerType
import pl.jclab.refio.core.context.mcp.MCPTestResult
import pl.jclab.refio.core.context.mcp.MCPTestRunner
import pl.jclab.refio.core.context.mcp.MCPToolWorkflowConfig
import pl.jclab.refio.core.context.mcp.MCPToolWorkflowStep
import pl.jclab.refio.core.context.mcp.MCPToolsExposureMode
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Paths
import javax.swing.*
import javax.swing.BoxLayout
import javax.swing.border.EmptyBorder

class MCPSettingsPanel(private val project: Project) : JBPanel<MCPSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("MCPSettingsPanel")
    private val projectId: String? = project.basePath?.let { ProjectIdGenerator.generate(Paths.get(it)) }
    private var serversListPanel: JPanel = JBPanel<JBPanel<*>>()
    private val presetSelector = JComboBox(MCPServerPresets.ALL.toTypedArray())
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        border = LCATheme.paddedBorder(LCATheme.margin)
        // MCPManager is already initialized by CoreConnectionManager.createProjectRouter()
        // with the correct ToolRegistry. Do NOT re-initialize here without ToolRegistry!
        add(createSectionPanel("MCP Servers", createServersSection()), BorderLayout.CENTER)
        refreshServers()
    }

    fun reload() {
        refreshServers()
    }

    fun disposePanel() {
        // no-op
    }

    private fun createServersSection(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(LCATheme.padding)

            val description = JBLabel(
                "<html>Configure MCP servers. In PLAN mode only read resources, AGENT mode enables tools.<br>" +
                "Servers marked as <b>Enabled</b> will automatically connect at project startup or when creating a session.<br>" +
                "Configurations are stored in the database per project.</html>"
            ).apply {
                foreground = LCATheme.descriptionForeground
                border = LCATheme.paddedBorder(0, 0, 12, 0)
            }
            add(description, BorderLayout.NORTH)

            serversListPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            add(JBScrollPane(serversListPanel), BorderLayout.CENTER)

            val actions = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = LCATheme.paddedBorder(8, 0, 0, 0)

                // Row 1: Add Custom Server + Refresh
                add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                    add(JButton("Add Custom Server").apply {
                        toolTipText = "Add a custom MCP server with manual configuration"
                        addActionListener { openConfigDialog(null) }
                    })
                    add(JButton("Refresh").apply {
                        toolTipText = "Refresh the server list"
                        addActionListener { refreshServers() }
                    })
                })

                add(Box.createVerticalStrut(4))

                // Row 2: Quick Add in one line
                add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                    add(JBLabel("Quick Add:"))

                    presetSelector.renderer = object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(
                            list: JList<*>?,
                            value: Any?,
                            index: Int,
                            isSelected: Boolean,
                            cellHasFocus: Boolean
                        ): Component {
                            val preset = value as? MCPServerPresets.Preset
                            val label = preset?.let { "${it.category.icon} ${it.label}" } ?: "Select preset..."
                            return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
                        }
                    }
                    presetSelector.toolTipText = "Select a preset MCP server to quickly add"
                    presetSelector.preferredSize = java.awt.Dimension(200, presetSelector.preferredSize.height)

                    add(presetSelector)
                    add(JButton("Add").apply {
                        toolTipText = "Add the selected preset server"
                        addActionListener { addPresetFromSelection() }
                    })
                })
            }
            add(actions, BorderLayout.SOUTH)
        }
    }

    private fun addPresetFromSelection() {
        val preset = presetSelector.selectedItem as? MCPServerPresets.Preset ?: return
        val existingIds = MCPManager.getAllServers(projectId).map { it.id }.toSet()
        var candidateId = preset.id
        var suffix = 2
        while (existingIds.contains(candidateId)) {
            candidateId = "${preset.id}-$suffix"
            suffix++
        }
        val config = preset.build(project.basePath).copy(id = candidateId)
        MCPManager.addOrUpdateServer(projectId, config)
        refreshServers()
    }

    private fun refreshServers() {
        val servers = MCPManager.getAllServers(projectId)
        serversListPanel.removeAll()

        if (servers.isEmpty()) {
            serversListPanel.add(JBLabel("No MCP servers configured").apply {
                foreground = LCATheme.descriptionForeground
                border = LCATheme.paddedBorder(LCATheme.padding)
            })
        } else {
            servers.forEach { config ->
                serversListPanel.add(createServerCard(config))
                serversListPanel.add(Box.createVerticalStrut(8))
            }
        }

        serversListPanel.revalidate()
        serversListPanel.repaint()
    }

    private fun createServerCard(config: MCPServerConfig): JPanel {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            LCATheme.paddedBorder(4),
            BorderFactory.createLineBorder(LCATheme.borderColor, 1, true)
        )

        // Info section
        val infoPanel = JBPanel<JBPanel<*>>()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.border = LCATheme.paddedBorder(4)
        infoPanel.alignmentX = Component.LEFT_ALIGNMENT

        // Status badge
        val status = MCPManager.getServerStatus(projectId, config.id)
        val statusText = when (status) {
            pl.jclab.refio.core.context.mcp.MCPServerStatus.CONNECTED -> "<font color='#4CAF50'>● CONNECTED</font>"
            pl.jclab.refio.core.context.mcp.MCPServerStatus.CONNECTING -> "<font color='#FF9800'>● CONNECTING</font>"
            pl.jclab.refio.core.context.mcp.MCPServerStatus.DISCONNECTED -> "<font color='#9E9E9E'>● DISCONNECTED</font>"
            pl.jclab.refio.core.context.mcp.MCPServerStatus.ERROR -> "<font color='#F44336'>● ERROR</font>"
            pl.jclab.refio.core.context.mcp.MCPServerStatus.DISABLED -> "<font color='#757575'>● DISABLED</font>"
            pl.jclab.refio.core.context.mcp.MCPServerStatus.NEEDS_AUTH -> "<font color='#FF5722'>● NEEDS AUTH</font>"
            pl.jclab.refio.core.context.mcp.MCPServerStatus.STALE -> "<font color='#FFC107'>● STALE</font>"
        }

        infoPanel.add(JBLabel("<html><b>@${config.id}</b> (${config.displayName ?: "Unnamed"}) $statusText</html>"))
        infoPanel.add(Box.createVerticalStrut(2))
        infoPanel.add(JBLabel("Type: ${config.type} | Mode: ${config.accessMode}").apply {
            foreground = LCATheme.descriptionForeground
        })
        infoPanel.add(Box.createVerticalStrut(2))
        infoPanel.add(JBLabel("Command/URL: ${config.command ?: config.url ?: "-"}").apply {
            foreground = LCATheme.descriptionForeground
        })

        panel.add(infoPanel)
        panel.add(Box.createVerticalStrut(4))

        // Buttons section - all in one row
        val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            border = LCATheme.paddedBorder(0, 4, 4, 4)

            add(JButton("Edit").apply {
                toolTipText = "Edit server configuration"
                addActionListener { openConfigDialog(config) }
            })
            add(JButton("Remove").apply {
                toolTipText = "Remove this server"
                addActionListener {
                    val confirm = JOptionPane.showConfirmDialog(
                        this@MCPSettingsPanel,
                        "Remove MCP server ${config.id}?",
                        "Confirm",
                        JOptionPane.YES_NO_OPTION
                    )
                    if (confirm == JOptionPane.YES_OPTION) {
                        MCPManager.removeServer(projectId, config.id)
                        refreshServers()
                    }
                }
            })
            val testButton = JButton("Test Connection").apply {
                toolTipText = "Test MCP server connection and view request/response"
                addActionListener { testConnection(config, this) }
            }
            add(testButton)
            add(JCheckBox("Enabled", config.enabled).apply {
                toolTipText = "Enable/disable auto-connect"
                addActionListener {
                    val updated = config.copy(enabled = isSelected)
                    MCPManager.addOrUpdateServer(projectId, updated)
                    refreshServers()
                }
            })
        }

        panel.add(buttonsPanel)
        return panel
    }

    private fun testConnection(config: MCPServerConfig, button: JButton) {
        button.isEnabled = false
        coroutineScope.launch {
            val result = runCatching { MCPTestRunner.test(config) }
            ApplicationManager.getApplication().invokeLater {
                if (result.isSuccess) {
                    showTestResultDialog(config, result.getOrThrow())
                } else {
                    showTestResultDialog(
                        config,
                        MCPTestResult(
                            requestDetails = "Test request could not be built.",
                            responseDetails = null,
                            errorMessage = result.exceptionOrNull()?.message ?: "Unknown error"
                        )
                    )
                }
                button.isEnabled = true
            }
        }
    }

    private fun showTestResultDialog(config: MCPServerConfig, result: MCPTestResult) {
        val dialog = MCPTestResultDialog(project, config, result)
        dialog.show()
    }

    private fun openConfigDialog(existing: MCPServerConfig?) {
        val dialog = MCPServerConfigDialog(project, existing)
        if (dialog.showAndGet()) {
            val config = dialog.getConfig()
            MCPManager.addOrUpdateServer(projectId, config)
            refreshServers()
        }
    }

    private fun createSectionPanel(title: String, content: JPanel): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                    LCATheme.customLineBorder(LCATheme.borderColor, 1),
                    title
                ),
                LCATheme.paddedBorder(LCATheme.padding)
            )
            add(content, BorderLayout.CENTER)
        }
    }
}

private class MCPServerConfigDialog(
    private val project: Project,
    private val existing: MCPServerConfig?
) : DialogWrapper(project, true) {

    // Auto-generate ID for new servers
    private val idField = JBTextField(existing?.id ?: generateServerId()).apply {
        isEditable = existing == null
        toolTipText = "Unique identifier (auto-generated)"
    }
    private val nameField = JBTextField(existing?.displayName ?: "")
    private val descField = JBTextField(existing?.description ?: "")
    private val typeCombo = JComboBox(MCPServerType.values())
    private val accessCombo = JComboBox(MCPAccessMode.values())
    private val authTypeCombo = JComboBox(MCPAuthType.values()).apply {
        selectedItem = existing?.auth?.type ?: MCPAuthType.NONE
    }
    private val apiKeyField = JBPasswordField().apply {
        text = existing?.auth?.apiKey ?: ""
    }
    private val toolsExposureCombo = JComboBox(MCPToolsExposureMode.values()).apply {
        selectedItem = existing?.toolsExposureMode ?: MCPToolsExposureMode.TOOLS
    }
    private val contextToolNameField = JBTextField(existing?.contextToolName ?: "")
    private val contextToolParamField = JBTextField(existing?.contextToolQueryParam ?: "query")

    // STDIO fields - default working dir to project root
    private val commandField = JBTextField(existing?.command ?: "")
    private val argsField = JBTextField(existing?.args?.joinToString(",") ?: "")
    private val workingDirField = JBTextField(existing?.workingDirectory ?: project.basePath ?: ".").apply {
        toolTipText = "Defaults to project root"
    }
    private val stdioPanel = JBPanel<JBPanel<*>>()

    // HTTP fields
    private val urlField = JBTextField(existing?.url ?: "")
    private val httpPanel = JBPanel<JBPanel<*>>()

    private val instructionsArea = JBTextArea(existing?.serverInstructions ?: "", 3, 40)
    private val headersContainer = JBPanel<JBPanel<*>>().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val envContainer = JBPanel<JBPanel<*>>().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val toolParamsContainer = JBPanel<JBPanel<*>>().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val workflowStepsContainer = JBPanel<JBPanel<*>>().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val headerRows = mutableListOf<KeyValueRow>()
    private val envRows = mutableListOf<KeyValueRow>()
    private val toolParamRows = mutableListOf<KeyValueRow>()
    private val workflowStepRows = mutableListOf<WorkflowStepRow>()
    private val enabledBox = JCheckBox("Enabled", existing?.enabled ?: true)
    private val toolsEnabledBox = JCheckBox("Enable tools", existing?.toolsEnabled ?: true)
    private val resourcesEnabledBox = JCheckBox("Enable resources", existing?.resourcesEnabled ?: true)
    private val promptsEnabledBox = JCheckBox("Enable prompts", existing?.promptsEnabled ?: true)
    private val toolsContextPanel = JBPanel<JBPanel<*>>()

    // Warning label for READ_WRITE
    private val readWriteWarning = JBLabel("<html><font color='#E65100'>⚠️ Warning: READ_WRITE mode allows the MCP server to execute actions.</font></html>")

    init {
        title = if (existing == null) "Add MCP Server" else "Edit MCP Server"
        typeCombo.selectedItem = existing?.type ?: MCPServerType.STDIO
        accessCombo.selectedItem = existing?.accessMode ?: MCPAccessMode.READ

        // Initialize with existing data or create 3 empty rows for better UX
        if (existing != null) {
            existing.httpHeaders.forEach { addHeaderRow(it) }
            existing.env.forEach { addEnvRow(it) }
            existing.toolParamMapping.forEach { (toolName, paramName) ->
                addToolParamRow(toolName, paramName)
            }
            existing.toolWorkflow?.steps?.forEach { step ->
                addWorkflowStepRow(step.toolName, step.inputMapping, step.outputMapping)
            }
        }

        // Add at least 3 empty rows for headers and env vars
        while (headerRows.size < 3) addHeaderRow(null)
        while (envRows.size < 3) addEnvRow(null)
        while (toolParamRows.size < 3) addToolParamRow("", "")
        while (workflowStepRows.size < 2) addWorkflowStepRow("", emptyMap(), emptyMap())

        // Setup conditional visibility
        typeCombo.addActionListener { updateVisibility() }
        accessCombo.addActionListener { updateWarningVisibility() }
        authTypeCombo.addActionListener { updateAuthVisibility() }
        toolsExposureCombo.addActionListener { updateToolsExposureVisibility() }

        init()
        updateVisibility()
        updateWarningVisibility()
        updateAuthVisibility()
        updateToolsExposureVisibility()
    }

    private fun generateServerId(): String {
        return "mcp-${System.currentTimeMillis() % 100000}"
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JBPanel<JBPanel<*>>()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        mainPanel.border = EmptyBorder(8, 8, 8, 8)

        // Basic Info Section
        mainPanel.add(createSection("Basic Information", createBasicInfoPanel()))
        mainPanel.add(Box.createVerticalStrut(8))

        // Server Type Section
        mainPanel.add(createSection("Server Type", createTypePanel()))
        mainPanel.add(Box.createVerticalStrut(8))

        // STDIO Config (conditional)
        stdioPanel.layout = BorderLayout()
        stdioPanel.add(createStdioConfigPanel(), BorderLayout.CENTER)
        mainPanel.add(stdioPanel)

        // HTTP Config (conditional)
        httpPanel.layout = BorderLayout()
        httpPanel.add(createHttpConfigPanel(), BorderLayout.CENTER)
        mainPanel.add(httpPanel)

        // Instructions Section
        mainPanel.add(createSection("Server Instructions (Optional)", createInstructionsPanel()))
        mainPanel.add(Box.createVerticalStrut(8))

        // Advanced Configuration
        mainPanel.add(createSection("Advanced Configuration", createAdvancedPanel()))
        mainPanel.add(Box.createVerticalStrut(8))

        // Access Mode Section
        mainPanel.add(createSection("Access Mode", createAccessModePanel()))
        mainPanel.add(Box.createVerticalStrut(8))

        // Behavior Section
        mainPanel.add(createSection("Behavior", createBehaviorPanel()))

        return JBScrollPane(mainPanel).apply {
            border = null
            preferredSize = java.awt.Dimension(600, 500)
        }
    }

    private fun createSection(title: String, content: JPanel): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                    LCATheme.customLineBorder(LCATheme.borderColor, 1),
                    title
                ),
                LCATheme.paddedBorder(8)
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun createBasicInfoPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = java.awt.Insets(4, 4, 4, 4)
        }

        fun addRow(label: String, component: JComponent, row: Int) {
            gbc.gridx = 0
            gbc.gridy = row
            gbc.weightx = 0.0
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(component, gbc)
        }

        addRow("ID:", idField, 0)
        addRow("Display Name:", nameField, 1)
        addRow("Description:", descField, 2)

        return panel
    }

    private fun createTypePanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT))
        panel.add(JBLabel("Type:"))
        panel.add(typeCombo)
        return panel
    }

    private fun createStdioConfigPanel(): JPanel {
        val section = createSection("Local Process (STDIO)", JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(4, 4, 4, 4)
            }

            fun addRow(label: String, component: JComponent, row: Int) {
                gbc.gridx = 0
                gbc.gridy = row
                gbc.weightx = 0.0
                add(JBLabel(label), gbc)
                gbc.gridx = 1
                gbc.weightx = 1.0
                add(component, gbc)
            }

            addRow("Command:", commandField, 0)
            addRow("Args (comma):", argsField, 1)
            addRow("Working Dir:", workingDirField, 2)
        })
        return section
    }

    private fun createHttpConfigPanel(): JPanel {
        val section = createSection("HTTP/SSE Configuration", JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(4, 4, 4, 4)
            }

            fun addRow(label: String, component: JComponent, row: Int) {
                gbc.gridx = 0
                gbc.gridy = row
                gbc.weightx = 0.0
                add(JBLabel(label), gbc)
                gbc.gridx = 1
                gbc.weightx = 1.0
                add(component, gbc)
            }

            addRow("URL:", urlField, 0)
            addRow("Auth Type:", authTypeCombo, 1)
            addRow("API Key:", apiKeyField, 2)
        })
        return section
    }

    private fun createInstructionsPanel(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBScrollPane(instructionsArea), BorderLayout.CENTER)
        }
    }

    private fun createAdvancedPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // HTTP Headers section with scrollable container
        panel.add(JBLabel("HTTP Headers:"))
        panel.add(Box.createVerticalStrut(4))
        val headersScrollPane = JBScrollPane(headersContainer).apply {
            preferredSize = java.awt.Dimension(500, 100)
            minimumSize = java.awt.Dimension(400, 80)
        }
        panel.add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(headersScrollPane, BorderLayout.CENTER)
            add(JButton("+ Add Header").apply {
                addActionListener { addHeaderRow(null) }
            }, BorderLayout.SOUTH)
        })
        panel.add(Box.createVerticalStrut(8))

        // Environment Variables section with scrollable container
        panel.add(JBLabel("Environment Variables:"))
        panel.add(Box.createVerticalStrut(4))
        val envScrollPane = JBScrollPane(envContainer).apply {
            preferredSize = java.awt.Dimension(500, 100)
            minimumSize = java.awt.Dimension(400, 80)
        }
        panel.add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(envScrollPane, BorderLayout.CENTER)
            add(JButton("+ Add Variable").apply {
                addActionListener { addEnvRow(null) }
            }, BorderLayout.SOUTH)
        })

        panel.add(Box.createVerticalStrut(8))

        panel.add(JBLabel("Tool Param Mapping (tool -> param):"))
        panel.add(Box.createVerticalStrut(4))
        val toolParamScrollPane = JBScrollPane(toolParamsContainer).apply {
            preferredSize = java.awt.Dimension(500, 100)
            minimumSize = java.awt.Dimension(400, 80)
        }
        panel.add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolParamScrollPane, BorderLayout.CENTER)
            add(JButton("+ Add Mapping").apply {
                addActionListener { addToolParamRow("", "") }
            }, BorderLayout.SOUTH)
        })

        return panel
    }

    private fun createAccessModePanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        panel.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Access Mode:"))
            add(accessCombo)
        })

        panel.add(Box.createVerticalStrut(4))
        panel.add(readWriteWarning)

        return panel
    }

    private fun createBehaviorPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        panel.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(enabledBox)
        })

        panel.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(resourcesEnabledBox)
            add(toolsEnabledBox)
            add(promptsEnabledBox)
        })

        panel.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Tools exposure:"))
            add(toolsExposureCombo)
        })

        toolsContextPanel.layout = BoxLayout(toolsContextPanel, BoxLayout.Y_AXIS)
        toolsContextPanel.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Context tool name:"))
            add(contextToolNameField)
        })
        toolsContextPanel.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Context query param:"))
            add(contextToolParamField)
        })
        toolsContextPanel.add(Box.createVerticalStrut(6))
        toolsContextPanel.add(JBLabel("Tool workflow steps (tool, inputs, outputs):"))
        toolsContextPanel.add(Box.createVerticalStrut(4))
        val workflowScrollPane = JBScrollPane(workflowStepsContainer).apply {
            preferredSize = java.awt.Dimension(500, 120)
            minimumSize = java.awt.Dimension(400, 80)
        }
        toolsContextPanel.add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(workflowScrollPane, BorderLayout.CENTER)
            add(JButton("+ Add Step").apply {
                addActionListener { addWorkflowStepRow("", emptyMap(), emptyMap()) }
            }, BorderLayout.SOUTH)
        })
        panel.add(toolsContextPanel)

        return panel
    }

    private fun updateVisibility() {
        val isStdio = typeCombo.selectedItem == MCPServerType.STDIO
        stdioPanel.isVisible = isStdio
        httpPanel.isVisible = !isStdio
    }

    private fun updateWarningVisibility() {
        readWriteWarning.isVisible = accessCombo.selectedItem == MCPAccessMode.READ_WRITE
    }

    private fun updateAuthVisibility() {
        val isBearer = authTypeCombo.selectedItem == MCPAuthType.BEARER
        apiKeyField.isEnabled = isBearer
        apiKeyField.isVisible = isBearer
    }

    private fun updateToolsExposureVisibility() {
        toolsContextPanel.isVisible = toolsExposureCombo.selectedItem == MCPToolsExposureMode.CONTEXT
    }

    fun getConfig(): MCPServerConfig {
        val id = idField.text.trim()
        require(id.isNotBlank()) { "ID is required" }

        val authType = authTypeCombo.selectedItem as MCPAuthType
        val apiKey = String(apiKeyField.password).trim().ifBlank { null }
        val authConfig = if (authType == MCPAuthType.NONE) {
            null
        } else {
            MCPAuthConfig(type = authType, apiKey = apiKey, isSecret = true)
        }
        val toolsExposureMode = toolsExposureCombo.selectedItem as MCPToolsExposureMode

        return MCPServerConfig(
            id = id,
            displayName = nameField.text.trim().ifBlank { null },
            description = descField.text.trim().ifBlank { null },
            type = typeCombo.selectedItem as MCPServerType,
            command = commandField.text.trim().ifBlank { null },
            args = argsField.text.split(',').mapNotNull { it.trim().takeIf { part -> part.isNotEmpty() } },
            workingDirectory = workingDirField.text.trim().ifBlank { null },
            url = urlField.text.trim().ifBlank { null },
            oauth = existing?.oauth,
            auth = authConfig,
            httpHeaders = headerRows.mapNotNull { row ->
                val name = row.nameField.text.trim()
                val value = row.valueField.text.trim()
                if (name.isBlank() || value.isBlank()) null else MCPHttpHeader(name, value, row.secretBox.isSelected)
            },
            env = envRows.mapNotNull { row ->
                val name = row.nameField.text.trim()
                val value = row.valueField.text.trim()
                if (name.isBlank() || value.isBlank()) null else MCPEnvVariable(name, value, row.secretBox.isSelected)
            },
            serverInstructions = instructionsArea.text.trim().ifBlank { null },
            accessMode = accessCombo.selectedItem as MCPAccessMode,
            enabled = enabledBox.isSelected,
            timeout = existing?.timeout ?: 30_000,
            retryAttempts = existing?.retryAttempts ?: 3,
            retryDelayMs = existing?.retryDelayMs ?: 5_000,
            resourcesEnabled = resourcesEnabledBox.isSelected,
            toolsEnabled = toolsEnabledBox.isSelected,
            toolsExposureMode = toolsExposureMode,
            toolParamMapping = toolParamRows.mapNotNull { row ->
                val toolName = row.nameField.text.trim()
                val paramName = row.valueField.text.trim()
                if (toolName.isBlank() || paramName.isBlank()) null else toolName to paramName
            }.toMap(),
            toolWorkflow = if (toolsExposureMode == MCPToolsExposureMode.CONTEXT) {
                val steps = workflowStepRows.mapNotNull { row ->
                    val toolName = row.toolField.text.trim()
                    if (toolName.isBlank()) return@mapNotNull null
                    val inputs = parseMapping(row.inputsField.text)
                    val outputs = parseMapping(row.outputsField.text)
                    MCPToolWorkflowStep(
                        toolName = toolName,
                        inputMapping = inputs,
                        outputMapping = outputs
                    )
                }
                if (steps.isEmpty()) null else MCPToolWorkflowConfig(steps)
            } else null,
            contextToolName = if (toolsExposureMode == MCPToolsExposureMode.CONTEXT) {
                contextToolNameField.text.trim().ifBlank { null }
            } else null,
            contextToolQueryParam = if (toolsExposureMode == MCPToolsExposureMode.CONTEXT) {
                contextToolParamField.text.trim().ifBlank { "query" }
            } else null,
            promptsEnabled = promptsEnabledBox.isSelected
        )
    }


    private fun addHeaderRow(initial: MCPHttpHeader?) {
        val row = KeyValueRow(
            JBTextField(initial?.name ?: "", 12),
            JBTextField(initial?.value ?: "", 18),
            JCheckBox("Secret", initial?.isSecret ?: false)
        )
        headerRows.add(row)
        headersContainer.add(buildRowPanel(row) { removeHeaderRow(row) })
        headersContainer.revalidate()
        headersContainer.repaint()
    }

    private fun removeHeaderRow(row: KeyValueRow) {
        headerRows.remove(row)
        headersContainer.removeAll()
        headerRows.forEach { headersContainer.add(buildRowPanel(it) { removeHeaderRow(it) }) }
        headersContainer.revalidate()
        headersContainer.repaint()
    }

    private fun addEnvRow(initial: MCPEnvVariable?) {
        val row = KeyValueRow(
            JBTextField(initial?.name ?: "", 12),
            JBTextField(initial?.value ?: "", 18),
            JCheckBox("Secret", initial?.isSecret ?: false)
        )
        envRows.add(row)
        envContainer.add(buildRowPanel(row) { removeEnvRow(row) })
        envContainer.revalidate()
        envContainer.repaint()
    }

    private fun removeEnvRow(row: KeyValueRow) {
        envRows.remove(row)
        envContainer.removeAll()
        envRows.forEach { envContainer.add(buildRowPanel(it) { removeEnvRow(it) }) }
        envContainer.revalidate()
        envContainer.repaint()
    }

    private fun addToolParamRow(toolName: String, paramName: String) {
        val row = KeyValueRow(
            JBTextField(toolName, 12),
            JBTextField(paramName, 18),
            JCheckBox("Secret", false)
        )
        row.secretBox.isVisible = false
        toolParamRows.add(row)
        toolParamsContainer.add(buildRowPanel(row) { removeToolParamRow(row) })
        toolParamsContainer.revalidate()
        toolParamsContainer.repaint()
    }

    private fun removeToolParamRow(row: KeyValueRow) {
        toolParamRows.remove(row)
        toolParamsContainer.removeAll()
        toolParamRows.forEach { toolParamsContainer.add(buildRowPanel(it) { removeToolParamRow(it) }) }
        toolParamsContainer.revalidate()
        toolParamsContainer.repaint()
    }

    private fun addWorkflowStepRow(
        toolName: String,
        inputMapping: Map<String, String>,
        outputMapping: Map<String, String>
    ) {
        val row = WorkflowStepRow(
            JBTextField(toolName, 10),
            JBTextField(formatMapping(inputMapping), 18),
            JBTextField(formatMapping(outputMapping), 18)
        )
        workflowStepRows.add(row)
        workflowStepsContainer.add(buildWorkflowRowPanel(row) { removeWorkflowStepRow(row) })
        workflowStepsContainer.revalidate()
        workflowStepsContainer.repaint()
    }

    private fun removeWorkflowStepRow(row: WorkflowStepRow) {
        workflowStepRows.remove(row)
        workflowStepsContainer.removeAll()
        workflowStepRows.forEach { workflowStepsContainer.add(buildWorkflowRowPanel(it) { removeWorkflowStepRow(it) }) }
        workflowStepsContainer.revalidate()
        workflowStepsContainer.repaint()
    }

    private fun formatMapping(mapping: Map<String, String>): String {
        if (mapping.isEmpty()) {
            return ""
        }
        return mapping.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    private fun parseMapping(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return emptyMap()
        }
        return trimmed.split(",").mapNotNull { entry ->
            val token = entry.trim()
            if (token.isBlank()) {
                return@mapNotNull null
            }
            val parts = token.split("=", limit = 2)
            require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                "Invalid mapping entry: '$token'. Use key=value."
            }
            parts[0].trim() to parts[1].trim()
        }.toMap()
    }

    private fun buildRowPanel(row: KeyValueRow, onRemove: () -> Unit): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Name"))
            add(row.nameField)
            add(JBLabel("Value"))
            add(row.valueField)
            add(row.secretBox)
            add(JButton("Remove").apply { addActionListener { onRemove() } })
        }
    }

    private fun buildWorkflowRowPanel(row: WorkflowStepRow, onRemove: () -> Unit): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Tool"))
            add(row.toolField)
            add(JBLabel("Inputs"))
            add(row.inputsField)
            add(JBLabel("Outputs"))
            add(row.outputsField)
            add(JButton("Remove").apply { addActionListener { onRemove() } })
        }
    }
}

private class MCPTestResultDialog(
    project: Project,
    private val config: MCPServerConfig,
    private val result: MCPTestResult
) : DialogWrapper(project, true) {

    init {
        title = "MCP Test Connection: ${config.id}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val text = buildString {
            append("REQUEST\n")
            append(result.requestDetails)
            append("\n\n")
            append("RESPONSE\n")
            append(result.responseDetails ?: "(no response)")
            if (!result.errorMessage.isNullOrBlank()) {
                append("\n\n")
                append("ERROR\n")
                append(result.errorMessage)
            }
        }
        val area = JBTextArea(text, 24, 80).apply {
            isEditable = false
            font = Font("Monospaced", Font.PLAIN, 12)
        }
        return JBScrollPane(area)
    }
}

private data class KeyValueRow(
    val nameField: JBTextField,
    val valueField: JBTextField,
    val secretBox: JCheckBox
)

private data class WorkflowStepRow(
    val toolField: JBTextField,
    val inputsField: JBTextField,
    val outputsField: JBTextField
)

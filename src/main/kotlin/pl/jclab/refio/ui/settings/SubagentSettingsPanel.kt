package pl.jclab.refio.ui.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.core.subagents.models.SubagentInfo
import pl.jclab.refio.core.subagents.models.SubagentScope
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.ui.theme.LCATheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.*
import javax.swing.*
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

private val logger = dualLogger("SubagentSettingsPanel")

/**
 * Subagent Settings Panel
 * Manages subagents configuration with automatic save to filesystem
 */
class SubagentSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiClient?
) : JBPanel<SubagentSettingsPanel>(BorderLayout()), Disposable {

    private lateinit var subagentsTable: JBTable
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Flag to prevent auto-save during loading
    private var isLoadingSubagents = false

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                LCATheme.customLineBorder(LCATheme.borderColor, 1),
                "Subagents"
            ),
            LCATheme.paddedBorder(16)
        )

        // Main content
        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(8, 0, 0, 0)
            add(createSubagentsTable(), BorderLayout.CENTER)
            add(createButtonsPanel(), BorderLayout.SOUTH)
        }

        add(contentPanel, BorderLayout.CENTER)

        // Description
        val descPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("<html><font color='gray'>" +
                "Subagents are specialized AI assistants that can be invoked with <b>!agent-name</b> prefix.<br>" +
                "Built-in agents cannot be modified. Create project or user agents in .refio/agents/ directory." +
                "</font></html>"))
        }
        add(descPanel, BorderLayout.SOUTH)

        // Load subagents
        loadSubagents()
    }

    private fun createSubagentsTable(): JComponent {
        val columnNames = arrayOf("Name", "Description", "Model", "Tools", "Scope", "Enabled")

        subagentsTable = JBTable(object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                // Only "Enabled" column is editable
                return column == 5
            }

            override fun getColumnClass(column: Int): Class<*> {
                return when (column) {
                    5 -> java.lang.Boolean::class.java  // Enabled checkbox
                    else -> String::class.java
                }
            }
        }).apply {
            // Auto-save on checkbox change
            model.addTableModelListener { event ->
                if (event.type == TableModelEvent.UPDATE && event.column == 5) {
                    val row = event.firstRow
                    onEnabledChanged(row)
                }
            }
        }

        // Column widths
        subagentsTable.columnModel.getColumn(0).preferredWidth = 150  // Name
        subagentsTable.columnModel.getColumn(1).preferredWidth = 250  // Description
        subagentsTable.columnModel.getColumn(2).preferredWidth = 100  // Model
        subagentsTable.columnModel.getColumn(3).preferredWidth = 150  // Tools
        subagentsTable.columnModel.getColumn(4).preferredWidth = 80   // Scope
        subagentsTable.columnModel.getColumn(5).preferredWidth = 70   // Enabled

        // Custom renderer for scope column
        val scopeRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                val scope = value?.toString()?.lowercase()
                when (scope) {
                    "builtin" -> foreground = Color(100, 100, 100)  // Gray for builtin
                    "user" -> foreground = Color(0, 100, 200)      // Blue for user
                    "project" -> foreground = Color(0, 150, 0)     // Green for project
                }
                return component
            }
        }
        subagentsTable.columnModel.getColumn(4).cellRenderer = scopeRenderer

        // Custom renderer for model column
        val modelRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                val model = value?.toString()?.lowercase()
                when (model) {
                    "default" -> foreground = JBColor.foreground()
                    "weak" -> foreground = Color(150, 100, 0)      // Orange for weak
                    "coding" -> foreground = Color(0, 150, 0)      // Green for coding
                    "plan" -> foreground = Color(0, 100, 200)      // Blue for plan
                    else -> foreground = JBColor.foreground()
                }
                return component
            }
        }
        subagentsTable.columnModel.getColumn(2).cellRenderer = modelRenderer

        subagentsTable.setShowGrid(true)
        subagentsTable.gridColor = JBColor.LIGHT_GRAY
        subagentsTable.rowHeight = 28

        return JScrollPane(subagentsTable).apply {
            border = LCATheme.customLineBorder(LCATheme.grayColor, 1)
            preferredSize = Dimension(700, 300)
        }
    }

    private fun createButtonsPanel(): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            border = LCATheme.paddedBorder(8, 0, 0, 0)

            add(JButton("Create").apply {
                toolTipText = "Create a new subagent"
                addActionListener { onCreateSubagent() }
            })

            add(JButton("Edit").apply {
                toolTipText = "Edit selected subagent"
                addActionListener { onEditSubagent() }
            })

            add(JButton("Delete").apply {
                toolTipText = "Delete selected subagent"
                addActionListener { onDeleteSubagent() }
            })

            add(JLabel(" | "))

            add(JButton("Refresh").apply {
                toolTipText = "Refresh subagent list from filesystem"
                addActionListener { onRefreshSubagents() }
            })
        }
    }

    private fun loadSubagents() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available" }
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Loading subagents (including disabled and builtin)" }
                // includeDisabled = true to show all subagents in admin panel
                val subagents = coreApiClient.listSubagents(includeDisabled = true)

                ApplicationManager.getApplication().invokeLater {
                    populateTable(subagents)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load subagents" }
            }
        }
    }

    private fun populateTable(subagents: List<SubagentInfo>) {
        isLoadingSubagents = true
        try {
            val tableModel = subagentsTable.model as DefaultTableModel
            tableModel.rowCount = 0

            // Sort: builtin first, then by name
            val sorted = subagents.sortedWith(
                compareBy<SubagentInfo> {
                    when (it.scope.lowercase()) {
                        "builtin" -> 0
                        "user" -> 1
                        "project" -> 2
                        else -> 3
                    }
                }.thenBy { it.name }
            )

            sorted.forEach { agent ->
                tableModel.addRow(arrayOf(
                    agent.name,
                    agent.description,
                    agent.model,
                    agent.tools?.joinToString(", ") ?: "inherit",
                    agent.scope,
                    agent.enabled
                ))
            }

            logger.info { "Loaded ${subagents.size} subagents" }
        } finally {
            isLoadingSubagents = false
        }
    }

    private fun onEnabledChanged(row: Int) {
        if (isLoadingSubagents) return

        val tableModel = subagentsTable.model as DefaultTableModel
        val name = tableModel.getValueAt(row, 0) as String
        val enabled = tableModel.getValueAt(row, 5) as Boolean

        logger.info { "Updating subagent enabled: $name -> $enabled" }

        coroutineScope.launch {
            try {
                coreApiClient?.updateSubagent(name, enabled = enabled)
                logger.info { "Subagent enabled updated: $name -> $enabled" }

                ApplicationManager.getApplication().invokeLater {
                    onSettingChanged("subagents", "enabled_$name", enabled)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to update subagent: $name" }
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this@SubagentSettingsPanel,
                        "Failed to update subagent: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                    loadSubagents()  // Reload to revert
                }
            }
        }
    }

    private fun onCreateSubagent() {
        val dialog = SubagentEditDialog(
            parent = SwingUtilities.getWindowAncestor(this) as? JFrame,
            title = "Create Subagent",
            subagent = null
        )
        dialog.isVisible = true

        if (dialog.isOk) {
            coroutineScope.launch {
                try {
                    coreApiClient?.createSubagent(
                        name = dialog.nameField.text,
                        description = dialog.descriptionField.text,
                        systemPrompt = dialog.systemPromptArea.text,
                        allowedTools = dialog.getToolsList(),
                        model = dialog.modelCombo.selectedItem as String,
                        scope = if (dialog.scopeCombo.selectedItem == "Project") SubagentScope.PROJECT else SubagentScope.USER,
                        enabled = dialog.enabledCheck.isSelected,
                        priority = dialog.prioritySpinner.value as Int
                    )

                    logger.info { "Subagent created: ${dialog.nameField.text}" }

                    ApplicationManager.getApplication().invokeLater {
                        loadSubagents()
                        onSettingChanged("subagents", "created", dialog.nameField.text)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create subagent" }
                    ApplicationManager.getApplication().invokeLater {
                        JOptionPane.showMessageDialog(
                            this@SubagentSettingsPanel,
                            "Failed to create subagent: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            }
        }
    }

    private fun onEditSubagent() {
        val selectedRow = subagentsTable.selectedRow
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a subagent to edit",
                "No Selection",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        val tableModel = subagentsTable.model as DefaultTableModel
        val name = tableModel.getValueAt(selectedRow, 0) as String
        val scope = tableModel.getValueAt(selectedRow, 4) as String
        val isBuiltin = scope.lowercase() == "builtin"

        // Load full subagent definition
        coroutineScope.launch {
            try {
                val subagent = coreApiClient?.getSubagent(name)
                if (subagent == null) {
                    ApplicationManager.getApplication().invokeLater {
                        JOptionPane.showMessageDialog(
                            this@SubagentSettingsPanel,
                            "Subagent not found: $name",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                    return@launch
                }

                ApplicationManager.getApplication().invokeLater {
                    val dialog = SubagentEditDialog(
                        parent = SwingUtilities.getWindowAncestor(this@SubagentSettingsPanel) as? JFrame,
                        title = if (isBuiltin) "View Subagent: $name (Read-Only)" else "Edit Subagent: $name",
                        subagent = subagent,
                        readOnly = isBuiltin
                    )
                    dialog.isVisible = true

                    // Only process updates for non-builtin subagents
                    if (!isBuiltin && dialog.isOk) {
                        coroutineScope.launch {
                            try {
                                coreApiClient?.updateSubagent(
                                    name = name,
                                    description = dialog.descriptionField.text,
                                    systemPrompt = dialog.systemPromptArea.text,
                                    allowedTools = dialog.getToolsList(),
                                    model = dialog.modelCombo.selectedItem as String,
                                    enabled = dialog.enabledCheck.isSelected,
                                    priority = dialog.prioritySpinner.value as Int
                                )

                                logger.info { "Subagent updated: $name" }

                                ApplicationManager.getApplication().invokeLater {
                                    loadSubagents()
                                    onSettingChanged("subagents", "updated", name)
                                }
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to update subagent" }
                                ApplicationManager.getApplication().invokeLater {
                                    JOptionPane.showMessageDialog(
                                        this@SubagentSettingsPanel,
                                        "Failed to update subagent: ${e.message}",
                                        "Error",
                                        JOptionPane.ERROR_MESSAGE
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load subagent: $name" }
            }
        }
    }

    private fun onDeleteSubagent() {
        val selectedRow = subagentsTable.selectedRow
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a subagent to delete",
                "No Selection",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        val tableModel = subagentsTable.model as DefaultTableModel
        val name = tableModel.getValueAt(selectedRow, 0) as String
        val scope = tableModel.getValueAt(selectedRow, 4) as String

        if (scope.lowercase() == "builtin") {
            JOptionPane.showMessageDialog(
                this,
                "Cannot delete built-in subagents",
                "Not Allowed",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to delete subagent '$name'?",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result != JOptionPane.YES_OPTION) return

        coroutineScope.launch {
            try {
                coreApiClient?.deleteSubagent(name)

                logger.info { "Subagent deleted: $name" }

                ApplicationManager.getApplication().invokeLater {
                    loadSubagents()
                    onSettingChanged("subagents", "deleted", name)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete subagent: $name" }
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this@SubagentSettingsPanel,
                        "Failed to delete subagent: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun onRefreshSubagents() {
        coroutineScope.launch {
            try {
                coreApiClient?.refreshSubagents()
                ApplicationManager.getApplication().invokeLater {
                    loadSubagents()
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to refresh subagents" }
            }
        }
    }

    override fun dispose() {
        coroutineScope.cancel()
    }

    /**
     * Reload subagents (called by SettingsView)
     */
    fun reload() {
        logger.info { "Reloading subagents panel" }
        loadSubagents()
    }
}

/**
 * Dialog for creating/editing/viewing subagents
 */
class SubagentEditDialog(
    parent: JFrame?,
    title: String,
    private val subagent: pl.jclab.refio.core.subagents.models.SubagentDefinition?,
    private val readOnly: Boolean = false
) : JDialog(parent, title, true) {

    val nameField = JTextField(20)
    val descriptionField = JTextField(40)
    val systemPromptArea = JTextArea(10, 50)
    val toolsField = JTextField(40)
    val modelCombo = JComboBox(arrayOf("default", "weak", "coding", "plan", "inherit"))
    val scopeCombo = JComboBox(arrayOf("Project", "User", "Built-in"))
    val enabledCheck = JCheckBox("Enabled", true)
    val prioritySpinner = JSpinner(SpinnerNumberModel(0, 0, 100, 1))

    var isOk = false
        private set

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = true

        // Pre-populate fields if editing
        subagent?.let { agent ->
            nameField.text = agent.name
            nameField.isEditable = false  // Cannot change name
            descriptionField.text = agent.description
            systemPromptArea.text = agent.systemPrompt
            toolsField.text = agent.allowedTools?.joinToString(", ") ?: ""
            modelCombo.selectedItem = agent.model
            scopeCombo.selectedItem = when (agent.scope) {
                SubagentScope.USER -> "User"
                SubagentScope.BUILTIN -> "Built-in"
                else -> "Project"
            }
            scopeCombo.isEnabled = false  // Cannot change scope
            enabledCheck.isSelected = agent.enabled
            prioritySpinner.value = agent.priority
        }

        // If read-only mode, disable all fields
        if (readOnly) {
            descriptionField.isEditable = false
            systemPromptArea.isEditable = false
            toolsField.isEditable = false
            modelCombo.isEnabled = false
            enabledCheck.isEnabled = false
            prioritySpinner.isEnabled = false
        }

        contentPane = createContentPane()
        pack()
        setLocationRelativeTo(parent)
    }

    private fun createContentPane(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout(10, 10)).apply {
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

            // Form panel
            val formPanel = JBPanel<JBPanel<*>>(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 4, 4, 4)
            }

            // Name
            formPanel.add(JLabel("Name:"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            formPanel.add(nameField, gbc)

            // Description
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            formPanel.add(JLabel("Description:"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            formPanel.add(descriptionField, gbc)

            // Model
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            formPanel.add(JLabel("Model:"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            formPanel.add(modelCombo, gbc)

            // Tools
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            formPanel.add(JLabel("Tools (comma-separated):"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            formPanel.add(toolsField, gbc)

            // Scope
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            formPanel.add(JLabel("Scope:"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            formPanel.add(scopeCombo, gbc)

            // Priority
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            formPanel.add(JLabel("Priority:"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            formPanel.add(prioritySpinner, gbc)

            // Enabled
            gbc.gridx = 0
            gbc.gridy++
            gbc.gridwidth = 2
            formPanel.add(enabledCheck, gbc)

            // System prompt
            gbc.gridx = 0
            gbc.gridy++
            gbc.gridwidth = 2
            gbc.weighty = 0.0
            formPanel.add(JLabel("System Prompt:"), gbc)

            gbc.gridy++
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            systemPromptArea.lineWrap = true
            systemPromptArea.wrapStyleWord = true
            formPanel.add(JBScrollPane(systemPromptArea), gbc)

            add(formPanel, BorderLayout.CENTER)

            // Buttons
            val buttonPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
                if (readOnly) {
                    // Read-only mode: only Close button
                    add(JButton("Close").apply {
                        addActionListener {
                            isOk = false
                            dispose()
                        }
                    })
                } else {
                    // Edit mode: Cancel and Save buttons
                    add(JButton("Cancel").apply {
                        addActionListener {
                            isOk = false
                            dispose()
                        }
                    })
                    add(JButton("Save").apply {
                        addActionListener {
                            if (validateForm()) {
                                isOk = true
                                dispose()
                            }
                        }
                    })
                }
            }
            add(buttonPanel, BorderLayout.SOUTH)
        }
    }

    private fun validateForm(): Boolean {
        if (nameField.text.isBlank()) {
            JOptionPane.showMessageDialog(this, "Name is required", "Validation Error", JOptionPane.ERROR_MESSAGE)
            return false
        }
        if (descriptionField.text.isBlank()) {
            JOptionPane.showMessageDialog(this, "Description is required", "Validation Error", JOptionPane.ERROR_MESSAGE)
            return false
        }
        if (systemPromptArea.text.isBlank()) {
            JOptionPane.showMessageDialog(this, "System prompt is required", "Validation Error", JOptionPane.ERROR_MESSAGE)
            return false
        }
        return true
    }

    fun getToolsList(): List<String>? {
        val tools = toolsField.text.trim()
        if (tools.isBlank()) return null
        return tools.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}

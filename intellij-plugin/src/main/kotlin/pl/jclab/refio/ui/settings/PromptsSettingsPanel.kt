package pl.jclab.refio.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Prompts Settings Panel
 * Manages system prompts and slash commands with list-based UI
 */
class PromptsSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiClient?
) : JBPanel<PromptsSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("PromptsSettingsPanel")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var promptsTable: JBTable
    private lateinit var commandsTable: JBTable

    // Cache for full prompt data (used for row -> ID mapping)
    private val promptsCache = mutableListOf<PromptDto>()
    private val commandsCache = mutableListOf<PromptDto>()

    init {
        border = LCATheme.paddedBorder(16)

        // Header
        val headerPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Prompts").apply {
                font = font.deriveFont(14f).deriveFont(Font.BOLD)
            })
            isEnabled = false
        }
        add(headerPanel, BorderLayout.NORTH)

        // Tabbed pane for system prompts + commands
        val tabbedPane = JBTabbedPane().apply {
            addTab("System Prompts", createPromptsPanel())
            addTab("Commands", createCommandsPanel())
        }

        add(tabbedPane, BorderLayout.CENTER)
    }

    private fun createPromptsPanel(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(8)

            // Description
            val descLabel =
                JLabel("<html><font color='gray'>System prompts define the AI's role and behavior. Use 'Use Default' to restore system prompts.</font></html>")
            add(descLabel, BorderLayout.NORTH)

            // Table
            val columnNames = arrayOf("Prompt Name", "Type", "Content", "Custom")
            val data = loadPromptsData()

            promptsTable = JBTable(object : DefaultTableModel(data, columnNames) {
                override fun getColumnClass(column: Int): Class<*> {
                    return if (column == 3) Boolean::class.javaObjectType else String::class.java
                }

                override fun isCellEditable(row: Int, column: Int): Boolean {
                    return false
                }
            })
            promptsTable.setShowGrid(true)
            promptsTable.gridColor = JBColor.LIGHT_GRAY

            val scrollPane = JScrollPane(promptsTable).apply {
                preferredSize = Dimension(600, 300)
            }

            // Buttons panel
            val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Edit").apply {
                    addActionListener { onEditPrompt() }
                })
                add(JButton("Use Default").apply {
                    addActionListener { onUseDefaultPrompt() }
                })
            }

            val contentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(scrollPane, BorderLayout.CENTER)
                add(buttonsPanel, BorderLayout.SOUTH)
            }

            add(contentPanel, BorderLayout.CENTER)
        }
    }

    private fun createCommandsPanel(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(8)

            // Description
            val descLabel =
                JLabel("<html><font color='gray'>Define slash commands that can be used in the prompt input</font></html>")
            add(descLabel, BorderLayout.NORTH)

            // Table
            val columnNames = arrayOf("Command", "Description", "Enabled")
            val data = loadCommandsData()

            commandsTable = JBTable(object : DefaultTableModel(data, columnNames) {
                override fun getColumnClass(column: Int): Class<*> {
                    return if (column == 2) Boolean::class.javaObjectType else String::class.java
                }

                override fun isCellEditable(row: Int, column: Int): Boolean {
                    return false
                }
            })
            commandsTable.setShowGrid(true)
            commandsTable.gridColor = JBColor.LIGHT_GRAY

            val scrollPane = JScrollPane(commandsTable).apply {
                preferredSize = Dimension(600, 300)
            }

            // Buttons panel
            val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Add").apply {
                    addActionListener { onAddCommand() }
                })
                add(JButton("Edit").apply {
                    addActionListener { onEditCommand() }
                })
                add(JButton("Delete").apply {
                    addActionListener { onDeleteCommand() }
                })
            }

            val contentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(scrollPane, BorderLayout.CENTER)
                add(buttonsPanel, BorderLayout.SOUTH)
            }

            add(contentPanel, BorderLayout.CENTER)
        }
    }

    // ========== System Prompts Actions ==========

    private fun onEditPrompt() {
        val selectedRow = promptsTable.selectedRow
        if (selectedRow < 0) {
            showError("Select a system prompt to edit")
            return
        }

        if (selectedRow >= promptsCache.size) {
            showError("Invalid row")
            return
        }

        val prompt = promptsCache[selectedRow]
        val promptType = PromptType.valueOf(prompt.type)

        coroutineScope.launch {
            try {
                // Get default content on IO dispatcher
                val defaultContent = withContext(Dispatchers.IO) {
                    coreApiClient?.getDefaultSystemPromptContent(promptType)
                        ?: throw Exception("CoreApiClient not available")
                }

                // Show dialog on EDT
                ApplicationManager.getApplication().invokeLater {
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    val dialog = SystemPromptEditDialog(project, prompt, defaultContent)
                    if (dialog.showAndGet()) {
                        // Launch update in coroutine
                        coroutineScope.launch {
                            updateSystemPrompt(promptType, dialog.useDefault(), dialog.getCustomContent())
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load prompt" }
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to load prompt: ${e.message}")
                }
            }
        }
    }

    private suspend fun updateSystemPrompt(type: PromptType, useDefault: Boolean, customContent: String) {
        try {
            logger.info { "Updating system prompt: $type, useDefault=$useDefault" }

            // Update backend on IO dispatcher
            withContext(Dispatchers.IO) {
                if (useDefault) {
                    // Reset to default
                    coreApiClient?.resetSystemPromptToDefault(type)
                        ?: throw Exception("CoreApiClient not available")
                } else {
                    // Update with custom content
                    coreApiClient?.updateSystemPrompt(
                        UpdateSystemPromptRequest(
                            type = type,
                            content = customContent
                        )
                    ) ?: throw Exception("CoreApiClient not available")
                }
            }

            // Reload data (already handles IO + invokeLater)
            reloadPrompts()

            // Notify settings changed on EDT
            ApplicationManager.getApplication().invokeLater {
                onSettingChanged("prompts", "system_prompt_updated", type.name)
            }

            logger.info { "System prompt updated: $type" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update system prompt" }
            ApplicationManager.getApplication().invokeLater {
                showError("Failed to update prompt: ${e.message}")
            }
        }
    }

    private fun onUseDefaultPrompt() {
        val selectedRow = promptsTable.selectedRow
        if (selectedRow < 0) {
            showError("Select a system prompt to reset")
            return
        }

        if (selectedRow >= promptsCache.size) {
            showError("Invalid row")
            return
        }

        val prompt = promptsCache[selectedRow]
        val promptType = PromptType.valueOf(prompt.type)

        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to reset to default prompt?",
            "Confirm Reset",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result != JOptionPane.YES_OPTION) {
            return
        }

        coroutineScope.launch {
            updateSystemPrompt(promptType, useDefault = true, customContent = "")
        }
    }

    // ========== Commands Actions ==========

    private fun onAddCommand() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val dialog = CommandEditDialog(project)
        if (dialog.showAndGet()) {
            coroutineScope.launch {
                try {
                    logger.info { "Adding new command: ${dialog.getCommandName()}" }

                    // Save to backend on IO dispatcher
                    val command = withContext(Dispatchers.IO) {
                        coreApiClient?.saveCommand(
                            SaveCommandRequest(
                                id = null,
                                name = dialog.getCommandName(),
                                content = dialog.getContent(),
                                description = dialog.getDescription(),
                                isEnabled = dialog.isEnabled()
                            )
                        ) ?: throw Exception("CoreApiClient not available")
                    }

                    // Reload data (already handles IO + invokeLater)
                    reloadCommands()

                    // Notify settings changed on EDT
                    ApplicationManager.getApplication().invokeLater {
                        onSettingChanged("prompts", "command_added", command.prompt.id)
                    }

                    logger.info { "Command added: ${command.prompt.id}" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to add command" }
                    ApplicationManager.getApplication().invokeLater {
                        showError("Failed to add command: ${e.message}")
                    }
                }
            }
        }
    }

    private fun onEditCommand() {
        val selectedRow = commandsTable.selectedRow
        if (selectedRow < 0) {
            showError("Select a command to edit")
            return
        }

        if (selectedRow >= commandsCache.size) {
            showError("Invalid row")
            return
        }

        val existingCommand = commandsCache[selectedRow]
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val dialog = CommandEditDialog(project, existingCommand)

        if (dialog.showAndGet()) {
            coroutineScope.launch {
                try {
                    logger.info { "Updating command: ${existingCommand.name}" }

                    // Save to backend on IO dispatcher
                    val updated = withContext(Dispatchers.IO) {
                        coreApiClient?.saveCommand(
                            SaveCommandRequest(
                                id = existingCommand.id,
                                name = dialog.getCommandName(),
                                content = dialog.getContent(),
                                description = dialog.getDescription(),
                                isEnabled = dialog.isEnabled()
                            )
                        ) ?: throw Exception("CoreApiClient not available")
                    }

                    // Reload data (already handles IO + invokeLater)
                    reloadCommands()

                    // Notify settings changed on EDT
                    ApplicationManager.getApplication().invokeLater {
                        onSettingChanged("prompts", "command_updated", updated.prompt.id)
                    }

                    logger.info { "Command updated: ${updated.prompt.id}" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to update command" }
                    ApplicationManager.getApplication().invokeLater {
                        showError("Failed to update command: ${e.message}")
                    }
                }
            }
        }
    }

    private fun onDeleteCommand() {
        val selectedRow = commandsTable.selectedRow
        if (selectedRow < 0) {
            showError("Select a command to delete")
            return
        }

        if (selectedRow >= commandsCache.size) {
            showError("Invalid row")
            return
        }

        val command = commandsCache[selectedRow]

        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to delete command '${command.name}'?",
            "Confirm Deletion",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result != JOptionPane.YES_OPTION) {
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Deleting command: ${command.name}" }

                // Delete from backend on IO dispatcher
                withContext(Dispatchers.IO) {
                    coreApiClient?.deletePrompt(command.id)
                        ?: throw Exception("CoreApiClient not available")
                }

                // Reload data (already handles IO + invokeLater)
                reloadCommands()

                // Notify settings changed on EDT
                ApplicationManager.getApplication().invokeLater {
                    onSettingChanged("prompts", "command_deleted", command.name)
                }

                logger.info { "Command deleted: ${command.name}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete command" }
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to delete command: ${e.message}")
                }
            }
        }
    }

    // ========== Data Loading Helpers ==========

    /**
     * Load prompts data from API (synchronous for initial UI setup)
     */
    private fun loadPromptsData(): Array<Array<Any>> {
        if (coreApiClient == null) {
            promptsCache.clear()
            return arrayOf()
        }

        return try {
            val response = coreApiClient.getSystemPrompts()
            promptsCache.clear()
            promptsCache.addAll(response.prompts)

            response.prompts.map { prompt ->
                arrayOf<Any>(
                    prompt.name,
                    prompt.type,
                    previewContent(prompt.content),
                    prompt.isCustom
                )
            }.toTypedArray()
        } catch (e: Exception) {
            logger.error(e) { "Failed to load system prompts" }
            promptsCache.clear()  // Clear cache on error
            arrayOf()
        }
    }

    /**
     * Load prompts data asynchronously and update cache
     */
    private suspend fun loadPromptsDataAsync(): List<PromptDto> {
        if (coreApiClient == null) {
            return emptyList()
        }

        return try {
            withContext(Dispatchers.IO) { coreApiClient.getSystemPrompts().prompts }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load system prompts async" }
            emptyList()
        }
    }

    /**
     * Load commands data from API (synchronous for initial UI setup)
     */
    private fun loadCommandsData(): Array<Array<Any>> {
        if (coreApiClient == null) {
            commandsCache.clear()
            return arrayOf(
                arrayOf("/example", "Example command description", true)
            )
        }

        return try {
            val response = coreApiClient.getPromptsByType(PromptType.SLASH_COMMAND)

            // Update cache during initial load (Bug #6 fix)
            commandsCache.clear()
            commandsCache.addAll(response.prompts)

            response.prompts.map { prompt ->
                arrayOf<Any>(prompt.name, prompt.description ?: "", prompt.isEnabled)
            }.toTypedArray()
        } catch (e: Exception) {
            logger.error(e) { "Failed to load commands" }
            commandsCache.clear()  // Clear cache on error
            arrayOf()
        }
    }

    /**
     * Load commands data asynchronously and update cache
     */
    private suspend fun loadCommandsDataAsync(): List<PromptDto> {
        if (coreApiClient == null) {
            return emptyList()
        }

        return try {
            val response = withContext(Dispatchers.IO) {
                coreApiClient.getPromptsByType(PromptType.SLASH_COMMAND)
            }
            response.prompts
        } catch (e: Exception) {
            logger.error(e) { "Failed to load commands async" }
            emptyList()
        }
    }

    /**
     * Update prompts table with new data (must be called on EDT)
     */
    private fun updatePromptsTable(prompts: List<PromptDto>) {
        promptsCache.clear()
        promptsCache.addAll(prompts)

        val model = promptsTable.model as DefaultTableModel
        model.rowCount = 0
        prompts.forEach { prompt ->
            model.addRow(
                arrayOf<Any>(
                    prompt.name,
                    prompt.type,
                    previewContent(prompt.content),
                    prompt.isCustom
                )
            )
        }
    }

    /**
     * Update commands table with new data (must be called on EDT)
     */
    private fun updateCommandsTable(prompts: List<PromptDto>) {
        commandsCache.clear()
        commandsCache.addAll(prompts)

        val model = commandsTable.model as DefaultTableModel
        model.rowCount = 0
        prompts.forEach { prompt ->
            model.addRow(arrayOf<Any>(prompt.name, prompt.description ?: "", prompt.isEnabled))
        }
    }

    /**
     * Reload prompts from backend asynchronously
     */
    private suspend fun reloadPrompts() {
        val prompts = loadPromptsDataAsync()
        ApplicationManager.getApplication().invokeLater {
            updatePromptsTable(prompts)
        }
    }

    /**
     * Reload commands from backend asynchronously
     */
    private suspend fun reloadCommands() {
        val prompts = loadCommandsDataAsync()
        ApplicationManager.getApplication().invokeLater {
            updateCommandsTable(prompts)
        }
    }

    private fun previewContent(text: String, limit: Int = 120): String {
        return if (text.length <= limit) {
            text
        } else {
            text.take(limit) + "..."
        }
    }

    /**
     * Reload all settings from backend (called from external code)
     */
    fun reload() {
        logger.info { "Reloading prompts panel" }
        coroutineScope.launch {
            try {
                reloadPrompts()
                reloadCommands()
            } catch (e: Exception) {
                logger.error(e) { "Failed to reload prompts panel" }
            }
        }
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(
            this,
            message,
            "Error",
            JOptionPane.ERROR_MESSAGE
        )
    }

    override fun removeNotify() {
        super.removeNotify()
        coroutineScope.cancel()
    }
}

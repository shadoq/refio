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
 * Manages system prompts and slash prompts with list-based UI
 */
class PromptsSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiClient?
) : JBPanel<PromptsSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("PromptsSettingsPanel")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var promptsTable: JBTable
    private lateinit var slashPromptsTable: JBTable

    // Cache for full prompt data (used for row -> ID mapping)
    private val promptsCache = mutableListOf<PromptDto>()
    private val slashPromptsCache = mutableListOf<PromptDto>()

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

        // Tabbed pane for system prompts + slash prompts
        val tabbedPane = JBTabbedPane().apply {
            addTab("System Prompts", createPromptsPanel())
            addTab("Prompts", createSlashPromptsPanel())
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

    private fun createSlashPromptsPanel(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(8)

            // Description
            val descLabel =
                JLabel("<html><font color='gray'>Define reusable prompts that can be invoked in the chat input by typing /name</font></html>")
            add(descLabel, BorderLayout.NORTH)

            // Table
            val columnNames = arrayOf("Prompt", "Description", "Enabled")
            val data = loadSlashPromptsData()

            slashPromptsTable = JBTable(object : DefaultTableModel(data, columnNames) {
                override fun getColumnClass(column: Int): Class<*> {
                    return if (column == 2) Boolean::class.javaObjectType else String::class.java
                }

                override fun isCellEditable(row: Int, column: Int): Boolean {
                    return false
                }
            })
            slashPromptsTable.setShowGrid(true)
            slashPromptsTable.gridColor = JBColor.LIGHT_GRAY

            val scrollPane = JScrollPane(slashPromptsTable).apply {
                preferredSize = Dimension(600, 300)
            }

            // Buttons panel
            val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Add").apply {
                    addActionListener { onAddSlashPrompt() }
                })
                add(JButton("Edit").apply {
                    addActionListener { onEditSlashPrompt() }
                })
                add(JButton("Delete").apply {
                    addActionListener { onDeleteSlashPrompt() }
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

    // ========== Slash Prompts Actions ==========

    private fun onAddSlashPrompt() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val dialog = PromptEditDialog(project)
        if (dialog.showAndGet()) {
            coroutineScope.launch {
                try {
                    logger.info { "Adding new slash prompt: ${dialog.getPromptName()}" }

                    // Save to backend on IO dispatcher
                    val saved = withContext(Dispatchers.IO) {
                        coreApiClient?.saveSlashPrompt(
                            SaveSlashPromptRequest(
                                id = null,
                                name = dialog.getPromptName(),
                                content = dialog.getContent(),
                                description = dialog.getDescription(),
                                isEnabled = dialog.isEnabled()
                            )
                        ) ?: throw Exception("CoreApiClient not available")
                    }

                    // Reload data (already handles IO + invokeLater)
                    reloadSlashPrompts()

                    // Notify settings changed on EDT
                    ApplicationManager.getApplication().invokeLater {
                        onSettingChanged("prompts", "slash_prompt_added", saved.prompt.id)
                    }

                    logger.info { "Slash prompt added: ${saved.prompt.id}" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to add slash prompt" }
                    ApplicationManager.getApplication().invokeLater {
                        showError("Failed to add prompt: ${e.message}")
                    }
                }
            }
        }
    }

    private fun onEditSlashPrompt() {
        val selectedRow = slashPromptsTable.selectedRow
        if (selectedRow < 0) {
            showError("Select a prompt to edit")
            return
        }

        if (selectedRow >= slashPromptsCache.size) {
            showError("Invalid row")
            return
        }

        val existing = slashPromptsCache[selectedRow]
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val dialog = PromptEditDialog(project, existing)

        if (dialog.showAndGet()) {
            coroutineScope.launch {
                try {
                    logger.info { "Updating slash prompt: ${existing.name}" }

                    // Save to backend on IO dispatcher
                    val updated = withContext(Dispatchers.IO) {
                        coreApiClient?.saveSlashPrompt(
                            SaveSlashPromptRequest(
                                id = existing.id,
                                name = dialog.getPromptName(),
                                content = dialog.getContent(),
                                description = dialog.getDescription(),
                                isEnabled = dialog.isEnabled()
                            )
                        ) ?: throw Exception("CoreApiClient not available")
                    }

                    // Reload data (already handles IO + invokeLater)
                    reloadSlashPrompts()

                    // Notify settings changed on EDT
                    ApplicationManager.getApplication().invokeLater {
                        onSettingChanged("prompts", "slash_prompt_updated", updated.prompt.id)
                    }

                    logger.info { "Slash prompt updated: ${updated.prompt.id}" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to update slash prompt" }
                    ApplicationManager.getApplication().invokeLater {
                        showError("Failed to update prompt: ${e.message}")
                    }
                }
            }
        }
    }

    private fun onDeleteSlashPrompt() {
        val selectedRow = slashPromptsTable.selectedRow
        if (selectedRow < 0) {
            showError("Select a prompt to delete")
            return
        }

        if (selectedRow >= slashPromptsCache.size) {
            showError("Invalid row")
            return
        }

        val target = slashPromptsCache[selectedRow]

        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to delete prompt '${target.name}'?",
            "Confirm Deletion",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result != JOptionPane.YES_OPTION) {
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Deleting slash prompt: ${target.name}" }

                // Delete from backend on IO dispatcher
                withContext(Dispatchers.IO) {
                    coreApiClient?.deletePrompt(target.id)
                        ?: throw Exception("CoreApiClient not available")
                }

                // Reload data (already handles IO + invokeLater)
                reloadSlashPrompts()

                // Notify settings changed on EDT
                ApplicationManager.getApplication().invokeLater {
                    onSettingChanged("prompts", "slash_prompt_deleted", target.name)
                }

                logger.info { "Slash prompt deleted: ${target.name}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete slash prompt" }
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to delete prompt: ${e.message}")
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
     * Load slash prompts data from API (synchronous for initial UI setup)
     */
    private fun loadSlashPromptsData(): Array<Array<Any>> {
        if (coreApiClient == null) {
            slashPromptsCache.clear()
            return arrayOf(
                arrayOf("/example", "Example prompt description", true)
            )
        }

        return try {
            val response = coreApiClient.getPromptsByType(PromptType.SLASH_PROMPT)

            slashPromptsCache.clear()
            slashPromptsCache.addAll(response.prompts)

            response.prompts.map { prompt ->
                arrayOf<Any>(prompt.name, prompt.description ?: "", prompt.isEnabled)
            }.toTypedArray()
        } catch (e: Exception) {
            logger.error(e) { "Failed to load slash prompts" }
            slashPromptsCache.clear()
            arrayOf()
        }
    }

    /**
     * Load slash prompts data asynchronously and update cache
     */
    private suspend fun loadSlashPromptsDataAsync(): List<PromptDto> {
        if (coreApiClient == null) {
            return emptyList()
        }

        return try {
            val response = withContext(Dispatchers.IO) {
                coreApiClient.getPromptsByType(PromptType.SLASH_PROMPT)
            }
            response.prompts
        } catch (e: Exception) {
            logger.error(e) { "Failed to load slash prompts async" }
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
     * Update slash prompts table with new data (must be called on EDT)
     */
    private fun updateSlashPromptsTable(prompts: List<PromptDto>) {
        slashPromptsCache.clear()
        slashPromptsCache.addAll(prompts)

        val model = slashPromptsTable.model as DefaultTableModel
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
     * Reload slash prompts from backend asynchronously
     */
    private suspend fun reloadSlashPrompts() {
        val prompts = loadSlashPromptsDataAsync()
        ApplicationManager.getApplication().invokeLater {
            updateSlashPromptsTable(prompts)
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
                reloadSlashPrompts()
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

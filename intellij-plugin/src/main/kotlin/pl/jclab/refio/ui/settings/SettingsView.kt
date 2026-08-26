package pl.jclab.refio.ui.settings

import pl.jclab.refio.core.config.ConfigKeys

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import pl.jclab.refio.services.notification.NotificationService
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * Main Settings View component
 *
 * Structure:
 * - Header with back button and title
 * - Tabbed pane with different settings sections
 * - Footer with Reset to Defaults button
 * - Auto-save mechanism for all changes
 */
class SettingsView(
    private val project: Project,
    private val coreApiClient: pl.jclab.refio.core.api.CoreApiRouter?,
    private val onBack: () -> Unit
) : JBPanel<SettingsView>(BorderLayout()) {

    private val logger = dualLogger("SettingsView")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Debounce for auto-save
    private var saveJob: Job? = null
    private val saveDebounceMs = 100L  // Reduced from 300ms for faster responsiveness

    // Settings panels with callback for auto-save
    // Note: Consolidated from 10 to 8 tabs:
    // - Context + Index merged into ContextSettingsPanel
    // - Limits merged into AdvancedSettingsPanel
    private val generalPanel = GeneralSettingsPanel(::onSettingChanged, coreApiClient)
    private val providersPanel = ProvidersSettingsPanel(::onSettingChanged, coreApiClient)
    private val modelsPanel = ModelsSettingsPanel(::onSettingChanged, coreApiClient)
    private val promptsPanel = PromptsSettingsPanel(::onSettingChanged, coreApiClient)
    private val contextSettingsPanel = ContextSettingsPanel(project, ::onSettingChanged)
    private val mcpPanel = MCPSettingsPanel(project)
    private val docsPanel = DocsSettingsPanel(project, ::onSettingChanged)
    private val toolsPanel = ToolsSettingsPanel(::onSettingChanged, coreApiClient)
    private val subagentPanel = SubagentSettingsPanel(::onSettingChanged, coreApiClient)
    private val advancedPanel = AdvancedSettingsPanel(::onSettingChanged, coreApiClient)
    private val themePanel = ThemeSettingsPanel()

    /** One entry of the category column; a blank [id] marks a non-selectable group separator. */
    private data class Category(val id: String, val title: String = "", val icon: Icon? = null)

    private val categories = listOf(
        Category("general", "General", AllIcons.General.Settings),
        Category("providers", "Providers", AllIcons.General.Web),
        Category("models", "Models", AllIcons.Nodes.PpLib),
        Category("prompts", "Prompts", AllIcons.Actions.Edit),
        Category("context", "Context", AllIcons.Actions.ListFiles),
        Category(""),
        Category("mcp", "MCP Servers", AllIcons.Nodes.Plugin),
        Category("tools", "Tools", AllIcons.General.GearPlain),
        Category("subagents", "Subagents", AllIcons.Actions.Lightning),
        Category(""),
        Category("appearance", "Appearance", AllIcons.Actions.Colors),
        Category("advanced", "Advanced", AllIcons.General.ShowInfos),
        Category("docs", "Documentation", AllIcons.Actions.Help)
    )

    private val categoryModel = CollectionListModel(categories)
    private val categoryList = JBList(categoryModel)
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    init {
        border = LCATheme.emptyBorder()

        providersPanel.setOnModelsRefreshedCallback(modelsPanel::onProviderModelsRefreshed)

        cards.add(createStyledTabPanel("General", generalPanel, scrollable = true), "general")
        cards.add(providersPanel, "providers")
        cards.add(createStyledTabPanel("Models", modelsPanel), "models")
        cards.add(createStyledTabPanel("Prompts", promptsPanel, scrollable = true), "prompts")
        cards.add(createStyledTabPanel("Context", contextSettingsPanel), "context")
        cards.add(createStyledTabPanel("MCP Servers", mcpPanel), "mcp")
        cards.add(toolsPanel, "tools")
        cards.add(subagentPanel, "subagents")
        cards.add(createStyledTabPanel("Appearance", themePanel), "appearance")
        cards.add(advancedPanel, "advanced")
        cards.add(docsPanel, "docs")

        categoryList.apply {
            cellRenderer = CategoryRenderer()
            // A cell that does not fit the viewport is otherwise re-painted in a slightly offset
            // popup on hover, which makes the icons look like they jump sideways.
            setExpandableItemsEnabled(false)
            // Group headers are labels, not destinations: a click on one keeps the previous
            // selection instead of showing an empty right-hand side.
            selectionModel = object : DefaultListSelectionModel() {
                override fun setSelectionInterval(index0: Int, index1: Int) {
                    val candidate = categoryModel.getElementAt(index1)
                    if (candidate.id.isNotEmpty()) super.setSelectionInterval(index1, index1)
                }
            }
            addListSelectionListener { event ->
                if (event.valueIsAdjusting) return@addListSelectionListener
                selectedValue?.id?.takeIf { it.isNotEmpty() }?.let { cardLayout.show(cards, it) }
            }
            // Icons carry no text, so the name has to arrive on hover.
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent) {
                    val index = locationToIndex(e.point).takeIf { it >= 0 }
                    toolTipText = index
                        ?.let { categoryModel.getElementAt(it) }
                        ?.takeIf { it.id.isNotEmpty() }
                        ?.title
                }
            })
        }

        // Fixed-width icon strip rather than a splitter: at dock width a draggable divider
        // ends up collapsed to a few pixels and the labels disappear entirely.
        val categoryStrip = JBScrollPane(categoryList).apply {
            border = JBUI.Borders.customLineRight(LCATheme.borderColor)
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(JBUI.scale(CATEGORY_STRIP_WIDTH), 0)
            minimumSize = Dimension(JBUI.scale(CATEGORY_STRIP_WIDTH), 0)
        }

        val body = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(categoryStrip, BorderLayout.WEST)
            add(cards, BorderLayout.CENTER)
        }

        add(createHeader(), BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        add(createFooter(), BorderLayout.SOUTH)

        categoryList.selectedIndex = categories.indexOfFirst { it.id == "general" }
    }

    private fun createHeader(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)

            add(JButton("Back to Chat", AllIcons.Actions.Back).apply {
                addActionListener { onBack() }
            }, BorderLayout.WEST)

            add(JLabel("Settings", SwingConstants.CENTER), BorderLayout.CENTER)
        }
    }

    /**
     * Icon-only cells. The category name is not drawn because the strip is narrower than any of
     * the titles; it is delivered through the list tooltip instead.
     */
    private inner class CategoryRenderer : ListCellRenderer<Category> {

        private val iconCell = JLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            isOpaque = true
            border = JBUI.Borders.empty(4)
            // Height only: a cell as wide as the strip is one pixel wider than the viewport left
            // by the strip's right-hand line, and the list would then overflow it. Width comes
            // from the list, which the icon is centered in.
            preferredSize = Dimension(0, JBUI.scale(30))
        }

        private val separatorCell = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 6)
            add(JSeparator(SwingConstants.HORIZONTAL), BorderLayout.CENTER)
            preferredSize = Dimension(0, JBUI.scale(9))
        }

        override fun getListCellRendererComponent(
            list: JList<out Category>,
            value: Category?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ): java.awt.Component {
            val entry = value ?: return separatorCell
            if (entry.id.isEmpty()) return separatorCell

            iconCell.icon = entry.icon
            iconCell.background = if (selected) {
                JBUI.CurrentTheme.List.Selection.background(true)
            } else {
                list.background
            }
            return iconCell
        }
    }

    private fun createFooter(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.customLineBorder(LCATheme.borderColor, 1, 0, 0, 0)

            val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JButton("Export…").apply {
                    toolTipText = "Export settings to a config file"
                    addActionListener { showExportMenu(this) }
                })
                add(HyperlinkLabel("Reload config").apply {
                    toolTipText = "Reload configuration from ~/.refio/config.yaml"
                    addHyperlinkListener { onReloadFromLocalConfig() }
                })
            }

            val right = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
                add(JButton("Reset").apply {
                    foreground = LCATheme.redColor
                    toolTipText = "Reset all settings to default values"
                    addActionListener { onResetToDefaults() }
                })
            }

            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    private fun showExportMenu(anchor: JComponent) {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Export to user config (~/.refio/config.yaml)").apply {
            addActionListener { onExportToUserConfig() }
        })
        menu.add(JMenuItem("Export to project config (<project>/.refio/config.yaml)").apply {
            addActionListener { onExportToProjectConfig() }
        })
        menu.show(anchor, 0, anchor.height)
    }

    private fun createStyledTabPanel(title: String, content: JComponent, scrollable: Boolean = false): JComponent {
        val wrappedContent: JComponent = if (scrollable) settingsScrollPane(content) else content

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.createSettingsBorder(title)
            add(wrappedContent, BorderLayout.CENTER)
        }
    }

    /**
     * Auto-save mechanism - called when any setting changes
     */
    fun onSettingChanged(section: String, key: String, value: Any) {
        // Apply advanced view setting immediately
        if (section == "general" && key == "advanced_view" && value is Boolean) {
            logger.info { "Advanced view setting changed to: $value" }
            // Fire property change event to notify RefioMainPanel
            firePropertyChange("advancedViewChanged", !value, value)
        }

        saveJob?.cancel()
        saveJob = coroutineScope.launch {
            delay(saveDebounceMs)
            saveSettingToBackend(section, key, value)
        }
    }

    /**
     * Auto-save mechanism - overloaded version that accepts full config key from ConfigService.
     *
     * @param fullKey Full configuration key from ConfigService constants (e.g. ConfigKeys.API_CALL_TIMEOUT.key)
     * @param value New value for the setting
     */
    fun onSettingChanged(fullKey: String, value: Any) {
        val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(fullKey)
        onSettingChanged(section, key, value)
    }

    /**
     * Saves a setting to the backend
     */
    private suspend fun saveSettingToBackend(section: String, key: String, value: Any) {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, cannot save setting: $section.$key" }
            return
        }

        try {
            logger.debug { "Auto-saving setting: $section.$key = $value" }

            // Call backend API
            val settings = mapOf(key to value)
            coreApiClient.configRouter.updateConfig(
                section = section,
                scope = "app",
                taskId = null,
                settings = settings
            )

            logger.info { "Setting saved: $section.$key" }

            // Refresh cached max-context StateFlow so UI bars reflect the new limit
            // without anyone having to call SessionLifecycleService.getMaxContextWindow()
            // synchronously on EDT.
            val (maxCtxSection, maxCtxKey) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                ConfigKeys.MAX_CONTEXT_SIZE.key
            )
            if (section == maxCtxSection && key == maxCtxKey) {
                pl.jclab.refio.services.session.SessionManager.getInstance(project)
                    .refreshMaxContextWindow()
            }

            // Optional: show subtle notification
            ApplicationManager.getApplication().invokeLater {
                showSaveNotification("Setting saved")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save setting: $section.$key" }
            ApplicationManager.getApplication().invokeLater {
                showErrorNotification("Failed to save setting: ${e.message}")
            }
        }
    }

    /**
     * Shows a subtle save notification
     */
    private fun showSaveNotification(message: String) {
        // Optional: could use IntelliJ Notifications API
        logger.debug { message }
    }

    /**
     * Shows an error notification
     */
    private fun showErrorNotification(message: String) {
        JOptionPane.showMessageDialog(
            this,
            message,
            "Save Error",
            JOptionPane.ERROR_MESSAGE
        )
    }

    /**
     * Export current configuration to user config file (~/.refio/config.yaml)
     */
    private fun onExportToUserConfig() {
        val configPath = pl.jclab.refio.core.config.ConfigYaml.getUserConfigPath()

        val includeApiKeys = JOptionPane.showConfirmDialog(
            this,
            "Include API keys in export?\n\n" +
                    "Yes = API keys included (masked)\n" +
                    "No = API keys omitted",
            "Include API Keys?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        ) == JOptionPane.YES_OPTION

        // Show warning only if file exists
        if (configPath.exists()) {
            val result = JOptionPane.showConfirmDialog(
                this,
                "Overwrite existing config file?\n${configPath.absolutePath}",
                "File Exists",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (result != JOptionPane.YES_OPTION) {
                return
            }
        }

        coroutineScope.launch {
            try {
                logger.info { "Exporting configuration to user config: ${configPath.absolutePath}" }

                val configService = coreApiClient?.configService
                    ?: throw IllegalStateException("ConfigService not available")

                // Include MCP servers from the current project in user export too — they are stored per-project
                // in DB but user config.yaml is the natural home for personal MCP setup.
                val projectPath = project.basePath
                val projectId = projectPath?.let {
                    pl.jclab.refio.core.utils.ProjectIdGenerator.generate(java.nio.file.Paths.get(it))
                }
                configService.exportToYaml(
                    configPath,
                    includeApiKeys,
                    projectId = projectId,
                    toolPermissionsService = coreApiClient.toolPermissionsService
                )

                ApplicationManager.getApplication().invokeLater {
                    NotificationService.showInfo(
                        project,
                        "Configuration Exported",
                        "User config saved to: ${configPath.absolutePath}"
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to export configuration to user config" }
                ApplicationManager.getApplication().invokeLater {
                    NotificationService.showError(
                        project,
                        "Export Failed",
                        "Failed to export configuration: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Export project-specific configuration to project config file (<project>/.refio/config.yaml)
     */
    private fun onExportToProjectConfig() {
        val projectPath = project.basePath
        if (projectPath == null) {
            NotificationService.showError(project, "Export Failed", "Cannot determine project root directory.")
            return
        }

        val configPath = pl.jclab.refio.core.config.ConfigYaml.getProjectConfigPath(
            java.nio.file.Paths.get(projectPath)
        )

        // Show warning only if file exists
        if (configPath.exists()) {
            val result = JOptionPane.showConfirmDialog(
                this,
                "Overwrite existing project config?\n${configPath.absolutePath}",
                "File Exists",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (result != JOptionPane.YES_OPTION) {
                return
            }
        }

        coroutineScope.launch {
            try {
                logger.info { "Exporting configuration to project config: ${configPath.absolutePath}" }

                val configService = coreApiClient?.configService
                    ?: throw IllegalStateException("ConfigService not available")

                // Export without API keys for project config
                val projectId = pl.jclab.refio.core.utils.ProjectIdGenerator.generate(
                    java.nio.file.Paths.get(projectPath)
                )
                configService.exportToYaml(
                    configPath,
                    includeApiKeys = false,
                    projectId = projectId,
                    toolPermissionsService = coreApiClient.toolPermissionsService
                )

                ApplicationManager.getApplication().invokeLater {
                    NotificationService.showInfo(
                        project,
                        "Configuration Exported",
                        "Project config saved to: ${configPath.name}"
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to export configuration to project config" }
                ApplicationManager.getApplication().invokeLater {
                    NotificationService.showError(
                        project,
                        "Export Failed",
                        "Failed to export configuration: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Reload configuration from local YAML file
     */
    private fun onReloadFromLocalConfig() {
        val configPath = pl.jclab.refio.core.config.ConfigYaml.getConfigPath()

        if (!configPath.exists()) {
            NotificationService.showWarning(
                project,
                "Config Not Found",
                "Config file not found at: ${configPath.absolutePath}"
            )
            return
        }

        val result = JOptionPane.showConfirmDialog(
            this,
            "Reload settings from YAML file?\nThis will overwrite current settings.",
            "Confirm Reload",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result != JOptionPane.YES_OPTION) {
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Reloading configuration from YAML file: ${configPath.absolutePath}" }

                val configService = coreApiClient?.configService
                    ?: throw IllegalStateException("ConfigService not available")

                val updatedCount = configService.reloadFromYaml()

                ApplicationManager.getApplication().invokeLater {
                    reloadAllPanels()
                    NotificationService.showInfo(
                        project,
                        "Configuration Reloaded",
                        "$updatedCount settings updated from YAML file."
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to reload configuration from YAML" }
                ApplicationManager.getApplication().invokeLater {
                    NotificationService.showError(
                        project,
                        "Reload Failed",
                        "Failed to reload configuration: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Reset all settings to defaults
     */
    private fun onResetToDefaults() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to reset all settings to default values?\n" +
                    "This operation cannot be undone.",
            "Confirm Reset",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result != JOptionPane.YES_OPTION) {
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Resetting all settings to defaults" }

                // Call backend API
                coreApiClient?.configRouter?.resetAllSettingsToDefaults()

                ApplicationManager.getApplication().invokeLater {
                    // Reload all panels
                    reloadAllPanels()

                    NotificationService.showInfo(
                        project,
                        "Reset Complete",
                        "All settings have been reset to default values"
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to reset settings" }
                ApplicationManager.getApplication().invokeLater {
                    NotificationService.showError(
                        project,
                        "Reset Failed",
                        "Failed to reset settings: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Reloads all settings panels
     */
    private fun reloadAllPanels() {
        generalPanel.reload()
        providersPanel.reload()
        modelsPanel.reload()
        promptsPanel.reload()
        contextSettingsPanel.reload()
        mcpPanel.reload()
        docsPanel.reload()
        toolsPanel.reload()
        subagentPanel.reload()
        advancedPanel.reload()  // Merged: Advanced + Limits
        themePanel.reload()
    }

    @Volatile
    private var disposed = false

    /**
     * Cleanup coroutines and child panels. Idempotent. Must be called explicitly by the
     * owner (RefioContentPanel) because this view lives in a CardLayout and is only
     * hidden on "Back", so removeNotify() alone is not a reliable cleanup hook.
     */
    fun dispose() {
        if (disposed) return
        disposed = true

        // Cancel any pending save job immediately (don't wait)
        // This prevents blocking EDT which causes UI freezes
        saveJob?.cancel()

        // Cancel the coroutine scope
        coroutineScope.cancel()

        // Dispose panels with their own coroutine scopes
        contextSettingsPanel.dispose()
        mcpPanel.disposePanel()
        providersPanel.dispose()
        subagentPanel.dispose()

        logger.debug { "SettingsView cleanup completed" }
    }

    override fun removeNotify() {
        super.removeNotify()
        dispose()
    }

    private companion object {
        /** Wide enough for a 16 px icon plus the selection highlight, and no wider. */
        const val CATEGORY_STRIP_WIDTH = 30
    }
}

package pl.jclab.refio.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTabbedPane
import pl.jclab.refio.services.notification.NotificationService
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.BorderLayout
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
    private val coreApiClient: pl.jclab.refio.api.CoreApiClient?,
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

    init {
        border = LCATheme.emptyBorder()

        providersPanel.setOnModelsRefreshedCallback(modelsPanel::onProviderModelsRefreshed)

        // Header
        add(createHeader(), BorderLayout.NORTH)

        // Tabbed pane for settings sections
        val tabbedPane = JBTabbedPane(SwingConstants.TOP).apply {
            addTab("General", generalPanel)
            addTab("Providers", providersPanel)
            addTab("Models", modelsPanel)
            addTab("Prompts", promptsPanel)
            addTab("Context", contextSettingsPanel)
            addTab("MCP Servers", mcpPanel)
            addTab("Documentation", docsPanel)
            addTab("Tools", toolsPanel)
            addTab("Subagents", subagentPanel)  // AI subagents configuration
            addTab("Advanced", advancedPanel)  // Merged: Advanced + Limits
            addTab("Theme", themePanel)  // LCATheme visual preview
        }
        add(tabbedPane, BorderLayout.CENTER)

        // Footer
        add(createFooter(), BorderLayout.SOUTH)
    }

    private fun createHeader(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(12, 16)

            val backButton = JButton("< Back to Chat").apply {
                addActionListener { onBack() }
            }
            add(backButton, BorderLayout.WEST)

            val titleLabel = JLabel("Settings").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
            }
            add(titleLabel, BorderLayout.CENTER)
        }
    }

    private fun createFooter(): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            border = LCATheme.customLineBorder(
                LCATheme.borderColor,
                1, 0, 0, 0
            )

            val exportToUserButton = JButton("Export User").apply {
                addActionListener { onExportToUserConfig() }
                toolTipText = "Export current settings to ~/.refio/config.yaml"
            }
            add(exportToUserButton)

            val exportToProjectButton = JButton("Export Project").apply {
                addActionListener { onExportToProjectConfig() }
                toolTipText = "Export project-specific settings to <project>/.refio/config.yaml"
            }
            add(exportToProjectButton)

            val reloadFromLocalButton = JButton("Reload Config").apply {
                addActionListener { onReloadFromLocalConfig() }
                toolTipText = "Reload configuration from ~/.refio/config.yaml"
            }
            add(reloadFromLocalButton)

            val resetButton = JButton("Reset").apply {
                addActionListener { onResetToDefaults() }
                toolTipText = "Reset all settings to default values"
            }
            add(resetButton)
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
     * @param fullKey Full configuration key from ConfigService constants (e.g. ConfigService.KEY_API_CALL_TIMEOUT)
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
            coreApiClient.updateConfig(
                section = section,
                scope = "app",
                taskId = null,
                settings = settings
            )

            logger.info { "Setting saved: $section.$key" }

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

                val configService = coreApiClient?.router?.configService
                    ?: throw IllegalStateException("ConfigService not available")

                configService.exportToYaml(configPath, includeApiKeys)

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

                val configService = coreApiClient?.router?.configService
                    ?: throw IllegalStateException("ConfigService not available")

                // Export without API keys for project config
                configService.exportToYaml(configPath, includeApiKeys = false)

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

                val configService = coreApiClient?.router?.configService
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
                coreApiClient?.resetAllSettingsToDefaults()

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

    /**
     * Cleanup coroutines when component is removed
     * IMPORTANT: Cancel pending saves immediately to avoid blocking EDT
     */
    override fun removeNotify() {
        super.removeNotify()

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
}

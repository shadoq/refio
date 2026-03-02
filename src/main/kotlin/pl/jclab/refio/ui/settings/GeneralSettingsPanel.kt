package pl.jclab.refio.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.JLabel

/**
 * General Settings Panel
 * Contains general application settings
 */
class GeneralSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiClient?
) : JBPanel<GeneralSettingsPanel>(GridBagLayout()) {

    private val logger = dualLogger("GeneralSettingsPanel")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val formatMarkdownCheckbox: JBCheckBox
    private val streamingEnabledCheckbox: JBCheckBox
    private val advancedViewCheckbox: JBCheckBox

    // Flag to prevent triggering onSettingChanged during programmatic updates
    private var isUpdatingProgrammatically = false

    init {
        border = LCATheme.paddedBorder(LCATheme.margin)

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = LCATheme.insetsMedium
        }

        // Section title
        add(JLabel("General Settings").apply {
            font = font.deriveFont(14f).deriveFont(java.awt.Font.BOLD)
        }, gbc)

        // Format Markdown option
        gbc.gridy++
        gbc.insets = LCATheme.insetsGridBagLarge
        formatMarkdownCheckbox = JBCheckBox("Format Markdown in responses", true).apply {
            addItemListener { event ->
                if (!isUpdatingProgrammatically) {
                    val isSelected = event.stateChange == ItemEvent.SELECTED
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeys.split(
                        pl.jclab.refio.core.services.ConfigService.KEY_FORMAT_MARKDOWN
                    )
                    onSettingChanged(section, key, isSelected)
                }
            }
        }
        add(formatMarkdownCheckbox, gbc)

        // Add description
        gbc.gridy++
        gbc.insets = LCATheme.insetsDetailsIndented
        add(JLabel("<html><font color='gray'>Enable markdown rendering in chat responses</font></html>"), gbc)

        // Enable Streaming option (US-027)
        gbc.gridy++
        gbc.insets = LCATheme.insetsGridBagLarge
        streamingEnabledCheckbox = JBCheckBox("Enable streaming responses", true).apply {
            addItemListener { event ->
                if (!isUpdatingProgrammatically) {
                    val isSelected = event.stateChange == ItemEvent.SELECTED
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeys.split(
                        pl.jclab.refio.core.services.ConfigService.KEY_STREAMING_ENABLED
                    )
                    onSettingChanged(section, key, isSelected)
                }
            }
        }
        add(streamingEnabledCheckbox, gbc)

        // Add description
        gbc.gridy++
        gbc.insets = LCATheme.insetsDetailsIndented
        add(JLabel("<html><font color='gray'>Stream LLM responses in real-time (US-027)</font></html>"), gbc)

        // Advanced View option
        gbc.gridy++
        gbc.insets = LCATheme.insetsGridBagLarge
        advancedViewCheckbox = JBCheckBox("Advanced View", false).apply {
            addItemListener { event ->
                if (!isUpdatingProgrammatically) {
                    val isSelected = event.stateChange == ItemEvent.SELECTED
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeys.split(
                        pl.jclab.refio.core.services.ConfigService.KEY_ADVANCED_VIEW
                    )
                    onSettingChanged(section, key, isSelected)
                }
            }
        }
        add(advancedViewCheckbox, gbc)

        // Add description
        gbc.gridy++
        gbc.insets = LCATheme.insetsDetailsIndented
        add(
            JLabel("<html><font color='gray'>Show additional metrics rows and all tabs (Steps, Context, RAG, Debug)</font></html>"),
            gbc
        )

        // Filler to push content to top
        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        add(JBPanel<JBPanel<*>>(), gbc)

        loadGeneralConfig()
    }

    /**
     * Get current format markdown setting
     */
    fun isFormatMarkdownEnabled(): Boolean {
        return formatMarkdownCheckbox.isSelected
    }

    /**
     * Set format markdown setting
     */
    fun setFormatMarkdownEnabled(enabled: Boolean) {
        formatMarkdownCheckbox.isSelected = enabled
    }

    /**
     * Get current streaming enabled setting (US-027)
     */
    fun isStreamingEnabled(): Boolean {
        return streamingEnabledCheckbox.isSelected
    }

    /**
     * Set streaming enabled setting (US-027)
     */
    fun setStreamingEnabled(enabled: Boolean) {
        streamingEnabledCheckbox.isSelected = enabled
    }

    /**
     * Get current advanced view setting
     */
    fun isAdvancedViewEnabled(): Boolean {
        return advancedViewCheckbox.isSelected
    }

    /**
     * Set advanced view setting
     */
    fun setAdvancedViewEnabled(enabled: Boolean) {
        advancedViewCheckbox.isSelected = enabled
    }

    /**
     * Load general configuration from backend
     */
    private fun loadGeneralConfig() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, using defaults" }
            return
        }

        try {
            logger.info { "Loading general configuration" }

            val config = coreApiClient.getConfig(section = "general", scope = "app")
            applyGeneralConfig(config.settings)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load general config" }
        }
    }

    /**
     * Apply loaded configuration to UI
     */
    private fun applyGeneralConfig(settings: Map<String, Any>) {
        logger.info { "Applying general config: ${settings.keys}" }

        isUpdatingProgrammatically = true
        try {
            // Apply format_markdown setting
            val formatMarkdown = (settings["format_markdown"] as? String).toBoolean()
            logger.info { "Setting format_markdown = $formatMarkdown" }
            formatMarkdownCheckbox.isSelected = formatMarkdown

            // Apply streaming_enabled setting
            val streamingEnabled = (settings["streaming_enabled"] as? String).toBoolean()
            logger.info { "Setting streaming_enabled = $streamingEnabled" }
            streamingEnabledCheckbox.isSelected = streamingEnabled

            // Apply advanced_view setting
            val advancedView = (settings["advanced_view"] as? String).toBoolean()
            logger.info { "Setting advanced_view = $advancedView" }
            advancedViewCheckbox.isSelected = advancedView
        } finally {
            isUpdatingProgrammatically = false
        }
    }

    /**
     * Reload settings from backend
     */
    fun reload() {
        logger.info { "Reloading general configuration" }

        // Reset to defaults
        isUpdatingProgrammatically = true
        formatMarkdownCheckbox.isSelected = true
        streamingEnabledCheckbox.isSelected = true
        advancedViewCheckbox.isSelected = false
        isUpdatingProgrammatically = false

        // Reload from backend
        loadGeneralConfig()
    }

    /**
     * Cleanup coroutines when component is removed
     */
    override fun removeNotify() {
        super.removeNotify()
        coroutineScope.cancel()
    }
}

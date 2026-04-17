package pl.jclab.refio.ui.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.JComboBox
import javax.swing.JLabel

/**
 * General Settings Panel
 * Contains general application settings
 */
class GeneralSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiRouter?
) : JBPanel<GeneralSettingsPanel>(GridBagLayout()) {

    private val logger = dualLogger("GeneralSettingsPanel")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val formatMarkdownCheckbox: JBCheckBox
    private val streamingEnabledCheckbox: JBCheckBox
    private val advancedViewCheckbox: JBCheckBox
    private val thinkingEnabledCheckbox: JBCheckBox
    private val noEgressEnabledCheckbox: JBCheckBox
    private val executionModeCombo: JComboBox<String>

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
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.config.ConfigKeys.FORMAT_MARKDOWN.key
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
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.config.ConfigKeys.STREAMING_ENABLED.key
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
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.config.ConfigKeys.ADVANCED_VIEW.key
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

        // Thinking mode
        gbc.gridy++
        gbc.insets = LCATheme.insetsGridBagLarge
        thinkingEnabledCheckbox = JBCheckBox("Thinking mode", false).apply {
            addItemListener { event ->
                if (!isUpdatingProgrammatically) {
                    val isSelected = event.stateChange == ItemEvent.SELECTED
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.config.ConfigKeys.GENERAL_THINKING_ENABLED.key
                    )
                    onSettingChanged(section, key, isSelected)
                }
            }
        }
        add(thinkingEnabledCheckbox, gbc)

        gbc.gridy++
        gbc.insets = LCATheme.insetsDetailsIndented
        add(JLabel("<html><font color='gray'>Enable extended reasoning/thinking for models that support it</font></html>"), gbc)

        // No-egress mode
        gbc.gridy++
        gbc.insets = LCATheme.insetsGridBagLarge
        noEgressEnabledCheckbox = JBCheckBox("No-egress mode (block network)", false).apply {
            addItemListener { event ->
                if (!isUpdatingProgrammatically) {
                    val isSelected = event.stateChange == ItemEvent.SELECTED
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.config.ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key
                    )
                    onSettingChanged(section, key, isSelected)
                }
            }
        }
        add(noEgressEnabledCheckbox, gbc)

        gbc.gridy++
        gbc.insets = LCATheme.insetsDetailsIndented
        add(JLabel("<html><font color='gray'>Restrict tools to local-only operations (no outbound network)</font></html>"), gbc)

        // Execution mode
        gbc.gridy++
        gbc.insets = LCATheme.insetsGridBagLarge
        executionModeCombo = JComboBox(arrayOf("AUTO", "INTERACTIVE")).apply {
            addActionListener {
                if (!isUpdatingProgrammatically) {
                    val value = selectedItem as? String ?: return@addActionListener
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.config.ConfigKeys.GENERAL_EXECUTION_MODE.key
                    )
                    onSettingChanged(section, key, value)
                }
            }
        }
        val execPanel = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(JLabel("Execution mode: "))
            add(executionModeCombo)
        }
        add(execPanel, gbc)

        gbc.gridy++
        gbc.insets = LCATheme.insetsDetailsIndented
        add(JLabel("<html><font color='gray'>AUTO executes steps automatically; INTERACTIVE waits for confirmation</font></html>"), gbc)

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

            val config = coreApiClient.configRouter.getConfig(section = "general", scope = "app")
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
            formatMarkdownCheckbox.isSelected = (settings["format_markdown"] as? String).toBoolean()
            streamingEnabledCheckbox.isSelected = (settings["streaming_enabled"] as? String).toBoolean()
            advancedViewCheckbox.isSelected = (settings["advanced_view"] as? String).toBoolean()
            thinkingEnabledCheckbox.isSelected = (settings["thinking_enabled"] as? String).toBoolean()
            noEgressEnabledCheckbox.isSelected = (settings["no_egress_enabled"] as? String).toBoolean()

            val executionMode = (settings["execution_mode"] as? String)?.trim()?.uppercase()
            executionModeCombo.selectedItem = if (executionMode == "INTERACTIVE") "INTERACTIVE" else "AUTO"
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
        thinkingEnabledCheckbox.isSelected = false
        noEgressEnabledCheckbox.isSelected = false
        executionModeCombo.selectedItem = "AUTO"
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

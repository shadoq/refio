package pl.jclab.refio.ui.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.ui.dsl.builder.panel
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigKeyUtil
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.JComboBox

/**
 * General Settings Panel
 *
 * Built with the Kotlin UI DSL so label alignment, row spacing and the grey explanation lines
 * come from the platform instead of hand-tuned insets. Values are saved as they change (there is
 * no Apply button), hence listeners rather than DSL bindings.
 */
class GeneralSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiRouter?
) : JBPanel<GeneralSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("GeneralSettingsPanel")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var formatMarkdownCheckbox: JBCheckBox
    private lateinit var streamingEnabledCheckbox: JBCheckBox
    private lateinit var advancedViewCheckbox: JBCheckBox
    private lateinit var reasoningEffortCombo: JComboBox<String>
    private lateinit var noEgressEnabledCheckbox: JBCheckBox
    private lateinit var executionModeCombo: JComboBox<String>
    private lateinit var nativeToolsModeCombo: JComboBox<String>

    // Flag to prevent triggering onSettingChanged during programmatic updates
    private var isUpdatingProgrammatically = false

    init {
        val form = panel {
            group("Responses") {
                row {
                    formatMarkdownCheckbox = checkBox("Format Markdown in responses")
                        .comment("Enable markdown rendering in chat responses")
                        .applyToComponent { onToggle(ConfigKeys.FORMAT_MARKDOWN.key) }
                        .component
                }
                row {
                    streamingEnabledCheckbox = checkBox("Enable streaming responses")
                        .comment("Stream LLM responses in real-time")
                        .applyToComponent { onToggle(ConfigKeys.STREAMING_ENABLED.key) }
                        .component
                }
                row("Reasoning effort:") {
                    reasoningEffortCombo = comboBox(listOf("OFF", "LOW", "MEDIUM", "HIGH"))
                        .applyToComponent { onSelect(ConfigKeys.GENERAL_REASONING_EFFORT.key) }
                        .component
                }.rowComment("Reasoning strength for models that support it (OFF disables it where the provider allows)")
            }

            group("Execution") {
                row("Execution mode:") {
                    executionModeCombo = comboBox(listOf("AUTO", "INTERACTIVE"))
                        .applyToComponent { onSelect(ConfigKeys.GENERAL_EXECUTION_MODE.key) }
                        .component
                }.rowComment("AUTO executes steps automatically; INTERACTIVE waits for confirmation")

                row("Native tools mode:") {
                    nativeToolsModeCombo = comboBox(listOf("auto", "always", "never"))
                        .applyToComponent { onSelect(ConfigKeys.NATIVE_TOOLS_MODE.key) }
                        .component
                }.rowComment("auto: use native tools if the model supports it; always: force native tools; never: use JSON fallback")
            }

            group("Interface") {
                row {
                    advancedViewCheckbox = checkBox("Advanced View")
                        .comment("Show additional metrics and the Context, RAG, Debug, Logs and API screens")
                        .applyToComponent { onToggle(ConfigKeys.ADVANCED_VIEW.key) }
                        .component
                }
                row {
                    noEgressEnabledCheckbox = checkBox("No-egress mode (block network)")
                        .comment("Restrict tools to local-only operations (no outbound network)")
                        .applyToComponent { onToggle(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key) }
                        .component
                }
            }
        }

        add(form, BorderLayout.NORTH)

        loadGeneralConfig()
    }

    private fun javax.swing.JCheckBox.onToggle(fullKey: String) {
        addItemListener { event ->
            if (isUpdatingProgrammatically) return@addItemListener
            val (section, key) = ConfigKeyUtil.split(fullKey)
            onSettingChanged(section, key, event.stateChange == ItemEvent.SELECTED)
        }
    }

    private fun JComboBox<String>.onSelect(fullKey: String) {
        addActionListener {
            if (isUpdatingProgrammatically) return@addActionListener
            val value = selectedItem as? String ?: return@addActionListener
            val (section, key) = ConfigKeyUtil.split(fullKey)
            onSettingChanged(section, key, value)
        }
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
     * Get current streaming enabled setting
     */
    fun isStreamingEnabled(): Boolean {
        return streamingEnabledCheckbox.isSelected
    }

    /**
     * Set streaming enabled setting
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

            val generalSettings = coreApiClient.configRouter.getConfig(section = "general", scope = "app").settings
            val toolsSettings = coreApiClient.configRouter.getConfig(section = "tools", scope = "app").settings
            applyGeneralConfig(generalSettings, toolsSettings)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load general config" }
        }
    }

    private fun applyGeneralConfig(settings: Map<String, Any>, toolsSettings: Map<String, Any> = emptyMap()) {
        logger.info { "Applying general config: ${settings.keys}" }

        isUpdatingProgrammatically = true
        try {
            formatMarkdownCheckbox.isSelected = (settings["format_markdown"] as? String).toBoolean()
            streamingEnabledCheckbox.isSelected = (settings["streaming_enabled"] as? String).toBoolean()
            advancedViewCheckbox.isSelected = (settings["advanced_view"] as? String).toBoolean()
            val reasoningEffort = (settings["reasoning_effort"] as? String)?.trim()?.uppercase()
            reasoningEffortCombo.selectedItem = if (reasoningEffort in setOf("LOW", "MEDIUM", "HIGH")) reasoningEffort else "OFF"
            noEgressEnabledCheckbox.isSelected = (settings["no_egress_enabled"] as? String).toBoolean()

            val executionMode = (settings["execution_mode"] as? String)?.trim()?.uppercase()
            executionModeCombo.selectedItem = if (executionMode == "INTERACTIVE") "INTERACTIVE" else "AUTO"

            val nativeToolsMode = (toolsSettings["native_tools"] as? String)?.trim()?.lowercase()
            nativeToolsModeCombo.selectedItem = if (nativeToolsMode in setOf("always", "never")) nativeToolsMode else "auto"
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
        reasoningEffortCombo.selectedItem = "OFF"
        noEgressEnabledCheckbox.isSelected = false
        executionModeCombo.selectedItem = "AUTO"
        nativeToolsModeCombo.selectedItem = "auto"
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

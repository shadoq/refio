package pl.jclab.refio.ui.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigKeyUtil
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import javax.swing.*

/**
 * Advanced Settings Panel
 *
 * Manages advanced configuration options including:
 * - Security settings (read-only mode)
 * - Agent sampling
 * - Limits (timeouts, size limits, context auto-optimize)
 *
 * Laid out with the Kotlin UI DSL: groups, label alignment and comment styling come from the
 * platform rather than from struts and hand-set insets. Settings save on change, so the controls
 * carry listeners instead of DSL bindings.
 */
class AdvancedSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiRouter?
) : JBPanel<AdvancedSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("AdvancedSettingsPanel")

    // Security
    private lateinit var readOnlyModeCheckbox: JBCheckBox

    // Limits - Timeouts
    private lateinit var toolExecutionSlider: JSlider
    private lateinit var toolExecutionLabel: JLabel
    private lateinit var apiCallSlider: JSlider
    private lateinit var apiCallLabel: JLabel

    // Limits - Size
    private lateinit var maxFileSizeField: JBTextField
    private lateinit var maxContextSizeField: JBTextField
    private lateinit var maxOutputSizeField: JBTextField

    // Performance
    private lateinit var autoOptimizeSlider: JSlider
    private lateinit var autoOptimizeLabel: JLabel

    // Agent - sampling
    private lateinit var decisionTempField: JBTextField

    // Flag to prevent triggering onSettingChanged during programmatic updates
    private var isUpdatingProgrammatically = false

    init {
        val form = panel {
            group("Security") {
                row {
                    readOnlyModeCheckbox = checkBox("Read-only mode")
                        .comment("Prevent all file write operations")
                        .applyToComponent {
                            addItemListener {
                                if (isUpdatingProgrammatically) return@addItemListener
                                save(ConfigKeys.READ_ONLY_MODE.key, isSelected)
                            }
                        }
                        .component
                }
            }

            group("Agent") {
                row("Decision-turn temperature:") {
                    decisionTempField = textField()
                        .columns(6)
                        .applyToComponent {
                            text = "0.7"
                            addFocusListener(object : java.awt.event.FocusAdapter() {
                                override fun focusLost(e: java.awt.event.FocusEvent?) {
                                    if (isUpdatingProgrammatically) return
                                    val value = (text.toDoubleOrNull() ?: 0.7).coerceIn(0.0, 2.0)
                                    // Normalize the field to the accepted value (clamps/garbage → canonical text)
                                    isUpdatingProgrammatically = true
                                    text = formatTemp(value)
                                    isUpdatingProgrammatically = false
                                    save(ConfigKeys.AGENT_DECISION_TEMPERATURE.key, value)
                                }
                            })
                        }
                        .component
                    label("0.0 - 2.0")
                }.rowComment(
                    "Sampling temperature for the PLAN/AGENT turn that picks tools. " +
                        "Lower = stricter contract adherence (helps small/local models); " +
                        "higher = more variety. Default 0.7."
                )
            }

            group("Timeouts") {
                row("Tool execution:") {
                    toolExecutionSlider = slider(5, 520, 10, 50)
                        .applyToComponent {
                            value = 360
                            addChangeListener {
                                toolExecutionLabel.text = "$value seconds"
                                if (valueIsAdjusting || isUpdatingProgrammatically) return@addChangeListener
                                save(ConfigKeys.TOOL_EXECUTION_TIMEOUT.key, value)
                            }
                        }
                        .component
                    toolExecutionLabel = label("360 seconds").component
                }

                row("API call:") {
                    apiCallSlider = slider(5, 520, 10, 50)
                        .applyToComponent {
                            value = 360
                            addChangeListener {
                                apiCallLabel.text = "$value seconds"
                                if (valueIsAdjusting || isUpdatingProgrammatically) return@addChangeListener
                                save(ConfigKeys.API_CALL_TIMEOUT.key, value)
                            }
                        }
                        .component
                    apiCallLabel = label("360 seconds").component
                }
            }

            group("Size limits") {
                row("Maximum file size:") {
                    maxFileSizeField = intField("10", ConfigKeys.MAX_FILE_SIZE.key, 10)
                    label("MB")
                }
                row("Maximum context size:") {
                    maxContextSizeField = intField("128000", ConfigKeys.MAX_CONTEXT_SIZE.key, 128000)
                    label("tokens")
                }
                row("Maximum output size:") {
                    maxOutputSizeField = intField("8192", ConfigKeys.MAX_OUTPUT_SIZE.key, 8192)
                    label("tokens")
                }
            }

            group("Performance") {
                row("Auto-optimize context at:") {
                    autoOptimizeSlider = slider(80, 95, 1, 5)
                        .applyToComponent {
                            value = 85
                            addChangeListener {
                                autoOptimizeLabel.text = "$value%"
                                if (valueIsAdjusting || isUpdatingProgrammatically) return@addChangeListener
                                save(ConfigKeys.AUTO_OPTIMIZE_PERCENTAGE.key, value)
                            }
                        }
                        .component
                    autoOptimizeLabel = label("85%").component
                }.rowComment("Automatically optimize context when it reaches this percentage of the limit")
            }
        }

        val scrollPane = JBScrollPane(form).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        loadAdvancedConfig()
    }

    /** Numeric field that commits on focus loss, falling back to [defaultValue] on garbage input. */
    private fun com.intellij.ui.dsl.builder.Row.intField(
        initial: String,
        fullKey: String,
        defaultValue: Int
    ): JBTextField = textField()
        .columns(8)
        .applyToComponent {
            text = initial
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    if (isUpdatingProgrammatically) return
                    save(fullKey, text.toIntOrNull() ?: defaultValue)
                }
            })
        }
        .component

    private fun save(fullKey: String, value: Any) {
        val (section, key) = ConfigKeyUtil.split(fullKey)
        onSettingChanged(section, key, value)
    }

    // ==================== PUBLIC API ====================

    fun isReadOnlyModeEnabled(): Boolean = readOnlyModeCheckbox.isSelected
    fun setReadOnlyMode(enabled: Boolean) {
        readOnlyModeCheckbox.isSelected = enabled
    }

    fun getAutoOptimizePercentage(): Int = autoOptimizeSlider.value
    fun setAutoOptimizePercentage(percentage: Int) {
        autoOptimizeSlider.value = percentage
    }

    fun getDecisionTemperature(): Double = (decisionTempField.text.toDoubleOrNull() ?: 0.7).coerceIn(0.0, 2.0)
    fun setDecisionTemperature(value: Double) {
        decisionTempField.text = formatTemp(value.coerceIn(0.0, 2.0))
    }

    // Limits API (from LimitsSettingsPanel)
    fun getToolExecutionTimeout(): Int = toolExecutionSlider.value
    fun setToolExecutionTimeout(seconds: Int) {
        toolExecutionSlider.value = seconds
    }

    fun getApiCallTimeout(): Int = apiCallSlider.value
    fun setApiCallTimeout(seconds: Int) {
        apiCallSlider.value = seconds
    }

    fun getMaxFileSizeMB(): Int = maxFileSizeField.text.toIntOrNull() ?: 10
    fun setMaxFileSizeMB(sizeMB: Int) {
        maxFileSizeField.text = sizeMB.toString()
    }

    fun getMaxContextSize(): Int = maxContextSizeField.text.toIntOrNull() ?: 128000
    fun setMaxContextSize(tokens: Int) {
        maxContextSizeField.text = tokens.toString()
    }

    fun getMaxOutputSize(): Int = maxOutputSizeField.text.toIntOrNull() ?: 8192
    fun setMaxOutputSize(tokens: Int) {
        maxOutputSizeField.text = tokens.toString()
    }

    fun reload() {
        logger.info { "Reloading advanced configuration" }

        isUpdatingProgrammatically = true
        try {
            readOnlyModeCheckbox.isSelected = false
            toolExecutionSlider.value = 120
            apiCallSlider.value = 240
            maxFileSizeField.text = "10"
            maxContextSizeField.text = "128000"
            maxOutputSizeField.text = "8192"
            autoOptimizeSlider.value = 85
            decisionTempField.text = "0.7"
        } finally {
            isUpdatingProgrammatically = false
        }

        loadAdvancedConfig()
    }

    // ==================== HELPERS ====================

    private fun loadAdvancedConfig() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, using defaults" }
            return
        }

        try {
            logger.info { "Loading advanced configuration" }
            val advancedConfig = coreApiClient.configRouter.getConfig(section = "advanced", scope = "app")
            val limitsConfig = coreApiClient.configRouter.getConfig(section = "limits", scope = "app")
            val agentConfig = coreApiClient.configRouter.getConfig(section = "agent", scope = "app")

            applyAdvancedConfig(advancedConfig.settings)
            applyLimitsConfig(limitsConfig.settings)
            applyAgentConfig(agentConfig.settings)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load advanced config" }
        }
    }

    private fun applyAdvancedConfig(settings: Map<String, Any>) {
        isUpdatingProgrammatically = true
        try {
            val readOnlyMode = parseBoolean(settings["read_only_mode"], false)
            val autoOptimize = parseInt(settings["auto_optimize_percentage"], 85)
                .coerceIn(autoOptimizeSlider.minimum, autoOptimizeSlider.maximum)

            readOnlyModeCheckbox.isSelected = readOnlyMode
            autoOptimizeSlider.value = autoOptimize
        } finally {
            isUpdatingProgrammatically = false
        }
    }

    private fun applyLimitsConfig(settings: Map<String, Any>) {
        isUpdatingProgrammatically = true
        try {
            val toolExecution = parseInt(settings["tool_execution_timeout"], 120)
                .coerceIn(toolExecutionSlider.minimum, toolExecutionSlider.maximum)
            val apiCallTimeout = parseInt(settings["api_call_timeout"], 240)
                .coerceIn(apiCallSlider.minimum, apiCallSlider.maximum)
            val maxFileSize = parseInt(settings["max_file_size"], 10)
            val maxContextSize = parseInt(settings["max_context_size"], 128000)
            val maxOutputSize = parseInt(settings["max_output_size"], 8192)

            toolExecutionSlider.value = toolExecution
            apiCallSlider.value = apiCallTimeout
            maxFileSizeField.text = maxFileSize.toString()
            maxContextSizeField.text = maxContextSize.toString()
            maxOutputSizeField.text = maxOutputSize.toString()
        } finally {
            isUpdatingProgrammatically = false
        }
    }

    private fun applyAgentConfig(settings: Map<String, Any>) {
        isUpdatingProgrammatically = true
        try {
            val temp = parseDouble(settings["decision_temperature"], 0.7).coerceIn(0.0, 2.0)
            decisionTempField.text = formatTemp(temp)
        } finally {
            isUpdatingProgrammatically = false
        }
    }

    /** Render a temperature as canonical config text ("0.7", "0.55") regardless of UI locale. */
    private fun formatTemp(value: Double): String = value.toString()

    private fun parseBoolean(raw: Any?, defaultValue: Boolean): Boolean {
        return when (raw) {
            is Boolean -> raw
            is String -> raw.toBoolean()
            else -> defaultValue
        }
    }

    private fun parseInt(raw: Any?, defaultValue: Int): Int {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun parseDouble(raw: Any?, defaultValue: Double): Double {
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: defaultValue
            else -> defaultValue
        }
    }
}

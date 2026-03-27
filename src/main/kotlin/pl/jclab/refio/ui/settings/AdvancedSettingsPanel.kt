package pl.jclab.refio.ui.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/**
 * Advanced Settings Panel
 *
 * Manages advanced configuration options including:
 * - Security settings (No-Egress, Read-only mode)
 * - Limits settings (Timeouts, Size limits) - merged from LimitsSettingsPanel
 * - Context optimization threshold (Auto-optimize)
 */
class AdvancedSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiClient?
) : JBPanel<AdvancedSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("AdvancedSettingsPanel")

    // Security
    private lateinit var noEgressDefaultCheckbox: JBCheckBox
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

    // Flag to prevent triggering onSettingChanged during programmatic updates
    private var isUpdatingProgrammatically = false

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                LCATheme.customLineBorder(LCATheme.borderColor, 1),
                "Advanced Settings"
            ),
            LCATheme.paddedBorder(LCATheme.margin)
        )

        // Main content with visual sections
        val contentPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(createSectionPanel("Security", createSecurityPanel()))
            add(Box.createVerticalStrut(LCATheme.spacingLg))
            add(createSectionPanel("Limits", createLimitsPanel()))

            // Filler
            add(Box.createVerticalGlue())
        }

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        loadAdvancedConfig()
    }

    // ==================== SECURITY ====================

    private fun createSecurityPanel(): JPanel {
        noEgressDefaultCheckbox = JBCheckBox("Enable No-Egress mode by default", false).apply {
            addItemListener {
                if (isUpdatingProgrammatically) {
                    return@addItemListener
                }
                val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                    pl.jclab.refio.core.services.ConfigService.KEY_NO_EGRESS_DEFAULT
                )
                onSettingChanged(section, key, isSelected)
            }
        }

        readOnlyModeCheckbox = JBCheckBox("Read-only mode", false).apply {
            addItemListener {
                if (isUpdatingProgrammatically) {
                    return@addItemListener
                }
                val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                    pl.jclab.refio.core.services.ConfigService.KEY_READ_ONLY_MODE
                )
                onSettingChanged(section, key, isSelected)
            }
        }

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = LCATheme.paddedBorder(LCATheme.padding)

            add(noEgressDefaultCheckbox)
            add(JBLabel("Block all external network calls by default").apply {
                foreground = LCATheme.descriptionForeground
                border = LCATheme.paddedBorder(0, 24, 8, 0)
            })

            add(readOnlyModeCheckbox)
            add(JBLabel("Prevent all file write operations").apply {
                foreground = LCATheme.descriptionForeground
                border = LCATheme.paddedBorder(0, 24, 0, 0)
            })
        }
    }

    // ==================== LIMITS (merged from LimitsSettingsPanel) ====================

    private fun createLimitsPanel(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = LCATheme.paddedBorder(LCATheme.padding)

            // Timeouts section
            add(JBLabel("Timeouts:").apply {
                font = LCATheme.boldFont
            })
            add(Box.createVerticalStrut(8))

            // Tool execution timeout
            add(JBLabel("Tool Execution Timeout:"))
            toolExecutionSlider = JSlider(5, 520, 360).apply {
                majorTickSpacing = 50
                minorTickSpacing = 10
                paintTicks = true
                paintLabels = true
            }
            toolExecutionLabel = JLabel("360 seconds")

            toolExecutionSlider.addChangeListener {
                toolExecutionLabel.text = "${toolExecutionSlider.value} seconds"
                if (!toolExecutionSlider.valueIsAdjusting) {
                    if (isUpdatingProgrammatically) {
                        return@addChangeListener
                    }
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.services.ConfigService.KEY_TOOL_EXECUTION_TIMEOUT
                    )
                    onSettingChanged(section, key, toolExecutionSlider.value)
                }
            }

            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(toolExecutionSlider, BorderLayout.CENTER)
                add(toolExecutionLabel, BorderLayout.EAST)
            })

            add(Box.createVerticalStrut(12))

            // API call timeout
            add(JBLabel("API Call Timeout:"))
            apiCallSlider = JSlider(5, 520, 360).apply {
                majorTickSpacing = 50
                minorTickSpacing = 10
                paintTicks = true
                paintLabels = true
            }
            apiCallLabel = JLabel("360 seconds")

            apiCallSlider.addChangeListener {
                apiCallLabel.text = "${apiCallSlider.value} seconds"
                if (!apiCallSlider.valueIsAdjusting) {
                    if (isUpdatingProgrammatically) {
                        return@addChangeListener
                    }
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.services.ConfigService.KEY_API_CALL_TIMEOUT
                    )
                    onSettingChanged(section, key, apiCallSlider.value)
                }
            }

            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(apiCallSlider, BorderLayout.CENTER)
                add(apiCallLabel, BorderLayout.EAST)
            })

            add(Box.createVerticalStrut(16))

            // Size limits section
            add(JBLabel("Size Limits:").apply {
                font = LCATheme.boldFont
            })
            add(Box.createVerticalStrut(8))

            // Max file size
            maxFileSizeField = JBTextField("10", 8).apply {
                addFocusListener(object : java.awt.event.FocusAdapter() {
                    override fun focusLost(e: java.awt.event.FocusEvent?) {
                        if (isUpdatingProgrammatically) {
                            return
                        }
                        val value = text.toIntOrNull() ?: 10
                        val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                            pl.jclab.refio.core.services.ConfigService.KEY_MAX_FILE_SIZE
                        )
                        onSettingChanged(section, key, value)
                    }
                })
            }
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Maximum File Size:"))
                add(maxFileSizeField)
                add(JBLabel("MB"))
            })

            // Max context size
            maxContextSizeField = JBTextField("128000", 10).apply {
                addFocusListener(object : java.awt.event.FocusAdapter() {
                    override fun focusLost(e: java.awt.event.FocusEvent?) {
                        if (isUpdatingProgrammatically) {
                            return
                        }
                        val value = text.toIntOrNull() ?: 128000
                        val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                            pl.jclab.refio.core.services.ConfigService.KEY_MAX_CONTEXT_SIZE
                        )
                        onSettingChanged(section, key, value)
                    }
                })
            }
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Maximum Context Size:"))
                add(maxContextSizeField)
                add(JBLabel("tokens"))
            })

            // Max output size
            maxOutputSizeField = JBTextField("8192", 10).apply {
                addFocusListener(object : java.awt.event.FocusAdapter() {
                    override fun focusLost(e: java.awt.event.FocusEvent?) {
                        if (isUpdatingProgrammatically) {
                            return
                        }
                        val value = text.toIntOrNull() ?: 8192
                        val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                            pl.jclab.refio.core.services.ConfigService.KEY_MAX_OUTPUT_SIZE
                        )
                        onSettingChanged(section, key, value)
                    }
                })
            }
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Maximum Output Size:"))
                add(maxOutputSizeField)
                add(JBLabel("tokens"))
            })
            add(Box.createVerticalStrut(16))

            // Context optimization threshold
            add(JBLabel("Auto-optimize context at:").apply {
                font = LCATheme.boldFont
            })
            add(Box.createVerticalStrut(4))

            autoOptimizeSlider = JSlider(80, 95, 85).apply {
                majorTickSpacing = 5
                minorTickSpacing = 1
                paintTicks = true
                paintLabels = true
            }
            autoOptimizeLabel = JLabel("85%")

            autoOptimizeSlider.addChangeListener {
                autoOptimizeLabel.text = "${autoOptimizeSlider.value}%"
                if (!autoOptimizeSlider.valueIsAdjusting) {
                    if (isUpdatingProgrammatically) {
                        return@addChangeListener
                    }
                    val (section, key) = pl.jclab.refio.core.services.ConfigKeyUtil.split(
                        pl.jclab.refio.core.services.ConfigService.KEY_AUTO_OPTIMIZE_PERCENTAGE
                    )
                    onSettingChanged(section, key, autoOptimizeSlider.value)
                }
            }

            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(autoOptimizeSlider, BorderLayout.CENTER)
                add(autoOptimizeLabel, BorderLayout.EAST)
            })

            add(JBLabel("Automatically optimize context when it reaches this percentage of limit").apply {
                foreground = LCATheme.descriptionForeground
            })
        }
    }

    // ==================== PUBLIC API ====================

    fun isNoEgressDefaultEnabled(): Boolean = noEgressDefaultCheckbox.isSelected
    fun setNoEgressDefault(enabled: Boolean) {
        noEgressDefaultCheckbox.isSelected = enabled
    }

    fun isReadOnlyModeEnabled(): Boolean = readOnlyModeCheckbox.isSelected
    fun setReadOnlyMode(enabled: Boolean) {
        readOnlyModeCheckbox.isSelected = enabled
    }

    fun getAutoOptimizePercentage(): Int = autoOptimizeSlider.value
    fun setAutoOptimizePercentage(percentage: Int) {
        autoOptimizeSlider.value = percentage
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
            noEgressDefaultCheckbox.isSelected = false
            readOnlyModeCheckbox.isSelected = false
            toolExecutionSlider.value = 120
            apiCallSlider.value = 240
            maxFileSizeField.text = "10"
            maxContextSizeField.text = "128000"
            maxOutputSizeField.text = "8192"
            autoOptimizeSlider.value = 85
        } finally {
            isUpdatingProgrammatically = false
        }

        loadAdvancedConfig()
    }

    // ==================== HELPERS ====================

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

    private fun loadAdvancedConfig() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, using defaults" }
            return
        }

        try {
            logger.info { "Loading advanced configuration" }
            val advancedConfig = coreApiClient.getConfig(section = "advanced", scope = "app")
            val limitsConfig = coreApiClient.getConfig(section = "limits", scope = "app")

            applyAdvancedConfig(advancedConfig.settings)
            applyLimitsConfig(limitsConfig.settings)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load advanced config" }
        }
    }

    private fun applyAdvancedConfig(settings: Map<String, Any>) {
        isUpdatingProgrammatically = true
        try {
            val noEgressDefault = parseBoolean(settings["no_egress_default"], false)
            val readOnlyMode = parseBoolean(settings["read_only_mode"], false)
            val autoOptimize = parseInt(settings["auto_optimize_percentage"], 85)
                .coerceIn(autoOptimizeSlider.minimum, autoOptimizeSlider.maximum)

            noEgressDefaultCheckbox.isSelected = noEgressDefault
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
}

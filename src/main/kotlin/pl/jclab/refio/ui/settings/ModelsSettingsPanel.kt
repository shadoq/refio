package pl.jclab.refio.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.notification.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.*
import javax.swing.*
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableModel

/**
 * Model preset configuration - predefined combinations of models for different use cases.
 *
 * @param name Display name for the preset
 * @param description Short description of the preset use case
 * @param defaultModel Model for chat/default operations (provider/modelId format)
 * @param planModel Model for planning operations (null = use defaultModel)
 * @param codingModel Model for coding operations (null = use defaultModel)
 * @param weakModel Model for auxiliary operations like summaries (null = use defaultModel)
 * @param visibleModels Additional models to show in dropdown (besides the ones used in preset)
 */
data class ModelPreset(
    val name: String,
    val description: String,
    val defaultModel: String,
    val planModel: String,
    val codingModel: String,
    val weakModel: String,
    val visibleModels: List<String> = emptyList()
) {
    /**
     * Get all unique models used in this preset (for visibility settings).
     */
    fun getAllUsedModels(): Set<String> {
        return setOfNotNull(defaultModel, planModel, codingModel, weakModel) + visibleModels
    }

    companion object {
        /**
         * Predefined presets for common use cases.
         * Models are specified in "provider/modelId" format.
         */
        val PRESETS = listOf(
            ModelPreset(
                name = "OpenAI Mini models",
                description = "OpenAI Mini-tier cloud models for most tasks",
                defaultModel = "openai/gpt-4.1-mini",
                planModel = "openai/gpt-4.1-mini",
                codingModel = "openai/gpt-5.1-codex-mini",
                weakModel = "openai/gpt-4.1-nano",
                visibleModels = listOf(
                    "openai/gpt-4.1-mini",
                    "openai/gpt-4.1-nano",
                    "openai/gpt-5-mini",
                    "openai/gpt-5-nano",
                    "openai/gpt-5.1-codex-mini",
                    "openai/o3-mini",
                    "openai/o4-mini",
                )
            ),
            ModelPreset(
                name = "OpenAI coding models (4.1/5.1-codex)",
                description = "OpenAI cloud models for coding tasks",
                defaultModel = "openai/gpt-4.1",
                planModel = "openai/gpt-4.1",
                codingModel = "openai/gpt-5.1-codex",
                weakModel = "openai/gpt-4.1",
                visibleModels = listOf(
                    "openai/gpt-4.1",
                    "openai/gpt-5",
                    "openai/gpt-5.1-codex",
                    "openai/gpt-5.1",
                    "openai/gpt-5.2",
                )
            ),
            ModelPreset(
                name = "Anthropic medium models",
                description = "Anthropic medium models for coding tasks",
                defaultModel = "anthropic/claude-sonnet-4-5-20250929",
                planModel = "anthropic/claude-sonnet-4-5-20250929",
                codingModel = "anthropic/claude-sonnet-4-5-20250929",
                weakModel = "anthropic/claude-haiku-4-5-20251001",
                visibleModels = listOf(
                    "anthropic/claude-sonnet-4-5-20250929",
                    "anthropic/claude-haiku-4-5-20251001",
                )
            ),
            ModelPreset(
                name = "Local Qwen2.5 Coder 14b",
                description = "Local models Qwen2.5",
                defaultModel = "ollama/qwen2.5-coder:14b",
                planModel = "ollama/qwen2.5-coder:14b",
                codingModel = "ollama/qwen2.5-coder:14b",
                weakModel = "ollama/qwen2.5-coder:14b",
                visibleModels = listOf(
                    "ollama/qwen2.5:3b",
                    "ollama/qwen2.5:7b",
                    "ollama/qwen2.5-coder:7b",
                    "ollama/qwen2.5:14b",
                    "ollama/qwen2.5-coder:14b",
                )
            ),
            ModelPreset(
                name = "Local Qwen3 14b",
                description = "Local models Qwen2.5",
                defaultModel = "ollama/qwen3:14b",
                planModel = "ollama/qwen3:14b",
                codingModel = "ollama/qwen3:14b",
                weakModel = "ollama/qwen3:14b",
                visibleModels = listOf(
                    "ollama/qwen3:8b",
                    "ollama/qwen3:14b",
                    "ollama/qwen3:30b",
                    "ollama/qwen3:32b",
                    "ollama/qwen3-coder:30b",
                )
            ),
            ModelPreset(
                name = "Local Qwen3 Coder 30b",
                description = "Local models Qwen3",
                defaultModel = "ollama/qwen3:14b",
                planModel = "ollama/qwen3:14b",
                codingModel = "ollama/qwen3:14b",
                weakModel = "ollama/qwen3:14b",
                visibleModels = listOf(
                    "ollama/qwen3:8b",
                    "ollama/qwen3:14b",
                    "ollama/qwen3:30b",
                    "ollama/qwen3:32b",
                    "ollama/qwen3-coder:30b",
                )
            ),
            ModelPreset(
                name = "Local Gemma3 12b",
                description = "Local Gemma3 12b",
                defaultModel = "ollama/gemma3:12b",
                planModel = "ollama/gemma3:12b",
                codingModel = "ollama/gemma3:12b",
                weakModel = "ollama/gemma3:12b",
                visibleModels = listOf(
                    "ollama/gemma3:12b",
                    "ollama/gemma3:27b",
                )
            ),
            ModelPreset(
                name = "Local GPT OSS 20b",
                description = "Local GPT OSS 20b",
                defaultModel = "ollama/gpt-oss:20b",
                planModel = "ollama/gpt-oss:20b",
                codingModel = "ollama/gpt-oss:20b",
                weakModel = "ollama/gpt-oss:20b",
                visibleModels = listOf(
                    "ollama/gpt-oss:20b",
                )
            ),
        )
    }
}

/**
 * Models Settings Panel
 * Manages available models and per-mode model selection
 */
class ModelsSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiClient?
) : JBPanel<ModelsSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("ModelsSettingsPanel")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var modelsTable: JBTable
    private lateinit var refreshStatusLabel: JLabel
    private lateinit var defaultModelCombo: JComboBox<String>
    private lateinit var planModelCombo: JComboBox<String>
    private lateinit var codingModelCombo: JComboBox<String>
    private lateinit var weakModelCombo: JComboBox<String>
    private lateinit var embeddingModelCombo: JComboBox<String>

    // Flag to prevent saving when dropdowns are updated programmatically
    private var isUpdatingDropdowns = false

    init {
        border = LCATheme.paddedBorder(LCATheme.margin)

        // Initialize combo boxes first
        defaultModelCombo = JComboBox()
        planModelCombo = JComboBox()
        codingModelCombo = JComboBox()
        weakModelCombo = JComboBox()
        embeddingModelCombo = JComboBox()

        // Main content
        val contentPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                weighty = 0.0
            }

            // Presets panel (quick selection)
            add(createPresetsPanel(), gbc)

            gbc.gridy++
            gbc.fill = GridBagConstraints.BOTH
            gbc.weighty = 1.0
            gbc.insets = LCATheme.insetsTopMedium
            add(createModelsTable(), gbc)

            gbc.gridy++
            gbc.weighty = 0.0
            gbc.insets = LCATheme.insetsTopMedium
            add(createModelSelectionPanel(), gbc)
        }

        // Wrap contentPanel in scroll pane for small screens
        val scrollPane = JBScrollPane(contentPanel).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        // Load models from backend
        loadModels()
    }

    private fun createModelsTable(): JComponent {
        val columnNames = arrayOf(
            "Provider",
            "Model Name",
            "Context Size",
            "Capabilities",
            "Price IN ($/1M)",
            "Price OUT ($/1M)",
            "Show in Dropdown",
            "Model ID"  // Hidden column for internal use
        )

        modelsTable = JBTable(object : DefaultTableModel(columnNames, 0) {
            override fun getColumnClass(columnIndex: Int): Class<*> {
                return when (columnIndex) {
                    6 -> java.lang.Boolean::class.java  // Show in Dropdown
                    else -> String::class.java
                }
            }

            override fun isCellEditable(row: Int, column: Int): Boolean {
                return column == 6  // Only checkbox "Show in Dropdown"
            }
        }).apply {
            setShowGrid(true)
            gridColor = JBColor.LIGHT_GRAY

            // Auto-save on checkbox change
            model.addTableModelListener { event ->
                if (event.type == TableModelEvent.UPDATE && event.column == 6) {
                    val row = event.firstRow
                    val modelId = getValueAt(row, 7) as String  // Use hidden column 7 (model.id)
                    val showInDropdown = getValueAt(row, 6) as Boolean
                    onModelVisibilityChanged(modelId, showInDropdown)
                }
            }

            // Column widths
            columnModel.getColumn(0).preferredWidth = 100  // Provider
            columnModel.getColumn(1).preferredWidth = 250  // Model Name
            columnModel.getColumn(2).preferredWidth = 100  // Context Size
            columnModel.getColumn(3).preferredWidth = 200  // Capabilities
            columnModel.getColumn(4).preferredWidth = 100  // Price IN
            columnModel.getColumn(5).preferredWidth = 100  // Price OUT
            columnModel.getColumn(6).preferredWidth = 120  // Show in Dropdown

            // Hide column 7 (Model ID) - used only internally
            columnModel.getColumn(7).minWidth = 0
            columnModel.getColumn(7).maxWidth = 0
            columnModel.getColumn(7).preferredWidth = 0
        }

        val scrollPane = JScrollPane(modelsTable).apply {
            minimumSize = Dimension(200, 200)
            preferredSize = Dimension(400, 300)
            maximumSize = Dimension(900, 400)
        }

        // Create panel with table and refresh button
        val tablePanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {

            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Models"),
                LCATheme.paddedBorder(LCATheme.padding)
            )

            // Refresh button panel
            val refreshPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                border = LCATheme.paddedBorder(0, 0, 8, 0)

                add(JButton("Refresh").apply {
                    toolTipText = "Refresh model list from all providers"
                    addActionListener { onRefreshModels() }
                })

                add(JLabel(" | "))

                add(JButton("Show All").apply {
                    toolTipText = "Show all models in dropdown"
                    addActionListener { onShowAllModels() }
                })

                add(JButton("Hide All").apply {
                    toolTipText = "Hide all models from dropdown"
                    addActionListener { onHideAllModels() }
                })

                refreshStatusLabel = JLabel(" ")
                add(refreshStatusLabel)
            }
            add(refreshPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        return tablePanel
    }

    /**
     * Create presets panel with dropdown selector and Apply button.
     */
    private fun createPresetsPanel(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Quick Presets"),
                LCATheme.paddedBorder(LCATheme.padding)
            )

            // Description label
            val descLabel =
                JLabel("<html><font color='gray'>Select a preset to quickly configure all model slots. You can customize individual models below.</font></html>")
            add(descLabel, BorderLayout.NORTH)

            // Dropdown and Apply button panel
            val controlsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 8))

            // Preset dropdown
            val presetCombo = JComboBox(ModelPreset.PRESETS.toTypedArray()).apply {
                renderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean
                    ): Component {
                        val label =
                            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                        if (value is ModelPreset) {
                            label.text = value.name
                            label.toolTipText = buildString {
                                append("<html>")
                                append("<b>${value.description}</b><br><br>")
                                append("Default: ${value.defaultModel}<br>")
                                append("Plan: ${value.planModel ?: value.defaultModel}<br>")
                                append("Coding: ${value.codingModel ?: value.defaultModel}<br>")
                                append("Weak: ${value.weakModel ?: value.defaultModel}")
                                append("</html>")
                            }
                        }
                        return label
                    }
                }
                preferredSize = Dimension(250, preferredSize.height)
            }
            controlsPanel.add(presetCombo)

            // Apply button
            val applyButton = JButton("Apply").apply {
                addActionListener {
                    val selectedPreset = presetCombo.selectedItem as? ModelPreset
                    if (selectedPreset != null) {
                        applyPreset(selectedPreset)
                    }
                }
            }
            controlsPanel.add(applyButton)

            add(controlsPanel, BorderLayout.CENTER)
        }
    }

    /**
     * Apply a model preset - sets all model slots and updates model visibility.
     *
     * Steps:
     * 1. Hide all models (like "Hide All" button)
     * 2. Show only models from preset.visibleModels
     * 3. Set default models for each operation slot
     */
    private fun applyPreset(preset: ModelPreset) {
        logger.info { "Applying preset: ${preset.name}" }

        coroutineScope.launch {
            try {
                // Step 1: Get all models and hide them
                val allModels = coreApiClient?.getModelsWithVisibility() ?: emptyList()
                logger.info { "Updating visibility for ${allModels.size} models (preset)" }

                // Step 2: Show only models from preset
                val modelsToShow = preset.getAllUsedModels()
                logger.info { "Showing ${modelsToShow.size} models from preset: $modelsToShow" }

                val visibleModelIds = mutableSetOf<String>()
                for (modelFullId in modelsToShow) {
                    // modelFullId format: "provider/modelId" -> need to find matching model.id
                    val parts = modelFullId.split("/", limit = 2)
                    if (parts.size == 2) {
                        val provider = parts[0]
                        val modelId = parts[1]

                        // Find model by provider and modelId
                        val matchingModel = allModels.find { model ->
                            model.provider.equals(provider, ignoreCase = true) &&
                                    (model.id == modelId || model.id.equals(modelFullId, ignoreCase = true))
                        }

                        if (matchingModel != null) {
                            visibleModelIds.add(matchingModel.id)
                            logger.info { "Enabled model: ${matchingModel.id}" }
                        } else {
                            logger.warn { "Model not found: $modelFullId (may not be installed)" }
                        }
                    }
                }

                val visibilityMap = buildVisibilityMap(allModels, visibleModelIds)
                coreApiClient?.updateModelsVisibility(visibilityMap)

                // Step 3: Set default models for each operation
                val defaultParts = preset.defaultModel.split("/", limit = 2)
                if (defaultParts.size == 2) {
                    coreApiClient?.setDefaultModel(
                        request = pl.jclab.refio.core.api.SetDefaultModelRequest(
                            operation = ModelOperation.DEFAULT,
                            modelId = defaultParts[1],
                            provider = defaultParts[0]
                        ),
                        taskId = null
                    )
                }

                // Plan model (fallback to default if not specified)
                val planModel = preset.planModel ?: preset.defaultModel
                val planParts = planModel.split("/", limit = 2)
                if (planParts.size == 2) {
                    coreApiClient?.setDefaultModel(
                        request = pl.jclab.refio.core.api.SetDefaultModelRequest(
                            operation = ModelOperation.PLAN,
                            modelId = planParts[1],
                            provider = planParts[0]
                        ),
                        taskId = null
                    )
                }

                // Coding model (fallback to default if not specified)
                val codingModel = preset.codingModel ?: preset.defaultModel
                val codingParts = codingModel.split("/", limit = 2)
                if (codingParts.size == 2) {
                    coreApiClient?.setDefaultModel(
                        request = pl.jclab.refio.core.api.SetDefaultModelRequest(
                            operation = ModelOperation.CODING,
                            modelId = codingParts[1],
                            provider = codingParts[0]
                        ),
                        taskId = null
                    )
                }

                // Weak model (fallback to default if not specified)
                val weakModel = preset.weakModel ?: preset.defaultModel
                val weakParts = weakModel.split("/", limit = 2)
                if (weakParts.size == 2) {
                    coreApiClient?.setDefaultModel(
                        request = pl.jclab.refio.core.api.SetDefaultModelRequest(
                            operation = ModelOperation.WEAK,
                            modelId = weakParts[1],
                            provider = weakParts[0]
                        ),
                        taskId = null
                    )
                }

                logger.info { "Preset '${preset.name}' applied successfully" }

                // Step 4: Reload UI to reflect changes
                val updatedModels = coreApiClient?.getModelsWithVisibility() ?: emptyList()
                val visibleCount = updatedModels.count { it.showInDropdown }

                ApplicationManager.getApplication().invokeLater {
                    // Reload table and dropdowns
                    populateModelsTable(updatedModels)
                    // Note: loadSavedModelSelections() is already called inside populateModelsTable()

                    // Show success notification
                    val notificationContent = buildString {
                        append("Visible models: $visibleCount\n")
                        append("Default: ${preset.defaultModel}\n")
                        append("Plan: ${preset.planModel ?: preset.defaultModel}\n")
                        append("Coding: ${preset.codingModel ?: preset.defaultModel}\n")
                        append("Weak: ${preset.weakModel ?: preset.defaultModel}")
                    }
                    NotificationService.showInfo(
                        project = null,
                        title = "Preset '${preset.name}' Applied",
                        content = notificationContent
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to apply preset: ${preset.name}" }
                ApplicationManager.getApplication().invokeLater {
                    NotificationService.showError(
                        project = null,
                        title = "Failed to Apply Preset",
                        content = "Failed to apply preset: ${e.message}\n\n" +
                                "Note: Some models in this preset may not be available. " +
                                "Make sure the required providers are configured."
                    )
                }
            }
        }
    }

    private fun createModelSelectionPanel(): JPanel {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Model Selection per Mode"),
                LCATheme.paddedBorder(LCATheme.padding)
            )

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                insets = LCATheme.insetsFormField
            }

            // Default model
            add(JLabel("Default Model:"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            add(defaultModelCombo.apply {
                addActionListener {
                    onModelSelectionChanged("default", selectedItem as? String)
                }
                isEnabled = true
            }, gbc)

            // Plan model
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            add(JLabel("Plan Model:"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            add(planModelCombo.apply {
                addActionListener {
                    onModelSelectionChanged("plan", selectedItem as? String)
                }
                isEnabled = true
            }, gbc)

            // Coding model
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            add(JLabel("Coding Model:"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            add(codingModelCombo.apply {
                addActionListener {
                    onModelSelectionChanged("coding", selectedItem as? String)
                }
                isEnabled = true
            }, gbc)

            // Weak model
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            add(JLabel("Weak Model (auxiliary):"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            add(weakModelCombo.apply {
                addActionListener {
                    onModelSelectionChanged("weak", selectedItem as? String)
                }
                isEnabled = true
            }, gbc)

            // Embedding model
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            add(JLabel("Embedding Model (RAG):"), gbc)
            gbc.gridx++
            gbc.weightx = 1.0
            add(embeddingModelCombo.apply {
                addActionListener {
                    onModelSelectionChanged("embedding", selectedItem as? String)
                }
                isEnabled = true
            }, gbc)

            // Description
            gbc.gridx = 0
            gbc.gridy++
            gbc.gridwidth = 2
            gbc.insets = LCATheme.insetsDialogField
            add(
                JLabel(
                    "<html><font color='gray'>" +
                            "• Default: used when no specialized model is specified<br>" +
                            "• Plan: used in Planning mode<br>" +
                            "• Coding: used in Agent mode for coding tasks<br>" +
                            "• Weak: cheaper model for auxiliary tasks (summaries, simple questions)<br>" +
                            "• Embedding: model for generating embeddings (RAG search) - prefer Ollama for free local embeddings" +
                            "</font></html>"
                ), gbc
            )
        }
    }

    private fun onRefreshModels() {
        coroutineScope.launch {
            try {
                ApplicationManager.getApplication().invokeLater {
                    refreshStatusLabel.text = "⟳ Refreshing..."
                    refreshStatusLabel.foreground = JBColor.BLUE
                }

                logger.info { "Refreshing models from all providers" }

                val models = coreApiClient?.refreshAllModels()
                    ?: throw Exception("CoreApiClient not available")

                // Apply smart defaults for new models (preserves existing settings)
                val modelsWithDefaults = applySmartDefaults(models)

                ApplicationManager.getApplication().invokeLater {
                    populateModelsTable(modelsWithDefaults)
                    refreshStatusLabel.text = "✓ Refreshed (${modelsWithDefaults.size} models)"
                    refreshStatusLabel.foreground = Color(0, 150, 0)

                    // Hide status after 3 seconds
                    Timer(3000) {
                        refreshStatusLabel.text = ""
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to refresh models" }
                ApplicationManager.getApplication().invokeLater {
                    refreshStatusLabel.text = "✗ Refresh error"
                    refreshStatusLabel.foreground = JBColor.RED

                    JOptionPane.showMessageDialog(
                        this@ModelsSettingsPanel,
                        "Failed to refresh model list:\n${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun onShowAllModels() {
        val tableModel = modelsTable.model as DefaultTableModel
        val rowCount = tableModel.rowCount

        if (rowCount == 0) {
            logger.warn { "No models to show" }
            return
        }

        logger.info { "Showing all $rowCount models" }

        coroutineScope.launch {
            try {
                val visibilityMap = buildVisibilityMapFromTable(showInDropdown = true)
                coreApiClient?.updateModelsVisibility(visibilityMap)

                // Reload models from database and update UI
                val models = coreApiClient?.getModelsWithVisibility() ?: emptyList()
                ApplicationManager.getApplication().invokeLater {
                    populateModelsTable(models)
                    logger.info { "All models shown successfully" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to show all models" }
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this@ModelsSettingsPanel,
                        "Failed to show all models:\n${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun onHideAllModels() {
        val tableModel = modelsTable.model as DefaultTableModel
        val rowCount = tableModel.rowCount

        if (rowCount == 0) {
            logger.warn { "No models to hide" }
            return
        }

        logger.info { "Hiding all $rowCount models" }

        coroutineScope.launch {
            try {
                val visibilityMap = buildVisibilityMapFromTable(showInDropdown = false)
                coreApiClient?.updateModelsVisibility(visibilityMap)

                // Reload models from database and update UI
                val models = coreApiClient?.getModelsWithVisibility() ?: emptyList()
                ApplicationManager.getApplication().invokeLater {
                    populateModelsTable(models)
                    logger.info { "All models hidden successfully" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to hide all models" }
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this@ModelsSettingsPanel,
                        "Failed to hide all models:\n${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun loadModels() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, using sample data" }
            populateSampleModels()
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Loading models from backend" }

                val models = coreApiClient.getModelsWithVisibility()

                // Apply smart defaults if this is the first time (no visibility settings yet)
                val modelsWithDefaults = applySmartDefaults(models)

                ApplicationManager.getApplication().invokeLater {
                    populateModelsTable(modelsWithDefaults)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load models" }
                ApplicationManager.getApplication().invokeLater {
                    populateSampleModels()
                }
            }
        }
    }

    /**
     * Apply smart defaults for model visibility on first load.
     *
     * Rules:
     * - Local models (Ollama): Show all by default (fast, free)
     * - Cheap cloud models: Show (GPT-4o-mini, Claude Haiku, Gemini Flash)
     * - Expensive models: Hide by default (GPT-4, Claude Opus)
     * - Preserve existing visibility settings from DB
     */
    private suspend fun applySmartDefaults(models: List<pl.jclab.refio.core.api.ModelInfo>): List<pl.jclab.refio.core.api.ModelInfo> {
        // Check if we have any visibility settings already (i.e., user has configured before)
        val hasExistingSettings = models.any { model ->
            // If visibility differs from our default, it means user has set it
            val defaultVisibility = getDefaultVisibility(model)
            model.showInDropdown != defaultVisibility
        }

        // If user has existing settings, preserve them
        if (hasExistingSettings) {
            logger.info { "Found existing visibility settings, preserving them" }
            return models
        }

        // First time - apply smart defaults and persist to DB
        logger.info { "Applying smart defaults for model visibility (first time setup)" }

        val modelsWithDefaults = models.map { model ->
            val defaultVisibility = getDefaultVisibility(model)

            // If model already has visibility setting in DB, preserve it
            // Otherwise use smart default
            if (model.showInDropdown != defaultVisibility) {
                // Update DB with smart default
                try {
                    coreApiClient?.updateModelVisibility(model.id, defaultVisibility)
                    model.copy(showInDropdown = defaultVisibility)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to set default visibility for ${model.id}" }
                    model
                }
            } else {
                model
            }
        }

        logger.info { "Applied smart defaults to ${modelsWithDefaults.count { it.showInDropdown }} models" }
        return modelsWithDefaults
    }

    /**
     * Get default visibility for a model based on provider and cost.
     */
    private fun getDefaultVisibility(model: pl.jclab.refio.core.api.ModelInfo): Boolean {
        return when (model.provider.lowercase()) {
            "ollama" -> true  // All local models shown by default

            "openai" -> when {
                model.id.contains("gpt-4o-mini", ignoreCase = true) -> true
                model.id.contains("gpt-3.5", ignoreCase = true) -> true
                model.id.contains("gpt-4o", ignoreCase = true) -> true  // Standard GPT-4o is ok
                model.id.contains("gpt-4-turbo", ignoreCase = true) -> false  // Expensive
                model.id.contains("gpt-4", ignoreCase = true) -> false  // Expensive
                else -> false
            }

            "anthropic" -> when {
                model.id.contains("haiku", ignoreCase = true) -> true  // Cheap
                model.id.contains("sonnet", ignoreCase = true) -> true  // Reasonable
                model.id.contains("opus", ignoreCase = true) -> false  // Expensive
                else -> false
            }

            "gemini" -> when {
                model.id.contains("flash", ignoreCase = true) -> true  // Fast/tańsze
                else -> false
            }

            "google" -> when {
                model.id.contains("flash", ignoreCase = true) -> true  // Cheap
                model.id.contains("pro", ignoreCase = true) -> false  // Expensive
                else -> false
            }

            "openrouter" -> when {
                // Show only cheap models by default
                model.pricing?.let { (it.inputPer1MTokens + it.outputPer1MTokens) < 2.0 } ?: false -> true
                else -> false
            }

            "lmstudio" -> true  // Local, free

            else -> false  // Unknown providers hidden by default
        }
    }

    private fun populateModelsTable(models: List<pl.jclab.refio.core.api.ModelInfo>) {
        val tableModel = modelsTable.model as DefaultTableModel
        tableModel.rowCount = 0  // Clear table

        // Sort models: alphabetically by provider, then by model name
        val sortedModels = models.sortedWith(
            compareBy<pl.jclab.refio.core.api.ModelInfo> { it.provider.lowercase() }
                .thenBy { it.name.lowercase() }
        )

        sortedModels.forEach { model ->
            tableModel.addRow(
                arrayOf(
                    model.provider,
                    model.name,
                    formatContextSize(model.contextSize),
                    formatCapabilities(model.capabilities),
                    formatPrice(model.pricing?.inputPer1MTokens),
                    formatPrice(model.pricing?.outputPer1MTokens),
                    model.showInDropdown,
                    model.id  // Hidden column 7: model ID for persistence
                )
            )
        }

        logger.info { "Loaded ${sortedModels.size} models (sorted by provider, then model name)" }

        // Update model dropdowns (with sorting)
        updateModelDropdowns(sortedModels)

        // Load saved selections from backend
        loadSavedModelSelections()
    }

    private fun loadSavedModelSelections() {
        coroutineScope.launch {
            try {
                // Load saved models for each mode
                val chatModel = coreApiClient?.getDefaultModel(ModelOperation.DEFAULT)
                val planModel = coreApiClient?.getDefaultModel(ModelOperation.PLAN)
                val agentModel = coreApiClient?.getDefaultModel(ModelOperation.CODING)

                val embeddingModel = coreApiClient?.getDefaultModel(ModelOperation.EMBEDDING)

                ApplicationManager.getApplication().invokeLater {
                    // Set flag to prevent saving during programmatic update
                    isUpdatingDropdowns = true
                    try {
                        // Set dropdown values (format: provider/modelId)
                        chatModel?.let { selectModelInCombo(defaultModelCombo, "${it.provider}/${it.modelId}") }
                        planModel?.let { selectModelInCombo(planModelCombo, "${it.provider}/${it.modelId}") }
                        agentModel?.let { selectModelInCombo(codingModelCombo, "${it.provider}/${it.modelId}") }

                        // Set embedding model or default to Ollama nomic-embed-text
                        val embeddingValue =
                            embeddingModel?.let { "${it.provider}/${it.modelId}" } ?: "ollama/nomic-embed-text"
                        selectModelInCombo(embeddingModelCombo, embeddingValue)

                        logger.info { "Loaded saved model selections: chat=${chatModel?.modelId}, plan=${planModel?.modelId}, agent=${agentModel?.modelId}, embedding=$embeddingValue" }
                    } finally {
                        // Always reset flag
                        isUpdatingDropdowns = false
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load saved model selections" }
            }
        }
    }

    private fun selectModelInCombo(combo: JComboBox<String>, value: String) {
        for (i in 0 until combo.itemCount) {
            if (combo.getItemAt(i) == value) {
                combo.selectedIndex = i
                return
            }
        }
        logger.warn { "Model not found in combo: $value" }
    }

    private fun formatContextSize(size: Int): String {
        return when {
            size >= 1_000_000 -> "${size / 1_000_000}M"
            size >= 1_000 -> "${size / 1_000}K"
            else -> size.toString()
        }
    }

    private fun formatCapabilities(capabilities: List<String>): String {
        return capabilities.joinToString(", ")
    }

    private fun formatPrice(price: Double?): String {
        return price?.let { String.format("%.2f", it) } ?: "N/A"
    }

    private fun onModelVisibilityChanged(modelId: String, showInDropdown: Boolean) {
        logger.info { "Model visibility changed: $modelId -> $showInDropdown" }

        coroutineScope.launch {
            try {
                coreApiClient?.updateModelVisibility(
                    modelId = modelId,
                    showInDropdown = showInDropdown
                )

                logger.info { "Model visibility saved: $modelId -> $showInDropdown" }

                // Refresh dropdowns with updated visibility
                val models = coreApiClient?.getModelsWithVisibility() ?: emptyList()
                ApplicationManager.getApplication().invokeLater {
                    updateModelDropdowns(models)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save model visibility: $modelId" }

                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this@ModelsSettingsPanel,
                        "Failed to save setting: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )

                    // Restore previous value
                    reloadModels()
                }
            }
        }
    }

    private fun onModelSelectionChanged(modelType: String, modelId: String?) {
        if (modelId == null || modelId.isEmpty()) {
            return
        }

        // Skip saving if we're updating dropdowns programmatically
        if (isUpdatingDropdowns) {
            logger.debug { "Skipping save (programmatic update): $modelType -> $modelId" }
            return
        }

        logger.debug { "Model selection changed: $modelType -> $modelId" }

        coroutineScope.launch {
            try {
                // Extract provider and modelId from combined format (provider/modelId)
                val parts = modelId.split("/", limit = 2)
                if (parts.size != 2) {
                    logger.error { "Invalid modelId format: $modelId (expected: provider/modelId)" }
                    return@launch
                }
                val provider = parts[0]
                val model = parts[1]

                val operation = when (modelType) {
                    "default" -> ModelOperation.DEFAULT
                    "plan" -> ModelOperation.PLAN
                    "coding" -> ModelOperation.CODING
                    "weak" -> ModelOperation.WEAK
                    "embedding" -> ModelOperation.EMBEDDING
                    else -> {
                        logger.error { "Unknown modelType: $modelType" }
                        return@launch
                    }
                }

                // Save using proper API
                coreApiClient?.setDefaultModel(
                    request = pl.jclab.refio.core.api.SetDefaultModelRequest(
                        operation = operation,
                        modelId = model,
                        provider = provider
                    ),
                    taskId = null
                )

                logger.info { "Model saved: $modelType -> $modelId (provider=$provider, model=$model)" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save model selection: $modelType -> $modelId" }

                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this@ModelsSettingsPanel,
                        "Failed to save model: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun updateModelDropdowns(models: List<pl.jclab.refio.core.api.ModelInfo>) {
        // Set flag to prevent saving during programmatic update
        isUpdatingDropdowns = true
        try {
            // Get only models with showInDropdown = true
            val visibleModels = models.filter { it.showInDropdown }

            // Sort visible models: alphabetically by provider, then by model ID
            val sortedVisibleModels = visibleModels.sortedWith(
                compareBy<pl.jclab.refio.core.api.ModelInfo> { it.provider.lowercase() }
                    .thenBy { it.id.lowercase() }
            )

            val currentDefault = defaultModelCombo.selectedItem as? String
            val currentPlan = planModelCombo.selectedItem as? String
            val currentCoding = codingModelCombo.selectedItem as? String
            val currentWeak = weakModelCombo.selectedItem as? String
            val currentEmbedding = embeddingModelCombo.selectedItem as? String

            // Update dropdowns - use model.id (not model.name) for proper persistence
            listOf(defaultModelCombo, planModelCombo, codingModelCombo, weakModelCombo).forEach { combo ->
                combo.removeAllItems()
                sortedVisibleModels.forEach { model ->
                    // Format: provider/modelId (e.g., "ollama/qwen2.5:14b")
                    combo.addItem("${model.provider}/${model.id}")
                }
            }

            logger.debug { "Updated model dropdowns with ${sortedVisibleModels.size} visible models (sorted by provider, then model ID)" }

            // Populate embedding model combo with hardcoded embedding-specific models
            embeddingModelCombo.removeAllItems()
            embeddingModelCombo.addItem("ollama/nomic-embed-text")  // Free, local
            embeddingModelCombo.addItem("openai/text-embedding-3-small")  // OpenAI

            // Restore previous selections (if still available)
            restoreSelection(defaultModelCombo, currentDefault)
            restoreSelection(planModelCombo, currentPlan)
            restoreSelection(codingModelCombo, currentCoding)
            restoreSelection(weakModelCombo, currentWeak)
            restoreSelection(embeddingModelCombo, currentEmbedding)
        } finally {
            // Always reset flag
            isUpdatingDropdowns = false
        }
    }

    private fun buildVisibilityMapFromTable(showInDropdown: Boolean): Map<String, Boolean> {
        val tableModel = modelsTable.model as DefaultTableModel
        val rowCount = tableModel.rowCount
        val visibilityMap = HashMap<String, Boolean>(rowCount)

        for (row in 0 until rowCount) {
            val modelId = tableModel.getValueAt(row, 7) as String
            visibilityMap[modelId] = showInDropdown
        }

        return visibilityMap
    }

    private fun buildVisibilityMap(
        allModels: List<pl.jclab.refio.core.api.ModelInfo>,
        modelIdsToShow: Set<String>
    ): Map<String, Boolean> {
        val visibilityMap = HashMap<String, Boolean>(allModels.size)
        for (model in allModels) {
            visibilityMap[model.id] = modelIdsToShow.contains(model.id)
        }
        return visibilityMap
    }

    private fun restoreSelection(combo: JComboBox<String>, previousSelection: String?) {
        if (previousSelection != null) {
            for (i in 0 until combo.itemCount) {
                if (combo.getItemAt(i) == previousSelection) {
                    combo.selectedIndex = i
                    return
                }
            }
        }
        // If not found, leave first element selected
    }

    private fun populateSampleModels() {
        val sampleModels = listOf<pl.jclab.refio.core.api.ModelInfo>(
        )

        populateModelsTable(sampleModels)
    }

    private fun reloadModels() {
        loadModels()
    }

    /**
     * Reload settings from backend
     */
    fun reload() {
        logger.info { "Reloading models panel" }
        loadModels()
    }

    /**
     * Public callback for provider models refresh
     */
    fun onProviderModelsRefreshed(provider: String, models: List<pl.jclab.refio.core.api.ModelInfo>) {
        logger.info { "Received ${models.size} models from provider: $provider" }

        coroutineScope.launch {
            try {
                // Just reload all models with visibility settings
                val allModels = coreApiClient?.getModelsWithVisibility() ?: emptyList()

                ApplicationManager.getApplication().invokeLater {
                    populateModelsTable(allModels)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to refresh models after provider update" }
            }
        }
    }
}

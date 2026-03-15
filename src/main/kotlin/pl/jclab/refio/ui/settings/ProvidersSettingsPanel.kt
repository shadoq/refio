package pl.jclab.refio.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.services.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Providers Settings Panel
 * Manages LLM provider configurations (Ollama, Anthropic, OpenAI, OpenRouter)
 *
 * Features:
 * - Auto-save with debounce (500ms)
 * - Connection testing with visual feedback
 * - Automatic model list refresh after successful connection
 * - Masked password fields for API keys
 */
class ProvidersSettingsPanel(
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit,
    private val coreApiClient: CoreApiClient?
) : JBPanel<ProvidersSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("ProvidersSettingsPanel")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val providersPanel: JPanel
    private val providerStates = mutableMapOf<String, ProviderCardState>()
    private var saveJobs = mutableMapOf<String, Job>()
    private val saveDebounceMs = 500L

    // Callback for model list refresh
    private var onModelsRefreshed: ((provider: String, models: List<pl.jclab.refio.core.api.ModelInfo>) -> Unit)? =
        null

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                LCATheme.customLineBorder(LCATheme.borderColor, 1),
                "Providers"
            ),
            LCATheme.paddedBorder(LCATheme.margin)
        )

        // Providers cards
        providersPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = LCATheme.paddedBorder(8, 0, 0, 0)
        }

        // Create all provider cards
        createAllProviderCards()

        // Wrap providersPanel in scroll pane for small screens
        val scrollPane = JBScrollPane(providersPanel).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        // Load configuration from backend
        loadProvidersConfig()
    }

    /**
     * Field types for provider configuration
     */
    private enum class FieldType {
        TEXT, PASSWORD, DROPDOWN
    }

    /**
     * Provider field definition
     */
    private data class ProviderField(
        val label: String,
        val type: FieldType,
        val key: String,
        val defaultValue: String? = null,
        val dropdownOptions: List<String>? = null
    )

    /**
     * Provider status indicators
     */
    private enum class ProviderStatus(val displayText: String, val color: Color) {
        DETECTED("✓ Detected locally", Color(0, 150, 0)),
        CONFIGURED("✓ Configured", Color(0, 150, 0)),
        NEEDS_CONFIG("⚠ Requires configuration", JBColor.ORANGE),
        ERROR("✗ Connection error", JBColor.RED),
        TESTING("⟳ Testing...", JBColor.BLUE)
    }

    /**
     * State holder for a provider card
     */
    private data class ProviderCardState(
        val providerName: String,
        val fields: Map<String, JComponent>,
        val statusLabel: JLabel,
        val testButton: JButton
    )

    /**
     * Create all provider cards
     */
    private fun createAllProviderCards() {
        providersPanel.add(
            createProviderCard(
                providerName = "Ollama",
                fields = listOf(
                    ProviderField("Server Endpoint", FieldType.TEXT, "ollama_endpoint", "http://localhost:11434"),
                    ProviderField(
                        label = "Context Size",
                        type = FieldType.DROPDOWN,
                        key = "ollama_context_size",
                        defaultValue = DEFAULT_CONTEXT_SIZE.toString(),
                        dropdownOptions = listOf("2048", "4096", "8192", "16384", "32768", "65536", "131072")
                    ),
                    ProviderField(
                        label = "Keep Alive (seconds)",
                        type = FieldType.DROPDOWN,
                        key = "ollama_keep_alive",
                        defaultValue = "1800",
                        dropdownOptions = listOf("0", "300", "600", "1800", "3600", "7200")
                    )
                ),
                initialStatus = ProviderStatus.NEEDS_CONFIG,
                description = "Local LLM server. Select context size and keep alive duration from dropdowns."
            )
        )

        providersPanel.add(Box.createVerticalStrut(12))

        providersPanel.add(
            createProviderCard(
                providerName = "Anthropic",
                fields = listOf(
                    ProviderField("API Key", FieldType.PASSWORD, "anthropic_api_key")
                ),
                initialStatus = ProviderStatus.NEEDS_CONFIG
            )
        )

        providersPanel.add(Box.createVerticalStrut(12))

        providersPanel.add(
            createProviderCard(
                providerName = "OpenAI",
                fields = listOf(
                    ProviderField("API Key", FieldType.PASSWORD, "openai_api_key")
                ),
                initialStatus = ProviderStatus.NEEDS_CONFIG
            )
        )

        providersPanel.add(Box.createVerticalStrut(12))

        providersPanel.add(
            createProviderCard(
                providerName = "OpenRouter",
                fields = listOf(
                    ProviderField("API Key", FieldType.PASSWORD, "openrouter_api_key")
                ),
                initialStatus = ProviderStatus.NEEDS_CONFIG,
                description = "Unified API for multiple LLM providers (Anthropic, OpenAI, Google, Meta, etc.)"
            )
        )

        providersPanel.add(Box.createVerticalStrut(12))

        providersPanel.add(
            createProviderCard(
                providerName = "Gemini",
                fields = listOf(
                    ProviderField("API Key", FieldType.PASSWORD, "gemini_api_key")
                ),
                initialStatus = ProviderStatus.NEEDS_CONFIG,
                description = "Google Gemini API (text + multimodal)"
            )
        )

        providersPanel.add(Box.createVerticalStrut(12))

        providersPanel.add(
            createProviderCard(
                providerName = "LMStudio",
                fields = listOf(
                    ProviderField("Base URL", FieldType.TEXT, "lmstudio_base_url", "http://localhost:1234/v1"),
                    ProviderField(
                        label = "Context Size",
                        type = FieldType.DROPDOWN,
                        key = "lmstudio_context_size",
                        defaultValue = DEFAULT_CONTEXT_SIZE.toString(),
                        dropdownOptions = listOf("2048", "4096", "8192", "16384", "32768", "65536", "131072")
                    )
                ),
                initialStatus = ProviderStatus.NEEDS_CONFIG,
                description = "Local LM Studio server (OpenAI-compatible API, zero cost). Select context size from dropdown."
            )
        )

        providersPanel.add(Box.createVerticalStrut(12))

        providersPanel.add(
            createProviderCard(
                providerName = "ZAI",
                fields = listOf(
                    ProviderField("API Key", FieldType.PASSWORD, "zai_api_key"),
                    ProviderField("Base URL", FieldType.TEXT, "zai_base_url", "https://api.z.ai/v1")
                ),
                initialStatus = ProviderStatus.NEEDS_CONFIG,
                description = "Z.AI provider with dedicated configuration and model refresh."
            )
        )
    }

    /**
     * Create a provider configuration card
     */
    private fun createProviderCard(
        providerName: String,
        fields: List<ProviderField>,
        initialStatus: ProviderStatus,
        description: String? = null
    ): JPanel {
        val fieldComponents = mutableMapOf<String, JComponent>()

        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                LCATheme.customLineBorder(LCATheme.borderColor, 1),
                LCATheme.paddedBorder(LCATheme.spacingLg)
            )

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = LCATheme.insetsSmall
            }

            // Header with status
            val statusLabel = JLabel(initialStatus.displayText).apply {
                foreground = initialStatus.color
            }

            val headerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(JLabel(providerName).apply {
                    font = font.deriveFont(Font.BOLD)
                }, BorderLayout.WEST)
                add(statusLabel, BorderLayout.EAST)
            }
            add(headerPanel, gbc)

            // Optional description
            if (description != null) {
                gbc.gridy++
                gbc.insets = LCATheme.insetsFormField
                add(JLabel("<html><font color='gray'>$description</font></html>"), gbc)
            }

            // Fields
            fields.forEach { field ->
                gbc.gridy++
                gbc.insets = LCATheme.insetsGridBagDefault
                add(JLabel("${field.label}:"), gbc)

                gbc.gridy++
                gbc.insets = LCATheme.insetsSmall

                val component: JComponent = when (field.type) {
                    FieldType.TEXT -> {
                        val textField = JBTextField(field.defaultValue ?: "")
                        // Auto-save on change
                        textField.document.addDocumentListener(object : DocumentListener {
                            override fun insertUpdate(e: DocumentEvent?) =
                                onFieldChanged(providerName, field.key, getFieldValue(textField))

                            override fun removeUpdate(e: DocumentEvent?) =
                                onFieldChanged(providerName, field.key, getFieldValue(textField))

                            override fun changedUpdate(e: DocumentEvent?) =
                                onFieldChanged(providerName, field.key, getFieldValue(textField))
                        })
                        textField
                    }

                    FieldType.PASSWORD -> {
                        val passwordField = JBPasswordField().apply {
                            text = field.defaultValue ?: ""
                        }
                        // Auto-save on change
                        passwordField.document.addDocumentListener(object : DocumentListener {
                            override fun insertUpdate(e: DocumentEvent?) =
                                onFieldChanged(providerName, field.key, getFieldValue(passwordField))

                            override fun removeUpdate(e: DocumentEvent?) =
                                onFieldChanged(providerName, field.key, getFieldValue(passwordField))

                            override fun changedUpdate(e: DocumentEvent?) =
                                onFieldChanged(providerName, field.key, getFieldValue(passwordField))
                        })
                        passwordField
                    }

                    FieldType.DROPDOWN -> JComboBox(field.dropdownOptions?.toTypedArray() ?: arrayOf()).apply {
                        selectedItem = field.defaultValue ?: field.dropdownOptions?.firstOrNull()
                        // Auto-save on selection change
                        addActionListener {
                            onFieldChanged(providerName, field.key, selectedItem as? String ?: "")
                        }
                    }
                }

                fieldComponents[field.key] = component
                add(component, gbc)
            }

            // Test Connection button
            gbc.gridy++
            gbc.anchor = GridBagConstraints.EAST
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0

            val testButton = JButton("Test Connection").apply {
                addActionListener { onTestConnection(providerName) }
            }
            add(testButton, gbc)

            // Save card state
            providerStates[providerName] = ProviderCardState(
                providerName = providerName,
                fields = fieldComponents,
                statusLabel = statusLabel,
                testButton = testButton
            )
        }
    }

    /**
     * Handle field value change with debounce
     */
    private fun onFieldChanged(providerName: String, fieldKey: String, value: String) {
        // Use lowercase provider name for config keys to match ConfigService expectations
        val jobKey = "${providerName.lowercase()}.$fieldKey"
        saveJobs[jobKey]?.cancel()

        saveJobs[jobKey] = coroutineScope.launch {
            delay(saveDebounceMs)

            logger.debug { "Auto-saving: $jobKey = [REDACTED]" }

            // Save to database
            ApplicationManager.getApplication().invokeLater {
                onSettingChanged("providers", jobKey, value)
            }

            // Re-sync API keys to System.properties (ensures keys work without restart)
            try {
                pl.jclab.refio.services.core.CoreConnectionManager.getInstance().resyncProviderKeys()
                logger.info { "✓ API keys re-synchronized after saving $jobKey" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to re-sync API keys" }
            }

            // Auto-refresh Ollama models when context size changes
            if (providerName.equals("Ollama", ignoreCase = true) && fieldKey == "ollama_context_size") {
                logger.info { "Ollama context size changed to $value - refreshing models..." }
                withContext(Dispatchers.IO) {
                    try {
                        refreshModelsList(providerName)
                        logger.info { "✓ Ollama models refreshed with new context size" }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to refresh Ollama models after context size change" }
                    }
                }
            }

            // Auto-refresh LMStudio models when context size changes
            if (providerName.equals("LMStudio", ignoreCase = true) && fieldKey == "lmstudio_context_size") {
                logger.info { "LM Studio context size changed to $value - refreshing models..." }
                withContext(Dispatchers.IO) {
                    try {
                        refreshModelsList(providerName)
                        logger.info { "✓ LM Studio models refreshed with new context size" }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to refresh LM Studio models after context size change" }
                    }
                }
            }
        }
    }

    /**
     * Test connection to provider
     */
    private fun onTestConnection(providerName: String) {
        val state = providerStates[providerName] ?: return

        // Disable button and update status
        state.testButton.isEnabled = false
        updateProviderStatus(providerName, ProviderStatus.TESTING)

        coroutineScope.launch {
            try {
                logger.info { "Testing connection to $providerName" }

                // Build provider config from fields
                val config = buildProviderConfig(providerName, state.fields)

                // Call backend API
                val result = coreApiClient?.testProviderConnection(
                    provider = providerName.lowercase(),
                    config = config
                ) ?: throw Exception("CoreApiClient not available")

                ApplicationManager.getApplication().invokeLater {
                    if (result.success) {
                        updateProviderStatus(providerName, ProviderStatus.CONFIGURED)
                        showConnectionSuccess(providerName, result)

                        // Re-sync API keys after successful test (ensures keys are available immediately)
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                pl.jclab.refio.services.core.CoreConnectionManager.getInstance()
                                    .resyncProviderKeys()
                                logger.info { "✓ API keys re-synchronized after successful connection test" }
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to re-sync API keys after test" }
                            }
                        }

                        // Auto-refresh models list
                        refreshModelsList(providerName)
                    } else {
                        updateProviderStatus(providerName, ProviderStatus.ERROR)
                        showConnectionError(providerName, result.message)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Connection test failed for $providerName" }
                ApplicationManager.getApplication().invokeLater {
                    updateProviderStatus(providerName, ProviderStatus.ERROR)
                    showConnectionError(providerName, e.message ?: "Unknown error")
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    state.testButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Get field value (handles JBPasswordField and JComboBox correctly)
     */
    private fun getFieldValue(field: JComponent?): String {
        return when (field) {
            is JBPasswordField -> String(field.password)
            is JComboBox<*> -> field.selectedItem as? String ?: ""
            is JTextField -> field.text ?: ""
            else -> ""
        }
    }

    /**
     * Set field value (handles JBPasswordField and JComboBox correctly)
     */
    private fun setFieldValue(field: JComponent?, value: String) {
        when (field) {
            is JBPasswordField -> field.text = value
            is JComboBox<*> -> field.selectedItem = value
            is JTextField -> field.text = value
        }
    }

    /**
     * Build provider configuration from fields
     */
    private fun buildProviderConfig(providerName: String, fields: Map<String, JComponent>): Map<String, String> {
        return when (providerName.lowercase()) {
            "ollama" -> mapOf(
                "base_url" to getFieldValue(fields["ollama_endpoint"]).ifEmpty { "http://localhost:11434" },
                "context_size" to getFieldValue(fields["ollama_context_size"]).ifEmpty { DEFAULT_CONTEXT_SIZE.toString() },
                "keep_alive" to getFieldValue(fields["ollama_keep_alive"]).ifEmpty { "1800" }
            )

            "anthropic" -> mapOf(
                "api_key" to getFieldValue(fields["anthropic_api_key"])
            )

            "openai" -> mapOf(
                "api_key" to getFieldValue(fields["openai_api_key"])
            )

            "openrouter" -> mapOf(
                "api_key" to getFieldValue(fields["openrouter_api_key"])
            )

            "gemini" -> mapOf(
                "api_key" to getFieldValue(fields["gemini_api_key"])
            )

            "lmstudio" -> mapOf(
                "api_key" to "",
                "base_url" to getFieldValue(fields["lmstudio_base_url"]).ifEmpty { "http://localhost:1234/v1" },
                "context_size" to getFieldValue(fields["lmstudio_context_size"]).ifEmpty { DEFAULT_CONTEXT_SIZE.toString() }
            )

            "zai" -> mapOf(
                "api_key" to getFieldValue(fields["zai_api_key"]),
                "base_url" to getFieldValue(fields["zai_base_url"]).ifEmpty { "https://api.z.ai/v1" }
            )

            else -> emptyMap()
        }
    }

    /**
     * Update provider status indicator
     */
    private fun updateProviderStatus(providerName: String, status: ProviderStatus) {
        val state = providerStates[providerName] ?: return
        state.statusLabel.text = status.displayText
        state.statusLabel.foreground = status.color
    }

    /**
     * Show connection success dialog
     */
    private fun showConnectionSuccess(
        providerName: String,
        result: pl.jclab.refio.core.api.TestConnectionResult
    ) {
        val modelsCount = result.details?.get("models_available")?.let {
            if (it is List<*>) it.size else 0
        } ?: 0

        JOptionPane.showMessageDialog(
            this,
            "Connection to $providerName successful!\n" +
                    "Latency: ${result.latencyMs}ms\n" +
                    "Available models: $modelsCount",
            "Connection Test",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    /**
     * Show connection error dialog
     */
    private fun showConnectionError(providerName: String, errorMessage: String) {
        JOptionPane.showMessageDialog(
            this,
            "Failed to connect to $providerName\n\n" +
                    "Error: $errorMessage",
            "Connection Error",
            JOptionPane.ERROR_MESSAGE
        )
    }

    /**
     * Refresh models list for provider
     */
    private fun refreshModelsList(providerName: String) {
        coroutineScope.launch {
            try {
                logger.info { "Refreshing models list for $providerName" }

                val models = coreApiClient?.refreshProviderModels(
                    provider = providerName.lowercase()
                ) ?: emptyList()

                logger.info { "Fetched ${models.size} models from $providerName" }

                ApplicationManager.getApplication().invokeLater {
                    notifyModelsRefreshed(providerName, models)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to refresh models for $providerName" }
            }
        }
    }

    /**
     * Notify models refreshed callback
     */
    private fun notifyModelsRefreshed(
        providerName: String,
        models: List<pl.jclab.refio.core.api.ModelInfo>
    ) {
        onModelsRefreshed?.invoke(providerName, models)
    }

    /**
     * Set callback for models refresh
     */
    fun setOnModelsRefreshedCallback(
        callback: (provider: String, models: List<pl.jclab.refio.core.api.ModelInfo>) -> Unit
    ) {
        onModelsRefreshed = callback
    }

    /**
     * Load providers configuration from backend
     */
    private fun loadProvidersConfig() {
        if (coreApiClient == null) {
            logger.warn { "CoreApiClient not available, using defaults" }
            return
        }

        coroutineScope.launch {
            try {
                logger.info { "Loading providers configuration" }

                val config = coreApiClient.getConfig(section = "providers", scope = "app")

                ApplicationManager.getApplication().invokeLater {
                    applyProvidersConfig(config.settings)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load providers config" }
            }
        }
    }

    /**
     * Apply loaded configuration to UI
     */
    private fun applyProvidersConfig(settings: Map<String, Any>) {
        logger.info { "Applying providers config: ${settings.keys}" }

        providerStates.forEach { (providerName, state) ->
            state.fields.forEach { (fieldKey, textField) ->
                // Use lowercase provider name to match saved keys
                val configKey = "${providerName.lowercase()}.$fieldKey"
                val value = settings[configKey] as? String

                logger.debug { "Looking for key: $configKey, found: ${value != null}" }

                if (value != null && value.isNotEmpty()) {
                    logger.info { "Setting $configKey to [REDACTED] (length=${value.length})" }
                    setFieldValue(textField, value)
                    updateProviderStatus(providerName, ProviderStatus.CONFIGURED)
                } else {
                    logger.debug { "No value for $configKey" }
                }
            }
        }
    }

    /**
     * Reload settings from backend (called on reset)
     */
    fun reload() {
        logger.info { "Reloading providers configuration" }

        // Clear all fields
        providerStates.forEach { (providerName, state) ->
            state.fields.values.forEach { field ->
                setFieldValue(field, "")
            }
            updateProviderStatus(providerName, ProviderStatus.NEEDS_CONFIG)
        }

        // Reload from backend
        loadProvidersConfig()
    }
}

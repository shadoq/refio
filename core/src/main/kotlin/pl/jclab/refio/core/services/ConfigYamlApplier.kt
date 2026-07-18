package pl.jclab.refio.core.services

import com.google.gson.Gson
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.ConfigYaml
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.logging.dualLogger
private val logger = dualLogger("ConfigYamlApplier")
private val gson = Gson()

/**
 * Materializes a parsed [ConfigYaml] into the APP-scoped config store.
 *
 * Extracted from [ConfigService] so the two-mode apply logic (overwrite on
 * explicit reload vs. load-if-missing on startup) lives in one focused place
 * rather than inside the larger service class.
 */
class ConfigYamlApplier(
    private val configRepository: ConfigRepository,
    private val setter: (key: String, value: String) -> Unit,
    private val modelsVisibilityGetter: () -> Map<String, Boolean>,
    private val defaultModelSetter: (op: ModelOperation, modelId: String, provider: String) -> Unit,
    private val modelStringParser: (String) -> Pair<String, String>,
) {
    /**
     * Apply YAML configuration to APP scope.
     *
     * @param yamlConfig Parsed YAML structure.
     * @param overwrite When true, every present key is written (counting updates).
     *                  When false, keys already in the DB are skipped ("load-if-missing").
     * @return Number of keys written.
     */
    fun apply(yamlConfig: ConfigYaml, overwrite: Boolean): Int {
        val verb = if (overwrite) "Reloaded" else "Loaded"
        var count = 0

        fun applyKey(key: String, value: String?, label: String) {
            if (value == null) return
            if (!overwrite && configRepository.get(key, ConfigScope.APP) != null) return
            setter(key, value)
            count++
            logger.info { "$verb $label from YAML: $value" }
        }

        fun applyDefaultModel(key: String, op: ModelOperation, model: String?, label: String) {
            if (model == null) return
            if (!overwrite && configRepository.get(key, ConfigScope.APP) != null) return
            val (provider, modelId) = modelStringParser(model)
            try {
                defaultModelSetter(op, modelId, provider)
                count++
                logger.info { "$verb default $label model from YAML: $modelId" }
            } catch (e: Exception) {
                logger.warn { "Failed to set $label model from YAML: ${e.message}" }
            }
        }

        yamlConfig.models?.defaults?.let { d ->
            applyDefaultModel(ConfigKeys.DEFAULT_MODEL_CHAT.key, ModelOperation.DEFAULT, d.chat, "chat")
            applyDefaultModel(ConfigKeys.DEFAULT_MODEL_PLAN.key, ModelOperation.PLAN, d.plan, "plan")
            applyDefaultModel(ConfigKeys.DEFAULT_MODEL_AGENT.key, ModelOperation.CODING, d.coding, "coding")
        }

        yamlConfig.models?.visibility?.let { visibility ->
            val finalMap = if (overwrite) {
                visibility
            } else {
                val existing = modelsVisibilityGetter().toMutableMap()
                val additions = visibility.filterKeys { !existing.containsKey(it) }
                if (additions.isEmpty()) return@let
                additions.forEach { (id, show) -> logger.info { "Loaded model visibility from YAML: $id -> $show" } }
                existing.apply { putAll(additions) }
            }
            configRepository.set(
                key = ConfigKeys.MODELS_VISIBILITY.key,
                value = gson.toJson(finalMap),
                scope = ConfigScope.APP,
                taskId = null,
                description = "Model visibility settings",
            )
            if (overwrite) {
                count++
                logger.info { "Reloaded model visibility from YAML: ${visibility.size} entries" }
            }
        }

        yamlConfig.general?.let { g ->
            applyKey(ConfigKeys.FORMAT_MARKDOWN.key, g.formatMarkdown?.toString(), "format markdown")
            applyKey(ConfigKeys.STREAMING_ENABLED.key, g.streamingEnabled?.toString(), "streaming enabled")
            applyKey(ConfigKeys.ADVANCED_VIEW.key, g.advancedView?.toString(), "advanced view")
            // Prefer the new enum; fall back to the legacy boolean for old config.yaml files.
            val reasoningEffort = g.reasoningEffort
                ?: g.thinkingEnabled?.let { if (it) "MEDIUM" else "OFF" }
            applyKey(ConfigKeys.GENERAL_REASONING_EFFORT.key, reasoningEffort, "reasoning effort")
            applyKey(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key, g.noEgressEnabled?.toString(), "no egress enabled")
            applyKey(ConfigKeys.GENERAL_EXECUTION_MODE.key, g.executionMode, "execution mode")
            applyKey(ConfigKeys.NATIVE_TOOLS_MODE.key, g.nativeToolsMode, "native tools mode")
        }

        yamlConfig.providers?.let { p ->
            applyKey(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT.key, p.ollama?.endpoint, "Ollama endpoint")
            applyKey(ConfigKeys.PROVIDER_ANTHROPIC_API_KEY.key, p.anthropic?.apiKey, "Anthropic API key")
            applyKey(ConfigKeys.PROVIDER_OPENAI_API_KEY.key, p.openai?.apiKey, "OpenAI API key")
            applyKey(ConfigKeys.PROVIDER_OPENROUTER_API_KEY.key, p.openrouter?.apiKey, "OpenRouter API key")
            applyKey(ConfigKeys.PROVIDER_GEMINI_API_KEY.key, p.gemini?.apiKey, "Gemini API key")
            applyKey(ConfigKeys.PROVIDER_LM_STUDIO_API_KEY.key, p.lmstudio?.apiKey, "LM Studio API key")
            applyKey(ConfigKeys.PROVIDER_LM_STUDIO_BASE_URL.key, p.lmstudio?.baseUrl, "LM Studio base URL")
            applyKey(ConfigKeys.PROVIDER_CUSTOM_OPENAI_API_KEY.key, p.genericOpenai?.apiKey, "Custom OpenAI API key")
            applyKey(ConfigKeys.PROVIDER_CUSTOM_OPENAI_BASE_URL.key, p.genericOpenai?.baseUrl, "Custom OpenAI base URL")
            applyKey(ConfigKeys.PROVIDER_CUSTOM_OPENAI_MODEL.key, p.genericOpenai?.model, "Custom OpenAI model")
            applyKey(ConfigKeys.PROVIDER_ZAI_API_KEY.key, p.zai?.apiKey, "Z.AI API key")
            applyKey(ConfigKeys.PROVIDER_ZAI_BASE_URL.key, p.zai?.baseUrl, "Z.AI base URL")
        }

        yamlConfig.limits?.let { l ->
            applyKey(ConfigKeys.API_CALL_TIMEOUT.key, l.apiCallTimeout?.toString(), "API call timeout")
            applyKey(ConfigKeys.TOOL_EXECUTION_TIMEOUT.key, l.toolExecutionTimeout?.toString(), "tool execution timeout")
            applyKey(ConfigKeys.MAX_CONTEXT_SIZE.key, l.maxContextSize?.toString(), "max context size")
            applyKey(ConfigKeys.MAX_OUTPUT_SIZE.key, l.maxOutputSize?.toString(), "max output size")
            applyKey(ConfigKeys.MAX_FILE_SIZE.key, l.maxFileSize?.toString(), "max file size")
        }

        return count
    }
}

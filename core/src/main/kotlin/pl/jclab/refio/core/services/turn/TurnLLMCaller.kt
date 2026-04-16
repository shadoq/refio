package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("TurnLLMCaller")

/**
 * Handles LLM invocation for turn execution.
 *
 * Responsibilities:
 * - Model selection (override, config, or default)
 * - LLM call execution with retry support
 * - Response format handling
 */
class TurnLLMCaller(
    private val llmClient: LLMClient,
    private val configService: ConfigService
) {
    /**
     * Call LLM with prompt.
     *
     * @param taskId Task ID for logging and tracking
     * @param mode Task mode (affects response format)
     * @param prompt Prompt with system message and conversation history
     * @param streamCallback Optional callback for streaming
     * @param model Optional model ID override
     * @param provider Optional provider override
     * @param profileOverrides Subagent profile overrides
     * @return LLM response with content, usage, and cost
     */
    suspend fun callLLM(
        taskId: String,
        mode: TaskMode,
        prompt: TurnPrompt,
        streamCallback: StreamCallback? = null,
        model: String? = null,
        provider: String? = null,
        profileOverrides: TurnProfileOverrides? = null
    ): LLMResponse {
        val (modelId, providerName) = resolveModelSelection(
            mode = mode,
            taskId = taskId,
            model = model,
            provider = provider,
            profileOverrides = profileOverrides
        )

        logger.info { "[CALL_LLM] Final model selection: $providerName/$modelId" }

        val responseFormat = resolveResponseFormat(mode, providerName)
        val thinkingEnabled = configService.getTyped(ConfigKeys.UI_THINKING_ENABLED, taskId)
        val noEgressEnabled = configService.getTyped(ConfigKeys.UI_NO_EGRESS_ENABLED, taskId)
        val reasoningEffortOverride = profileOverrides?.reasoningEffort
        if (reasoningEffortOverride != null) {
            logger.info {
                "[CALL_LLM] Subagent reasoning_effort override: $reasoningEffortOverride " +
                    "(subagent=${profileOverrides.subagentName ?: "?"})"
            }
        }

        return withContext(Dispatchers.IO) {
            llmClient.complete(
                provider = providerName,
                model = modelId,
                messages = prompt.messages,
                systemPrompt = prompt.systemPrompt,
                taskId = taskId,
                source = "AgentTurnLoop",
                responseFormat = responseFormat,
                thinking = thinkingEnabled,
                reasoningEffort = reasoningEffortOverride,
                noEgressEnabled = noEgressEnabled,
                stream = streamCallback != null,
                onChunk = streamCallback
            )
        }
    }

    /**
     * Resolve model and provider selection based on overrides, request params, or config.
     *
     * Priority:
     * 1. Profile overrides (for subagents)
     * 2. Request parameters (from UI)
     * 3. Config-based (per operation type)
     */
    fun resolveModelSelection(
        mode: TaskMode,
        taskId: String,
        model: String?,
        provider: String?,
        profileOverrides: TurnProfileOverrides?
    ): ModelSelection {
        if (profileOverrides?.modelOverride != null && profileOverrides.providerOverride != null) {
            logger.info {
                "[MODEL_RESOLVE] Using profile override: ${profileOverrides.providerOverride}/${profileOverrides.modelOverride}"
            }
            return ModelSelection(profileOverrides.modelOverride, profileOverrides.providerOverride)
        }

        if (model != null && provider != null) {
            logger.info { "[MODEL_RESOLVE] Using request override: $provider/$model" }
            return ModelSelection(model, provider)
        }

        // If model is set but provider is missing, infer provider from model name
        if (model != null && provider == null) {
            val inferredProvider = llmClient.inferProvider(model)
            logger.info { "[MODEL_RESOLVE] Using request model with inferred provider: $inferredProvider/$model" }
            return ModelSelection(model, inferredProvider)
        }

        val operation = when (mode) {
            TaskMode.CHAT -> ModelOperation.DEFAULT
            TaskMode.PLAN -> ModelOperation.PLAN
            TaskMode.AGENT -> ModelOperation.CODING
        }
        val configModel = configService.getModel(operation, taskId)
        logger.info { "[MODEL_RESOLVE] Using config model: ${configModel.second}/${configModel.first}" }
        return ModelSelection(configModel.first, configModel.second)
    }

    fun resolveResponseFormat(mode: TaskMode, provider: String?): Map<String, String>? {
        val isLocalProvider = provider.equals("ollama", ignoreCase = true) ||
            provider.equals("lmstudio", ignoreCase = true)

        return when {
            mode == TaskMode.CHAT -> null
            isLocalProvider -> null
            else -> mapOf("type" to "json_object")
        }
    }

    /**
     * Resolve max iterations from config or override.
     */
    fun resolveMaxIterations(
        config: TurnLoopConfig,
        profileOverrides: TurnProfileOverrides?
    ): Int {
        val override = profileOverrides?.maxIterationsOverride
        return if (override != null && override > 0) override else config.maxIterations
    }
}

/**
 * Selected model and provider.
 */
data class ModelSelection(
    val model: String,
    val provider: String
)

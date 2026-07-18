package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.ModelDefinitions
import pl.jclab.refio.core.llm.ReasoningEffort
import pl.jclab.refio.core.llm.SupportedModels
import pl.jclab.refio.core.llm.calculateCost
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.core.utils.GsonInstance.gson

/**
 * OpenRouter — unified OpenAI-compatible gateway to many providers.
 *
 * Differences from a stock OpenAI-compatible adapter:
 * - Adds `HTTP-Referer` and `X-Title` headers to every request.
 * - Supports `provider`, `route`, and `thinking` (Claude-only) kwargs.
 * - Detects mid-stream `{"error":{...}}` envelopes and status-200 errors.
 * - Rich `/models` payload with per-model pricing + architecture (vision detection).
 *
 * API docs: https://openrouter.ai/docs
 */
open class OpenRouterAdapter(
    model: String = "anthropic/claude-3.5-sonnet",
    configService: ConfigService? = null,
    taskId: String? = null,
    subtaskId: String? = null,
    source: String? = null,
    private val appName: String = "Refio",
    private val siteUrl: String = "https://github.com/shadoq/refio",
    httpClientOverride: HttpClient? = null,
) : OpenAICompatibleAdapter(
    model = model,
    providerName = "openrouter",
    configService = configService,
    taskId = taskId,
    subtaskId = subtaskId,
    source = source,
    requireApiKey = true,
    httpClientOverride = httpClientOverride,
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    }

    override fun resolveBaseUrl(): String = DEFAULT_BASE_URL

    override fun resolveApiKey(): String? = configService?.get(
        key = ConfigKeys.PROVIDER_OPENROUTER_API_KEY.key,
        scope = ConfigScope.APP,
    )
        ?: System.getProperty("OPENROUTER_API_KEY")
        ?: System.getenv("OPENROUTER_API_KEY")

    override fun extraRequestHeaders(): Map<String, String> = mapOf(
        "HTTP-Referer" to siteUrl,
        "X-Title" to appName,
    )

    override fun buildRequestBody(
        requestMessages: List<Map<String, Any>>,
        effectiveMaxTokens: Int,
        temperature: Double,
        streaming: Boolean,
        kwargs: Map<String, Any>,
        requestId: String,
    ): Map<String, Any> = super.buildRequestBody(
        requestMessages, effectiveMaxTokens, temperature, streaming, kwargs, requestId,
    ).toMutableMap().apply {
        // `thinking` arrives as Boolean true (toggle on, unspecified magnitude) or a non-blank
        // effort String ("low"/"medium"/"high"); absent/false/blank means thinking OFF.
        val thinkingRaw = kwargs["thinking"]
        val thinkingOn = thinkingRaw == true || (thinkingRaw is String && thinkingRaw.isNotBlank())
        val explicitEffort = ReasoningEffort.fromEffortString(thinkingRaw as? String)
        val isClaude = model.contains("claude", ignoreCase = true)
        when {
            thinkingOn && isClaude -> {
                // Claude on OpenRouter uses Anthropic-style extended thinking; scale the budget.
                val budget = when (explicitEffort) {
                    ReasoningEffort.HIGH -> 20000
                    ReasoningEffort.LOW -> 2048
                    else -> 10000
                }
                put("thinking", mapOf("type" to "enabled", "budget_tokens" to budget))
                logger.info { "[${providerTag}] Enabled thinking for $model (budget=$budget)" }
            }
            explicitEffort != null -> {
                // Non-Claude with an explicit level: OpenRouter's unified reasoning effort.
                put("reasoning", mapOf("effort" to explicitEffort.toEffortString()))
                logger.info { "[${providerTag}] Set reasoning effort=${explicitEffort.toEffortString()} for $model" }
            }
            thinkingOn -> {
                // Bare on without a level: let the provider reason at its own default.
            }
            !reasoningIsMandatory(model) -> {
                // Honour "thinking OFF": suppress upstream reasoning. Without this the toggle was
                // a silent no-op for OpenRouter - reasoning models (e.g. minimax-m3) defaulted to
                // reasoning ON and burned thousands of hidden completion tokens. OpenRouter's
                // unified `reasoning.enabled=false` suppresses it where the model allows.
                //
                // Some endpoints (e.g. moonshotai/kimi-k3) reject reasoning.enabled=false with a
                // hard error instead of ignoring it, so we skip suppression for those - see
                // ModelDefinition.reasoningMandatory.
                put("reasoning", mapOf("enabled" to false))
                logger.info { "[${providerTag}] Suppressing reasoning for $model (thinking OFF)" }
            }
        }
        (kwargs["provider"] as? Map<*, *>)?.let { put("provider", it) }
        (kwargs["route"] as? String)?.let { put("route", it) }
    }

    /**
     * Whether the given OpenRouter model mandates reasoning and rejects an explicit
     * `reasoning.enabled=false`. Resolved from the static registry (prefix match).
     */
    private fun reasoningIsMandatory(modelId: String): Boolean =
        ModelDefinitions.getDefinition("openrouter", modelId)?.reasoningMandatory == true

    /**
     * OpenRouter returns HTTP 200 with `{"error": {...}}` for upstream provider errors.
     * We detect and rethrow so the envelope doesn't leak through to `choices` parsing.
     */
    override fun ensureSuccess(httpStatus: Int, rawResponse: Map<String, Any?>, endpoint: String) {
        super.ensureSuccess(httpStatus, rawResponse, endpoint)
        @Suppress("UNCHECKED_CAST")
        val error = rawResponse["error"] as? Map<String, Any?> ?: return
        val message = error["message"] as? String ?: "Unknown error"
        val code = (error["code"] as? Number)?.toInt() ?: 500
        @Suppress("UNCHECKED_CAST")
        val metadata = error["metadata"] as? Map<String, Any?>
        val providerFromMeta = metadata?.get("provider_name") as? String ?: "OpenRouter"
        throw mapHttpError(code, "$providerFromMeta: $message")
    }

    /**
     * Detect OpenRouter's mid-stream `{"error":{...}}` envelope — throwing from here
     * aborts the SSE loop (see `consumeChatCompletionsSSE`).
     */
    override fun onStreamRawChunk(chunk: com.google.gson.JsonObject) {
        val error = chunk.get("error") as? com.google.gson.JsonObject ?: return
        val message = error.stringField("message") ?: "Unknown error"
        val code = error.intField("code") ?: 500
        val metadata = error.get("metadata") as? com.google.gson.JsonObject
        val providerFromMeta = metadata.stringField("provider_name") ?: "OpenRouter"
        throw IllegalStateException("$providerFromMeta error (HTTP $code): $message")
    }

    /**
     * Per-model pricing and vision capability parsing specific to OpenRouter's
     * `/models` payload structure.
     */
    override fun parseModelsPayload(rawBody: String): List<ModelConfig> {
        val parsed = gson.fromJson(rawBody, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val modelsData = parsed?.get("data") as? List<Map<String, Any?>> ?: emptyList()

        if (modelsData.isEmpty()) {
            logger.warn { "[${providerTag}] API returned empty model list" }
            return emptyList()
        }

        return modelsData.mapNotNull { modelData ->
            val modelId = modelData["id"] as? String ?: return@mapNotNull null
            if (!SupportedModels.isSupported("openrouter", modelId)) return@mapNotNull null

            val modelName = modelData["name"] as? String ?: modelId
            val contextLength = (modelData["context_length"] as? Number)?.toInt() ?: DEFAULT_CONTEXT_SIZE

            @Suppress("UNCHECKED_CAST")
            val pricingData = modelData["pricing"] as? Map<String, Any?>
            val promptPricePerToken = (pricingData?.get("prompt") as? String)?.toDoubleOrNull() ?: 0.0
            val completionPricePerToken = (pricingData?.get("completion") as? String)?.toDoubleOrNull() ?: 0.0
            val costPer1mInput = promptPricePerToken * 1_000_000
            val costPer1mOutput = completionPricePerToken * 1_000_000

            @Suppress("UNCHECKED_CAST")
            val architecture = modelData["architecture"] as? Map<String, Any?>
            val modality = architecture?.get("modality") as? String
            val capabilities = mutableListOf("chat", "streaming").apply {
                if (modality == "text+image" || modality?.contains("image") == true) add("vision")
            }

            ModelConfig(
                id = modelId,
                name = modelName,
                provider = "openrouter",
                capabilities = capabilities,
                maxContext = contextLength,
                costPer1mInput = costPer1mInput,
                costPer1mOutput = costPer1mOutput,
            )
        }
    }

    /**
     * Missing API key yields an empty list (caller-facing behavior preserved from the
     * pre-migration adapter so the settings UI can still list supported models lazily).
     */
    override suspend fun listModels(): List<ModelConfig> {
        if (resolveApiKey().isNullOrBlank()) return emptyList()
        return try {
            super.listModels()
        } catch (e: RefioError.ProviderNotConfigured) {
            emptyList()
        }
    }

    override fun estimateCost(usage: LLMUsage): Double = calculateCost(
        provider = provider,
        model = model,
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
    )
}

package pl.jclab.refio.core.llm.adapters

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.toModelConfig
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.errors.LLMErrorMapper
import pl.jclab.refio.core.security.SecureLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import java.util.UUID

/**
 * Adapter for LM Studio (local) using OpenAI-compatible API.
 */
class LMStudioAdapter(
    model: String = "local",
    private val baseUrl: String? = null,
    private val configService: ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null,
    httpClientOverride: HttpClient? = null
) : BaseLLMAdapter(model, "lmstudio") {

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:1234/v1"
        private const val CHAT_ENDPOINT = "/chat/completions"
        private const val MODELS_ENDPOINT = "/models"
    }

    private val logger = dualLogger("LMStudioAdapter")

    private val timeout: Long
        get() = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L

    private val client: HttpClient = httpClientOverride
        ?: LLMKtorClientFactory.create(timeout, logger)

    private fun resolveBaseUrl(): String {
        return baseUrl
            ?: configService?.get(ConfigService.KEY_PROVIDER_LM_STUDIO_BASE_URL, ConfigScope.APP)
            ?: System.getProperty("LM_STUDIO_BASE_URL")
            ?: System.getenv("LM_STUDIO_BASE_URL")
            ?: DEFAULT_BASE_URL
    }

    private fun resolveApiKey(): String? {
        return configService?.get(ConfigService.KEY_PROVIDER_LM_STUDIO_API_KEY, ConfigScope.APP)
            ?: System.getProperty("LM_STUDIO_API_KEY")
            ?: System.getenv("LM_STUDIO_API_KEY")
    }

    override suspend fun chat(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        streaming: Boolean,
        onStreamChunk: ((StreamChunk) -> Unit)?,
        kwargs: Map<String, Any>
    ): LLMResponse {
        logger.info { "[LMStudio] Sending ${if (streaming) "streaming" else "standard"} chat request: model=$model, messages=${messages.size}, systemMessages=${systemMessages.size}" }

        val resolvedBaseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()

        val lmMessages = OpenAICompatibleHelpers.buildMessages(this, systemMessages, messages)

        val effectiveMaxTokens = OpenAICompatibleHelpers.resolveEffectiveMaxTokens(
            requested = maxTokens,
            configLimit = configService?.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, taskId) ?: ConfigKeys.MAX_OUTPUT_SIZE.default,
            modelLimit = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("lmstudio", model)?.maxOutputTokens,
            providerTag = "LMStudio",
            model = model,
            log = { logger.warn(it) }
        )

        val requestBody = buildMap<String, Any> {
            put("model", model)
            put("messages", lmMessages)
            put("temperature", temperature)
            put("max_tokens", effectiveMaxTokens)
            if (streaming) put("stream", true)

            // LM Studio uses OpenAI-compatible API which doesn't support thinking parameter
            val thinking = kwargs["thinking"] as? Boolean ?: false
            if (thinking) {
                logger.info { "[LMStudio] Thinking mode requested but not supported by OpenAI-compatible API - parameter ignored" }
            }
            with(OpenAICompatibleHelpers) { addCommonKwargs(kwargs) }
        }

        val requestJson = gson.toJson(requestBody)
        val requestId = UUID.randomUUID().toString()
        val logPrefix = "[LMStudio][$requestId]"
        logger.debug { "$logPrefix Request: ${SecureLogger.redactAndTruncate(requestJson)}" }

        val startTime = System.currentTimeMillis()

        return try {
            if (streaming && onStreamChunk != null) {
                executeStreaming(resolvedBaseUrl, apiKey, requestBody, requestJson, startTime, onStreamChunk, logPrefix)
            } else {
                executeStandard(resolvedBaseUrl, apiKey, requestBody, requestJson, startTime, logPrefix)
            }
        } catch (e: CancellationException) {
            // Stream aborted by a guardrail (see core/llm/streaming/) — must propagate
            // so the caller can see StreamAbortedException instead of RefioError.LLMError.
            throw e
        } catch (e: Exception) {
            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e)
        }
    }

    private suspend fun executeStandard(
        baseUrl: String,
        apiKey: String?,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        logPrefix: String
    ): LLMResponse {
        var httpStatus: Int? = null

        try {
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            val response = client.post("$baseUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                apiKey?.let { header("Authorization", "Bearer $it") }
                setBody(requestBody)
            }

            httpStatus = response.status.value
            val rawResponse: Map<String, Any?> = response.body()

            if (httpStatus !in 200..299) {
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                val errorMessage = (rawResponse["error"] as? Map<*, *>)?.get("message") as? String
                    ?: "LM Studio API error (HTTP $httpStatus)"

                logger.apiError(
                    provider = provider,
                    model = model,
                    endpoint = "$baseUrl$CHAT_ENDPOINT",
                    requestJson = requestJson,
                    httpStatus = httpStatus,
                    error = Exception(errorMessage),
                    latencyMs = latencyMs,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    source = source
                )
                throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus, errorMessage)
            }

            @Suppress("UNCHECKED_CAST")
            val usageMap = rawResponse["usage"] as? Map<String, Any?> ?: emptyMap()
            val promptTokens = (usageMap["prompt_tokens"] as? Number)?.toInt() ?: 0
            val completionTokens = (usageMap["completion_tokens"] as? Number)?.toInt() ?: 0
            val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt() ?: promptTokens + completionTokens

            val usage = LLMUsage(
                inputTokens = promptTokens,
                outputTokens = completionTokens,
                totalTokens = totalTokens
            )

            @Suppress("UNCHECKED_CAST")
            val choices = rawResponse["choices"] as? List<Map<String, Any?>> ?: emptyList()
            val firstChoice = choices.firstOrNull() ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val message = firstChoice["message"] as? Map<String, Any?> ?: emptyMap()
            val content = message["content"] as? String ?: ""
            val normalizedToolCallsJson = if (content.isBlank()) {
                ToolCallContentNormalizer.fromOpenAiToolCalls(message["tool_calls"])
            } else {
                null
            }
            val finalContent = normalizedToolCallsJson ?: content
            if (normalizedToolCallsJson != null) {
                logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted LM Studio tool_calls to canonical JSON content" }
            }
            val finishReason = firstChoice["finish_reason"] as? String

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            val responseJson = gson.toJson(rawResponse)
            logger.info {
                "$logPrefix Response received: status=$httpStatus, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = 0.0,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            return LLMResponse(
                content = finalContent,
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,
                finishReason = finishReason,
                rawResponse = rawResponse
            )
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e)
        }
    }

    private suspend fun executeStreaming(
        baseUrl: String,
        apiKey: String?,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        logPrefix: String
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        val toolCallAccumulator = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator()
        var httpStatus: Int? = null
        var finalFinishReason: String? = null

        try {
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            client.preparePost("$baseUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                apiKey?.let { header("Authorization", "Bearer $it") }
                setBody(requestBody)
            }.execute { httpResponse ->
                httpStatus = httpResponse.status.value

                if (httpStatus !in 200..299) {
                    val errorBody = httpResponse.body<String>()
                    val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                    val errorMessage = "LM Studio API error (HTTP $httpStatus): $errorBody"

                    logger.apiError(
                        provider = provider,
                        model = model,
                        endpoint = "$baseUrl$CHAT_ENDPOINT",
                        requestJson = requestJson,
                        httpStatus = httpStatus,
                        error = Exception(errorMessage),
                        latencyMs = latencyMs,
                        taskId = taskId,
                        subtaskId = subtaskId,
                        source = source
                    )
                    throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus, errorMessage)
                }

                finalFinishReason = OpenAICompatibleHelpers.consumeChatCompletionsSSE(
                    channel = httpResponse.body(),
                    toolCallAccumulator = toolCallAccumulator,
                    checkCancelled = { pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled() },
                    onContent = { delta ->
                        contentBuilder.append(delta)
                        onStreamChunk(StreamChunk(delta = delta, finishReason = null))
                    }
                )
            }

            if (contentBuilder.isEmpty()) {
                val normalizedToolCallsJson = toolCallAccumulator.toCanonicalJson()
                if (normalizedToolCallsJson != null) {
                    contentBuilder.append(normalizedToolCallsJson)
                    logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted streamed LM Studio tool_calls to canonical JSON content" }
                }
            }

            @Suppress("UNCHECKED_CAST")
            val inputTokensEstimate = (requestBody["messages"] as? List<Map<String, Any?>>)
                ?.sumOf {
                    when (val content = it["content"]) {
                        is String -> content.length
                        is List<*> -> content.sumOf { part ->
                            @Suppress("UNCHECKED_CAST")
                            val partMap = part as? Map<String, Any?>
                            (partMap?.get("text") as? String)?.length ?: 0
                        }
                        else -> 0
                    }
                } ?: 0
            val outputTokensEstimate = contentBuilder.length / 4

            val usage = LLMUsage(
                inputTokens = inputTokensEstimate,
                outputTokens = outputTokensEstimate,
                totalTokens = inputTokensEstimate + outputTokensEstimate
            )

            onStreamChunk(StreamChunk(delta = "", finishReason = finalFinishReason, usage = usage))

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            val syntheticResponse = mapOf(
                "choices" to listOf(
                    mapOf(
                        "message" to mapOf("role" to "assistant", "content" to contentBuilder.toString()),
                        "finish_reason" to finalFinishReason
                    )
                ),
                "usage" to mapOf(
                    "prompt_tokens" to usage.inputTokens,
                    "completion_tokens" to usage.outputTokens,
                    "total_tokens" to usage.totalTokens
                ),
                "model" to model
            )
            val responseJson = gson.toJson(syntheticResponse)
            logger.info {
                "$logPrefix Response received: status=${httpStatus ?: 200}, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus ?: 200,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = 0.0,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,
                finishReason = finalFinishReason,
                rawResponse = syntheticResponse
            )
        } catch (e: CancellationException) {
            // Guardrail-triggered abort — log and rethrow as-is, do NOT wrap.
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw e
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e)
        }
    }

    /**
     * Lists models from LM Studio /v1/models endpoint.
     */
    suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        val resolvedBaseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()

        try {
            val response = client.get("$resolvedBaseUrl$MODELS_ENDPOINT") {
                apiKey?.let { header("Authorization", "Bearer $it") }
            }

            val body: Map<String, Any?> = response.body()
            @Suppress("UNCHECKED_CAST")
            val modelsData = body["data"] as? List<Map<String, Any?>> ?: emptyList()

            val contextSize = configService?.getTyped(ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE) ?: ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE.default

            return@withContext modelsData.mapNotNull { modelData ->
                val modelId = modelData["id"] as? String ?: return@mapNotNull null

                if (!pl.jclab.refio.core.llm.SupportedModels.isSupported("lmstudio", modelId)) {
                    return@mapNotNull null
                }

                // Get context length from model data or use configured context size
                val modelContextLength = (modelData["context_length"] as? Number)?.toInt() ?: contextSize

                // Get definition from registry or synthesize for unknown models.
                val baseDefinition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("lmstudio", modelId)
                    ?: run {
                        logger.warn {
                            "[LMSTUDIO] Model $modelId not in registry — using synthetic definition (context=$modelContextLength)"
                        }
                        pl.jclab.refio.core.llm.ModelDefinitions.syntheticDefinitionFor(
                            provider = "lmstudio",
                            modelId = modelId,
                            maxContext = modelContextLength
                        )
                    }

                // Always override maxContext with configured/model-reported value for LM Studio models
                val definition = baseDefinition.copy(maxContext = modelContextLength)

                definition.toModelConfig()
            }
        } catch (e: Exception) {
            logger.error(e) { "[LMStudio] Failed to fetch models: ${e.message}" }
            throw LLMErrorMapper.listModelsFailure(provider, e)
        }
    }

    override fun estimateCost(usage: LLMUsage): Double = 0.0

    override suspend fun close() {
        client.close()
    }
}

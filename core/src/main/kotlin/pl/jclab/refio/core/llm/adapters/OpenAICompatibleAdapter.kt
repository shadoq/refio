package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.ModelDefinitions
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.toModelConfig
import pl.jclab.refio.core.logging.DualLogger
import pl.jclab.refio.core.tools.base.ToolSchema
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.security.SecureLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.core.utils.GsonInstance.gson
import java.util.UUID

/**
 * Base adapter for OpenAI-compatible providers (Generic OpenAI, Z.AI, LM Studio).
 *
 * Encapsulates the shared protocol: `/v1/chat/completions` with `choices[].message.content`
 * responses and standard SSE streaming. Subclasses override only what truly differs
 * (endpoint URLs, API key resolution, error mapping, per-provider quirks).
 *
 * Not suitable for the OpenAI Responses API (use [OpenAIAdapter]) or providers with
 * structurally different streams (OpenRouter's mid-stream error envelopes).
 */
abstract class OpenAICompatibleAdapter(
    model: String,
    protected val providerName: String,
    protected val configService: ConfigService? = null,
    protected val taskId: String? = null,
    protected val subtaskId: String? = null,
    protected val source: String? = null,
    protected val requireApiKey: Boolean = false,
    httpClientOverride: HttpClient? = null,
    protected val logger: DualLogger = dualLogger("${providerName}Adapter"),
) : BaseLLMAdapter(model, providerName) {

    companion object {
        const val CHAT_ENDPOINT = "/chat/completions"
        const val MODELS_ENDPOINT = "/models"
    }

    protected val providerTag: String get() = providerName.uppercase()

    protected val client: HttpClient = httpClientOverride ?: run {
        val timeoutMs = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L
        LLMKtorClientFactory.create(timeoutMs, logger)
    }

    /** Endpoint suffix appended to [resolveBaseUrl]. Defaults to `/chat/completions`. */
    protected open val chatEndpointPath: String = CHAT_ENDPOINT

    /** Endpoint suffix for listing models. Defaults to `/models`. */
    protected open val modelsEndpointPath: String = MODELS_ENDPOINT

    /** Provider-specific base URL. Must be implemented by subclass. */
    protected abstract fun resolveBaseUrl(): String

    /** Provider-specific API key. Returns null for providers that don't require auth. */
    protected abstract fun resolveApiKey(): String?

    /** Provider-specific log message when `thinking` is requested but unsupported. */
    protected open fun logUnsupportedThinking(logPrefix: String) {
        // Default: silent. Subclasses may override to emit a hint.
    }

    /** Pre-request hook (rate limit mutex, etc.). Default: run block as-is. */
    protected open suspend fun <T> withProviderRateLimit(endpoint: String, block: suspend () -> T): T = block()

    /** Extra headers appended to every chat and models request. Default: none. */
    protected open fun extraRequestHeaders(): Map<String, String> = emptyMap()

    /**
     * Inspect each raw SSE chunk before normal delta processing. Throwing
     * [IllegalStateException] from here propagates up as a stream error
     * (used by OpenRouter to surface mid-stream provider errors).
     */
    protected open fun onStreamRawChunk(chunk: Map<String, Any?>) = Unit

    /** Rate-limit retry hook (Z.AI). Default: no retry. */
    protected open suspend fun <T> executeWithRateLimitRetry(endpoint: String, block: suspend () -> T): T = block()

    /**
     * Build the JSON request body. Subclasses can override to add custom keys or
     * strip parameters that their provider rejects (e.g. `response_format`).
     */
    protected open fun buildRequestBody(
        requestMessages: List<Map<String, Any>>,
        effectiveMaxTokens: Int,
        temperature: Double,
        streaming: Boolean,
        kwargs: Map<String, Any>,
        requestId: String,
    ): Map<String, Any> = buildMap {
        put("request_id", requestId)
        put("model", model)
        put("messages", requestMessages)
        put("temperature", temperature)
        put("max_tokens", effectiveMaxTokens)
        if (streaming) {
            put("stream", true)
            // Ask the server to emit a final usage chunk so we can report real token
            // counts (incl. reasoning_tokens for OpenRouter's o1/o3/DeepSeek-R1 etc.)
            // instead of estimating from streamed content.
            put("stream_options", mapOf("include_usage" to true))
        }
        with(OpenAICompatibleHelpers) { addCommonKwargs(kwargs) }
        kwargs["response_format"]?.let { put("response_format", it) }

        @Suppress("UNCHECKED_CAST")
        (kwargs["native_tools"] as? List<ToolSchema>)?.takeIf { it.isNotEmpty() }?.let { tools ->
            put("tools", OpenAICompatibleHelpers.buildOpenAIToolsArray(tools))
            put("tool_choice", "auto")
            logger.info {
                "[$providerTag][NATIVE_TOOLS] Sending ${tools.size} tool schemas: " +
                    tools.joinToString(", ") { it.name }
            }
        }
    }

    /**
     * Map a non-2xx HTTP response to a [RefioError]. Default mapping:
     * 401/403 → Authentication, 429 → RateLimit, else → LLMError.
     * Subclasses can override to parse business codes and produce richer messages.
     */
    protected open fun mapHttpError(httpStatus: Int, rawBody: String): RefioError {
        val parsed = parseProviderError(rawBody)
        val message = parsed.message ?: rawBody
        return when (httpStatus) {
            401, 403 -> RefioError.LLMAuthentication(providerName, model, IllegalStateException(message))
            429 -> RefioError.LLMRateLimit(providerName, null, IllegalStateException(message))
            else -> RefioError.LLMError(providerName, model, IllegalStateException(message))
        }
    }

    /**
     * Ensure a successful response. Default: delegate to [mapHttpError] on non-2xx.
     */
    protected open fun ensureSuccess(httpStatus: Int, rawResponse: Map<String, Any?>, endpoint: String) {
        if (httpStatus in 200..299) return

        val message = (rawResponse["error"] as? Map<*, *>)?.get("message") as? String
            ?: "OpenAI-compatible API error (HTTP $httpStatus) at $endpoint"
        throw mapHttpError(httpStatus, gson.toJson(rawResponse).ifBlank { message })
    }

    /**
     * Extract usage from the standard OpenAI-compatible `usage` map.
     */
    protected fun extractUsage(rawResponse: Map<String, Any?>): LLMUsage {
        @Suppress("UNCHECKED_CAST")
        val usageMap = rawResponse["usage"] as? Map<String, Any?> ?: emptyMap()
        val promptTokens = (usageMap["prompt_tokens"] as? Number)?.toInt() ?: 0
        val completionTokens = (usageMap["completion_tokens"] as? Number)?.toInt() ?: 0
        val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt() ?: (promptTokens + completionTokens)
        return LLMUsage(promptTokens, completionTokens, totalTokens)
    }

    /**
     * Parse a chat-completions provider error body into a structured payload.
     */
    data class ProviderErrorPayload(
        val code: String? = null,
        val message: String? = null,
    )

    fun parseProviderError(rawBody: String): ProviderErrorPayload {
        return runCatching {
            val parsed = gson.fromJson(rawBody, Map::class.java)
            val error = parsed?.get("error") as? Map<*, *>
            ProviderErrorPayload(
                code = error?.get("code")?.toString(),
                message = error?.get("message")?.toString(),
            )
        }.getOrDefault(ProviderErrorPayload(message = rawBody))
    }

    final override suspend fun chat(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        streaming: Boolean,
        onStreamChunk: ((StreamChunk) -> Unit)?,
        kwargs: Map<String, Any>
    ): LLMResponse {
        val baseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()
        if (requireApiKey && apiKey.isNullOrBlank()) {
            throw RefioError.ProviderNotConfigured(providerName, "api_key")
        }
        val requestMessages = OpenAICompatibleHelpers.buildMessages(this, systemMessages, messages)
        val configLimit = configService?.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, taskId)
            ?: ConfigKeys.MAX_OUTPUT_SIZE.default
        val effectiveMaxTokens = OpenAICompatibleHelpers.resolveEffectiveMaxTokens(
            requested = maxTokens,
            configLimit = configLimit,
            modelLimit = ModelDefinitions.getDefinition(providerName, model)?.maxOutputTokens,
            providerTag = providerTag,
            model = model,
            log = { logger.warn(it) }
        )
        val requestId = UUID.randomUUID().toString()
        val logPrefix = "[$providerTag][$requestId]"

        if (kwargs["thinking"] as? Boolean == true) {
            logUnsupportedThinking(logPrefix)
        }

        val requestBody = buildRequestBody(
            requestMessages = requestMessages,
            effectiveMaxTokens = effectiveMaxTokens,
            temperature = temperature,
            streaming = streaming,
            kwargs = kwargs,
            requestId = requestId,
        )
        val requestJson = gson.toJson(requestBody)
        val startTime = System.currentTimeMillis()

        return try {
            if (streaming && onStreamChunk != null) {
                executeStreaming(baseUrl, apiKey, requestBody, requestJson, startTime, onStreamChunk, logPrefix)
            } else {
                executeStandard(baseUrl, apiKey, requestBody, requestJson, startTime, logPrefix)
            }
        } catch (e: HttpRequestTimeoutException) {
            val timeoutMs = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L) ?: 0L
            throw RefioError.LLMTimeout(providerName, model, timeoutMs, e)
        }
    }

    protected open suspend fun executeStandard(
        baseUrl: String,
        apiKey: String?,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        logPrefix: String,
    ): LLMResponse {
        var httpStatus: Int? = null
        val endpoint = "$baseUrl$chatEndpointPath"

        try {
            logger.info { "$logPrefix Request start: endpoint=$endpoint, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            val response = executeWithRateLimitRetry(endpoint) {
                withProviderRateLimit(endpoint) {
                    client.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                        extraRequestHeaders().forEach { (k, v) -> header(k, v) }
                        setBody(requestBody)
                    }
                }
            }

            httpStatus = response.status.value
            val rawResponse: Map<String, Any?> = response.body()
            ensureSuccess(httpStatus, rawResponse, endpoint)

            val usage = extractUsage(rawResponse)
            if (rawResponse["choices"] !is List<*>) {
                throw RefioError.MalformedResponse(
                    provider = provider,
                    model = model,
                    reason = "Missing or non-list 'choices' in chat completions response",
                    bodyPreview = gson.toJson(rawResponse)
                )
            }
            @Suppress("UNCHECKED_CAST")
            val choices = rawResponse["choices"] as? List<Map<String, Any?>> ?: emptyList()
            val firstChoice = choices.firstOrNull() ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val message = firstChoice["message"] as? Map<String, Any?> ?: emptyMap()
            val rawContent = message["content"] as? String ?: ""
            // Some reasoning models (e.g. GLM via Z.AI, DeepSeek) put the whole answer in
            // `reasoning_content` and leave `content` empty. Recover it as the response text
            // when there is no content and no tool_calls, so the turn isn't fed a blank reply.
            val content = if (rawContent.isNotBlank() || message["tool_calls"] != null) {
                rawContent
            } else {
                (message["reasoning_content"] as? String)?.takeIf { it.isNotBlank() }
                    ?.also { logger.info { "$logPrefix [REASONING_FALLBACK] content empty, recovered ${it.length} chars from reasoning_content" } }
                    ?: ""
            }
            val normalizedToolCallsJson = if (content.isBlank()) {
                ToolCallContentNormalizer.fromOpenAiToolCalls(message["tool_calls"])
            } else {
                null
            }
            val toolsWereRequested = requestBody.containsKey("tools")
            // When tools were requested, always return a list (possibly empty) so AgentTurnLoop
            // can distinguish "native tools wired up, model produced 0 calls = final prose answer"
            // from "native tools were never requested" (returns null).
            val nativeToolCalls = if (toolsWereRequested) {
                val calls = OpenAICompatibleHelpers.parseOpenAIToolCalls(message["tool_calls"])
                logger.info { "$logPrefix [NATIVE_TOOLS_RESPONSE] calls=${calls.size}" }
                calls
            } else {
                null
            }

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = endpoint,
                requestJson = requestJson,
                responseJson = gson.toJson(rawResponse),
                httpStatus = httpStatus,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = estimateCost(usage),
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )

            return LLMResponse(
                content = if (nativeToolCalls != null) content else (normalizedToolCallsJson ?: content),
                usage = usage,
                model = model,
                provider = provider,
                cost = estimateCost(usage),
                finishReason = firstChoice["finish_reason"] as? String,
                rawResponse = rawResponse,
                nativeToolCalls = nativeToolCalls,
            )
        } catch (e: RefioError) {
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = endpoint,
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = endpoint,
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )
            throw RefioError.LLMError(providerName, model, e)
        }
    }

    protected open suspend fun executeStreaming(
        baseUrl: String,
        apiKey: String?,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        logPrefix: String,
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        val toolCallAccumulator = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator()
        var httpStatus: Int? = null
        var finalFinishReason: String? = null
        // Real usage from final SSE chunk (when stream_options.include_usage=true).
        // Includes reasoning_tokens for reasoning models proxied by OpenRouter etc.
        var streamUsage: LLMUsage? = null
        val endpoint = "$baseUrl$chatEndpointPath"

        try {
            logger.info { "$logPrefix Request start: endpoint=$endpoint, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            executeWithRateLimitRetry(endpoint) {
                withProviderRateLimit(endpoint) {
                    client.preparePost(endpoint) {
                        contentType(ContentType.Application.Json)
                        apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                        extraRequestHeaders().forEach { (k, v) -> header(k, v) }
                        setBody(requestBody)
                    }.execute { httpResponse ->
                        httpStatus = httpResponse.status.value
                        if (httpStatus !in 200..299) {
                            val errorBody = httpResponse.body<String>()
                            throw mapHttpError(httpStatus!!, errorBody)
                        }

                        finalFinishReason = OpenAICompatibleHelpers.consumeChatCompletionsSSE(
                            channel = httpResponse.body(),
                            toolCallAccumulator = toolCallAccumulator,
                            onContent = { delta ->
                                contentBuilder.append(delta)
                                onStreamChunk(StreamChunk(delta = delta))
                            },
                            onToolCallDelta = { tcDelta ->
                                onStreamChunk(StreamChunk(delta = "", toolCallDelta = tcDelta))
                            },
                            onRawChunk = { chunk ->
                                @Suppress("UNCHECKED_CAST")
                                (chunk["usage"] as? Map<String, Any?>)?.let { usageMap ->
                                    val promptTokens = (usageMap["prompt_tokens"] as? Number)?.toInt() ?: 0
                                    val completionTokens = (usageMap["completion_tokens"] as? Number)?.toInt() ?: 0
                                    val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt()
                                        ?: (promptTokens + completionTokens)
                                    streamUsage = LLMUsage(
                                        inputTokens = promptTokens,
                                        outputTokens = completionTokens,
                                        totalTokens = totalTokens,
                                    )
                                    val details = usageMap["completion_tokens_details"] as? Map<*, *>
                                    val reasoning = (details?.get("reasoning_tokens") as? Number)?.toInt()
                                    logger.info {
                                        "$logPrefix [STREAM_USAGE] prompt=$promptTokens, completion=$completionTokens" +
                                            (reasoning?.let { ", reasoning=$it (incl. in completion)" } ?: "")
                                    }
                                }
                                onStreamRawChunk(chunk)
                            },
                        )
                    }
                }
            }

            val toolsWereRequested = requestBody.containsKey("tools")
            // See comment in non-streaming branch — preserve empty-list semantics.
            val streamNativeToolCalls = if (toolsWereRequested) {
                val calls = toolCallAccumulator.toNativeToolCalls(toolsWereRequested) ?: emptyList()
                logger.info { "$logPrefix [NATIVE_TOOLS_RESPONSE] (stream) calls=${calls.size}" }
                calls
            } else {
                null
            }
            // Fallback only fires when native tools were never requested (null) — a model that
            // emits OpenAI-format tool_calls anyway gets dumped into content as JSON envelope.
            if (contentBuilder.isEmpty() && streamNativeToolCalls == null) {
                toolCallAccumulator.toCanonicalJson()?.let { contentBuilder.append(it) }
            }

            // Prefer real usage from the stream's final chunk; fall back to estimation.
            val usage = streamUsage ?: run {
                @Suppress("UNCHECKED_CAST")
                val inputChars = (requestBody["messages"] as? List<Map<String, Any?>>)?.sumOf {
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
                // Provider omitted usage on the stream — estimate via the shared chars→tokens
                // converter (docs/0057 §6) so input and output agree on one ratio instead of the
                // old split (input = raw chars, output = chars/4).
                val inputTokensEstimate = pl.jclab.refio.core.services.PromptTokenEstimator.estimateTokensForChars(inputChars)
                val outputTokensEstimate = pl.jclab.refio.core.services.PromptTokenEstimator.estimateBase(contentBuilder.toString())
                LLMUsage(
                    inputTokens = inputTokensEstimate,
                    outputTokens = outputTokensEstimate,
                    totalTokens = inputTokensEstimate + outputTokensEstimate,
                )
            }
            onStreamChunk(StreamChunk(delta = "", finishReason = finalFinishReason, usage = usage))

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = endpoint,
                requestJson = requestJson,
                responseJson = gson.toJson(mapOf("content" to contentBuilder.toString())),
                httpStatus = httpStatus ?: 200,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = estimateCost(usage),
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = estimateCost(usage),
                finishReason = finalFinishReason,
                rawResponse = mapOf("content" to contentBuilder.toString()),
                nativeToolCalls = streamNativeToolCalls,
            )
        } catch (e: RefioError) {
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = endpoint,
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )
            throw e
        } catch (e: CancellationException) {
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = endpoint,
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )
            throw e
        } catch (e: Exception) {
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = endpoint,
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )
            throw RefioError.LLMError(providerName, model, e)
        }
    }

    open suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()
        val endpoint = "$baseUrl$modelsEndpointPath"
        val startTime = System.currentTimeMillis()

        try {
            logger.info { "[$providerTag] Request start: endpoint=$endpoint" }
            val response = withProviderRateLimit(endpoint) {
                client.get(endpoint) {
                    apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    extraRequestHeaders().forEach { (k, v) -> header(k, v) }
                }
            }
            val rawBody = response.body<String>()
            if (response.status.value !in 200..299) {
                throw mapHttpError(response.status.value, rawBody)
            }
            parseModelsPayload(rawBody)
        } catch (e: RefioError) {
            logger.apiError(
                provider = provider,
                model = "models",
                endpoint = endpoint,
                requestJson = "",
                httpStatus = null,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )
            throw e
        } catch (e: Exception) {
            logger.apiError(
                provider = provider,
                model = "models",
                endpoint = endpoint,
                requestJson = "",
                httpStatus = null,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
            )
            throw RefioError.LLMError(providerName, model, e)
        }
    }

    open fun parseModelsPayload(rawBody: String): List<ModelConfig> {
        val parsed = gson.fromJson(rawBody, Any::class.java)
        val modelsData: List<*> = when (parsed) {
            is Map<*, *> -> parsed["data"] as? List<*> ?: emptyList<Any?>()
            is List<*> -> parsed
            else -> emptyList<Any?>()
        }

        return modelsData.mapNotNull { item ->
            val modelData = item as? Map<*, *> ?: return@mapNotNull null
            val modelId = modelData["id"] as? String ?: return@mapNotNull null
            val contextLength = (modelData["context_length"] as? Number)?.toInt() ?: DEFAULT_CONTEXT_SIZE
            val definition = ModelDefinitions.getDefinition(providerName, modelId)
                ?: run {
                    logger.warn {
                        "[$providerName] Model $modelId not in registry — using synthetic definition (context=$contextLength)"
                    }
                    ModelDefinitions.syntheticDefinitionFor(providerName, modelId, contextLength)
                }
            definition.toModelConfig()
        }
    }

    // Cost from the central pricing table (provider/model based). Returning 0.0 here used to
    // leave the API Logs rows at $0.00 for Z.AI/OpenRouter/etc even though the turn trace
    // (computed separately in LLMClient) showed the real cost - the two sources disagreed.
    override fun estimateCost(usage: LLMUsage): Double =
        pl.jclab.refio.core.llm.calculateCost(provider, model, usage.inputTokens, usage.outputTokens)

    override suspend fun close() {
        client.close()
    }
}

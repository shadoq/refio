package pl.jclab.refio.core.llm.adapters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.core.utils.GsonInstance.gson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.errors.LLMErrorMapper
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.security.SecureLogger
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID

/**
 * Adapter for OpenRouter - unified API for multiple LLM providers.
 *
 * OpenRouter provides access to models from OpenAI, Anthropic, Google, Meta, and others
 * through a single OpenAI-compatible API.
 *
 * API Documentation: https://openrouter.ai/docs
 */
class OpenRouterAdapter(
    model: String = "anthropic/claude-3.5-sonnet",
    private val configService: pl.jclab.refio.core.services.ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null,
    private val appName: String = "Refio",
    private val siteUrl: String = "https://github.com/shadoq/refio"
) : BaseLLMAdapter(model, "openrouter") {

    private val logger = dualLogger("OpenRouterAdapter")

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val CHAT_ENDPOINT = "/chat/completions"
        const val MODELS_ENDPOINT = "/models"
    }

    private val timeoutMs: Long
        get() = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
                serializeNulls()
            }
        }
        install(Logging) {
            level = LogLevel.INFO
            logger = object : KtorLogger {
                override fun log(message: String) {
                    this@OpenRouterAdapter.logger.debug { message }
                }
            }
            sanitizeHeader { header ->
                header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                    header.equals("x-api-key", ignoreCase = true) ||
                    header.equals("x-goog-api-key", ignoreCase = true)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 30000
            socketTimeoutMillis = timeoutMs
        }
    }

    override suspend fun chat(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        streaming: Boolean,
        onStreamChunk: ((pl.jclab.refio.core.llm.StreamChunk) -> Unit)?,
        kwargs: Map<String, Any>
    ): LLMResponse {
        logger.info { "[OPENROUTER] Sending ${if (streaming) "streaming" else "standard"} chat request: model=$model, messages=${messages.size}, systemMessages=${systemMessages.size}" }

        try {
            if (streaming && onStreamChunk != null) {
                return chatStreamingInternal(messages, systemMessages, maxTokens, temperature, kwargs, onStreamChunk)
            }

            return chatStandard(messages, systemMessages, maxTokens, temperature, kwargs)
        } catch (e: Exception) {
            throw LLMErrorMapper.fromThrowable(provider, model, timeoutMs, e)
        }
    }

    private suspend fun chatStandard(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        kwargs: Map<String, Any>
    ): LLMResponse {
        logger.info { "[OPENROUTER] Executing standard chat" }

        // Get API key from ConfigService (single source of truth)
        val apiKeyToUse = configService?.get(
            key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_OPENROUTER_API_KEY,
            scope = pl.jclab.refio.core.db.ConfigScope.APP
        )
            ?: System.getProperty("OPENROUTER_API_KEY")
            ?: System.getenv("OPENROUTER_API_KEY")
            ?: throw LLMErrorMapper.missingConfig(provider, "api_key")

        // Prepare messages
        val openrouterMessages = mutableListOf<Map<String, String>>()

        // Add system messages from systemMessages parameter
        systemMessages.filter { it.isNotBlank() }.forEach { sysMsg ->
            openrouterMessages.add(mapOf("role" to "system", "content" to sysMsg))
        }

        // Add conversation messages (filter out any system messages as they should be in systemMessages parameter)
        for (msg in messages.filter { it.role != "system" }) {
            openrouterMessages.add(mapOf("role" to msg.role, "content" to msg.content))
        }

        // Build request body
        val requestBody = buildMap {
            put("model", model)
            put("messages", openrouterMessages)
            put("temperature", temperature)

            if (maxTokens != null && maxTokens > 0) {
                put("max_tokens", maxTokens)
            }

            // Handle response_format for JSON mode
            val responseFormat = kwargs["response_format"] as? Map<*, *>
            if (responseFormat != null && responseFormat["type"] == "json_object") {
                put("response_format", mapOf("type" to "json_object"))
            }

            // Enable thinking mode for Anthropic models via OpenRouter
            val thinking = kwargs["thinking"] as? Boolean ?: false
            if (thinking && model.contains("claude", ignoreCase = true)) {
                put("thinking", mapOf(
                    "type" to "enabled",
                    "budget_tokens" to 10000
                ))
                logger.info { "[OPENROUTER] Enabled thinking mode for $model" }
            }

            // Additional parameters
            (kwargs["top_p"] as? Number)?.let { put("top_p", it) }
            (kwargs["frequency_penalty"] as? Number)?.let { put("frequency_penalty", it) }
            (kwargs["presence_penalty"] as? Number)?.let { put("presence_penalty", it) }
            kwargs["stop"]?.let { put("stop", it) }

            // OpenRouter-specific parameters
            (kwargs["provider"] as? Map<*, *>)?.let { put("provider", it) }
            (kwargs["route"] as? String)?.let { put("route", it) }
        }

        val requestJson = gson.toJson(requestBody)
        val requestId = UUID.randomUUID().toString()
        val logPrefix = "[OPENROUTER][$requestId]"
        logger.debug { "$logPrefix Request: ${SecureLogger.redactAndTruncate(requestJson)}" }

        val startTime = System.currentTimeMillis()
        var httpStatus: Int? = null

        try {
            // Make HTTP request
            logger.info { "$logPrefix Request start: endpoint=$DEFAULT_BASE_URL$CHAT_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            val httpResponse = client.post("$DEFAULT_BASE_URL$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKeyToUse")
                // OpenRouter-specific headers (optional but recommended)
                header("HTTP-Referer", siteUrl)
                header("X-Title", appName)
                setBody(requestBody)
            }

            httpStatus = httpResponse.status.value
            val response: Map<String, Any?> = httpResponse.body()

            val responseJson = gson.toJson(response)
            logger.debug { "$logPrefix Response: ${SecureLogger.redactAndTruncate(responseJson)}" }
            logger.info {
                "$logPrefix Response received: status=$httpStatus, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }


            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            @Suppress("UNCHECKED_CAST")
            val usageMap = response["usage"] as? Map<String, Any?> ?: emptyMap()
            val promptTokens = (usageMap["prompt_tokens"] as? Number)?.toInt() ?: 0
            val completionTokens = (usageMap["completion_tokens"] as? Number)?.toInt() ?: 0
            val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt() ?: 0

            val usage = LLMUsage(
                inputTokens = promptTokens,
                outputTokens = completionTokens,
                totalTokens = totalTokens
            )

            val cost = estimateCost(usage)
            val responseModel = response["model"] as? String ?: model

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$DEFAULT_BASE_URL$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = cost,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            // Check for error in response
            @Suppress("UNCHECKED_CAST")
            val errorObj = response["error"] as? Map<String, Any?>
            if (errorObj != null) {
                val errorMessage = errorObj["message"] as? String ?: "Unknown error"
                val errorCode = (errorObj["code"] as? Number)?.toInt() ?: 500
                @Suppress("UNCHECKED_CAST")
                val metadata = errorObj["metadata"] as? Map<String, Any?>
                val providerName = metadata?.get("provider_name") as? String ?: "OpenRouter"
                logger.error { "$logPrefix API error from $providerName (code $errorCode): $errorMessage" }
                throw LLMErrorMapper.fromHttpStatus(provider, model, errorCode, "OpenRouter error from $providerName: $errorMessage")
            }

            // Parse response
            @Suppress("UNCHECKED_CAST")
            val choices = response["choices"] as? List<Map<String, Any?>> ?: emptyList()
            if (choices.isEmpty()) {
                throw RefioError.LLMError(provider, model, IllegalStateException("OpenRouter returned empty choices"))
            }

            val choice = choices[0]
            @Suppress("UNCHECKED_CAST")
            val message = choice["message"] as? Map<String, Any?> ?: emptyMap()
            val content = message["content"] as? String ?: ""
            val normalizedToolCallsJson = if (content.isBlank()) {
                ToolCallContentNormalizer.fromOpenAiToolCalls(message["tool_calls"])
            } else {
                null
            }
            val finalContent = normalizedToolCallsJson ?: content
            if (normalizedToolCallsJson != null) {
                logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted OpenRouter tool_calls to canonical JSON content" }
            }
            val finishReason = choice["finish_reason"] as? String

            logger.info { "$logPrefix Response processed: model=$responseModel, " +
                    "tokens_in=${usage.inputTokens}, tokens_out=${usage.outputTokens}, " +
                    "cost=$${"%.4f".format(cost)}, finish_reason=$finishReason" }


            return LLMResponse(
                content = finalContent,
                usage = usage,
                model = responseModel,
                provider = provider,
                cost = cost,
                finishReason = finishReason,
                rawResponse = response
            )

        } catch (e: Exception) {
            // Error #15: Log error (console + database)
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$DEFAULT_BASE_URL$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            throw LLMErrorMapper.fromThrowable(provider, model, timeoutMs, e)
        }
    }

    /**
     * Stream chat completion from OpenRouter API (US-027)
     *
     * OpenRouter uses OpenAI-compatible SSE format:
     * - Lines start with "data: "
     * - JSON contains: {"choices":[{"delta":{"content":"..."}}]}
     * - Last message is "data: [DONE]"
     * - Usage info NOT included in stream (estimated at end)
     */
    private suspend fun chatStreamingInternal(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        kwargs: Map<String, Any>,
        onStreamChunk: (pl.jclab.refio.core.llm.StreamChunk) -> Unit
    ): LLMResponse {
        logger.info { "[OPENROUTER] Executing streaming chat" }

        val contentBuilder = StringBuilder()
        val toolCallAccumulator = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator()
        var finalUsage: LLMUsage? = null
        var finalFinishReason: String? = null

        // Get API key from ConfigService
        val apiKeyToUse = configService?.get(
            key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_OPENROUTER_API_KEY,
            scope = pl.jclab.refio.core.db.ConfigScope.APP
        )
            ?: System.getProperty("OPENROUTER_API_KEY")
            ?: System.getenv("OPENROUTER_API_KEY")
            ?: throw LLMErrorMapper.missingConfig(provider, "api_key")

        // Prepare messages
        val openrouterMessages = mutableListOf<Map<String, String>>()

        // Add system messages from systemMessages parameter
        systemMessages.filter { it.isNotBlank() }.forEach { sysMsg ->
            openrouterMessages.add(mapOf("role" to "system", "content" to sysMsg))
        }

        // Add conversation messages (filter out any system messages as they should be in systemMessages parameter)
        for (msg in messages.filter { it.role != "system" }) {
            openrouterMessages.add(mapOf("role" to msg.role, "content" to msg.content))
        }

        // Build request body with stream: true
        val requestBody = buildMap {
            put("model", model)
            put("messages", openrouterMessages)
            put("stream", true)  // Enable streaming
            put("temperature", temperature)

            if (maxTokens != null && maxTokens > 0) {
                put("max_tokens", maxTokens)
            }

            // Handle response_format for JSON mode
            val responseFormat = kwargs["response_format"] as? Map<*, *>
            if (responseFormat != null && responseFormat["type"] == "json_object") {
                put("response_format", mapOf("type" to "json_object"))
            }

            // Enable thinking mode for Anthropic models via OpenRouter
            val thinking = kwargs["thinking"] as? Boolean ?: false
            if (thinking && model.contains("claude", ignoreCase = true)) {
                put("thinking", mapOf(
                    "type" to "enabled",
                    "budget_tokens" to 10000
                ))
                logger.info { "[OPENROUTER] Enabled thinking mode for $model" }
            }

            // Additional parameters
            (kwargs["top_p"] as? Number)?.let { put("top_p", it) }
            (kwargs["frequency_penalty"] as? Number)?.let { put("frequency_penalty", it) }
            (kwargs["presence_penalty"] as? Number)?.let { put("presence_penalty", it) }
            kwargs["stop"]?.let { put("stop", it) }

            // OpenRouter-specific parameters
            (kwargs["provider"] as? Map<*, *>)?.let { put("provider", it) }
            (kwargs["route"] as? String)?.let { put("route", it) }
        }

        val requestJson = gson.toJson(requestBody)
        val requestId = UUID.randomUUID().toString()
        val logPrefix = "[OPENROUTER][$requestId]"
        logger.debug { "$logPrefix Streaming request: ${SecureLogger.redactAndTruncate(requestJson)}" }

        val startTime = System.currentTimeMillis()
        var totalTokensEstimate = 0
        var httpStatus: Int? = null
        var lineCount = 0
        var dataLineCount = 0

        try {
            // Make streaming HTTP request
            logger.info { "$logPrefix Request start: endpoint=$DEFAULT_BASE_URL$CHAT_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            client.preparePost("$DEFAULT_BASE_URL$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKeyToUse")
                header("HTTP-Referer", siteUrl)
                header("X-Title", appName)
                setBody(requestBody)
            }.execute { httpResponse ->
                httpStatus = httpResponse.status.value
                val channel: io.ktor.utils.io.ByteReadChannel = httpResponse.body()

                // Read SSE stream line by line
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    lineCount++
                    if (line.isBlank()) continue

                    // Debug: log first few lines to see what format we're getting
                    if (lineCount <= 5) {
                        logger.info { "$logPrefix SSE line $lineCount: ${line.take(200)}" }
                    }

                    // SSE format: "data: {...}" or "data: [DONE]"
                    if (!line.startsWith("data: ")) {
                        // Some models might send content without "data: " prefix
                        if (line.startsWith("{") && (line.contains("choices") || line.contains("error"))) {
                            logger.debug { "[OPENROUTER] Found JSON without data: prefix, processing..." }
                            // Process as if it was a data line
                            val data = line.trim()
                            dataLineCount++
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val chunk = gson.fromJson(data, Map::class.java) as Map<String, Any?>

                                // Check for error first
                                @Suppress("UNCHECKED_CAST")
                                val errorObj = chunk["error"] as? Map<String, Any?>
                                if (errorObj != null) {
                                    val errorMessage = errorObj["message"] as? String ?: "Unknown error"
                                    @Suppress("UNCHECKED_CAST")
                                    val metadata = errorObj["metadata"] as? Map<String, Any?>
                                    val providerName = metadata?.get("provider_name") as? String ?: "provider"
                                    logger.error { "$logPrefix Error from $providerName: $errorMessage" }
                                    contentBuilder.append("\n\n**Error from $providerName:** $errorMessage")
                                    onStreamChunk(pl.jclab.refio.core.llm.StreamChunk(
                                        delta = "\n\n**Error from $providerName:** $errorMessage",
                                        finishReason = "error"
                                    ))
                                    finalFinishReason = "error"
                                    break
                                }

                                @Suppress("UNCHECKED_CAST")
                                val choices = chunk["choices"] as? List<Map<String, Any?>>
                                if (choices != null && choices.isNotEmpty()) {
                                    val choice = choices[0]
                                    @Suppress("UNCHECKED_CAST")
                                    val message = choice["message"] as? Map<String, Any?>
                                    toolCallAccumulator.consumeToolCalls(message?.get("tool_calls"))
                                    val content = message?.get("content") as? String
                                    if (content != null && content.isNotEmpty()) {
                                        logger.info { "$logPrefix Found non-streaming response with content length: ${content.length}" }
                                        contentBuilder.append(content)
                                        totalTokensEstimate = content.split(" ").size
                                        onStreamChunk(pl.jclab.refio.core.llm.StreamChunk(
                                            delta = content,
                                            finishReason = choice["finish_reason"] as? String
                                        ))
                                    }
                                    finalFinishReason = choice["finish_reason"] as? String
                                }
                            } catch (e: Exception) {
                                logger.warn { "[OPENROUTER] Failed to parse non-prefixed JSON: ${e.message}" }
                            }
                        }
                        continue
                    }
                    dataLineCount++

                    val data = line.removePrefix("data: ").trim()

                    // Check for stream end
                    if (data == "[DONE]") {
                        logger.info { "$logPrefix Stream complete" }
                        break
                    }

                    // Parse JSON chunk
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val chunk = gson.fromJson(data, Map::class.java) as Map<String, Any?>

                        // Debug: log raw chunk structure for troubleshooting
                        logger.debug { "[OPENROUTER] Raw chunk: $data" }

                        // Check for error in stream response (OpenRouter sends errors as JSON in SSE)
                        @Suppress("UNCHECKED_CAST")
                        val errorObj = chunk["error"] as? Map<String, Any?>
                        if (errorObj != null) {
                            val errorMessage = errorObj["message"] as? String ?: "Unknown error"
                            val errorCode = (errorObj["code"] as? Number)?.toInt() ?: 500
                            @Suppress("UNCHECKED_CAST")
                            val metadata = errorObj["metadata"] as? Map<String, Any?>
                            val providerName = metadata?.get("provider_name") as? String ?: "OpenRouter"

                            logger.error { "$logPrefix Error from $providerName (code $errorCode): $errorMessage" }

                            // Throw exception instead of returning error in content
                            // This prevents PlanningService from trying to parse error message as JSON
                            throw IllegalStateException("$providerName error (HTTP $errorCode): $errorMessage")
                        }

                        @Suppress("UNCHECKED_CAST")
                        val choices = chunk["choices"] as? List<Map<String, Any?>> ?: continue
                        if (choices.isEmpty()) continue

                        val choice = choices[0]
                        @Suppress("UNCHECKED_CAST")
                        val delta = choice["delta"] as? Map<String, Any?>
                        toolCallAccumulator.consumeDelta(delta)

                        // Try multiple content locations (different models use different formats)
                        val content = delta?.get("content") as? String
                            ?: (choice["text"] as? String)  // Some models use "text" directly
                            ?: (delta?.get("text") as? String)  // Or delta.text

                        val finishReason = choice["finish_reason"] as? String

                        // Debug: log parsed values
                        if (!content.isNullOrEmpty() || finishReason != null) {
                            logger.debug { "[OPENROUTER] Parsed: content=${content?.take(50)}, finishReason=$finishReason" }
                        }

                        // Emit content chunk
                        if (content != null && content.isNotEmpty()) {
                            contentBuilder.append(content)
                            totalTokensEstimate += content.split(" ").size // Rough estimate

                            onStreamChunk(pl.jclab.refio.core.llm.StreamChunk(
                                delta = content,
                                finishReason = null
                            ))
                        }

                        // Emit final chunk with finish_reason
                        if (finishReason != null) {
                            finalFinishReason = finishReason
                            // Estimate token usage (OpenRouter doesn't send usage in stream)
                            val systemTokens = systemMessages.sumOf { it.split(" ").size }
                            val inputTokensEstimate = messages.sumOf { it.content.split(" ").size } + systemTokens
                            val usage = LLMUsage(
                                inputTokens = inputTokensEstimate,
                                outputTokens = totalTokensEstimate,
                                totalTokens = inputTokensEstimate + totalTokensEstimate
                            )

                            finalUsage = usage
                            finalFinishReason = finishReason

                            onStreamChunk(pl.jclab.refio.core.llm.StreamChunk(
                                delta = "",
                                finishReason = finishReason,
                                usage = usage
                            ))
                        }
                    } catch (e: Exception) {
                        logger.warn { "[OPENROUTER] Failed to parse chunk: $data - ${e.message}" }
                        continue
                    }
                }
            }

            if (contentBuilder.isEmpty()) {
                val normalizedToolCallsJson = toolCallAccumulator.toCanonicalJson()
                if (normalizedToolCallsJson != null) {
                    contentBuilder.append(normalizedToolCallsJson)
                    logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted streamed OpenRouter tool_calls to canonical JSON content" }
                }
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            // Debug: log final content length
            logger.info { "$logPrefix Stream finished: totalLines=$lineCount, dataLines=$dataLineCount, " +
                    "contentLength=${contentBuilder.length}, estimatedOutputTokens=$totalTokensEstimate, " +
                    "finishReason=$finalFinishReason" }

            // If no content was received but stream completed, log warning
            if (contentBuilder.isEmpty()) {
                logger.warn { "$logPrefix Stream completed but no content received! Model: $model" }
            }

            // Estimate final usage for logging
            val systemTokens = systemMessages.sumOf { it.split(" ").size }
            val inputTokensEstimate = messages.sumOf { it.content.split(" ").size } + systemTokens
            val cost = estimateCost(LLMUsage(
                inputTokens = inputTokensEstimate,
                outputTokens = totalTokensEstimate,
                totalTokens = inputTokensEstimate + totalTokensEstimate
            ))

            // Create synthetic response JSON for logging
            val syntheticResponse = mapOf(
                "choices" to listOf(
                    mapOf(
                        "message" to mapOf("role" to "assistant", "content" to contentBuilder.toString()),
                        "finish_reason" to finalFinishReason
                    )
                ),
                "usage" to mapOf(
                    "prompt_tokens" to inputTokensEstimate,
                    "completion_tokens" to totalTokensEstimate,
                    "total_tokens" to (inputTokensEstimate + totalTokensEstimate)
                ),
                "model" to model
            )
            val responseJson = gson.toJson(syntheticResponse)
            logger.info {
                "$logPrefix Response received: status=${httpStatus ?: 200}, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            // Log successful stream completion to API logs
            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$DEFAULT_BASE_URL$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus ?: 200,
                inputTokens = inputTokensEstimate,
                outputTokens = totalTokensEstimate,
                costUsd = cost,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            logger.info { "$logPrefix Streaming completed in ${latencyMs}ms, logged to API logs" }

            // Return complete LLMResponse
            return LLMResponse(
                content = contentBuilder.toString(),
                usage = finalUsage ?: LLMUsage(0, 0, 0),
                model = model,
                provider = provider,
                cost = finalUsage?.let { estimateCost(it) } ?: 0.0,
                finishReason = finalFinishReason,
                rawResponse = null
            )

        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$DEFAULT_BASE_URL$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = null,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw e
        }
    }

    /**
     * Lists all available models from OpenRouter API.
     *
     * @return List of ModelConfig objects with model metadata and pricing
     * @throws IllegalStateException if API key is not provided or API returns empty response
     */
    suspend fun listModels(): List<ModelConfig> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        logger.info { "[OPENROUTER] Fetching available models from $DEFAULT_BASE_URL$MODELS_ENDPOINT" }

        try {
            // Get API key from ConfigService (single source of truth)
            val apiKeyToUse = configService?.get(
                key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_OPENROUTER_API_KEY,
                scope = pl.jclab.refio.core.db.ConfigScope.APP
            )
                ?: System.getProperty("OPENROUTER_API_KEY")
                ?: System.getenv("OPENROUTER_API_KEY")
                ?: null

            if (apiKeyToUse==null){
                return@withContext emptyList()
            }

            // Make HTTP request
            val httpResponse = client.get("$DEFAULT_BASE_URL$MODELS_ENDPOINT") {
                header("Authorization", "Bearer $apiKeyToUse")
                header("HTTP-Referer", siteUrl)
                header("X-Title", appName)
            }

            val response: Map<String, Any?> = httpResponse.body()

            // Parse response
            @Suppress("UNCHECKED_CAST")
            val modelsData = response["data"] as? List<Map<String, Any?>> ?: emptyList()

            if (modelsData.isEmpty()) {
                logger.warn { "[OPENROUTER] API returned empty model list" }
                return@withContext emptyList()
            }

            val modelConfigs = modelsData.mapNotNull { modelData ->
                try {
                    val modelId = modelData["id"] as? String
                    if (modelId == null) {
                        logger.debug { "[OPENROUTER] Skipping model without ID: $modelData" }
                        return@mapNotNull null
                    }

                    // Filter using whitelist - only supported models
                    if (!pl.jclab.refio.core.llm.SupportedModels.isSupported("openrouter", modelId)) {
                        return@mapNotNull null
                    }

                    val modelName = modelData["name"] as? String ?: modelId
                    val contextLength = (modelData["context_length"] as? Number)?.toInt() ?: DEFAULT_CONTEXT_SIZE

                    // Parse pricing (OpenRouter returns price per token, we need per 1M tokens)
                    @Suppress("UNCHECKED_CAST")
                    val pricingData = modelData["pricing"] as? Map<String, Any?>
                    val promptPricePerToken = (pricingData?.get("prompt") as? String)?.toDoubleOrNull() ?: 0.0
                    val completionPricePerToken = (pricingData?.get("completion") as? String)?.toDoubleOrNull() ?: 0.0

                    // Convert from per-token to per-1M-tokens
                    val costPer1mInput = promptPricePerToken * 1_000_000
                    val costPer1mOutput = completionPricePerToken * 1_000_000

                    // Parse architecture to determine capabilities
                    @Suppress("UNCHECKED_CAST")
                    val architecture = modelData["architecture"] as? Map<String, Any?>
                    val modality = architecture?.get("modality") as? String

                    logger.debug { "[OPENROUTER] Model $modelId has modality: $modality" }

                    // All OpenRouter models support chat
                    val capabilities = mutableListOf("chat", "streaming")

                    // Add vision if it's a multimodal model
                    if (modality == "text+image" || modality?.contains("image") == true) {
                        capabilities.add("vision")
                    }

                    logger.debug { "[OPENROUTER] Found model: $modelId ($modelName) - context=$contextLength, " +
                            "input=$${"%.4f".format(costPer1mInput)}/1M, output=$${"%.4f".format(costPer1mOutput)}/1M" }

                    ModelConfig(
                        id = modelId,
                        name = "$modelName (via OpenRouter)",
                        provider = "openrouter",
                        capabilities = capabilities,
                        maxContext = contextLength,
                        costPer1mInput = costPer1mInput,
                        costPer1mOutput = costPer1mOutput
                    )
                } catch (e: Exception) {
                    logger.warn { "[OPENROUTER] Failed to parse model: ${e.message}" }
                    null
                }
            }

            logger.info { "[OPENROUTER] Found ${modelConfigs.size} models" }
            return@withContext modelConfigs

        } catch (e: Exception) {
            logger.error(e) { "[OPENROUTER] Failed to fetch models: ${e.message}" }
            throw LLMErrorMapper.listModelsFailure(provider, e)
        }
    }

    override fun estimateCost(usage: LLMUsage): Double {
        return pl.jclab.refio.core.llm.calculateCost(
            provider = provider,
            model = model,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens
        )
    }

    override suspend fun close() {
        client.close()
    }
}

package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.toModelConfig
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
import pl.jclab.refio.core.security.SecureLogger
import pl.jclab.refio.services.logging.dualLogger
import java.util.UUID

/**
 * Adapter for Anthropic Claude models.
 *
 * Provides integration with Claude API (claude-3-opus, claude-3-sonnet, claude-3-haiku, claude-3.5-sonnet).
 * Uses Ktor HTTP client to call Anthropic REST API.
 */
class AnthropicAdapter(
    model: String = "claude-3-5-sonnet-20241022",
    private val configService: pl.jclab.refio.core.services.ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null
) : BaseLLMAdapter(model, "anthropic") {

    private val logger = dualLogger("AnthropicAdapter")

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val MESSAGES_ENDPOINT = "/v1/messages"
        const val MODELS_ENDPOINT = "/v1/models"
        const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"
    }

    // Get timeout from ConfigService
    private val timeout: Long
        get() = configService?.getApiCallTimeoutMs(taskId)
            ?: pl.jclab.refio.core.services.ConfigService.DEFAULT_API_CALL_TIMEOUT * 1000L

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
                    this@AnthropicAdapter.logger.debug { message }
                }
            }
            sanitizeHeader { header ->
                header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                    header.equals("x-api-key", ignoreCase = true) ||
                    header.equals("x-goog-api-key", ignoreCase = true)
            }
        }
        install(HttpTimeout) {
            // Get dynamic timeout from ConfigService
            val timeoutMs = configService?.getApiCallTimeoutMs(taskId)
                ?: pl.jclab.refio.core.services.ConfigService.DEFAULT_API_CALL_TIMEOUT * 1000L
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
        onStreamChunk: ((StreamChunk) -> Unit)?,
        kwargs: Map<String, Any>
    ): LLMResponse {
        logger.info { "[ANTHROPIC] Sending ${if (streaming) "streaming" else "standard"} chat request: model=$model, messages=${messages.size}, systemMessages=${systemMessages.size}" }

        return chatInternal(messages, systemMessages, maxTokens, temperature, streaming, onStreamChunk, kwargs)
    }

    private suspend fun chatInternal(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        streaming: Boolean,
        onStreamChunk: ((StreamChunk) -> Unit)?,
        kwargs: Map<String, Any>
    ): LLMResponse {
        // Get API key from ConfigService (single source of truth)
        val apiKeyToUse = configService?.get(
            key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_ANTHROPIC_API_KEY,
            scope = pl.jclab.refio.core.db.ConfigScope.APP
        )
            ?: System.getProperty("ANTHROPIC_API_KEY")
            ?: System.getenv("ANTHROPIC_API_KEY")
            ?: throw IllegalStateException("Anthropic API key not provided")

        // Anthropic API requires system messages as top-level "system" parameter, not in messages array
        // Filter out any system messages from conversation messages (they should be in systemMessages parameter)
        val nonSystemMessages = messages.filter { it.role != "system" }

        // Combine all system messages from systemMessages parameter
        val combinedSystemPrompt = systemMessages
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotEmpty() }

        // Map non-system messages to Claude format
        val claudeMessages = nonSystemMessages.map { msg ->
            mapOf("role" to msg.role, "content" to msg.content)
        }

        // Build request body
        val requestBody = buildMap {
            put("model", model)
            put("messages", claudeMessages)

            // Add streaming parameter if requested
            if (streaming) {
                put("stream", true)
            }

            // Use min of provided maxTokens and configured limit (Claude requires max_tokens)
            val maxOutputLimit = configService?.getMaxOutputTokens(taskId)
                ?: pl.jclab.refio.core.services.ConfigService.DEFAULT_MAX_OUTPUT_SIZE
            val requestedMax = when {
                maxTokens != null && maxTokens > 0 -> minOf(maxTokens, maxOutputLimit)
                else -> maxOutputLimit
            }
            val modelLimit = pl.jclab.refio.core.llm.ModelDefinitions
                .getDefinition("anthropic", model)
                ?.maxOutputTokens
            val effectiveMaxTokens = if (modelLimit != null && modelLimit > 0 && requestedMax > modelLimit) {
                logger.warn {
                    "[ANTHROPIC] Requested max_tokens=$requestedMax exceeds model limit ($modelLimit) for $model - " +
                        "clamping to safe value"
                }
                modelLimit
            } else {
                requestedMax
            }
            put("max_tokens", effectiveMaxTokens)
            logger.debug {
                "[ANTHROPIC] Using maxTokens=$effectiveMaxTokens (requested=$maxTokens, configLimit=$maxOutputLimit, modelLimit=${modelLimit ?: "n/a"})"
            }

            put("temperature", temperature)

            // Add system prompt as separate parameter (not a message)
            // Use combinedSystemPrompt which combines all system messages from systemMessages parameter
            // If JSON mode is requested, enforce it in system prompt (Anthropic has no native JSON mode)
            val responseFormat = kwargs["response_format"] as? Map<*, *>
            val finalSystemPrompt = if (responseFormat != null && responseFormat["type"] == "json_object") {
                val jsonInstruction = "\n\nCRITICAL: You MUST respond with valid JSON only. Do not include any text, explanation, or markdown code blocks before or after the JSON object. Start your response with { and end with }."
                if (combinedSystemPrompt != null) {
                    combinedSystemPrompt + jsonInstruction
                } else {
                    jsonInstruction.trim()
                }
            } else {
                combinedSystemPrompt
            }

            if (finalSystemPrompt != null) {
                put("system", finalSystemPrompt)
                if (responseFormat != null) {
                    logger.info { "[ANTHROPIC] Enforcing JSON mode via system prompt" }
                }
            }

            // Enable thinking mode for Claude 3.5+ if requested
            val thinking = kwargs["thinking"] as? Boolean ?: false
            if (thinking && model.contains("claude-3-5", ignoreCase = true)) {
                put("thinking", mapOf(
                    "type" to "enabled",
                    "budget_tokens" to 10000
                ))
                logger.info { "[ANTHROPIC] Enabled thinking mode for $model" }
            }

            // Additional parameters
            (kwargs["top_p"] as? Number)?.let { put("top_p", it) }
            (kwargs["top_k"] as? Number)?.let { put("top_k", it) }
            (kwargs["stop_sequences"] as? List<*>)?.let { put("stop_sequences", it) }
        }

        val requestJson = gson.toJson(requestBody)
        val requestId = UUID.randomUUID().toString()
        val logPrefix = "[ANTHROPIC][$requestId]"
        logger.debug { "$logPrefix Request: ${SecureLogger.redactAndTruncate(requestJson)}" }

        val startTime = System.currentTimeMillis()

        return if (streaming && onStreamChunk != null) {
            // Streaming mode
            executeStreaming(apiKeyToUse, requestBody, requestJson, startTime, onStreamChunk, logPrefix)
        } else {
            // Standard mode
            executeStandard(apiKeyToUse, requestBody, requestJson, startTime, logPrefix)
        }
    }

    private suspend fun executeStandard(
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        logPrefix: String
    ): LLMResponse {
        var httpStatus: Int? = null

        try {
            // Make HTTP request
            logger.info { "$logPrefix Request start: endpoint=$DEFAULT_BASE_URL$MESSAGES_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            val httpResponse = client.post("$DEFAULT_BASE_URL$MESSAGES_ENDPOINT") {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", DEFAULT_ANTHROPIC_VERSION)
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

            // Check for error response
            if (httpStatus !in 200..299) {
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()

                // Extract error details from Anthropic error response
                @Suppress("UNCHECKED_CAST")
                val errorObj = response["error"] as? Map<String, Any?>
                val errorMessage = errorObj?.get("message") as? String ?: "Unknown error"
                val errorType = errorObj?.get("type") as? String

                val fullErrorMessage = buildString {
                    append("Anthropic API error (HTTP $httpStatus): $errorMessage")
                    if (errorType != null) append(" [type: $errorType]")
                }

                logger.error { "$logPrefix $fullErrorMessage" }

                // Log error to API logs
                logger.apiError(
                    provider = provider,
                    model = model,
                    endpoint = "$DEFAULT_BASE_URL$MESSAGES_ENDPOINT",
                    requestJson = requestJson,
                    httpStatus = httpStatus,
                    error = Exception(fullErrorMessage),
                    latencyMs = latencyMs,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    source = source
                )

                throw IllegalStateException(fullErrorMessage)
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            @Suppress("UNCHECKED_CAST")
            val usageMap = response["usage"] as? Map<String, Any?> ?: emptyMap()
            val inputTokens = (usageMap["input_tokens"] as? Number)?.toInt() ?: 0
            val outputTokens = (usageMap["output_tokens"] as? Number)?.toInt() ?: 0

            val usage = LLMUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )

            val cost = estimateCost(usage)

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$DEFAULT_BASE_URL$MESSAGES_ENDPOINT",
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

            // Parse response (handle content blocks including thinking)
            @Suppress("UNCHECKED_CAST")
            val contentBlocks = response["content"] as? List<Map<String, Any?>> ?: emptyList()

            var textContent = ""
            for (block in contentBlocks) {
                when (block["type"]) {
                    "text" -> textContent += (block["text"] as? String ?: "")
                    "thinking" -> {
                        // Log thinking process but don't include in final output
                        val thinking = block["thinking"] as? String ?: ""
                        logger.debug { "[ANTHROPIC] Claude thinking: ${thinking.take(200)}..." }
                    }
                }
            }

            val responseModel = response["model"] as? String ?: model
            val stopReason = response["stop_reason"] as? String

            logger.info { "$logPrefix Response processed: model=$responseModel, " +
                    "tokens_in=${usage.inputTokens}, tokens_out=${usage.outputTokens}, " +
                    "cost=${"%.4f".format(cost)}, stop_reason=$stopReason" }

            return LLMResponse(
                content = textContent,
                usage = usage,
                model = responseModel,
                provider = provider,
                cost = cost,
                finishReason = stopReason,
                rawResponse = response
            )

        } catch (e: Exception) {
            // Error #15: Log error (console + database)
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$DEFAULT_BASE_URL$MESSAGES_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            throw e
        }
    }

    private suspend fun executeStreaming(
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        logPrefix: String
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        var inputTokens = 0
        var outputTokens = 0
        var httpStatus: Int? = null
        var finalStopReason: String? = null

        try {
            // Make streaming HTTP request
            logger.info { "$logPrefix Request start: endpoint=$DEFAULT_BASE_URL$MESSAGES_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            client.preparePost("$DEFAULT_BASE_URL$MESSAGES_ENDPOINT") {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", DEFAULT_ANTHROPIC_VERSION)
                setBody(requestBody)
            }.execute { httpResponse ->
                httpStatus = httpResponse.status.value

                // Check for error response before reading stream
                if (httpStatus !in 200..299) {
                    val errorBody = httpResponse.body<String>()
                    val latencyMs = (System.currentTimeMillis() - startTime).toInt()

                    // Try to parse error JSON
                    val errorMessage = try {
                        @Suppress("UNCHECKED_CAST")
                        val errorResponse = gson.fromJson(errorBody, Map::class.java) as Map<String, Any?>
                        val errorObj = errorResponse["error"] as? Map<String, Any?>
                        val message = errorObj?.get("message") as? String ?: errorBody
                        val errorType = errorObj?.get("type") as? String

                        buildString {
                            append("Anthropic API error (HTTP $httpStatus): $message")
                            if (errorType != null) append(" [type: $errorType]")
                        }
                    } catch (e: Exception) {
                        "Anthropic API error (HTTP $httpStatus): $errorBody"
                    }

                    logger.error { "$logPrefix $errorMessage" }

                    logger.apiError(
                        provider = provider,
                        model = model,
                        endpoint = "$DEFAULT_BASE_URL$MESSAGES_ENDPOINT",
                        requestJson = requestJson,
                        httpStatus = httpStatus,
                        error = Exception(errorMessage),
                        latencyMs = latencyMs,
                        taskId = taskId,
                        subtaskId = subtaskId,
                        source = source
                    )

                    throw IllegalStateException(errorMessage)
                }

                val channel: io.ktor.utils.io.ByteReadChannel = httpResponse.body()

                var currentEvent: String? = null

                // Read SSE stream line by line
                while (!channel.isClosedForRead) {
                    // Check cancellation - break to return partial response
                    if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                        logger.info { "$logPrefix Streaming cancelled by user - returning partial response" }
                        finalStopReason = "cancelled"
                        break
                    }

                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank()) continue

                    // SSE format: "event: type" or "data: {...}"
                    when {
                        line.startsWith("event: ") -> {
                            currentEvent = line.removePrefix("event: ").trim()
                        }
                        line.startsWith("data: ") -> {
                            val data = line.removePrefix("data: ").trim()

                            try {
                                @Suppress("UNCHECKED_CAST")
                                val chunk = gson.fromJson(data, Map::class.java) as Map<String, Any?>
                                val eventType = chunk["type"] as? String

                                when (eventType) {
                                    "message_start" -> {
                                        // Extract input tokens from initial message
                                        @Suppress("UNCHECKED_CAST")
                                        val message = chunk["message"] as? Map<String, Any?>
                                        @Suppress("UNCHECKED_CAST")
                                        val usage = message?.get("usage") as? Map<String, Any?>
                                        inputTokens = (usage?.get("input_tokens") as? Number)?.toInt() ?: 0
                                        logger.debug { "[ANTHROPIC] Stream started, input_tokens=$inputTokens" }
                                    }

                                    "content_block_delta" -> {
                                        // Extract delta text
                                        @Suppress("UNCHECKED_CAST")
                                        val delta = chunk["delta"] as? Map<String, Any?>
                                        val deltaType = delta?.get("type") as? String

                                        when (deltaType) {
                                            "text_delta" -> {
                                                val text = delta["text"] as? String
                                                if (text != null && text.isNotEmpty()) {
                                                    contentBuilder.append(text)
                                                    onStreamChunk(StreamChunk(
                                                        delta = text,
                                                        finishReason = null
                                                    ))
                                                }
                                            }
                                            "thinking_delta" -> {
                                                // Log thinking process but don't emit
                                                val thinking = delta["thinking"] as? String
                                                if (thinking != null) {
                                                    logger.debug { "[ANTHROPIC] Claude thinking: ${thinking.take(100)}..." }
                                                }
                                            }
                                        }
                                    }

                                    "message_delta" -> {
                                        // Final message with stop_reason and output tokens
                                        @Suppress("UNCHECKED_CAST")
                                        val delta = chunk["delta"] as? Map<String, Any?>
                                        finalStopReason = delta?.get("stop_reason") as? String

                                        @Suppress("UNCHECKED_CAST")
                                        val usage = chunk["usage"] as? Map<String, Any?>
                                        outputTokens = (usage?.get("output_tokens") as? Number)?.toInt() ?: 0
                                    }

                                    "message_stop" -> {
                                        logger.debug { "$logPrefix Stream complete" }
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn { "$logPrefix Failed to parse chunk: $data - ${e.message}" }
                                continue
                            }
                        }
                    }
                }
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            val usage = LLMUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )

            val cost = estimateCost(usage)

            // Emit final chunk with usage
            onStreamChunk(StreamChunk(
                delta = "",
                finishReason = finalStopReason,
                usage = usage
            ))

            // Create synthetic response JSON for logging
            val syntheticResponse = mapOf(
                "content" to listOf(
                    mapOf("type" to "text", "text" to contentBuilder.toString())
                ),
                "stop_reason" to finalStopReason,
                "usage" to mapOf(
                    "input_tokens" to inputTokens,
                    "output_tokens" to outputTokens
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
                endpoint = "$DEFAULT_BASE_URL$MESSAGES_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus ?: 200,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = cost,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            logger.info { "$logPrefix Streaming completed in ${latencyMs}ms, tokens=$inputTokens/$outputTokens, logged to API logs" }

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = cost,
                finishReason = finalStopReason,
                rawResponse = syntheticResponse
            )

        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$DEFAULT_BASE_URL$MESSAGES_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
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
     * Lists all available models from Anthropic API.
     *
     * @return List of ModelConfig objects with model metadata and pricing
     * @throws IllegalStateException if API key is not provided or API returns empty response
     */
    suspend fun listModels(): List<ModelConfig> {
        logger.info { "[ANTHROPIC] Fetching available models from $DEFAULT_BASE_URL$MODELS_ENDPOINT" }

        try {
            // Get API key from ConfigService (single source of truth)
            val apiKeyToUse = configService?.get(
                key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_ANTHROPIC_API_KEY,
                scope = pl.jclab.refio.core.db.ConfigScope.APP
            )
                ?: System.getProperty("ANTHROPIC_API_KEY")
                ?: System.getenv("ANTHROPIC_API_KEY")
                ?: null

            if (apiKeyToUse==null){
                return emptyList()
            }

            // Make HTTP request
            val httpResponse = client.get("$DEFAULT_BASE_URL$MODELS_ENDPOINT") {
                header("x-api-key", apiKeyToUse)
                header("anthropic-version", DEFAULT_ANTHROPIC_VERSION)
            }

            val response: Map<String, Any?> = httpResponse.body()

            // Parse response
            @Suppress("UNCHECKED_CAST")
            val modelsData = response["data"] as? List<Map<String, Any?>> ?: emptyList()

            if (modelsData.isEmpty()) {
                logger.warn { "[ANTHROPIC] API returned empty model list" }
                return emptyList()
            }

            val modelConfigs = modelsData.mapNotNull { modelData ->
                val modelId = modelData["id"] as? String ?: return@mapNotNull null

                // Filter using whitelist - only supported models
                if (!pl.jclab.refio.core.llm.SupportedModels.isSupported("anthropic", modelId)) {
                    return@mapNotNull null
                }

                // Get static definition from ModelDefinitions or create fallback
                val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("anthropic", modelId)
                    ?: run {
                        logger.debug { "[ANTHROPIC] Model $modelId not in registry, using fallback" }

                        pl.jclab.refio.core.llm.ModelDefinitions.createFallback(
                            provider = "anthropic",
                            modelId = modelId,
                            maxContext = 200_000  // Claude models typically have 200K context
                        )
                    }

                logger.debug { "[ANTHROPIC] Found model: $modelId (streaming=${definition.supportsStreaming}, vision=${definition.supportsVision})" }

                // Convert to ModelConfig
                definition.toModelConfig()
            }

            logger.info { "[ANTHROPIC] Found ${modelConfigs.size} models" }
            return modelConfigs

        } catch (e: Exception) {
            logger.error(e) { "[ANTHROPIC] Failed to fetch models: ${e.message}" }
            throw Exception("Failed to fetch Anthropic models: ${e.message}", e)
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

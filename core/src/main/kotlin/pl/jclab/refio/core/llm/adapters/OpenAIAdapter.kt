package pl.jclab.refio.core.llm.adapters

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.toModelConfig
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
 * Adapter for OpenAI GPT models.
 *
 * Provides integration with OpenAI API (gpt-4o, gpt-4, gpt-3.5-turbo, o1, o3, gpt-5, gpt-4.1).
 * Uses Ktor HTTP client to call OpenAI REST API.
 *
 * Special handling for reasoning models (o1, o3, gpt-5, gpt-4.1):
 * - System prompts are converted to user messages (no system role support)
 * - Temperature parameter is not supported (always 1)
 * - Reasoning/thinking is ALWAYS ENABLED (built-in, cannot be disabled)
 * - JSON mode and some parameters (top_p, penalties, stop) are not supported
 * - Max tokens may have different behavior (completion tokens vs reasoning tokens)
 * - Streaming is not supported (falls back to standard mode)
 */
class OpenAIAdapter(
    model: String = "gpt-4o-mini",
    private val configService: pl.jclab.refio.core.services.ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null,
    private val baseUrlOverride: String? = null,
    private val httpClientOverride: HttpClient? = null
) : BaseLLMAdapter(model, "openai") {

    private val logger = dualLogger("OpenAIAdapter")

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val CHAT_ENDPOINT = "/chat/completions"
        const val MODELS_ENDPOINT = "/models"
    }

    private val timeoutMs: Long
        get() = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L

    private val baseUrl: String
        get() = baseUrlOverride ?: DEFAULT_BASE_URL

    /**
     * Get API endpoint path based on model definition.
     * Different models may require different endpoints (e.g., /responses for GPT-5.1 Codex).
     */
    private fun getEndpoint(definition: pl.jclab.refio.core.llm.ModelDefinition?): String {
        return definition?.endpointType?.path ?: CHAT_ENDPOINT
    }

    /**
     * Transform request parameters to Responses API format.
     * Converts "messages" parameter to "input" parameter.
     * Also adds "reasoning" parameter for reasoning models based on "thinking" flag.
     */
    private fun transformRequestToResponses(requestBody: Map<String, Any>): Map<String, Any> {
        val transformed = requestBody.toMutableMap()

        // Remove "messages" and add "input"
        val messages = transformed.remove("messages")
        if (messages is List<*>) {
            @Suppress("UNCHECKED_CAST")
            val messageList = messages as List<Map<String, Any?>>

            // Convert messages array to input format
            // User/system messages use "input_text", assistant messages use "output_text"
            val inputItems = messageList.mapNotNull { msg ->
                val role = msg["role"] ?: return@mapNotNull null
                val content = msg["content"] ?: return@mapNotNull null

                // Determine content type based on role
                val convertedContent = when (content) {
                    is String -> {
                        val contentType = when (role) {
                            "assistant" -> "output_text"
                            else -> "input_text"
                        }
                        listOf(
                            mapOf(
                                "type" to contentType,
                                "text" to content
                            )
                        )
                    }

                    is List<*> -> {
                        content.mapNotNull { block ->
                            @Suppress("UNCHECKED_CAST")
                            val blockMap = block as? Map<String, Any?> ?: return@mapNotNull null
                            when (blockMap["type"]) {
                                "text" -> mapOf(
                                    "type" to if (role == "assistant") "output_text" else "input_text",
                                    "text" to (blockMap["text"]?.toString() ?: "")
                                )

                                "image_url" -> {
                                    if (role == "assistant") {
                                        null
                                    } else {
                                        val imageUrl = blockMap["image_url"] as? Map<*, *>
                                        val image = imageUrl?.get("url")?.toString() ?: return@mapNotNull null
                                        mapOf(
                                            "type" to "input_image",
                                            "image_url" to image
                                        )
                                    }
                                }

                                else -> null
                            }
                        }
                    }

                    else -> emptyList()
                }

                mapOf(
                    "type" to "message",
                    "role" to role,
                    "content" to convertedContent
                )
            }

            transformed["input"] = inputItems
        }

        // Transform response_format to text.format for Responses API
        val responseFormat = transformed.remove("response_format") as? Map<*, *>
        if (responseFormat != null) {
            transformed["text"] = mapOf(
                "format" to responseFormat
            )
        }

        // Add reasoning parameter for reasoning models based on thinking flag
        // thinking: Boolean or String (low/medium/high)
        // - false (Boolean) → effort: "low"
        // - true (Boolean) → effort: "medium"
        // - "low"/"medium"/"high" (String) → effort: thinking
        val thinking = transformed.remove("thinking")
        if (thinking != null) {
            val effort = when (thinking) {
                is Boolean -> when (thinking) {
                    false -> "low"
                    true -> "medium"
                }
                is String -> {
                    // Validate string value
                    when (thinking.lowercase()) {
                        "low", "medium", "high" -> thinking.lowercase()
                        else -> "medium" // default to medium for invalid values
                    }
                }
                else -> "medium" // default fallback
            }

            transformed["reasoning"] = mapOf("effort" to effort)
            logger.info { "[OPENAI] Set reasoning effort to: $effort (thinking=$thinking)" }
        }

        return transformed
    }

    /**
     * Transform response from Responses API format to chat completions format.
     * Converts "output" to "choices" with "message" structure.
     * Preserves "usage" field for token counting.
     */
    private fun transformResponseFromResponses(response: Map<String, Any?>): Map<String, Any?> {
        val transformed = response.toMutableMap()

        // Log the complete raw response structure
//        logger.info { "[OPENAI] Raw Responses: ${response}" }
//        logger.info { "[OPENAI] Raw Responses API keys: ${response.keys}" }
//        logger.info { "[OPENAI] Raw Responses API usage field: ${response["usage"]}" }
//        logger.info { "[OPENAI] Raw Responses API text field: ${response["text"]}" }

        // Extract output array
        @Suppress("UNCHECKED_CAST")
        val output = response["output"] as? List<Map<String, Any?>> ?: emptyList()

        logger.info { "[OPENAI] Responses API output items count: ${output.size}" }

        // Log output in readable format (full JSON)
        try {
            @Suppress("UNUSED_VARIABLE")
            val _outputJson = gson.toJson(output)
//            logger.info { "[OPENAI] Responses API output JSON: $_outputJson" }
        } catch (e: Exception) {
            logger.warn { "[OPENAI] Failed to serialize output: ${e.message}" }
        }

        // Find first message item and extract text content
        val messageItem = output.firstOrNull { it["type"] == "message" }
        logger.info { "[OPENAI] Message item found: ${messageItem != null}" }

        fun extractTextFromMessageItem(item: Map<String, Any?>): String {
            val directContent = item["content"] as? String
            if (!directContent.isNullOrBlank()) {
                return directContent
            }

            @Suppress("UNCHECKED_CAST")
            val contentArray = item["content"] as? List<Map<String, Any?>> ?: emptyList()
            val textContent = contentArray.firstOrNull {
                val type = it["type"] as? String
                type == "output_text" || type == "text"
            }
            return textContent?.get("text") as? String ?: ""
        }

        fun extractTextFromOutputItems(items: List<Map<String, Any?>>): String {
            val parts = mutableListOf<String>()
            items.forEach { item ->
                when (item["type"] as? String) {
                    "output_text" -> {
                        val text = item["text"] as? String
                        if (!text.isNullOrBlank()) {
                            parts.add(text)
                        }
                    }
                    "message" -> {
                        val text = extractTextFromMessageItem(item)
                        if (text.isNotBlank()) {
                            parts.add(text)
                        }
                    }
                }
            }
            return parts.joinToString("")
        }

        var content = messageItem?.let { extractTextFromMessageItem(it) } ?: ""
        if (content.isBlank()) {
            content = extractTextFromOutputItems(output)
        }

        if (content.isBlank()) {
            // FALLBACK: If no output text found, check top-level "text" field
            logger.warn { "[OPENAI] No content in output, checking top-level 'text' field" }

            val topLevelText = response["text"]
            logger.info { "[OPENAI] Top-level text field type: ${topLevelText?.javaClass?.simpleName}" }

            content = when (topLevelText) {
                is String -> {
                    logger.info { "[OPENAI] Using top-level text string, length: ${topLevelText.length}" }
                    topLevelText
                }

                is Map<*, *> -> {
                    val textMap = topLevelText
                    val textContent = textMap["content"]?.toString() ?: textMap["text"]?.toString() ?: ""
                    logger.info { "[OPENAI] Using text from map, length: ${textContent.length}" }
                    textContent
                }

                else -> {
                    logger.error { "[OPENAI] Cannot extract content - no output text and no text field" }
                    ""
                }
            }
        }

        val role = messageItem?.get("role") as? String ?: "assistant"
        val status = messageItem?.get("status") as? String ?: response["status"] as? String
        logger.info { "[OPENAI] Status: $status (from ${if (messageItem != null) "message item" else "top-level"})" }

        // Transform to chat completions format
        transformed["choices"] = listOf(
            mapOf(
                "message" to mapOf(
                    "role" to role,
                    "content" to content
                ),
                "finish_reason" to status
            )
        )

        // Remove "output" field
        transformed.remove("output")

        // Transform usage field: Responses API uses input_tokens/output_tokens,
        // but Chat Completions API uses prompt_tokens/completion_tokens
        @Suppress("UNCHECKED_CAST")
        val rawUsage = response["usage"] as? Map<String, Any?>
        if (rawUsage != null) {
            val transformedUsage = mutableMapOf<String, Any?>()

            // Map Responses API field names to Chat Completions API field names
            rawUsage["input_tokens"]?.let { transformedUsage["prompt_tokens"] = it }
            rawUsage["output_tokens"]?.let { transformedUsage["completion_tokens"] = it }
            rawUsage["total_tokens"]?.let { transformedUsage["total_tokens"] = it }

            transformed["usage"] = transformedUsage
            logger.info { "[OPENAI] Transformed usage: input_tokens=${rawUsage["input_tokens"]} → prompt_tokens=${transformedUsage["prompt_tokens"]}, output_tokens=${rawUsage["output_tokens"]} → completion_tokens=${transformedUsage["completion_tokens"]}" }
        }

        // Log incomplete details if present
        @Suppress("UNCHECKED_CAST")
        val incompleteDetails = response["incomplete_details"] as? Map<String, Any?>
        if (incompleteDetails != null) {
            val reason = incompleteDetails["reason"]
            logger.warn { "[OPENAI] Response incomplete: reason=$reason (status=$status)" }
        }

        // Check and log usage field preservation
        logger.info { "[OPENAI] Usage field after transformation: ${transformed["usage"]}" }

        return transformed
    }

    // Get timeout from ConfigService
    private val timeout: Long
        get() = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L

    private val ownsHttpClient = httpClientOverride == null

    private val client = httpClientOverride ?: HttpClient(CIO) {
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
                    this@OpenAIAdapter.logger.debug { message }
                }
            }
            sanitizeHeader { header ->
                header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                    header.equals("x-api-key", ignoreCase = true) ||
                    header.equals("x-goog-api-key", ignoreCase = true)
            }
        }
        install(HttpTimeout) {
            val timeoutMs = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
                ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L
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
        // Get model definition from central registry
        val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("openai", model)
        val isReasoningModel = definition?.supportsReasoning ?: false

        // Check if streaming is requested but not supported
        if (streaming && definition?.supportsStreaming == false) {
            logger.info { "[OPENAI] Model $model does not support streaming (reasoning model) - falling back to standard mode" }
            // Fall back to non-streaming mode
            return chatInternal(
                messages,
                systemMessages,
                maxTokens,
                temperature,
                false,
                null,
                kwargs,
                definition,
                isReasoningModel
            )
        }

        logger.info { "[OPENAI] Sending ${if (streaming) "streaming" else "standard"} chat request: model=$model, messages=${messages.size}, systemMessages=${systemMessages.size}" }

        if (isReasoningModel) {
            logger.info { "[OPENAI] Using reasoning model: $model (built-in thinking mode, parameter restrictions apply)" }
        }

        return chatInternal(
            messages,
            systemMessages,
            maxTokens,
            temperature,
            streaming,
            onStreamChunk,
            kwargs,
            definition,
            isReasoningModel
        )
    }

    private suspend fun chatInternal(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        streaming: Boolean,
        onStreamChunk: ((StreamChunk) -> Unit)?,
        kwargs: Map<String, Any>,
        definition: pl.jclab.refio.core.llm.ModelDefinition?,
        isReasoningModel: Boolean
    ): LLMResponse {
        try {
            // Get API key from ConfigService (single source of truth)
            val apiKeyToUse = configService?.get(
                key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_OPENAI_API_KEY,
                scope = pl.jclab.refio.core.db.ConfigScope.APP
            )
                ?: System.getProperty("OPENAI_API_KEY")
                ?: System.getenv("OPENAI_API_KEY")
                ?: throw LLMErrorMapper.missingConfig(provider, "api_key")

            // Prepare messages
        val openaiMessages = mutableListOf<Map<String, Any>>()

        // Add system messages from systemMessages parameter
        // For reasoning models: system prompts must be converted to user messages
        // For other models: use system role normally
        systemMessages.filter { it.isNotBlank() }.forEach { sysMsg ->
            if (isReasoningModel) {
                // Add as user message for reasoning models (they don't support system role)
                openaiMessages.add(mapOf("role" to "user", "content" to sysMsg))
            } else {
                // Add as system message for standard models
                openaiMessages.add(mapOf("role" to "system", "content" to sysMsg))
            }
        }

        // Add conversation messages (filter out any system messages as they should be in systemMessages parameter).
        // Remap "tool" (used by LLMMessageMapper for tool results) to "assistant" — OpenAI's "tool" role
        // requires a matching tool_call_id, which this adapter does not currently emit.
        for (msg in messages.filter { it.role != "system" }) {
            val mappedRole = if (msg.role == "tool") "assistant" else msg.role
            openaiMessages.add(mapOf("role" to mappedRole, "content" to toOpenAiMessageContent(msg)))
        }

        // Build base parameters
        val baseParams = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to openaiMessages,
            "temperature" to temperature
        )

        // Add streaming parameter if requested
        if (streaming) {
            baseParams["stream"] = true
        }

        // Use min of provided maxTokens and configured limit
        val maxOutputLimit = configService?.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, taskId)
            ?: ConfigKeys.MAX_OUTPUT_SIZE.default
        val requestedMax = when {
            maxTokens != null && maxTokens > 0 -> minOf(maxTokens, maxOutputLimit)
            else -> maxOutputLimit
        }
        val modelLimit = definition?.maxOutputTokens
        val effectiveMaxTokens = if (modelLimit != null && modelLimit > 0 && requestedMax > modelLimit) {
            logger.warn {
                "[OPENAI] Requested max_tokens=$requestedMax exceeds model limit ($modelLimit) for $model - clamping to safe value"
            }
            modelLimit
        } else {
            requestedMax
        }
        baseParams["max_tokens"] = effectiveMaxTokens
        logger.debug {
            "[OPENAI] Using maxTokens=$effectiveMaxTokens (requested=$maxTokens, configLimit=$maxOutputLimit, modelLimit=${modelLimit ?: "n/a"})"
        }

        // Handle response_format for JSON mode
        val responseFormat = kwargs["response_format"] as? Map<*, *>
        if (responseFormat != null && responseFormat["type"] == "json_object") {
            baseParams["response_format"] = mapOf("type" to "json_object")
        }

        // Add thinking parameter for reasoning models (will be transformed to reasoning.effort for Responses API)
        // thinking can be Boolean (false/true) or String (low/medium/high)
        val thinking = kwargs["thinking"]
        if (thinking != null) {
            baseParams["thinking"] = thinking

            if (isReasoningModel) {
                val effortDisplay = when (thinking) {
                    is Boolean -> when (thinking) {
                        false -> "low (fast)"
                        true -> "medium (balanced)"
                    }
                    is String -> when (thinking.lowercase()) {
                        "low" -> "low (fast)"
                        "medium" -> "medium (balanced)"
                        "high" -> "high (thorough)"
                        else -> "medium (balanced, default)"
                    }
                    else -> "medium (balanced, default)"
                }
                logger.info { "[OPENAI] Using $model - reasoning effort: $effortDisplay" }
            }
        }

        // Additional parameters from kwargs
        (kwargs["top_p"] as? Number)?.let { baseParams["top_p"] = it }
        (kwargs["frequency_penalty"] as? Number)?.let { baseParams["frequency_penalty"] = it }
        (kwargs["presence_penalty"] as? Number)?.let { baseParams["presence_penalty"] = it }
        kwargs["stop"]?.let { baseParams["stop"] = it }

        // Apply format transformation if needed
        val transformedParams = if (definition?.apiFormat == pl.jclab.refio.core.llm.ApiFormat.RESPONSES) {
            logger.debug { "[OPENAI] Transforming request to Responses API format" }
            transformRequestToResponses(baseParams)
        } else {
            baseParams
        }

        // Apply universal parameter normalization (removeParams, paramMappings, defaults)
        val requestBody = normalizeRequestParams(transformedParams, definition)
        logger.debug { "[OPENAI] Parameters after normalization: ${requestBody.keys}" }

        val requestJson = gson.toJson(requestBody)
        val requestId = UUID.randomUUID().toString()
        val logPrefix = "[OPENAI][$requestId]"
        logger.debug { "$logPrefix Request: ${SecureLogger.redactAndTruncate(requestJson)}" }

        val startTime = System.currentTimeMillis()

            return if (streaming && onStreamChunk != null) {
                executeStreaming(apiKeyToUse, requestBody, requestJson, startTime, onStreamChunk, definition, logPrefix)
            } else {
                executeStandard(apiKeyToUse, requestBody, requestJson, startTime, definition, logPrefix)
            }
        } catch (e: CancellationException) {
            // Stream aborted by a guardrail (see core/llm/streaming/) — must propagate
            // so the caller can see StreamAbortedException instead of RefioError.LLMError.
            throw e
        } catch (e: Exception) {
            throw LLMErrorMapper.fromThrowable(provider, model, timeoutMs, e)
        }
    }

    private suspend fun executeStandard(
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        definition: pl.jclab.refio.core.llm.ModelDefinition?,
        logPrefix: String
    ): LLMResponse {
        var httpStatus: Int? = null
        val endpoint = getEndpoint(definition)

        try {
            // Make HTTP request
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$endpoint, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            val httpResponse = client.post("$baseUrl$endpoint") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(requestBody)
            }

            httpStatus = httpResponse.status.value
            val rawResponse: Map<String, Any?> = httpResponse.body()

            // Transform response if using Responses API format
            val response = if (definition?.apiFormat == pl.jclab.refio.core.llm.ApiFormat.RESPONSES) {
                logger.info { "[OPENAI] Transforming response from Responses API format" }
                logger.info { "[OPENAI] Raw response keys: ${rawResponse.keys}" }
                logger.info { "[OPENAI] Raw response size: ${gson.toJson(rawResponse).length} chars" }
                transformResponseFromResponses(rawResponse)
            } else {
                rawResponse
            }

            val responseJson = gson.toJson(response)
            logger.debug { "$logPrefix Response: ${SecureLogger.redactAndTruncate(responseJson)}" }
            logger.info {
                "$logPrefix Response received: status=$httpStatus, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            // Check for error response
            if (httpStatus !in 200..299) {
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()

                // Extract error details from OpenAI error response
                @Suppress("UNCHECKED_CAST")
                val errorObj = response["error"] as? Map<String, Any?>
                val errorMessage = errorObj?.get("message") as? String ?: "Unknown error"
                val errorType = errorObj?.get("type") as? String
                val errorCode = errorObj?.get("code") as? String

                val fullErrorMessage = buildString {
                    append("OpenAI API error (HTTP $httpStatus): $errorMessage")
                    if (errorType != null) append(" [type: $errorType]")
                    if (errorCode != null) append(" [code: $errorCode]")
                }

                logger.error { "$logPrefix $fullErrorMessage" }

                // Log error to API logs
                logger.apiError(
                    provider = provider,
                    model = model,
                    endpoint = "$baseUrl$endpoint",
                    requestJson = requestJson,
                    httpStatus = httpStatus,
                    error = Exception(fullErrorMessage),
                    latencyMs = latencyMs,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    source = source
                )

                throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus, fullErrorMessage)
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            // Extract usage
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
                endpoint = "$baseUrl$endpoint",
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

            // Parse response
            @Suppress("UNCHECKED_CAST")
            val choices = response["choices"] as? List<Map<String, Any?>> ?: emptyList()
            if (choices.isEmpty()) {
                throw RefioError.LLMError(provider, model, IllegalStateException("OpenAI returned empty choices"))
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
                logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted OpenAI tool_calls to canonical JSON content" }
            }
            val finishReason = choice["finish_reason"] as? String

            logger.info {
                "$logPrefix Response processed: model=$responseModel, " +
                        "tokens_in=${usage.inputTokens}, tokens_out=${usage.outputTokens}, " +
                        "cost=${"%.4f".format(cost)}, finish_reason=$finishReason"
            }

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
                endpoint = "$baseUrl$endpoint",
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

    private suspend fun executeStreaming(
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        definition: pl.jclab.refio.core.llm.ModelDefinition?,
        logPrefix: String
    ): LLMResponse {
        return if (definition?.apiFormat == pl.jclab.refio.core.llm.ApiFormat.RESPONSES) {
            executeResponsesStreaming(apiKey, requestBody, requestJson, startTime, onStreamChunk, definition, logPrefix)
        } else {
            executeChatStreaming(apiKey, requestBody, requestJson, startTime, onStreamChunk, definition, logPrefix)
        }
    }

    private suspend fun executeChatStreaming(
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        definition: pl.jclab.refio.core.llm.ModelDefinition?,
        logPrefix: String
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        val toolCallAccumulator = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator()
        var totalTokensEstimate = 0
        var httpStatus: Int? = null
        var finalFinishReason: String? = null
        val endpoint = getEndpoint(definition)

        try {
            // Make streaming HTTP request
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$endpoint, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            client.preparePost("$baseUrl$endpoint") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
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
                        @Suppress("UNCHECKED_CAST")
                        val errorObj = errorResponse["error"] as? Map<String, Any?>
                        val message = errorObj?.get("message") as? String ?: errorBody
                        val errorType = errorObj?.get("type") as? String
                        val errorCode = errorObj?.get("code") as? String

                        buildString {
                            append("OpenAI API error (HTTP $httpStatus): $message")
                            if (errorType != null) append(" [type: $errorType]")
                            if (errorCode != null) append(" [code: $errorCode]")
                        }
                    } catch (e: Exception) {
                        "OpenAI API error (HTTP $httpStatus): $errorBody"
                    }

                    logger.error { "$logPrefix $errorMessage" }

                    logger.apiError(
                        provider = provider,
                        model = model,
                        endpoint = "$baseUrl$endpoint",
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

                // Read SSE stream line by line
                while (!channel.isClosedForRead) {
                    // Check cancellation - break to return partial response
                    if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                        logger.info { "$logPrefix Streaming cancelled by user - returning partial response" }
                        finalFinishReason = "cancelled"
                        break
                    }

                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank()) continue

                    // SSE format: "data: {...}" or "data: [DONE]"
                    if (!line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()

                    // Check for stream end
                    if (data == "[DONE]") {
                        logger.debug { "$logPrefix Stream complete" }
                        break
                    }

                    // Parse JSON chunk
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val chunk = gson.fromJson(data, Map::class.java) as Map<String, Any?>

                        @Suppress("UNCHECKED_CAST")
                        val choices = chunk["choices"] as? List<Map<String, Any?>> ?: continue
                        if (choices.isEmpty()) continue

                        val choice = choices[0]

                        @Suppress("UNCHECKED_CAST")
                        val delta = choice["delta"] as? Map<String, Any?>
                        toolCallAccumulator.consumeDelta(delta)
                        val content = delta?.get("content") as? String
                        val finishReason = choice["finish_reason"] as? String

                        // Emit content chunk
                        if (content != null && content.isNotEmpty()) {
                            contentBuilder.append(content)
                            totalTokensEstimate += countApproxTokens(content) // Rough estimate

                            onStreamChunk(
                                StreamChunk(
                                    delta = content,
                                    finishReason = null
                                )
                            )
                        }

                        // Track final finish_reason
                        if (finishReason != null) {
                            finalFinishReason = finishReason
                        }
                    } catch (e: CancellationException) {
                        // Let stream abort (guardrail trip) propagate out of the loop.
                        throw e
                    } catch (e: Exception) {
                        logger.warn { "$logPrefix Failed to parse chunk: $data - ${e.message}" }
                        continue
                    }
                }
            }

            if (contentBuilder.isEmpty()) {
                val normalizedToolCallsJson = toolCallAccumulator.toCanonicalJson()
                if (normalizedToolCallsJson != null) {
                    contentBuilder.append(normalizedToolCallsJson)
                    logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted streamed OpenAI tool_calls to canonical JSON content" }
                }
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            // Estimate final usage for logging (OpenAI doesn't send usage in stream)
            val inputTokensEstimate = estimateInputTokens(requestBody)

            val usage = LLMUsage(
                inputTokens = inputTokensEstimate,
                outputTokens = totalTokensEstimate,
                totalTokens = inputTokensEstimate + totalTokensEstimate
            )

            val cost = estimateCost(usage)

            // Emit final chunk with usage
            onStreamChunk(
                StreamChunk(
                    delta = "",
                    finishReason = finalFinishReason,
                    usage = usage
                )
            )

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
                endpoint = "$baseUrl$endpoint",
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

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = cost,
                finishReason = finalFinishReason,
                rawResponse = syntheticResponse
            )

        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$endpoint",
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

    private suspend fun executeResponsesStreaming(
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        definition: pl.jclab.refio.core.llm.ModelDefinition?,
        logPrefix: String
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        var totalTokensEstimate = 0
        var httpStatus: Int? = null
        var finalFinishReason: String? = null
        var finalUsage: LLMUsage? = null
        var finalRawResponse: Map<String, Any?>? = null
        val endpoint = getEndpoint(definition)

        try {
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$endpoint, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            client.preparePost("$baseUrl$endpoint") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(requestBody)
            }.execute { httpResponse ->
                httpStatus = httpResponse.status.value

                if (httpStatus !in 200..299) {
                    val errorBody = httpResponse.body<String>()
                    val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                    val errorMessage = parseStreamingErrorMessage(errorBody, httpStatus)
                    logger.error { "$logPrefix $errorMessage" }
                    logger.apiError(
                        provider = provider,
                        model = model,
                        endpoint = "$baseUrl$endpoint",
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

                while (!channel.isClosedForRead) {
                    if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                        logger.info { "$logPrefix Streaming cancelled by user - returning partial Responses payload" }
                        finalFinishReason = "cancelled"
                        break
                    }

                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank()) continue

                        if (line.startsWith("event:")) {
                            currentEvent = line.removePrefix("event:").trim()
                            continue
                        }

                        if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload.isBlank()) continue
                    if (payload == "[DONE]") {
                        logger.debug { "$logPrefix Responses stream complete" }
                        break
                    }

                    try {
                        @Suppress("UNCHECKED_CAST")
                        val eventData = gson.fromJson(payload, Map::class.java) as Map<String, Any?>
                        val eventType = (eventData["type"] as? String) ?: currentEvent

                        when (eventType) {
                            "response.output_text.delta",
                            "response.refusal.delta",
                            "response.message.delta" -> {
                                val deltaText = extractResponsesDeltaText(eventData)
                                if (deltaText.isNotEmpty()) {
                                    contentBuilder.append(deltaText)
                                    totalTokensEstimate += countApproxTokens(deltaText)
                                    onStreamChunk(StreamChunk(delta = deltaText, finishReason = null))
                                }
                            }

                            "response.completed" -> {
                                @Suppress("UNCHECKED_CAST")
                                finalRawResponse = eventData["response"] as? Map<String, Any?>
                                finalUsage = mapUsage(eventData["usage"] ?: finalRawResponse?.get("usage"))
                                val status = finalRawResponse?.get("status") as? String
                                finalFinishReason = when (status) {
                                    "completed" -> "stop"
                                    else -> status ?: finalFinishReason
                                }
                            }

                            "response.error" -> {
                                val message = extractResponsesError(eventData)
                                throw IllegalStateException("OpenAI Responses stream error: $message")
                            }

                            "response.canceled" -> {
                                finalFinishReason = "cancelled"
                            }
                        }

                        if (finalUsage == null) {
                            finalUsage = mapUsage(eventData["usage"])
                        }
                    } catch (e: CancellationException) {
                        // Let stream abort (guardrail trip) propagate out of the loop.
                        throw e
                    } catch (e: Exception) {
                        logger.warn { "$logPrefix Failed to parse Responses chunk: $payload - ${e.message}" }
                        continue
                    }
                }
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            val usage = finalUsage ?: run {
                val inputEstimate = estimateInputTokens(requestBody)
                val outputEstimate = totalTokensEstimate
                LLMUsage(
                    inputTokens = inputEstimate,
                    outputTokens = outputEstimate,
                    totalTokens = inputEstimate + outputEstimate
                )
            }

            val rawResponse = finalRawResponse ?: buildSyntheticResponsesPayload(
                content = contentBuilder.toString(),
                finishReason = finalFinishReason,
                usage = usage
            )

            val responseJson = gson.toJson(rawResponse)
            val cost = estimateCost(usage)

            logger.info {
                "$logPrefix Response received: status=${httpStatus ?: 200}, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            onStreamChunk(
                StreamChunk(
                    delta = "",
                    finishReason = finalFinishReason,
                    usage = usage
                )
            )

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$endpoint",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus ?: 200,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = cost,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            logger.info { "[OPENAI] Responses streaming completed in ${latencyMs}ms" }

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = cost,
                finishReason = finalFinishReason,
                rawResponse = transformResponseFromResponses(rawResponse)
            )
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$endpoint",
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

    private fun parseStreamingErrorMessage(errorBody: String, status: Int?): String {
        return try {
            @Suppress("UNCHECKED_CAST")
            val errorResponse = gson.fromJson(errorBody, Map::class.java) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val errorObj = errorResponse["error"] as? Map<String, Any?>
            val message = errorObj?.get("message") as? String ?: errorBody
            val errorType = errorObj?.get("type") as? String
            val errorCode = errorObj?.get("code") as? String

            buildString {
                append("OpenAI API error (HTTP $status): $message")
                if (errorType != null) append(" [type: $errorType]")
                if (errorCode != null) append(" [code: $errorCode]")
            }
        } catch (e: Exception) {
            "OpenAI API error (HTTP $status): $errorBody"
        }
    }

    private fun estimateInputTokens(requestBody: Map<String, Any>): Int {
        @Suppress("UNCHECKED_CAST")
        val messageList = requestBody["messages"] as? List<Map<String, Any?>>
        if (messageList != null) {
            return messageList.sumOf { msg ->
                val content = msg["content"] as? String ?: ""
                countApproxTokens(content)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val inputList = requestBody["input"] as? List<Map<String, Any?>>
        if (inputList != null) {
            return inputList.sumOf { item ->
                when (val content = item["content"]) {
                    is List<*> -> content.sumOf { block ->
                        @Suppress("UNCHECKED_CAST")
                        val blockMap = block as? Map<String, Any?>
                        val text = blockMap?.get("text") as? String ?: ""
                        countApproxTokens(text)
                    }

                    is String -> countApproxTokens(content)
                    else -> 0
                }
            }
        }

        return 0
    }

    private fun extractResponsesDeltaText(eventData: Map<String, Any?>): String {
        when (val directDelta = eventData["delta"]) {
            is String -> if (directDelta.isNotEmpty()) return directDelta
            is Map<*, *> -> {
                val text = directDelta["text"] as? String
                    ?: directDelta["content"] as? String
                if (!text.isNullOrEmpty()) {
                    return text
                }
            }
        }

        val deltaWrapper = eventData["output_text_delta"] as? Map<*, *>
        val wrapperText = deltaWrapper?.get("text") as? String
        if (!wrapperText.isNullOrEmpty()) {
            return wrapperText
        }

        val text = eventData["text"] as? String
        if (!text.isNullOrEmpty()) {
            return text
        }

        val contentList = eventData["content"] as? List<*>
        if (contentList != null) {
            val aggregated = contentList.joinToString("") { block ->
                @Suppress("UNCHECKED_CAST")
                val blockMap = block as? Map<String, Any?>
                blockMap?.get("text") as? String ?: ""
            }
            if (aggregated.isNotEmpty()) {
                return aggregated
            }
        }

        return ""
    }

    private fun extractResponsesError(eventData: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val errorObj = eventData["error"] as? Map<String, Any?>
        val message = errorObj?.get("message") as? String
        val code = errorObj?.get("code") as? String
        return buildString {
            append(message ?: "Unknown error")
            if (!code.isNullOrEmpty()) append(" [code: $code]")
        }
    }

    private fun mapUsage(source: Any?): LLMUsage? {
        val usageMap = source as? Map<*, *> ?: return null
        val inputTokens = (usageMap["input_tokens"] as? Number)?.toInt()
        val outputTokens = (usageMap["output_tokens"] as? Number)?.toInt()
        val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt()

        if (inputTokens == null && outputTokens == null && totalTokens == null) {
            return null
        }

        val resolvedInput = inputTokens ?: 0
        val resolvedOutput = outputTokens ?: 0
        val resolvedTotal = totalTokens ?: (resolvedInput + resolvedOutput)

        return LLMUsage(
            inputTokens = resolvedInput,
            outputTokens = resolvedOutput,
            totalTokens = resolvedTotal
        )
    }

    private fun buildSyntheticResponsesPayload(
        content: String,
        finishReason: String?,
        usage: LLMUsage
    ): Map<String, Any?> {
        val status = finishReason ?: "completed"

        return mapOf(
            "id" to "synthetic_${UUID.randomUUID()}",
            "model" to model,
            "status" to status,
            "output" to listOf(
                mapOf(
                    "type" to "message",
                    "role" to "assistant",
                    "status" to status,
                    "content" to listOf(
                        mapOf(
                            "type" to "output_text",
                            "text" to content
                        )
                    )
                )
            ),
            "usage" to mapOf(
                "input_tokens" to usage.inputTokens,
                "output_tokens" to usage.outputTokens,
                "total_tokens" to usage.totalTokens
            )
        )
    }

    private fun countApproxTokens(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(Regex("\\s+")).size
    }

    /**
     * Lists all available models from OpenAI API.
     *
     * @return List of ModelConfig objects with model metadata and pricing
     * @throws IllegalStateException if API key is not provided or API returns empty response
     */
    suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        logger.info { "[OPENAI] Fetching available models from $baseUrl$MODELS_ENDPOINT" }

        try {
            // Get API key from ConfigService (single source of truth)
            val apiKeyToUse = configService?.get(
                key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_OPENAI_API_KEY,
                scope = pl.jclab.refio.core.db.ConfigScope.APP
            )
                ?: System.getProperty("OPENAI_API_KEY")
                ?: System.getenv("OPENAI_API_KEY")
                ?: null

            if (apiKeyToUse == null) {
                return@withContext emptyList()
            }

            // Make HTTP request
            val httpResponse = client.get("$baseUrl$MODELS_ENDPOINT") {
                header("Authorization", "Bearer $apiKeyToUse")
            }

            val response: Map<String, Any?> = httpResponse.body()

            // Parse response
            @Suppress("UNCHECKED_CAST")
            val modelsData = response["data"] as? List<Map<String, Any?>> ?: emptyList()

            if (modelsData.isEmpty()) {
                logger.warn { "[OPENAI] API returned empty model list" }
                return@withContext emptyList()
            }

            val modelConfigs = modelsData.mapNotNull { modelData ->
                val modelId = modelData["id"] as? String ?: return@mapNotNull null

                // Filter using whitelist - only supported models
                if (!pl.jclab.refio.core.llm.SupportedModels.isSupported("openai", modelId)) {
                    return@mapNotNull null
                }

                // Get static definition from ModelDefinitions or create fallback
                val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("openai", modelId)
                    ?: run {
                        // Extract context length from API response if available
                        @Suppress("UNCHECKED_CAST")
                        val contextLength = (modelData["context_length"] as? Number)?.toInt() ?: DEFAULT_CONTEXT_SIZE

                        logger.debug { "[OPENAI] Model $modelId not in registry, using fallback (context=$contextLength)" }

                        pl.jclab.refio.core.llm.ModelDefinitions.createFallback(
                            provider = "openai",
                            modelId = modelId,
                            maxContext = contextLength
                        )
                    }

                logger.debug { "[OPENAI] Found model: $modelId (streaming=${definition.supportsStreaming}, reasoning=${definition.supportsReasoning})" }

                // Convert to ModelConfig
                definition.toModelConfig()
            }

            logger.info { "[OPENAI] Found ${modelConfigs.size} chat models" }
            return@withContext modelConfigs

        } catch (e: Exception) {
            logger.error(e) { "[OPENAI] Failed to fetch models: ${e.message}" }
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
        if (ownsHttpClient) {
            client.close()
        }
    }
}

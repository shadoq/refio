package pl.jclab.refio.core.llm.adapters

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.NativeToolCall
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.ToolSchemaSanitizer
import pl.jclab.refio.core.llm.ToolsNotSupportedException
import pl.jclab.refio.core.llm.toModelConfig
import pl.jclab.refio.core.tools.base.ToolSchema
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
    private val source: String? = null,
    private val baseUrlOverride: String? = null,
    private val httpClientOverride: HttpClient? = null
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
        get() = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L

    private val ownsHttpClient = httpClientOverride == null

    private val baseUrl: String
        get() = baseUrlOverride ?: DEFAULT_BASE_URL

    private val client = httpClientOverride ?: run {
        val socketTimeoutMs = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L
        LLMKtorClientFactory.create(socketTimeoutMs, logger)
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

        return try {
            chatInternal(messages, systemMessages, maxTokens, temperature, streaming, onStreamChunk, kwargs)
        } catch (e: CancellationException) {
            // Stream aborted by a guardrail (see core/llm/streaming/) — must propagate
            // so the caller can see StreamAbortedException instead of RefioError.LLMError.
            throw e
        } catch (e: ToolsNotSupportedException) {
            throw e
        } catch (e: Exception) {
            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e)
        }
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
            key = ConfigKeys.PROVIDER_ANTHROPIC_API_KEY.key,
            scope = pl.jclab.refio.core.db.ConfigScope.APP
        )
            ?: System.getProperty("ANTHROPIC_API_KEY")
            ?: System.getenv("ANTHROPIC_API_KEY")
            ?: throw LLMErrorMapper.missingConfig(provider, "api_key")

        // Anthropic API requires system messages as top-level "system" parameter, not in messages array
        // Filter out any system messages from conversation messages (they should be in systemMessages parameter)
        val nonSystemMessages = messages.filter { it.role != "system" }

        // Combine all system messages from systemMessages parameter
        val combinedSystemPrompt = systemMessages
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotEmpty() }

        // Map non-system messages to Claude format. Claude only accepts
        // "user"/"assistant" in the messages array — remap "tool" (used by
        // LLMMessageMapper for tool results) to "assistant" so the request
        // body stays valid.
        // Tool results must be on the "user" role for Anthropic — mapping them to
        // "assistant" makes the conversation end with an assistant message, which Anthropic
        // interprets as a prefill (and some models like opus-4-6 reject prefill entirely).
        val claudeMessages = nonSystemMessages.mapIndexed { index, msg ->
            val mappedRole = if (msg.role == "tool") "user" else msg.role
            val content = toAnthropicMessageContent(msg)
            val sanitizedContent = if (index == nonSystemMessages.lastIndex && mappedRole == "assistant" && content is String) {
                content.trimEnd()
            } else {
                content
            }
            mapOf("role" to mappedRole, "content" to sanitizedContent)
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
            val maxOutputLimit = configService?.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, taskId)
                ?: ConfigKeys.MAX_OUTPUT_SIZE.default
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

            // Respect model-level removeParams (e.g. claude-opus-4-7 deprecated `temperature`).
            val removeParams = pl.jclab.refio.core.llm.ModelDefinitions
                .getDefinition("anthropic", model)
                ?.removeParams
                ?: emptyList()
            if ("temperature" !in removeParams) {
                put("temperature", temperature)
            }

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
            if (thinking) {
                put("thinking", mapOf(
                    "type" to "enabled",
                    "budget_tokens" to 4096
                ))
                logger.info { "[ANTHROPIC] Enabled thinking mode for $model" }
            }

            // Additional parameters
            (kwargs["top_p"] as? Number)?.let { put("top_p", it) }
            (kwargs["top_k"] as? Number)?.let { put("top_k", it) }
            (kwargs["stop_sequences"] as? List<*>)?.let { put("stop_sequences", it) }

            // Native function-calling tools
            @Suppress("UNCHECKED_CAST")
            val nativeTools = kwargs["native_tools"] as? List<ToolSchema>
            if (!nativeTools.isNullOrEmpty()) {
                put("tools", buildAnthropicToolsArray(nativeTools))
                logger.info { "[ANTHROPIC][NATIVE_TOOLS] Sending ${nativeTools.size} tool schemas" }
            }
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

    private fun buildAnthropicToolsArray(tools: List<ToolSchema>): List<Map<String, Any>> =
        tools.map { rawTool ->
            val tool = ToolSchemaSanitizer.forAnthropic(rawTool)
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "input_schema" to tool.parametersJsonSchema,
            )
        }

    private fun sanitizeInputSchemaForAnthropic(toolName: String, schema: Map<String, Any>): Map<String, Any> {
        val forbidden = listOf("oneOf", "allOf", "anyOf")
        val stripped = forbidden.filter { schema.containsKey(it) }
        if (stripped.isEmpty()) return schema
        logger.warn {
            "[ANTHROPIC][NATIVE_TOOLS] Tool '$toolName' schema has top-level ${stripped.joinToString()} " +
                "which Anthropic rejects — stripping. Encode constraints in field descriptions instead."
        }
        return schema.filterKeys { it !in forbidden }
    }

    private fun parseNativeAnthropicToolCalls(contentBlocks: List<Map<String, Any?>>): List<NativeToolCall> {
        return contentBlocks.mapNotNull { block ->
            if (block["type"] != "tool_use") return@mapNotNull null
            val id = block["id"] as? String ?: return@mapNotNull null
            val name = block["name"] as? String ?: return@mapNotNull null
            val input = block["input"]
            val argumentsJson = when (input) {
                is Map<*, *> -> gson.toJson(input)
                null -> "{}"
                else -> gson.toJson(input)
            }
            NativeToolCall(
                id = id,
                name = ToolCallContentNormalizer.normalizeToolName(name),
                argumentsJson = argumentsJson
            )
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
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$MESSAGES_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            val httpResponse = client.post("$baseUrl$MESSAGES_ENDPOINT") {
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
                    endpoint = "$baseUrl$MESSAGES_ENDPOINT",
                    requestJson = requestJson,
                    httpStatus = httpStatus,
                    error = Exception(fullErrorMessage),
                    latencyMs = latencyMs,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    source = source
                )

                if (requestBody.containsKey("tools") && isToolsNotSupportedError(httpStatus, errorMessage)) {
                    throw ToolsNotSupportedException(fullErrorMessage)
                }
                throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus, fullErrorMessage)
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
                endpoint = "$baseUrl$MESSAGES_ENDPOINT",
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
            if (response["content"] !is List<*>) {
                throw RefioError.MalformedResponse(
                    provider = provider,
                    model = model,
                    reason = "Missing or non-list 'content' in Anthropic response",
                    bodyPreview = gson.toJson(response)
                )
            }
            @Suppress("UNCHECKED_CAST")
            val contentBlocks = response["content"] as? List<Map<String, Any?>> ?: emptyList()

            var textContent = ""
            var thinkingContent = ""
            for (block in contentBlocks) {
                when (block["type"]) {
                    "text" -> textContent += (block["text"] as? String ?: "")
                    "thinking" -> {
                        val thinking = block["thinking"] as? String ?: ""
                        thinkingContent += thinking
                        logger.debug { "[ANTHROPIC] Claude thinking: ${thinking.take(200)}..." }
                    }
                }
            }

            val toolsWereRequested = requestBody.containsKey("tools")
            val nativeToolCalls: List<NativeToolCall>? = if (toolsWereRequested) {
                parseNativeAnthropicToolCalls(contentBlocks).also { calls ->
                    logger.info { "$logPrefix [NATIVE_TOOLS_RESPONSE] calls=${calls.size}" }
                }
            } else null

            if (nativeToolCalls == null && textContent.isBlank()) {
                val normalizedToolCallsJson = ToolCallContentNormalizer.fromAnthropicContentBlocks(contentBlocks)
                if (normalizedToolCallsJson != null) {
                    textContent = normalizedToolCallsJson
                    logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted Anthropic tool_use blocks to canonical JSON content" }
                }
            }

            val responseModel = response["model"] as? String ?: model
            val stopReason = response["stop_reason"] as? String

            logger.info { "$logPrefix Response processed: model=$responseModel, " +
                    "tokens_in=${usage.inputTokens}, tokens_out=${usage.outputTokens}, " +
                    "cost=${"%.4f".format(cost)}, stop_reason=$stopReason" +
                    if (thinkingContent.isNotEmpty()) ", thinking=${thinkingContent.length} chars" else ""
            }

            return LLMResponse(
                content = textContent,
                usage = usage,
                model = responseModel,
                provider = provider,
                cost = cost,
                finishReason = stopReason,
                rawResponse = response,
                thinking = thinkingContent.takeIf { it.isNotEmpty() },
                nativeToolCalls = nativeToolCalls
            )

        } catch (e: ToolsNotSupportedException) {
            throw e
        } catch (e: Exception) {
            // Error #15: Log error (console + database)
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$MESSAGES_ENDPOINT",
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
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        logPrefix: String
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()  // Collect thinking process
        // Triple: (id, name, argsBuilder)
        val activeToolUseByIndex = mutableMapOf<Int, Triple<String?, String, StringBuilder>>()
        val completedToolUseBlocks = mutableListOf<Map<String, Any?>>()
        var inputTokens = 0
        var outputTokens = 0
        var httpStatus: Int? = null
        var finalStopReason: String? = null

        try {
            // Make streaming HTTP request
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$MESSAGES_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            client.preparePost("$baseUrl$MESSAGES_ENDPOINT") {
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
                        @Suppress("UNCHECKED_CAST")
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
                        endpoint = "$baseUrl$MESSAGES_ENDPOINT",
                        requestJson = requestJson,
                        httpStatus = httpStatus,
                        error = Exception(errorMessage),
                        latencyMs = latencyMs,
                        taskId = taskId,
                        subtaskId = subtaskId,
                        source = source
                    )

                    if (requestBody.containsKey("tools") && isToolsNotSupportedError(httpStatus ?: 500, errorMessage)) {
                        throw ToolsNotSupportedException(errorMessage)
                    }
                    throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus ?: 500, errorMessage)
                }

                val channel: io.ktor.utils.io.ByteReadChannel = httpResponse.body()

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
                            // Event type tracked by SSE protocol; data block handles type via chunk["type"]
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

                                    "content_block_start" -> {
                                        val index = (chunk["index"] as? Number)?.toInt() ?: -1
                                        @Suppress("UNCHECKED_CAST")
                                        val contentBlock = chunk["content_block"] as? Map<String, Any?> ?: emptyMap()
                                        if (contentBlock["type"] == "tool_use") {
                                            val name = contentBlock["name"] as? String
                                            val id = contentBlock["id"] as? String
                                            if (!name.isNullOrBlank()) {
                                                val argsBuilder = StringBuilder()
                                                val input = contentBlock["input"]
                                                if (input is Map<*, *> && input.isNotEmpty()) {
                                                    argsBuilder.append(gson.toJson(input))
                                                }
                                                activeToolUseByIndex[index] = Triple(id, name, argsBuilder)
                                            }
                                        }
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
                                                // Collect and emit thinking process for UI display
                                                val thinking = delta["thinking"] as? String
                                                if (thinking != null && thinking.isNotEmpty()) {
                                                    thinkingBuilder.append(thinking)
                                                    onStreamChunk(StreamChunk(
                                                        delta = "",  // No content delta
                                                        thinking = thinking,  // Thinking delta for UI
                                                        finishReason = null
                                                    ))
                                                    logger.debug { "[ANTHROPIC] Claude thinking: ${thinking.take(100)}..." }
                                                }
                                            }
                                            "input_json_delta" -> {
                                                val index = (chunk["index"] as? Number)?.toInt() ?: -1
                                                val partialJson = delta["partial_json"] as? String
                                                if (!partialJson.isNullOrEmpty()) {
                                                    activeToolUseByIndex[index]?.third?.append(partialJson)
                                                }
                                            }
                                        }
                                    }

                                    "content_block_stop" -> {
                                        val index = (chunk["index"] as? Number)?.toInt() ?: -1
                                        val active = activeToolUseByIndex.remove(index)
                                        if (active != null) {
                                            val (toolId, name, argsBuilder) = active
                                            val argsRaw = argsBuilder.toString().trim()
                                            val input = if (argsRaw.isEmpty()) {
                                                emptyMap<String, Any?>()
                                            } else {
                                                try {
                                                    @Suppress("UNCHECKED_CAST")
                                                    gson.fromJson(argsRaw, Map::class.java) as? Map<String, Any?> ?: mapOf("raw" to argsRaw)
                                                } catch (_: Exception) {
                                                    mapOf("raw" to argsRaw)
                                                }
                                            }
                                            completedToolUseBlocks.add(
                                                mapOf(
                                                    "type" to "tool_use",
                                                    "id" to (toolId ?: java.util.UUID.randomUUID().toString()),
                                                    "name" to name,
                                                    "input" to input
                                                )
                                            )
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
                            } catch (e: CancellationException) {
                                // Let stream abort (guardrail trip) propagate out of the loop.
                                throw e
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
                endpoint = "$baseUrl$MESSAGES_ENDPOINT",
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

            val toolsWereRequested = requestBody.containsKey("tools")
            val streamNativeToolCalls: List<NativeToolCall>? = if (toolsWereRequested) {
                parseNativeAnthropicToolCalls(completedToolUseBlocks).also { calls ->
                    logger.info { "$logPrefix [NATIVE_TOOLS_RESPONSE] (stream) calls=${calls.size}" }
                }
            } else null

            if (streamNativeToolCalls == null && contentBuilder.isEmpty()) {
                val normalizedToolCallsJson = ToolCallContentNormalizer.fromAnthropicContentBlocks(completedToolUseBlocks)
                if (normalizedToolCallsJson != null) {
                    contentBuilder.append(normalizedToolCallsJson)
                    logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted streamed Anthropic tool_use blocks to canonical JSON content" }
                }
            }

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = cost,
                finishReason = finalStopReason,
                rawResponse = syntheticResponse,
                thinking = thinkingBuilder.takeIf { it.isNotEmpty() }?.toString(),
                nativeToolCalls = streamNativeToolCalls
            )

        } catch (e: CancellationException) {
            // Guardrail-triggered abort — log and rethrow as-is, do NOT wrap.
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$MESSAGES_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw e
        } catch (e: ToolsNotSupportedException) {
            throw e
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$MESSAGES_ENDPOINT",
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
     * Lists all available models from Anthropic API.
     *
     * @return List of ModelConfig objects with model metadata and pricing
     * @throws IllegalStateException if API key is not provided or API returns empty response
     */
    suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        logger.info { "[ANTHROPIC] Fetching available models from $baseUrl$MODELS_ENDPOINT" }

        try {
            // Get API key from ConfigService (single source of truth)
            val apiKeyToUse = configService?.get(
                key = ConfigKeys.PROVIDER_ANTHROPIC_API_KEY.key,
                scope = pl.jclab.refio.core.db.ConfigScope.APP
            )
                ?: System.getProperty("ANTHROPIC_API_KEY")
                ?: System.getenv("ANTHROPIC_API_KEY")
                ?: null

            if (apiKeyToUse==null){
                return@withContext emptyList()
            }

            // Make HTTP request
            val httpResponse = client.get("$baseUrl$MODELS_ENDPOINT") {
                header("x-api-key", apiKeyToUse)
                header("anthropic-version", DEFAULT_ANTHROPIC_VERSION)
            }

            val response: Map<String, Any?> = httpResponse.body()

            // Parse response
            @Suppress("UNCHECKED_CAST")
            val modelsData = response["data"] as? List<Map<String, Any?>> ?: emptyList()

            if (modelsData.isEmpty()) {
                logger.warn { "[ANTHROPIC] API returned empty model list" }
                return@withContext emptyList()
            }

            val modelConfigs = modelsData.mapNotNull { modelData ->
                val modelId = modelData["id"] as? String ?: return@mapNotNull null

                // Filter using whitelist - only supported models
                if (!pl.jclab.refio.core.llm.SupportedModels.isSupported("anthropic", modelId)) {
                    return@mapNotNull null
                }

                // Get static definition from ModelDefinitions or synthesize for unknown models.
                val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("anthropic", modelId)
                    ?: run {
                        logger.warn {
                            "[ANTHROPIC] Model $modelId not in registry — using synthetic definition (context=200000)"
                        }

                        pl.jclab.refio.core.llm.ModelDefinitions.syntheticDefinitionFor(
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
            return@withContext modelConfigs

        } catch (e: Exception) {
            logger.error(e) { "[ANTHROPIC] Failed to fetch models: ${e.message}" }
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

    private fun isToolsNotSupportedError(httpStatus: Int, errorMessage: String): Boolean {
        if (httpStatus != 400 && httpStatus != 422) return false
        val lower = errorMessage.lowercase()
        val mentionsTooling =
            lower.contains("tools") ||
                lower.contains("tool_use") ||
                lower.contains("function calling") ||
                lower.contains("input_schema") ||
                lower.contains("schema")
        val unsupportedShape =
            lower.contains("not supported") ||
                lower.contains("unsupported") ||
                lower.contains("unknown parameter") ||
                lower.contains("unexpected parameter") ||
                lower.contains("invalid parameter") ||
                lower.contains("invalid schema") ||
                lower.contains("invalid_request_error")
        return mentionsTooling && unsupportedShape
    }

    override suspend fun close() {
        if (ownsHttpClient) {
            client.close()
        }
    }
}

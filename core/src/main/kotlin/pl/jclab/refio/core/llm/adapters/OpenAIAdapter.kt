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

        @Suppress("UNCHECKED_CAST")
        val tools = transformed["tools"] as? List<Map<String, Any?>>
        if (tools != null) {
            transformed["tools"] = tools.map { tool ->
                val function = tool["function"] as? Map<String, Any?>
                if ((tool["type"] as? String) == "function" && function != null) {
                    mapOf(
                        "type" to "function",
                        "name" to (function["name"] as? String ?: ""),
                        "description" to (function["description"] as? String ?: ""),
                        "parameters" to (function["parameters"] as? Map<*, *> ?: emptyMap<String, Any>()),
                        "strict" to true
                    )
                } else {
                    tool
                }
            }
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
                    when (thinking.lowercase()) {
                        "low", "medium", "high" -> thinking.lowercase()
                        else -> throw IllegalArgumentException(
                            "OpenAI reasoning effort must be one of [low, medium, high], got: '$thinking'"
                        )
                    }
                }
                else -> throw IllegalArgumentException(
                    "OpenAI reasoning 'thinking' must be Boolean or String, got: ${thinking.javaClass.simpleName}"
                )
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

        val toolCalls = output.mapNotNull { item ->
            if (item["type"] != "function_call") return@mapNotNull null
            val id = (item["call_id"] as? String)
                ?: (item["id"] as? String)
                ?: return@mapNotNull null
            val name = item["name"] as? String ?: return@mapNotNull null
            val arguments = item["arguments"] as? String ?: "{}"
            mapOf(
                "id" to id,
                "type" to "function",
                "function" to mapOf(
                    "name" to name,
                    "arguments" to arguments
                )
            )
        }

        if (content.isBlank() && toolCalls.isEmpty()) {
            // No silent fallback — let caller see the malformed structure. (Previously
            // probed top-level "text" field; that path hid bugs in Responses API
            // integration. See REFACTOR.md §1.)
            val preview = try {
                gson.toJson(response).take(500)
            } catch (_: Exception) {
                response.keys.joinToString(prefix = "[keys: ", postfix = "]")
            }
            throw RefioError.MalformedResponse(
                provider = provider,
                model = model,
                reason = "Responses API returned no content in 'output[*]' and no readable message item",
                bodyPreview = preview
            )
        }

        val role = messageItem?.get("role") as? String ?: "assistant"
        val status = messageItem?.get("status") as? String ?: response["status"] as? String
        logger.info { "[OPENAI] Status: $status (from ${if (messageItem != null) "message item" else "top-level"})" }

        // Transform to chat completions format
        transformed["choices"] = listOf(
            mapOf(
                "message" to mapOf(
                    "role" to role,
                    "content" to content,
                    "tool_calls" to toolCalls
                ),
                "finish_reason" to if (toolCalls.isNotEmpty()) "tool_calls" else status
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
                key = ConfigKeys.PROVIDER_OPENAI_API_KEY.key,
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
            // Ask OpenAI to include the final `usage` object in the stream so we can
            // report real token counts (incl. reasoning_tokens for o1/o3/gpt-5*) instead
            // of approximating from streamed content — which yields 0 when the model
            // returns only tool_calls or only reasoning.
            baseParams["stream_options"] = mapOf("include_usage" to true)
        }

        val maxOutputLimit = configService?.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, taskId)
            ?: ConfigKeys.MAX_OUTPUT_SIZE.default
        val effectiveMaxTokens = OpenAICompatibleHelpers.resolveEffectiveMaxTokens(
            requested = maxTokens,
            configLimit = maxOutputLimit,
            modelLimit = definition?.maxOutputTokens,
            providerTag = "OPENAI",
            model = model,
            log = { logger.warn(it) }
        )
        baseParams["max_tokens"] = effectiveMaxTokens
        logger.debug {
            "[OPENAI] Using maxTokens=$effectiveMaxTokens (requested=$maxTokens, configLimit=$maxOutputLimit, modelLimit=${definition?.maxOutputTokens ?: "n/a"})"
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

        // Add native tools if requested and model supports function calling
        @Suppress("UNCHECKED_CAST")
        val nativeTools = kwargs["native_tools"] as? List<ToolSchema>
        if (!nativeTools.isNullOrEmpty()) {
            baseParams["tools"] = if (definition?.apiFormat == pl.jclab.refio.core.llm.ApiFormat.RESPONSES) {
                buildResponsesToolsArray(nativeTools)
            } else {
                buildOpenAIToolsArray(nativeTools)
            }
            baseParams["tool_choice"] = "auto"
            logger.info { "[OPENAI][NATIVE_TOOLS] Sending ${nativeTools.size} tool schemas" }
        }

        with(OpenAICompatibleHelpers) { baseParams.addCommonKwargs(kwargs) }

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
        } catch (e: ToolsNotSupportedException) {
            throw e
        } catch (e: Exception) {
            throw LLMErrorMapper.fromThrowable(provider, model, timeoutMs, e)
        }
    }

    private fun buildOpenAIToolsArray(tools: List<ToolSchema>): List<Map<String, Any>> =
        tools.map { rawTool ->
            val tool = ToolSchemaSanitizer.forOpenAI(rawTool).tool
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parametersJsonSchema,
                )
            )
        }

    private fun buildResponsesToolsArray(tools: List<ToolSchema>): List<Map<String, Any>> =
        tools.map { rawTool ->
            val sanitized = ToolSchemaSanitizer.forOpenAI(rawTool)
            if (!sanitized.strict) {
                logger.warn {
                    "[OPENAI][NATIVE_TOOLS] Tool '${rawTool.name}' is not strict-compatible; " +
                        "sending strict=false. Reasons: ${sanitized.strictIncompatibilities.joinToString("; ")}"
                }
            }
            val tool = sanitized.tool
            mapOf(
                "type" to "function",
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.parametersJsonSchema,
                "strict" to sanitized.strict
            )
        }

    private fun parseNativeOpenAIToolCalls(rawToolCalls: Any?): List<NativeToolCall> {
        @Suppress("UNCHECKED_CAST")
        val toolCalls = rawToolCalls as? List<Map<String, Any?>> ?: return emptyList()
        return toolCalls.mapNotNull { call ->
            val id = call["id"] as? String ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val function = call["function"] as? Map<String, Any?> ?: return@mapNotNull null
            val name = function["name"] as? String ?: return@mapNotNull null
            val argsString = function["arguments"] as? String ?: "{}"
            NativeToolCall(
                id = id,
                name = ToolCallContentNormalizer.normalizeToolName(name),
                argumentsJson = argsString
            )
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

                if (requestBody.containsKey("tools") && isToolsNotSupportedError(httpStatus, errorMessage)) {
                    throw ToolsNotSupportedException(fullErrorMessage)
                }
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
            val finishReason = choice["finish_reason"] as? String

            val toolsWereRequested = requestBody.containsKey("tools")
            val nativeToolCalls: List<NativeToolCall>? = if (toolsWereRequested) {
                parseNativeOpenAIToolCalls(message["tool_calls"]).also { calls ->
                    logger.info { "$logPrefix [NATIVE_TOOLS_RESPONSE] calls=${calls.size}" }
                }
            } else null

            val finalContent = if (nativeToolCalls == null && content.isBlank()) {
                val normalizedToolCallsJson = ToolCallContentNormalizer.fromOpenAiToolCalls(message["tool_calls"])
                if (normalizedToolCallsJson != null) {
                    logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted OpenAI tool_calls to canonical JSON content" }
                }
                normalizedToolCallsJson ?: content
            } else {
                content
            }

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
                rawResponse = response,
                nativeToolCalls = nativeToolCalls
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
        // Real usage from the stream's final chunk (only present when stream_options.include_usage=true).
        // For reasoning models (o1, o3, gpt-5*) completion_tokens already includes reasoning_tokens.
        var streamUsage: LLMUsage? = null
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

                        // Final usage chunk arrives with empty choices when
                        // stream_options.include_usage=true. Capture and continue.
                        @Suppress("UNCHECKED_CAST")
                        val usageMap = chunk["usage"] as? Map<String, Any?>
                        if (usageMap != null) {
                            val promptTokens = (usageMap["prompt_tokens"] as? Number)?.toInt() ?: 0
                            val completionTokens = (usageMap["completion_tokens"] as? Number)?.toInt() ?: 0
                            val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt()
                                ?: (promptTokens + completionTokens)
                            streamUsage = LLMUsage(
                                inputTokens = promptTokens,
                                outputTokens = completionTokens,
                                totalTokens = totalTokens
                            )
                            logger.info {
                                val details = usageMap["completion_tokens_details"] as? Map<*, *>
                                val reasoning = (details?.get("reasoning_tokens") as? Number)?.toInt()
                                "$logPrefix [STREAM_USAGE] prompt=$promptTokens, completion=$completionTokens" +
                                    (reasoning?.let { ", reasoning=$it (incl. in completion)" } ?: "")
                            }
                        }

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

            val toolsWereRequested = requestBody.containsKey("tools")
            val streamNativeToolCalls = toolCallAccumulator.toNativeToolCalls(toolsWereRequested)?.also { calls ->
                logger.info { "$logPrefix [NATIVE_TOOLS_RESPONSE] (stream) calls=${calls.size}" }
            }

            if (streamNativeToolCalls == null && contentBuilder.isEmpty()) {
                val normalizedToolCallsJson = toolCallAccumulator.toCanonicalJson()
                if (normalizedToolCallsJson != null) {
                    contentBuilder.append(normalizedToolCallsJson)
                    logger.info { "$logPrefix [TOOL_CALLS_NORMALIZED] Converted streamed OpenAI tool_calls to canonical JSON content" }
                }
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            // Prefer real usage from the stream (stream_options.include_usage=true);
            // fall back to estimation if the server didn't send it.
            val inputTokensEstimate = streamUsage?.inputTokens ?: estimateInputTokens(requestBody)
            val outputTokensFinal = streamUsage?.outputTokens ?: totalTokensEstimate

            val usage = streamUsage ?: LLMUsage(
                inputTokens = inputTokensEstimate,
                outputTokens = outputTokensFinal,
                totalTokens = inputTokensEstimate + outputTokensFinal
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

            // Log successful stream completion to API logs
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

            logger.info { "$logPrefix Streaming completed in ${latencyMs}ms, logged to API logs" }

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = cost,
                finishReason = finalFinishReason,
                rawResponse = syntheticResponse,
                nativeToolCalls = streamNativeToolCalls
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
        val responseOutputItems = linkedMapOf<Int, MutableMap<String, Any?>>()
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
                    if (requestBody.containsKey("tools") && isToolsNotSupportedError(httpStatus ?: 500, errorMessage)) {
                        throw ToolsNotSupportedException(errorMessage)
                    }
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

                            "response.output_item.added",
                            "response.output_item.done" -> {
                                @Suppress("UNCHECKED_CAST")
                                val item = (eventData["item"] as? Map<String, Any?>)?.toMutableMap()
                                val index = (eventData["output_index"] as? Number)?.toInt()
                                if (item != null && index != null) {
                                    responseOutputItems[index] = item
                                }
                            }

                            "response.function_call_arguments.delta" -> {
                                val index = (eventData["output_index"] as? Number)?.toInt()
                                val delta = eventData["delta"] as? String
                                if (index != null && delta != null) {
                                    val item = responseOutputItems.getOrPut(index) {
                                        mutableMapOf("type" to "function_call")
                                    }
                                    val existing = item["arguments"] as? String ?: ""
                                    item["arguments"] = existing + delta
                                    (eventData["item_id"] as? String)?.let { item["id"] = it }
                                }
                            }

                            "response.function_call_arguments.done" -> {
                                @Suppress("UNCHECKED_CAST")
                                val item = (eventData["item"] as? Map<String, Any?>)?.toMutableMap()
                                val index = (eventData["output_index"] as? Number)?.toInt()
                                if (item != null && index != null) {
                                    responseOutputItems[index] = item
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

            val streamedOutputItems = responseOutputItems.toSortedMap().values.map { it.toMap() }
            val rawResponse = finalRawResponse ?: buildSyntheticResponsesPayload(
                content = contentBuilder.toString(),
                finishReason = finalFinishReason,
                usage = usage,
                outputItems = streamedOutputItems
            )

            val transformedResponse = transformResponseFromResponses(rawResponse)
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
                rawResponse = transformedResponse,
                nativeToolCalls = run {
                    @Suppress("UNCHECKED_CAST")
                    val choices = transformedResponse["choices"] as? List<Map<String, Any?>> ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    val message = choices.firstOrNull()?.get("message") as? Map<String, Any?> ?: emptyMap()
                    if (requestBody.containsKey("tools")) parseNativeOpenAIToolCalls(message["tool_calls"]) else null
                }
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
        usage: LLMUsage,
        outputItems: List<Map<String, Any?>> = emptyList()
    ): Map<String, Any?> {
        val status = finishReason ?: "completed"
        val finalOutput = if (outputItems.isNotEmpty()) {
            outputItems
        } else {
            listOf(
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
            )
        }

        return mapOf(
            "id" to "synthetic_${UUID.randomUUID()}",
            "model" to model,
            "status" to status,
            "output" to finalOutput,
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
                key = ConfigKeys.PROVIDER_OPENAI_API_KEY.key,
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

                // Get static definition from ModelDefinitions or synthesize for unknown models.
                val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("openai", modelId)
                    ?: run {
                        @Suppress("UNCHECKED_CAST")
                        val contextLength = (modelData["context_length"] as? Number)?.toInt() ?: DEFAULT_CONTEXT_SIZE

                        logger.warn {
                            "[OPENAI] Model $modelId not in registry — using synthetic definition (context=$contextLength)"
                        }

                        pl.jclab.refio.core.llm.ModelDefinitions.syntheticDefinitionFor(
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

    private fun isToolsNotSupportedError(httpStatus: Int, errorMessage: String): Boolean {
        if (httpStatus != 400 && httpStatus != 422) return false
        val lower = errorMessage.lowercase()
        val mentionsTooling =
            lower.contains("tools") ||
                lower.contains("tool_choice") ||
                lower.contains("tool calling") ||
                lower.contains("function calling") ||
                lower.contains("function '") ||
                lower.contains("schema for function")
        val unsupportedShape =
            lower.contains("not supported") ||
                lower.contains("unsupported") ||
                lower.contains("unknown parameter") ||
                lower.contains("unexpected parameter") ||
                lower.contains("invalid parameter") ||
                lower.contains("invalid schema") ||
                lower.contains("invalid_function_parameters") ||
                lower.contains("additionalproperties")
        return mentionsTooling && unsupportedShape
    }

    override suspend fun close() {
        if (ownsHttpClient) {
            client.close()
        }
    }
}

package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ReasoningEffort
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.NativeToolCall
import pl.jclab.refio.core.llm.NativeToolCallDelta
import pl.jclab.refio.core.llm.StreamChunk
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
import pl.jclab.refio.core.services.OllamaRequestGate
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.services.ConfigService

/**
 * Adapter for Ollama local models.
 *
 * Provides integration with locally-running Ollama server (localhost:11434).
 * Uses Ktor HTTP client to call Ollama REST API.
 */
class OllamaAdapter(
    model: String = "qwen2.5:7b",
    baseUrlOverride: String? = null,
    private val configService: pl.jclab.refio.core.services.ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null,
    httpClientOverride: HttpClient? = null
) : BaseLLMAdapter(model, "ollama") {

    private val logger = dualLogger("OllamaAdapter")
    private val baseUrl: String = baseUrlOverride?.takeIf { it.isNotBlank() }
        ?: configService?.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT)
        ?: System.getProperty("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("OLLAMA_ENDPOINT")?.takeIf { it.isNotBlank() }
        ?: System.getenv("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: DEFAULT_BASE_URL

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:11434"
        const val CHAT_ENDPOINT = "/api/chat"
        const val TAGS_ENDPOINT = "/api/tags"

        /** Floor for num_predict so the model can always produce at least a short reply. */
        const val OLLAMA_MIN_OUTPUT_TOKENS = 512

        /** Headroom inside num_ctx beyond estimated input — accounts for tokenizer drift. */
        const val OLLAMA_OUTPUT_SAFETY_MARGIN = 256
    }

    /**
     * Estimate how many tokens the rendered chat payload will consume on the server.
     * Uses the shared [pl.jclab.refio.core.services.PromptTokenEstimator.estimateBase] ratio
     * so this number is comparable with the budget math done upstream by [ContextService].
     *
     * Includes message content, native tool schemas (rough JSON serialization), and a small
     * per-message overhead for role tokens. Not exact — Ollama's actual tokenization differs
     * by model — but consistently sized so the warning fires before silent truncation.
     */
    /**
     * Render the conversation the way Ollama's chat API expects it.
     *
     * An assistant turn keeps its `tool_calls` in the structured field rather than as prose, so a
     * model that answered with a bare call (no text, reasoning in its thinking channel) still sees
     * that it made that call. Ollama wants the arguments as an object, unlike the OpenAI shape
     * where they are a JSON string; an unparsable argument string is passed through verbatim
     * rather than dropping the call.
     */
    internal fun toOllamaMessages(
        systemMessages: List<String>,
        messages: List<LLMMessage>,
    ): List<Map<String, Any>> = buildList {
        systemMessages.filter { it.isNotBlank() }.forEach { sysMsg ->
            add(mapOf<String, Any>("role" to "system", "content" to sysMsg))
        }
        for (msg in messages.filter { it.role != "system" }) {
            val entry = mutableMapOf<String, Any>(
                "role" to msg.role,
                "content" to msg.textOnlyContent(),
            )
            if (msg.toolCalls.isNotEmpty()) {
                entry["tool_calls"] = msg.toolCalls.map { call ->
                    mapOf(
                        "function" to mapOf(
                            "name" to call.name,
                            "arguments" to parseToolArguments(call.argumentsJson),
                        )
                    )
                }
            }
            add(entry)
        }
    }

    private fun parseToolArguments(argumentsJson: String): Any {
        if (argumentsJson.isBlank()) {
            return emptyMap<String, Any>()
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(argumentsJson, Map::class.java) as? Map<String, Any> ?: argumentsJson
        } catch (e: Exception) {
            logger.debug { "[OLLAMA] tool-call arguments are not a JSON object, sending verbatim: ${e.message}" }
            argumentsJson
        }
    }

    internal fun estimateOllamaInputTokens(
        messages: List<Map<String, Any>>,
        tools: List<ToolSchema>?,
    ): Int {
        val messageChars = messages.sumOf { (it["content"]?.toString()?.length ?: 0) + 10 }
        val toolChars = tools?.sumOf { schema ->
            schema.name.length + schema.description.length + schema.parametersJsonSchema.toString().length
        } ?: 0
        // Pass [model] so dense local tokenizers (qwen/llama ~3.2 chars/token) are not under-counted
        // by the flat 3.5 default - otherwise the overflow guard misses real num_ctx overflows.
        return pl.jclab.refio.core.services.PromptTokenEstimator.estimateBase("x".repeat(messageChars + toolChars), model)
    }

    // Get timeout from ConfigService (fallback to 120s for Ollama local models)
    private val timeout: Long
        get() = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.TOOL_EXECUTION_TIMEOUT.default.toLong() * 1000L

    private val client = httpClientOverride ?: run {
        val socketTimeoutMs = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
            ?: ConfigKeys.TOOL_EXECUTION_TIMEOUT.default.toLong() * 1000L
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
        logger.info { "[OLLAMA] Sending ${if (streaming) "streaming" else "standard"} chat request: model=$model, messages=${messages.size}, systemMessages=${systemMessages.size}" }

        return try {
            chatInternal(messages, systemMessages, maxTokens, temperature, streaming, onStreamChunk, kwargs)
        } catch (e: CancellationException) {
            // Stream aborted by a guardrail (see core/llm/streaming/) — must propagate
            // so the caller can see StreamAbortedException instead of RefioError.LLMError.
            throw e
        } catch (e: Exception) {
            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e, baseUrl)
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
        // Prepare messages
        val ollamaMessages = toOllamaMessages(systemMessages, messages)

        val responseFormat = kwargs["response_format"] as? Map<*, *>
        val jsonMode = responseFormat?.get("type") == "json_object"
        // Ollama's `think` is a plain on/off with no magnitude, so any effort level enables it.
        val thinkingRequested = ReasoningEffort.fromThinkingKwarg(kwargs["thinking"]).isOn
        @Suppress("UNCHECKED_CAST")
        val nativeTools = kwargs["native_tools"] as? List<ToolSchema>

        val requestBody = buildOllamaRequestBody(
            ollamaMessages = ollamaMessages,
            jsonMode = jsonMode,
            thinkingRequested = thinkingRequested,
            streaming = streaming,
            maxTokens = maxTokens,
            temperature = temperature,
            tools = nativeTools
        )

        val requestJson = gson.toJson(requestBody)
        val requestId = UUID.randomUUID().toString()
        val logPrefix = "[OLLAMA][$requestId]"
        logger.debug { "$logPrefix Request: ${SecureLogger.redactAndTruncate(requestJson)}" }

        val startTime = System.currentTimeMillis()

        val forceNonStreamingForJsonMode = jsonMode && streaming && onStreamChunk != null

        if (forceNonStreamingForJsonMode) {
            logger.info {
                "[OLLAMA] JSON mode with streaming requested - using non-streaming request to avoid Ollama EOF on some models"
            }
            // Force stream=false for fallback standard request.
            // Otherwise Ollama may still return NDJSON (application/x-ndjson).
            val nonStreamingRequestBody = requestBody.toMutableMap().apply {
                this["stream"] = false
            }
            val nonStreamingRequestJson = gson.toJson(nonStreamingRequestBody)
            val response = executeStandard(nonStreamingRequestBody, nonStreamingRequestJson, startTime, logPrefix)

            // Preserve streaming UI behavior by emitting a single accumulated chunk + final usage chunk.
            if (response.content.isNotEmpty()) {
                onStreamChunk?.invoke(
                    StreamChunk(
                        delta = response.content,
                        finishReason = null
                    )
                )
            }
            onStreamChunk?.invoke(
                StreamChunk(
                    delta = "",
                    finishReason = response.finishReason,
                    usage = response.usage
                )
            )
            return response
        }

        return if (streaming && onStreamChunk != null) {
            // Streaming mode
            executeStreaming(requestBody, requestJson, startTime, onStreamChunk, logPrefix)
        } else {
            // Standard mode
            executeStandard(requestBody, requestJson, startTime, logPrefix)
        }
    }

    /**
     * Builds the Ollama `/api/chat` request body. Extracted as `internal` so it can be unit-tested
     * without spinning up the HTTP client.
     *
     * Important: the `think` key is ALWAYS set explicitly (true or false). Skipping it lets thinking
     * models like qwen3 fall back to their built-in default (think=true), which produces empty
     * `content` chunks when Refio expects structured JSON. See AgentTurnLoop empty-content retries.
     */
    internal fun buildOllamaRequestBody(
        ollamaMessages: List<Map<String, Any>>,
        jsonMode: Boolean,
        thinkingRequested: Boolean,
        streaming: Boolean,
        maxTokens: Int?,
        temperature: Double,
        tools: List<ToolSchema>? = null
    ): Map<String, Any> {
        return buildMap {
            put("model", model)
            put("messages", ollamaMessages)
            put("stream", streaming)

            if (!tools.isNullOrEmpty()) {
                put("tools", buildOllamaToolsArray(tools))
                logger.info { "[OLLAMA][NATIVE_TOOLS] Sending ${tools.size} tool schemas: ${tools.map { it.name }}" }
            }

            // Keep model in GPU memory to avoid loading delays.
            val keepAlive = configService?.getTyped(ConfigKeys.PROVIDER_OLLAMA_KEEP_ALIVE)
                ?: ConfigKeys.PROVIDER_OLLAMA_KEEP_ALIVE.default
            put("keep_alive", keepAlive)

            if (jsonMode) {
                put("format", "json")
                logger.info { "[OLLAMA] Enabled JSON mode" }
            }

            // ALWAYS pass `think` explicitly. For thinking-capable models (qwen3, deepseek-r1, gpt-oss)
            // omitting this key causes Ollama to default to think=true, which can yield empty content.
            put("think", thinkingRequested)
            if (thinkingRequested) {
                logger.info { "[OLLAMA] Enabled thinking mode for $model" }
            }

            put("options", buildMap {
                put("temperature", temperature)

                // ModelWindow.resolveProvider handles the user override + default in one
                // place. Falls back to DEFAULT_CONTEXT_SIZE only if configService is null
                // (standalone bootstrap with no config bound).
                val contextSize = configService?.let {
                    pl.jclab.refio.core.llm.ModelWindow.resolveProvider("ollama", it, taskId)
                } ?: DEFAULT_CONTEXT_SIZE
                put("num_ctx", contextSize)

                // Pre-flight estimate of how many tokens the rendered prompt will consume.
                // Ollama silently truncates from the head when input exceeds num_ctx — we want a
                // loud warning before that happens, and we want num_predict to leave room for the
                // input rather than reserving the entire window for output.
                val estimatedInputTokens = estimateOllamaInputTokens(ollamaMessages, tools)
                if (estimatedInputTokens > contextSize) {
                    logger.warn {
                        "[OLLAMA_CONTEXT_OVERFLOW] Estimated input ~${estimatedInputTokens} tokens " +
                            "exceeds num_ctx=$contextSize for model=$model. Ollama will silently " +
                            "truncate from the head — expect empty or low-quality output. " +
                            "Increase providers.ollama.ollama_context_size or reduce the prompt."
                    }
                    // Surface the silent truncation into run.json. Pre-send
                    // estimate is the ONLY reliable signal for Ollama: its returned prompt_eval_count
                    // is already post-truncation, so a returned-usage check would never fire.
                    taskId?.let { pl.jclab.refio.core.debug.ContextOverflowTracker.markOverflow(it) }
                }

                val maxOutputLimit = configService?.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, taskId)
                    ?: ConfigKeys.MAX_OUTPUT_SIZE.default
                // An explicit caller request (e.g. advance_code_editing) is honored up to the model
                // limit / context budget below; MAX_OUTPUT_SIZE is only the default when no value is
                // passed, and the ceiling for unknown models. num_ctx (outputBudget) is the real cap.
                val requestedMaxTokens = if (maxTokens != null && maxTokens > 0) maxTokens else maxOutputLimit
                val modelLimit =
                    pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("ollama", model)?.maxOutputTokens

                // Clamp num_predict so input + output fits inside num_ctx with a safety margin.
                // Previously num_predict defaulted to MAX_OUTPUT_SIZE (16384) which, with a
                // 16384 num_ctx, left zero tokens for input — Ollama then truncated input and
                // the model generated nothing useful. Floor at 512 so the model can always emit
                // at least a short reply or error.
                val outputBudget = (contextSize - estimatedInputTokens - OLLAMA_OUTPUT_SAFETY_MARGIN)
                    .coerceAtLeast(OLLAMA_MIN_OUTPUT_TOKENS)
                val effectiveMaxTokens = when {
                    modelLimit != null && modelLimit > 0 -> minOf(requestedMaxTokens, modelLimit, outputBudget)
                    else -> minOf(requestedMaxTokens, maxOutputLimit, outputBudget)
                }
                if (effectiveMaxTokens < requestedMaxTokens) {
                    logger.warn {
                        "[OLLAMA_NUM_PREDICT_CLAMPED] Requested num_predict=$requestedMaxTokens " +
                            "clamped to $effectiveMaxTokens (num_ctx=$contextSize, " +
                            "estimatedInput=$estimatedInputTokens, modelLimit=${modelLimit ?: "n/a"})"
                    }
                }
                put("num_predict", effectiveMaxTokens)
                logger.info {
                    "[OLLAMA] Using num_predict=$effectiveMaxTokens, num_ctx=$contextSize, " +
                        "estInput=$estimatedInputTokens, temp=$temperature " +
                        "(requested=$maxTokens, configLimit=$maxOutputLimit, modelLimit=${modelLimit ?: "n/a"})"
                }
            })
        }
    }

    private suspend fun executeStandard(
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        logPrefix: String
    ): LLMResponse {
        var httpStatus: Int? = null

        try {
            // Make HTTP request
            logger.info {
                "$logPrefix Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${
                    SecureLogger.redactAndTruncate(
                        requestJson
                    )
                }"
            }
            val httpResponse = OllamaRequestGate.withPermit(baseUrl) {
                client.post("$baseUrl$CHAT_ENDPOINT") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
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

                // Extract error details from Ollama error response
                val errorMessage = response["error"] as? String ?: "Unknown error"
                val fullErrorMessage = "Ollama API error (HTTP $httpStatus): $errorMessage"

                logger.error { "$logPrefix $fullErrorMessage" }

                // Log error to API logs
                logger.apiError(
                    provider = provider,
                    model = model,
                    endpoint = "$baseUrl$CHAT_ENDPOINT",
                    requestJson = requestJson,
                    httpStatus = httpStatus,
                    error = Exception(fullErrorMessage),
                    latencyMs = latencyMs,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    source = source
                )

                if (requestBody.containsKey("tools") && isToolsNotSupportedError(httpStatus, errorMessage)) {
                    throw pl.jclab.refio.core.llm.ToolsNotSupportedException(fullErrorMessage)
                }
                throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus, fullErrorMessage)
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            // Extract usage (Ollama provides eval_count, prompt_eval_count)
            val evalCount = (response["eval_count"] as? Number)?.toInt() ?: 0
            val promptEvalCount = (response["prompt_eval_count"] as? Number)?.toInt() ?: 0

            val usage = LLMUsage(
                inputTokens = promptEvalCount,
                outputTokens = evalCount,
                totalTokens = promptEvalCount + evalCount
            )

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

            // Parse response
            if (response["message"] !is Map<*, *>) {
                throw RefioError.MalformedResponse(
                    provider = provider,
                    model = model,
                    reason = "Missing or non-object 'message' in Ollama response",
                    bodyPreview = gson.toJson(response)
                )
            }
            @Suppress("UNCHECKED_CAST")
            val messageMap = response["message"] as? Map<String, Any?> ?: emptyMap()
            var rawContent = messageMap["content"] as? String ?: ""
            val rawThinking = messageMap["thinking"] as? String  // Reasoning models (gpt-oss)
            val rawToolCalls = extractOllamaToolCalls(messageMap)

            val toolsWereRequested = requestBody.containsKey("tools")
            val nativeToolCalls = if (toolsWereRequested) {
                parseNativeOllamaToolCalls(rawToolCalls).also { calls ->
                    logger.info { "$logPrefix [NATIVE_TOOLS_RESPONSE] calls=${calls.size}" }
                }
            } else null

            if (nativeToolCalls == null && rawContent.isBlank() && rawToolCalls.isNotEmpty()) {
                rawContent = convertToolCallsToCanonicalJson(rawToolCalls)
                logger.info {
                    "$logPrefix [TOOL_CALLS_NORMALIZED] Converted Ollama message.tool_calls to canonical JSON content " +
                        "(count=${rawToolCalls.size}, contentLength=${rawContent.length})"
                }
            }

            // Debug: Log thinking content for gpt-oss models
            if (!rawThinking.isNullOrEmpty()) {
                logger.info { "$logPrefix [THINKING_DEBUG] Raw thinking preview (150 chars): ${rawThinking.take(150)}" }
                logger.info { "$logPrefix [THINKING_DEBUG] Raw thinking length: ${rawThinking.length}" }
                logger.info { "$logPrefix [THINKING_DEBUG] Raw content length: ${rawContent.length}" }
            }

            // Process thinking field to extract actual content and thinking
            val thinkingWasRequested = requestBody["think"] as? Boolean ?: false
            val (finalContent, finalThinking) = processThinkingField(rawContent, rawThinking, thinkingWasRequested)

            logger.info {
                "$logPrefix [CONTENT_DEBUG] Final content length: ${finalContent.length}, " +
                "final thinking length: ${finalThinking?.length ?: 0}, " +
                "content starts with JSON: ${finalContent.trim().startsWith("{")}"
            }

            val doneReason = response["done_reason"] as? String

            logger.info {
                "$logPrefix Response processed: tokens_in=${usage.inputTokens}, " +
                        "tokens_out=${usage.outputTokens}, done_reason=$doneReason" +
                        ", content=${finalContent.length}, thinking=${finalThinking?.length ?: 0}"
            }

            return LLMResponse(
                content = finalContent,
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,  // Free local execution
                finishReason = doneReason,
                rawResponse = response,
                thinking = finalThinking,
                nativeToolCalls = nativeToolCalls
            )

        } catch (e: Exception) {
            // Error #15: Log error (console + database)
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

            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e, baseUrl)
        }
    }

    /**
     * Execute a streaming request and fold the NDJSON chunks into one [LLMResponse].
     *
     * Makes exactly one attempt. Retrying a failed call is the job of the retry handler wrapping
     * the turn, which knows whether any chunk already reached the UI - this loop has usually
     * emitted content by the time it fails, so re-streaming from here would duplicate output.
     * A cold model shows up as a connect or read timeout, which that handler already retries.
     *
     * `done_reason` (including "load") is reported as the response's finish reason, not acted on.
     */
    private suspend fun executeStreaming(
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        logPrefix: String
    ): LLMResponse {
        val rawContentBuilder = StringBuilder()
        val rawThinkingBuilder = StringBuilder()
        val rawToolCalls = mutableListOf<Map<String, Any?>>()
        var inputTokens = 0
        var outputTokens = 0
        var httpStatus: Int? = null
        var finalDoneReason: String? = null
        var receivedDoneChunk = false
        var lastRawChunkLine: String? = null

        try {
            // Make streaming HTTP request
            logger.info {
                "$logPrefix Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${
                    SecureLogger.redactAndTruncate(
                        requestJson
                    )
                }"
            }
            OllamaRequestGate.withPermit(baseUrl) {
                client.preparePost("$baseUrl$CHAT_ENDPOINT") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }.execute { httpResponse ->
                httpStatus = httpResponse.status.value

                // Check for error response before reading stream
                if (httpStatus !in 200..299) {
                    val errorBody = httpResponse.body<String>()
                    val latencyMs = (System.currentTimeMillis() - startTime).toInt()

                    val errorMessage = "Ollama API error (HTTP $httpStatus): $errorBody"
                    logger.error { "$logPrefix $errorMessage" }

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

                    if (requestBody.containsKey("tools") &&
                        httpStatus == 400 &&
                        errorBody.contains("does not support tools", ignoreCase = true)
                    ) {
                        throw pl.jclab.refio.core.llm.ToolsNotSupportedException(errorMessage)
                    }
                    throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus ?: 500, errorMessage)
                }

                val channel: io.ktor.utils.io.ByteReadChannel = httpResponse.body()

                // Read NDJSON stream line by line.
                // Watchdog: a suspended `readUTF8Line` will not see the cancellation flag until
                // the next NDJSON chunk arrives, which can be 30+ s on a slow model. Forcibly
                // cancel the channel from a sibling coroutine so the read returns immediately.
                var chunkCount = 0
                coroutineScope {
                    val cancelWatchdog = launch {
                        while (isActive) {
                            if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                                logger.info { "$logPrefix Cancellation detected — forcibly closing read channel" }
                                channel.cancel(CancellationException("User requested cancellation"))
                                break
                            }
                            delay(100)
                        }
                    }
                    try {
                while (!channel.isClosedForRead) {
                    // Check cancellation - break to return partial response
                    if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                        logger.info { "$logPrefix Streaming cancelled by user - returning partial response" }
                        finalDoneReason = "cancelled"
                        break
                    }

                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank()) continue
                    lastRawChunkLine = line

                    chunkCount++
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val chunk = gson.fromJson(line, Map::class.java) as Map<String, Any?>

                        // Extract message content
                        @Suppress("UNCHECKED_CAST")
                        val message = chunk["message"] as? Map<String, Any?>
                        val content = message?.get("content") as? String ?: ""
                        val thinking = message?.get("thinking") as? String ?: ""  // Reasoning field
                        if (message != null) {
                            // Accumulate across chunks instead of replacing: a server that splits
                            // parallel calls over several chunks would otherwise leave only the
                            // last one and the turn would silently skip the rest of the work.
                            // Calls already collected are ignored, so a server re-sending the
                            // cumulative array cannot make the same tool run twice.
                            val newToolCalls = extractOllamaToolCalls(message)
                                .filterNot { rawToolCalls.contains(it) }
                            if (newToolCalls.isNotEmpty()) {
                                // Index continues across chunks so a consumer merging deltas by
                                // index does not fold two separate calls into one.
                                val firstIndex = rawToolCalls.size
                                rawToolCalls.addAll(newToolCalls)
                                // Ollama emits the whole tool call at once (no per-arg streaming),
                                // so surface one progress snapshot per call.
                                parseNativeOllamaToolCalls(newToolCalls).forEachIndexed { idx, call ->
                                    onStreamChunk(
                                        StreamChunk(
                                            delta = "",
                                            toolCallDelta = NativeToolCallDelta(
                                                index = firstIndex + idx,
                                                nameDelta = call.name,
                                                argumentsDelta = call.argumentsJson,
                                            ),
                                        )
                                    )
                                }
                            }
                        }

                        // Debug: Log first chunk structure
                        if (chunkCount == 1) {
                            logger.info { "$logPrefix First chunk keys: ${chunk.keys.joinToString()}, has_message=${chunk.containsKey("message")}" }
                            if (message != null) {
                                logger.info { "$logPrefix Message keys: ${message.keys.joinToString()}, has_content=${message.containsKey("content")}, has_thinking=${message.containsKey("thinking")}" }
                                if (thinking.isNotEmpty()) {
                                    logger.info { "$logPrefix First thinking preview (150 chars): ${thinking.take(150)}" }
                                }
                            }
                        }

                        // Collect raw content and thinking (will be processed after stream completes)
                        if (content.isNotEmpty()) {
                            rawContentBuilder.append(content)
                            // For UI streaming, emit content as-is during streaming
                            onStreamChunk(
                                StreamChunk(
                                    delta = content,
                                    finishReason = null
                                )
                            )
                        }

                        // Also collect and emit thinking for UI display
                        if (thinking.isNotEmpty()) {
                            rawThinkingBuilder.append(thinking)
                            onStreamChunk(
                                StreamChunk(
                                    delta = "",  // No content delta
                                    thinking = thinking,  // Thinking delta
                                    finishReason = null
                                )
                            )
                        }

                        // Check if done
                        val done = chunk["done"] as? Boolean ?: false
                        if (done) {
                            receivedDoneChunk = true
                            // Extract usage from final chunk
                            inputTokens = (chunk["prompt_eval_count"] as? Number)?.toInt() ?: 0
                            outputTokens = (chunk["eval_count"] as? Number)?.toInt() ?: 0
                            finalDoneReason = chunk["done_reason"] as? String

                            logger.info {
                                "$logPrefix Stream complete: input=$inputTokens, output=$outputTokens, " +
                                "rawContent=${rawContentBuilder.length}, rawThinking=${rawThinkingBuilder.length}"
                            }
                            break
                        }
                    } catch (e: CancellationException) {
                        // Let stream abort (guardrail trip) propagate out of the loop.
                        throw e
                    } catch (e: Exception) {
                        logger.warn { "$logPrefix Failed to parse chunk : ${line.take(100)} - ${e.message}" }
                        continue
                    }
                }
                    } catch (e: CancellationException) {
                        // Watchdog cancelled the channel because the user clicked Stop.
                        // Convert to a clean partial-response path instead of bubbling up as an error.
                        if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                            logger.info { "$logPrefix Stream read cancelled by watchdog - returning partial response" }
                            finalDoneReason = "cancelled"
                        } else {
                            throw e
                        }
                    } finally {
                        cancelWatchdog.cancel()
                    }
                }
                }
            }

            if (!receivedDoneChunk && finalDoneReason != "cancelled") {
                val durationMs = System.currentTimeMillis() - startTime
                // The channel closed without the trailing done=true sentinel. Only hard-fail when
                // NOTHING usable arrived — if we already captured a tool call, content, or thinking,
                // discarding it would turn a usable turn into a failure (and a retry would pay the
                // full model latency again for the same likely-truncated stream). Ollama emits whole
                // tool calls in a single chunk (not incrementally), so a captured call is complete.
                val haveUsableOutput = rawToolCalls.isNotEmpty() ||
                    rawContentBuilder.isNotEmpty() ||
                    rawThinkingBuilder.isNotEmpty()
                if (!haveUsableOutput) {
                    // Server closed the NDJSON channel before emitting any content/tool call.
                    // Common causes: remote Ollama restart, idle proxy timeout, network drop, model crash.
                    // Marked retryable by LLMRetryHandler.shouldRetryByMessage via "stream ended before".
                    throw LLMErrorMapper.fromThrowable(
                        provider,
                        model,
                        timeout,
                        IllegalStateException(
                            "Ollama stream ended before done=true final chunk " +
                                "(contentBytes=${rawContentBuilder.length}, " +
                                "thinkingBytes=${rawThinkingBuilder.length}, durationMs=$durationMs)"
                        )
                    )
                }
                // Finalize gracefully with what we captured. Usage stats stay 0 because the final
                // chunk that carries prompt_eval_count/eval_count never arrived. finishReason is left
                // null (not "stop"/"length") so downstream truncation handling is not falsely triggered.
                logger.warn {
                    "$logPrefix Stream ended before done=true but usable output was captured — " +
                        "finalizing anyway (toolCalls=${rawToolCalls.size}, " +
                        "contentBytes=${rawContentBuilder.length}, " +
                        "thinkingBytes=${rawThinkingBuilder.length}, durationMs=$durationMs)"
                }
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            val usage = LLMUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )

            // Emit final chunk with usage
            onStreamChunk(
                StreamChunk(
                    delta = "",
                    finishReason = finalDoneReason,
                    usage = usage
                )
            )

            // Create synthetic response JSON for logging (use raw content at this point)
            val syntheticResponse = mapOf(
                "message" to mapOf("role" to "assistant", "content" to rawContentBuilder.toString()),
                "done" to true,
                "done_reason" to finalDoneReason,
                "prompt_eval_count" to inputTokens,
                "eval_count" to outputTokens,
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
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus ?: 200,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = 0.0,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source,
                rawApiResponseChunk = lastRawChunkLine
            )

            logger.info { "$logPrefix Streaming completed in ${latencyMs}ms, tokens=$inputTokens/$outputTokens" }

            // Process raw thinking field to extract actual content (post-processing)
            var rawContent = rawContentBuilder.toString()
            val rawThinking = rawThinkingBuilder.takeIf { it.isNotEmpty() }?.toString()

            val toolsWereRequestedStream = requestBody.containsKey("tools")
            val streamNativeToolCalls = if (toolsWereRequestedStream) {
                parseNativeOllamaToolCalls(rawToolCalls).also { calls ->
                    logger.info { "$logPrefix [NATIVE_TOOLS_RESPONSE] (stream) calls=${calls.size}" }
                }
            } else null

            if (streamNativeToolCalls == null && rawContent.isBlank() && rawToolCalls.isNotEmpty()) {
                rawContent = convertToolCallsToCanonicalJson(rawToolCalls)
                logger.info {
                    "$logPrefix [TOOL_CALLS_NORMALIZED] Converted Ollama streamed message.tool_calls to canonical JSON content " +
                        "(count=${rawToolCalls.size}, contentLength=${rawContent.length})"
                }
            }

            logger.info {
                "$logPrefix [STREAM_CONTENT_DEBUG] Raw content length: ${rawContent.length}, " +
                "raw thinking length: ${rawThinking?.length ?: 0}"
            }

            val thinkingWasRequested = requestBody["think"] as? Boolean ?: false
            val (finalContent, finalThinking) = processThinkingField(rawContent, rawThinking, thinkingWasRequested)

            logger.info {
                "$logPrefix [STREAM_CONTENT_DEBUG] Final content length: ${finalContent.length}, " +
                "final thinking length: ${finalThinking?.length ?: 0}, " +
                "content starts with JSON: ${finalContent.trim().startsWith("{")}"
            }

            return LLMResponse(
                content = finalContent,
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,  // Free local execution
                finishReason = finalDoneReason,
                rawResponse = syntheticResponse,
                thinking = finalThinking,
                nativeToolCalls = streamNativeToolCalls
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
            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e, baseUrl)
        }
    }

    override fun estimateCost(usage: LLMUsage): Double {
        // Ollama is free (local execution)
        return 0.0
    }

    /**
     * Lists all available models from Ollama /api/tags endpoint.
     *
     * @return List of ModelConfig objects with model metadata
     * @throws Exception if connection fails or response is invalid
     */
    suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        logger.info { "[OLLAMA] Fetching available models from $baseUrl$TAGS_ENDPOINT" }

        try {
            val httpResponse = OllamaRequestGate.withPermit(baseUrl) {
                client.get("$baseUrl$TAGS_ENDPOINT")
            }
            val response: Map<String, Any?> = httpResponse.body()

            @Suppress("UNCHECKED_CAST")
            val modelsData = response["models"] as? List<Map<String, Any?>> ?: emptyList()

            if (modelsData.isEmpty()) {
                logger.warn { "[OLLAMA] No models found. Is Ollama running?" }
                return@withContext emptyList()
            }

            // Get context size from ConfigService (global setting for all Ollama models)
            val contextSize = configService?.let {
                pl.jclab.refio.core.llm.ModelWindow.resolveProvider("ollama", it)
            } ?: DEFAULT_CONTEXT_SIZE

            logger.info { "[OLLAMA] Using context size: $contextSize tokens (from config)" }

            val modelConfigs = modelsData.mapNotNull { modelData ->
                val modelName = modelData["name"] as? String ?: return@mapNotNull null

                // Get definition from registry or synthesize for unknown models (new releases).
                val baseDefinition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("ollama", modelName)
                    ?: run {
                        logger.warn {
                            "[OLLAMA] Model $modelName not in registry — using synthetic definition with defaults (context=$contextSize)"
                        }
                        pl.jclab.refio.core.llm.ModelDefinitions.syntheticDefinitionFor(
                            provider = "ollama",
                            modelId = modelName,
                            maxContext = contextSize
                        )
                    }

                // Always override maxContext with configured value for Ollama models
                val definition = baseDefinition.copy(maxContext = contextSize)

                logger.debug { "[OLLAMA] Found model: $modelName (context: ${definition.maxContext})" }

                definition.toModelConfig()
            }

            logger.info { "[OLLAMA] Found ${modelConfigs.size} models" }
            return@withContext modelConfigs

        } catch (e: Exception) {
            logger.error(e) { "[OLLAMA] Failed to fetch models: ${e.message}" }
            throw LLMErrorMapper.listModelsFailure(provider, e)
        }
    }

    /**
     * Keep content and thinking separated.
     * We do not copy thinking into content - JSON/tool parsing is driven by content only.
     *
     * @return Pair of (content, thinking)
     */
    private fun processThinkingField(rawContent: String, rawThinking: String?, thinkingRequested: Boolean = true): Pair<String, String?> {
        // If we have content, use it directly and keep thinking separate
        if (rawContent.isNotBlank()) {
            return Pair(rawContent, rawThinking?.takeIf { it.isNotBlank() })
        }

        // Content is empty, keep thinking separate (don't duplicate it into content)
        if (rawThinking.isNullOrBlank()) {
            return Pair("", null)
        }

        // When thinking was NOT requested but model produced thinking with empty content,
        // move thinking to content so the caller gets usable output.
        // This handles models like qwen3.5 that generate thinking tokens even without think=true.
        if (!thinkingRequested) {
            logger.info { "[OLLAMA] Thinking not requested but model returned thinking with empty content — moving thinking to content" }
            return Pair(rawThinking, null)
        }

        // Keep fields separate - content is empty, thinking has the value
        return Pair("", rawThinking)
    }

    private fun buildOllamaToolsArray(tools: List<ToolSchema>): List<Map<String, Any>> {
        return tools.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parametersJsonSchema,
                )
            )
        }
    }

    internal fun parseNativeOllamaToolCalls(rawCalls: List<Map<String, Any?>>): List<NativeToolCall> {
        return rawCalls.mapNotNull { call ->
            @Suppress("UNCHECKED_CAST")
            val function = call["function"] as? Map<String, Any?> ?: return@mapNotNull null
            val name = function["name"] as? String ?: return@mapNotNull null
            val argsRaw = function["arguments"]
            val argumentsJson = when (argsRaw) {
                is String -> argsRaw
                is Map<*, *> -> gson.toJson(argsRaw)
                null -> "{}"
                else -> gson.toJson(argsRaw)
            }
            NativeToolCall(
                id = java.util.UUID.randomUUID().toString(),
                name = normalizeToolName(name),
                argumentsJson = argumentsJson,
            )
        }
    }

    internal fun extractOllamaToolCalls(messageMap: Map<String, Any?>): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val toolCalls = messageMap["tool_calls"] as? List<Map<String, Any?>> ?: return emptyList()
        return toolCalls
    }

    private fun convertToolCallsToCanonicalJson(toolCalls: List<Map<String, Any?>>): String {
        val actions = toolCalls.mapNotNull { toolCall ->
            @Suppress("UNCHECKED_CAST")
            val function = toolCall["function"] as? Map<String, Any?> ?: return@mapNotNull null

            val rawName = function["name"] as? String ?: return@mapNotNull null
            val normalizedName = normalizeToolName(rawName)
            val arguments = normalizeToolArguments(function["arguments"])

            mapOf(
                "tool" to normalizedName,
                "arguments" to arguments
            )
        }

        val canonical = mapOf(
            "actions" to actions,
            "response" to ""
        )
        return gson.toJson(canonical)
    }

    private fun normalizeToolName(rawName: String): String {
        val byDot = rawName.substringAfterLast('.')
        return byDot.substringAfterLast('/')
    }

    private fun normalizeToolArguments(rawArguments: Any?): Any {
        return when (rawArguments) {
            null -> emptyMap<String, Any?>()
            is Map<*, *> -> rawArguments.entries.associate { (k, v) -> k.toString() to v }
            is String -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(rawArguments, Map::class.java) as? Map<String, Any?> ?: emptyMap<String, Any?>()
                } catch (_: Exception) {
                    mapOf("raw" to rawArguments)
                }
            }
            else -> mapOf("value" to rawArguments.toString())
        }
    }

    private fun isToolsNotSupportedError(httpStatus: Int, errorMessage: String): Boolean {
        if (httpStatus != 400 && httpStatus != 422) return false
        val lower = errorMessage.lowercase()
        val mentionsTooling =
            lower.contains("\"tools\"") ||
                lower.contains("tool calling") ||
                lower.contains("function calling")
        val unsupportedShape =
            lower.contains("not supported") ||
                lower.contains("unsupported") ||
                lower.contains("unknown field") ||
                lower.contains("unknown parameter") ||
                lower.contains("unexpected")
        return mentionsTooling && unsupportedShape
    }

    override suspend fun close() {
        client.close()
    }
}

package pl.jclab.refio.core.llm

import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE

/**
 * API endpoint types for different model families.
 * Different models may require different API endpoints.
 */
enum class ApiEndpointType(val path: String) {
    /** Standard chat completions endpoint - used by most models */
    CHAT_COMPLETIONS("/chat/completions"),

    /** Responses endpoint - used by GPT-5.1 Codex models */
    RESPONSES("/responses"),

    /** Legacy text completions endpoint - deprecated but kept for compatibility */
    COMPLETIONS("/completions")
}

/**
 * API format types for request/response transformation.
 * Different endpoints use different request/response formats.
 */
enum class ApiFormat {
    /**
     * Chat Completions format:
     * - Request: {"messages": [{"role": "user", "content": "..."}]}
     * - Response: {"choices": [{"message": {"content": "..."}}]}
     */
    CHAT_COMPLETIONS,

    /**
     * Responses format (new OpenAI API):
     * - Request: {"input": "..." or [...]}
     * - Response: {"output": [{"type": "message", "content": [...]}]}
     */
    RESPONSES
}

/**
 * Central Model Registry - Single Source of Truth for LLM Model Definitions
 *
 * This object contains static definitions for all supported models with complete configuration:
 * - Capabilities (chat, vision, function calling, etc.)
 * - Pricing (per 1M tokens in USD - industry standard)
 * - Context limits and token limits
 * - Features (streaming, reasoning, thinking mode, etc.)
 * - Provider-specific parameter mappings
 *
 * Benefits:
 * - Single source of truth for model configuration
 * - Easy to add new models (single definition)
 * - Consistent behavior across adapters
 * - Fallback support for unknown models
 *
 * Usage:
 * ```
 * val definition = ModelDefinitions.getDefinition("openai", "gpt-4.1-mini")
 * val supportsStreaming = definition?.supportsStreaming ?: true
 * ```
 */
object ModelDefinitions {

    private const val GEMINI_MAX_CONTEXT = 128_000

    /**
     * OpenAI Models Registry
     *
     * Updated: 2025-12-08
     * Source: https://platform.openai.com/docs/models
     */
    val OPENAI_MODELS = mapOf(

        //
        // GPT 5.5
        //
        "gpt-5.5" to ModelDefinition(
            id = "gpt-5.5",
            name = "GPT-5.5",
            provider = "openai",
            description = "Best intelligence at scale for agentic, coding, and professional workflows",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 1_050_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 2.50,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "temperature"
            ),
            active = true
        ),
        //
        // GPT 5.4
        //
        "gpt-5.4" to ModelDefinition(
            id = "gpt-5.4",
            name = "GPT-5.4",
            provider = "openai",
            description = "Best intelligence at scale for agentic, coding, and professional workflows",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 1_050_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 2.50,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "temperature"
            ),
            active = true
        ),
        "gpt-5.4-mini" to ModelDefinition(
            id = "gpt-5.4-mini",
            name = "GPT-5.4 Mini",
            provider = "openai",
            description = "Balanced GPT-5.4 tier for general tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 0.25,
            costPer1MOutput = 2.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),
        "gpt-5.4-nano" to ModelDefinition(
            id = "gpt-5.4-nano",
            name = "GPT-5.4 Nano",
            provider = "openai",
            description = "Ultra cost-effective model for simple tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 0.05,
            costPer1MOutput = 0.40,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),

        //
        // GPT 5.2
        //
        "gpt-5.2" to ModelDefinition(
            id = "gpt-5.2",
            name = "GPT-5.2",
            provider = "openai",
            description = "GPT-5.2 general model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 1.75,
            costPer1MOutput = 14.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "temperature"
            ),
            active = true
        ),

        //
        // GPT 5.3 codex
        //
        "gpt-5.3-codex" to ModelDefinition(
            id = "gpt-5.3-codex",
            name = "GPT-5.3 Codex",
            provider = "openai",
            description = "The most capable agentic coding model to date",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.TEXT,
            maxContext = 400_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 1.75,
            costPer1MOutput = 14.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf(
                "max_tokens" to "max_output_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),

        //
        // GPT 5.2 codex
        //
        "gpt-5.2-codex" to ModelDefinition(
            id = "gpt-5.2-codex",
            name = "GPT-5.2 Codex",
            provider = "openai",
            description = "GPT-5.2 Codex full variant",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf(
                "max_tokens" to "max_output_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),
        "gpt-5.2-codex-max" to ModelDefinition(
            id = "gpt-5.2-codex-max",
            name = "GPT-5.2 Codex MAX",
            provider = "openai",
            description = "GPT-5.2 Codex MAX variant",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf(
                "max_tokens" to "max_output_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),
        "gpt-5.2-codex-mini" to ModelDefinition(
            id = "gpt-5.2-codex-mini",
            name = "GPT-5.2 Codex Mini",
            provider = "openai",
            description = "Specialized code generation model with built-in reasoning and extended context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 0.25,
            costPer1MOutput = 2.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf(
                "max_tokens" to "max_output_tokens"
            ),
            defaultParams = mapOf(
                "reasoning" to mapOf(
                    "effort" to "medium",
                )
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),

        //
        // GPT 5.1
        //
        "gpt-5.1" to ModelDefinition(
            id = "gpt-5.1",
            name = "GPT-5.1",
            provider = "openai",
            description = "GPT-5.1 general model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "temperature"
            ),
            active = true
        ),

        //
        // GPT 5
        //
        "gpt-5" to ModelDefinition(
            id = "gpt-5",
            name = "GPT-5",
            provider = "openai",
            description = "GPT-5 general model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "temperature"
            ),
            active = true
        ),
        "gpt-5-mini" to ModelDefinition(
            id = "gpt-5-mini",
            name = "GPT-5 Mini",
            provider = "openai",
            description = "Balanced performance and cost for general tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 0.25,
            costPer1MOutput = 2.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),
        "gpt-5-nano" to ModelDefinition(
            id = "gpt-5-nano",
            name = "GPT-5 Nano",
            provider = "openai",
            description = "Ultra cost-effective model for simple tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 0.05,
            costPer1MOutput = 0.40,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),

        //
        // GPT 5.1 codex
        //
        "gpt-5.1-codex" to ModelDefinition(
            id = "gpt-5.1-codex",
            name = "GPT-5.1 Codex",
            provider = "openai",
            description = "GPT-5.1 Codex full variant",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf(
                "max_tokens" to "max_output_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),
        "gpt-5.1-codex-max" to ModelDefinition(
            id = "gpt-5.1-codex-max",
            name = "GPT-5.1 Codex MAX",
            provider = "openai",
            description = "GPT-5.1 Codex MAX variant",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf(
                "max_tokens" to "max_output_tokens"
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),
        "gpt-5.1-codex-mini" to ModelDefinition(
            id = "gpt-5.1-codex-mini",
            name = "GPT-5.1 Codex Mini",
            provider = "openai",
            description = "Specialized code generation model with built-in reasoning and extended context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 0.25,
            costPer1MOutput = 2.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf(
                "max_tokens" to "max_output_tokens"
            ),
            defaultParams = mapOf(
                "reasoning" to mapOf(
                    "effort" to "medium",
                )
            ),
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            active = true
        ),

        //
        // GPT-4.1
        //
        "gpt-4.1" to ModelDefinition(
            id = "gpt-4.1",
            name = "GPT-4.1",
            provider = "openai",
            description = "GPT-4.1 general model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 2.00,
            costPer1MOutput = 8.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf(
            ),
            active = true
        ),
        "gpt-4.1-mini" to ModelDefinition(
            id = "gpt-4.1-mini",
            name = "GPT-4.1 Mini",
            provider = "openai",
            description = "Fast and cost-effective model for well-defined tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 0.40,
            costPer1MOutput = 1.60,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            paramMappings = mapOf(
            ),
            defaultParams = mapOf(
            ),
            active = true
        ),

        "gpt-4.1-nano" to ModelDefinition(
            id = "gpt-4.1-nano",
            name = "GPT-4.1 Nano",
            provider = "openai",
            description = "Ultra cost-effective GPT-4 variant",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 0.10,
            costPer1MOutput = 0.40,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            paramMappings = mapOf(
            ),
            active = true
        ),

        //
        // GPT-4o
        //
        "gpt-4o" to ModelDefinition(
            id = "gpt-4o",
            name = "GPT-4o",
            provider = "openai",
            description = "GPT-4o flagship multimodal model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 16_384,
            costPer1MInput = 2.50,
            costPer1MOutput = 10.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf(
                "temperature" to 0.7
            ),
            active = true
        ),
        "gpt-4o-mini" to ModelDefinition(
            id = "gpt-4o-mini",
            name = "GPT-4o Mini",
            provider = "openai",
            description = "Fast, cost-effective model for most tasks with multimodal support",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 16_384,
            costPer1MInput = 0.40,
            costPer1MOutput = 0.60,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf(
                "temperature" to 0.7
            ),
            active = true
        ),

        //
        // GPT O models
        //
        "o1-mini" to ModelDefinition(
            id = "o1-mini",
            name = "O1 Mini",
            provider = "openai",
            description = "Reasoning model for complex problem-solving",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 1.10,
            costPer1MOutput = 4.40,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            defaultParams = emptyMap(),
            active = true
        ),
        "o1" to ModelDefinition(
            id = "o1",
            name = "O1",
            provider = "openai",
            description = "Reasoning-focused O1 model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 15.00,
            costPer1MOutput = 60.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            active = true
        ),
        "o3" to ModelDefinition(
            id = "o3",
            name = "O3",
            provider = "openai",
            description = "Reasoning O3 model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 2.00,
            costPer1MOutput = 8.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            active = true
        ),
        "o3-mini" to ModelDefinition(
            id = "o3-mini",
            name = "O3 Mini",
            provider = "openai",
            description = "Reasoning O3 mini model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 1.10,
            costPer1MOutput = 4.40,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            active = true
        ),
        "o3-pro" to ModelDefinition(
            id = "o3-pro",
            name = "O3 Pro",
            provider = "openai",
            description = "Reasoning O3 Pro model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 20.00,
            costPer1MOutput = 80.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf(
                "frequency_penalty",
                "presence_penalty",
                "top_p",
                "temperature"
            ),
            paramMappings = mapOf(
                "max_tokens" to "max_completion_tokens"
            ),
            active = true
        ),

        // ---------------------------------------------
        // To Check
        // ---------------------------------------------
        "gpt-5-search-api" to ModelDefinition(
            id = "gpt-5-search-api",
            name = "GPT-5 Search API",
            provider = "openai",
            description = "Search-optimized GPT-5 endpoint (Standard $1.25 in / $10 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf("max_tokens" to "max_output_tokens"),
            removeParams = listOf("frequency_penalty", "presence_penalty", "top_p", "temperature"),
            active = true
        ),
        "computer-use-preview" to ModelDefinition(
            id = "computer-use-preview",
            name = "Computer Use Preview",
            provider = "openai",
            description = "GPT-5 computer-use tier for UI/control workflows (Standard $3.00 in / $12.00 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = 32_768,
            costPer1MInput = 3.00,
            costPer1MOutput = 12.00,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.RESPONSES,
            apiFormat = ApiFormat.RESPONSES,
            paramMappings = mapOf("max_tokens" to "max_output_tokens"),
            removeParams = listOf("frequency_penalty", "presence_penalty", "temperature"),
            active = true
        ),

        // O1 reasoning models (don't support streaming!)
        "o1-pro" to ModelDefinition(
            id = "o1-pro",
            name = "O1 Pro",
            provider = "openai",
            description = "Top-tier O1 Pro reasoning model (Standard $150 in / $600 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 150.00,
            costPer1MOutput = 600.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf("frequency_penalty", "presence_penalty", "top_p", "temperature"),
            active = true
        ),
        "o3-pro" to ModelDefinition(
            id = "o3-pro",
            name = "O3 Pro",
            provider = "openai",
            description = "Premium O3 reasoning tier (Standard $20 in / $80 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 20.00,
            costPer1MOutput = 80.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf("frequency_penalty", "presence_penalty", "top_p", "temperature"),
            paramMappings = mapOf("max_tokens" to "max_completion_tokens"),
            active = true
        ),
        "o3-deep-research" to ModelDefinition(
            id = "o3-deep-research",
            name = "O3 Deep Research",
            provider = "openai",
            description = "Extended-horizon O3 variant for deep research (Standard $10 in / $40 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 10.00,
            costPer1MOutput = 40.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf("frequency_penalty", "presence_penalty", "top_p", "temperature"),
            paramMappings = mapOf("max_tokens" to "max_completion_tokens"),
            active = true
        ),

        "o4-mini" to ModelDefinition(
            id = "o4-mini",
            name = "O4 Mini",
            provider = "openai",
            description = "Compact O4 reasoning tier (Standard $1.10 in / $4.40 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 1.10,
            costPer1MOutput = 4.40,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf("frequency_penalty", "presence_penalty", "top_p", "temperature"),
            paramMappings = mapOf("max_tokens" to "max_completion_tokens"),
            active = true
        ),
        "o4-mini-deep-research" to ModelDefinition(
            id = "o4-mini-deep-research",
            name = "O4 Mini Deep Research",
            provider = "openai",
            description = "Deep-research tuned O4 Mini (Standard $2.00 in / $8.00 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 65_536,
            costPer1MInput = 2.00,
            costPer1MOutput = 8.00,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            supportsThinking = true,
            removeParams = listOf("frequency_penalty", "presence_penalty", "top_p", "temperature"),
            paramMappings = mapOf("max_tokens" to "max_completion_tokens"),
            active = true
        ),
    )

    /**
     * Anthropic Models Registry
     *
     * Source: https://docs.anthropic.com/claude/docs/models-overview
     */
    val ANTHROPIC_MODELS = mapOf(
        // Sonnet models (latest, most capable)
        "claude-sonnet-4-6" to ModelDefinition(
            id = "claude-sonnet-4-6",
            name = "Claude Sonnet 4.6",
            provider = "anthropic",
            description = "Best combination of speed and intelligence with extended and adaptive thinking, 1M context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 1_000_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 3.00,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "anthropic.claude-sonnet-4-6" to ModelDefinition(
            id = "anthropic.claude-sonnet-4-6",
            name = "Claude Sonnet 4.6 (Bedrock)",
            provider = "anthropic",
            description = "AWS Bedrock alias for Claude Sonnet 4.6",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 1_000_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 3.00,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-sonnet-4-5-20250929" to ModelDefinition(
            id = "claude-sonnet-4-5-20250929",
            name = "Claude Sonnet 4.5",
            provider = "anthropic",
            description = "Smart Claude Sonnet 4.5 model for complex agents and coding",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 3.00,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,  // Extended thinking mode
            active = true
        ),

        "claude-sonnet-4-5" to ModelDefinition(
            id = "claude-sonnet-4-5",
            name = "Claude Sonnet 4.5",
            provider = "anthropic",
            description = "Smart Claude Sonnet 4.5 model for complex agents and coding",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 3.00,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-sonnet-4-20250514" to ModelDefinition(
            id = "claude-sonnet-4-20250514",
            name = "Claude Sonnet 4.0",
            provider = "anthropic",
            description = "Legacy Claude Sonnet 4 model for complex tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 3.00,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-sonnet-4-0" to ModelDefinition(
            id = "claude-sonnet-4-0",
            name = "Claude Sonnet 4.0",
            provider = "anthropic",
            description = "Legacy Claude Sonnet 4 model for complex tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 3.00,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-3-7-sonnet-20250219" to ModelDefinition(
            id = "claude-3-7-sonnet-20250219",
            name = "Claude 3.7 Sonnet",
            provider = "anthropic",
            description = "Claude 3.7 Sonnet with extended thinking beta support",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 3.00,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-3-7-sonnet-latest" to ModelDefinition(
            id = "claude-3-7-sonnet-latest",
            name = "Claude 3.7 Sonnet (Latest)",
            provider = "anthropic",
            description = "Claude 3.7 Sonnet latest alias with extended thinking beta support",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 3.00,
            costPer1MOutput = 15.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        // Opus models (premium intelligence)
        "claude-opus-4-7" to ModelDefinition(
            id = "claude-opus-4-7",
            name = "Claude Opus 4.7",
            provider = "anthropic",
            description = "Most capable Claude model for complex reasoning and agentic coding, 1M context with adaptive thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 1_000_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 5.00,
            costPer1MOutput = 25.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            removeParams = listOf("temperature"),
            active = true
        ),

        "anthropic.claude-opus-4-7" to ModelDefinition(
            id = "anthropic.claude-opus-4-7",
            name = "Claude Opus 4.7 (Bedrock)",
            provider = "anthropic",
            description = "AWS Bedrock alias for Claude Opus 4.7 (research preview on Bedrock)",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 1_000_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 5.00,
            costPer1MOutput = 25.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            removeParams = listOf("temperature"),
            active = true
        ),

        "anthropic.claude-opus-4-6-v1" to ModelDefinition(
            id = "anthropic.claude-opus-4-6-v1",
            name = "Claude Opus 4.6",
            provider = "anthropic",
            description = "Premium Claude Opus 4.6 model with maximum intelligence and vision support",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 5.00,
            costPer1MOutput = 25.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-opus-4-6" to ModelDefinition(
            id = "claude-opus-4-6",
            name = "Claude Opus 4.6",
            provider = "anthropic",
            description = "Premium Claude Opus 4.5 model with maximum intelligence and vision support",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 5.00,
            costPer1MOutput = 25.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),
        "claude-opus-4-5-20251101" to ModelDefinition(
            id = "claude-opus-4-5-20251101",
            name = "Claude Opus 4.5",
            provider = "anthropic",
            description = "Premium Claude Opus 4.5 model with maximum intelligence and vision support",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 5.00,
            costPer1MOutput = 25.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-opus-4-5" to ModelDefinition(
            id = "claude-opus-4-5",
            name = "Claude Opus 4.5",
            provider = "anthropic",
            description = "Premium Claude Opus 4.5 model with maximum intelligence and vision support",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 5.00,
            costPer1MOutput = 25.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-opus-4-1-20250805" to ModelDefinition(
            id = "claude-opus-4-1-20250805",
            name = "Claude Opus 4.1",
            provider = "anthropic",
            description = "Legacy Claude Opus 4.1 model with extended thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 32_000,
            costPer1MInput = 15.00,
            costPer1MOutput = 75.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-opus-4-1" to ModelDefinition(
            id = "claude-opus-4-1",
            name = "Claude Opus 4.1",
            provider = "anthropic",
            description = "Legacy Claude Opus 4.1 model with extended thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 32_000,
            costPer1MInput = 15.00,
            costPer1MOutput = 75.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-opus-4-20250514" to ModelDefinition(
            id = "claude-opus-4-20250514",
            name = "Claude Opus 4",
            provider = "anthropic",
            description = "Legacy Claude Opus 4 model with extended thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 32_000,
            costPer1MInput = 15.00,
            costPer1MOutput = 75.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-opus-4-0" to ModelDefinition(
            id = "claude-opus-4-0",
            name = "Claude Opus 4",
            provider = "anthropic",
            description = "Legacy Claude Opus 4 model with extended thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 32_000,
            costPer1MInput = 15.00,
            costPer1MOutput = 75.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        // Haiku models (cost-effective)
        "claude-haiku-4-5-20251001" to ModelDefinition(
            id = "claude-haiku-4-5-20251001",
            name = "Claude Haiku 4.5",
            provider = "anthropic",
            description = "Fastest Claude Haiku 4.5 model with near-frontier intelligence",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 1.00,
            costPer1MOutput = 5.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-haiku-4-5" to ModelDefinition(
            id = "claude-haiku-4-5",
            name = "Claude Haiku 4.5",
            provider = "anthropic",
            description = "Fastest Claude Haiku 4.5 model with near-frontier intelligence",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 64_000,
            costPer1MInput = 1.00,
            costPer1MOutput = 5.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),

        "claude-3-5-haiku-20241022" to ModelDefinition(
            id = "claude-3-5-haiku-20241022",
            name = "Claude 3.5 Haiku",
            provider = "anthropic",
            description = "Legacy Claude 3.5 Haiku model for everyday tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 8_192,
            costPer1MInput = 0.80,
            costPer1MOutput = 4.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            active = true
        ),

        "claude-3-5-haiku-latest" to ModelDefinition(
            id = "claude-3-5-haiku-latest",
            name = "Claude 3.5 Haiku (Latest)",
            provider = "anthropic",
            description = "Legacy Claude 3.5 Haiku latest alias",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 8_192,
            costPer1MInput = 0.80,
            costPer1MOutput = 4.00,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            active = true
        ),

        "claude-3-haiku-20240307" to ModelDefinition(
            id = "claude-3-haiku-20240307",
            name = "Claude 3 Haiku",
            provider = "anthropic",
            description = "Classic fast and affordable Claude model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 200_000,
            maxOutputTokens = 4_096,
            costPer1MInput = 0.25,
            costPer1MOutput = 1.25,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            active = true
        )
    )

    /**
     * Google Gemini Models Registry
     *
     * Updated: 2025-12-08
     * Source: https://ai.google.dev/gemini-api/docs/pricing
     */
    val GEMINI_MODELS = mapOf(
        "gemini-3-pro-preview" to ModelDefinition(
            id = "gemini-3-pro-preview",
            name = "Gemini 3 Pro Preview",
            provider = "gemini",
            description = "Flagship multimodal Gemini 3 Pro preview for agentic workflows (paid tier: $2 in / $12 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 2.0,
            costPer1MOutput = 12.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.5-pro" to ModelDefinition(
            id = "gemini-2.5-pro",
            name = "Gemini 2.5 Pro",
            provider = "gemini",
            description = "State-of-the-art multipurpose Gemini model for coding and complex reasoning ($1.25 in / $10 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.5-flash" to ModelDefinition(
            id = "gemini-2.5-flash",
            name = "Gemini 2.5 Flash",
            provider = "gemini",
            description = "Hybrid reasoning Gemini Flash model with 1M token context and thinking budgets ($0.30 in / $2.50 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 0.30,
            costPer1MOutput = 2.50,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),
        "gemini-2.5-flash-lite" to ModelDefinition(
            id = "gemini-2.5-flash-lite",
            name = "Gemini 2.5 Flash Lite",
            provider = "gemini",
            description = "Cost-optimized Flash Lite model for scaled usage ($0.10 in / $0.40 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 0.10,
            costPer1MOutput = 0.40,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.5-flash-lite-preview-09-2025" to ModelDefinition(
            id = "gemini-2.5-flash-lite-preview-09-2025",
            name = "Gemini 2.5 Flash Lite Preview (09-2025)",
            provider = "gemini",
            description = "Latest Flash Lite preview optimized for throughput (paid pricing mirrors Flash Lite).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 0.10,
            costPer1MOutput = 0.40,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.5-flash-native-audio-preview-09-2025" to ModelDefinition(
            id = "gemini-2.5-flash-native-audio-preview-09-2025",
            name = "Gemini 2.5 Flash Native Audio Preview",
            provider = "gemini",
            description = "Live API native audio model (text input $0.50 / audio output $2.00 per 1M tokens, audio surcharges noted in docs).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.AUDIO
            ),
            modelType = ModelType.AUDIO,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 4_096,
            costPer1MInput = 0.50,
            costPer1MOutput = 2.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.5-flash-image" to ModelDefinition(
            id = "gemini-2.5-flash-image",
            name = "Gemini 2.5 Flash Image",
            provider = "gemini",
            description = "Native image generation tuned for speed (text billed like Flash; paid image output listed as $30 per 1M tokens / $0.039 per image).",
            capabilities = listOf(
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.IMAGE_GENERATION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 4_096,
            costPer1MInput = 0.30,
            costPer1MOutput = 2.50,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.5-flash-preview-tts" to ModelDefinition(
            id = "gemini-2.5-flash-preview-tts",
            name = "Gemini 2.5 Flash Preview TTS",
            provider = "gemini",
            description = "2.5 Flash low-latency text-to-speech (paid tier $0.50 in / $10 audio out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.AUDIO
            ),
            modelType = ModelType.AUDIO,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = null,
            costPer1MInput = 0.50,
            costPer1MOutput = 10.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.5-pro-preview-tts" to ModelDefinition(
            id = "gemini-2.5-pro-preview-tts",
            name = "Gemini 2.5 Pro Preview TTS",
            provider = "gemini",
            description = "Higher-quality 2.5 Pro TTS preview (paid tier $1.00 in / $20 audio out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.AUDIO
            ),
            modelType = ModelType.AUDIO,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = null,
            costPer1MInput = 1.0,
            costPer1MOutput = 20.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.0-flash" to ModelDefinition(
            id = "gemini-2.0-flash",
            name = "Gemini 2.0 Flash",
            provider = "gemini",
            description = "Balanced multimodal Gemini Flash with 1M token context ($0.10 in / $0.40 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 8_192,
            costPer1MInput = 0.10,
            costPer1MOutput = 0.40,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            active = true
        ),
        "gemini-2.0-flash-lite" to ModelDefinition(
            id = "gemini-2.0-flash-lite",
            name = "Gemini 2.0 Flash Lite",
            provider = "gemini",
            description = "Lightweight Gemini Flash Lite ($0.075 in / $0.30 out per 1M tokens) for budget-sensitive workloads.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 4_096,
            costPer1MInput = 0.075,
            costPer1MOutput = 0.30,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            active = true
        ),
        "gemini-embedding-001" to ModelDefinition(
            id = "gemini-embedding-001",
            name = "Gemini Embedding 001",
            provider = "gemini",
            description = "Latest Gemini embedding model ($0.15 per 1M input tokens on paid tier).",
            capabilities = listOf(
                ModelCapability.EMBEDDINGS
            ),
            modelType = ModelType.EMBEDDING,
            maxContext = 65_536,
            maxOutputTokens = null,
            costPer1MInput = 0.15,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            supportsThinking = false,
            active = true
        ),
        "gemini-robotics-er-1.5-preview" to ModelDefinition(
            id = "gemini-robotics-er-1.5-preview",
            name = "Gemini Robotics-ER 1.5 Preview",
            provider = "gemini",
            description = "Embodied reasoning preview model for robotics ($0.30 in / $2.50 out per 1M tokens, shared grounding quotas with Flash Lite).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.AUDIO
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 8_192,
            costPer1MInput = 0.30,
            costPer1MOutput = 2.50,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),
        "gemini-2.5-computer-use-preview-10-2025" to ModelDefinition(
            id = "gemini-2.5-computer-use-preview-10-2025",
            name = "Gemini 2.5 Computer Use Preview",
            provider = "gemini",
            description = "Browser-control focused 2.5 model for automation (paid tier $1.25 in / $10 out per 1M tokens).",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),
        "gemini-flash-latest" to ModelDefinition(
            id = "gemini-flash-latest",
            name = "Gemini Flash (Latest Alias)",
            provider = "gemini",
            description = "Alias that mirrors Gemini 2.5 Flash pricing and capabilities.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 0.30,
            costPer1MOutput = 2.50,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            active = true
        ),
        "gemini-flash-lite-latest" to ModelDefinition(
            id = "gemini-flash-lite-latest",
            name = "Gemini Flash Lite (Latest Alias)",
            provider = "gemini",
            description = "Alias that mirrors Gemini 2.5 Flash Lite pricing and capabilities.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 0.10,
            costPer1MOutput = 0.40,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            active = true
        ),
        "gemini-pro-latest" to ModelDefinition(
            id = "gemini-pro-latest",
            name = "Gemini Pro (Latest Alias)",
            provider = "gemini",
            description = "Alias that mirrors Gemini 2.5 Pro pricing and capabilities.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = GEMINI_MAX_CONTEXT,
            maxOutputTokens = 65_536,
            costPer1MInput = 1.25,
            costPer1MOutput = 10.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            active = true
        )
    )

    /**
     * LM Studio Models Registry (local, OpenAI-compatible)
     *
     * LM Studio hosts user-downloaded models; we keep this empty and rely on dynamic listing
     * with zero-cost fallback definitions.
     */
    val LM_STUDIO_MODELS = emptyMap<String, ModelDefinition>()

    /**
     * Z.AI Models Registry
     *
     * Sources:
     * - https://docs.z.ai/guides/overview/pricing
     * - https://docs.z.ai/guides/overview/concept-param
     * - https://docs.z.ai/guides/overview/overview
     */
    val ZAI_MODELS = mapOf(
        "glm-5.1" to ModelDefinition(
            id = "glm-5.1",
            name = "GLM-5.1",
            provider = "zai",
            description = "New-generation flagship foundation model for agentic engineering, complex system work, and long-horizon coding tasks.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 1.0,
            costPer1MOutput = 3.2,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-5" to ModelDefinition(
            id = "glm-5",
            name = "GLM-5",
            provider = "zai",
            description = "New-generation flagship foundation model for agentic engineering, complex system work, and long-horizon coding tasks.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 1.0,
            costPer1MOutput = 3.2,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-5-turbo" to ModelDefinition(
            id = "glm-5-turbo",
            name = "GLM-5 Turbo",
            provider = "zai",
            description = "OpenClaw-optimized model for tool invocation, command following, persistent tasks, and long-chain execution.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 1.2,
            costPer1MOutput = 4.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.7" to ModelDefinition(
            id = "glm-4.7",
            name = "GLM-4.7",
            provider = "zai",
            description = "Enhanced programming and more stable multi-step reasoning and execution with strong agent task performance.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 0.6,
            costPer1MOutput = 2.2,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.7-flash" to ModelDefinition(
            id = "glm-4.7-flash",
            name = "GLM-4.7 Flash",
            provider = "zai",
            description = "Completely free lightweight GLM-4.7 variant for fast chat, coding, and agent usage.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.7-flashx" to ModelDefinition(
            id = "glm-4.7-flashx",
            name = "GLM-4.7 FlashX",
            provider = "zai",
            description = "Lightweight, high-speed, affordable GLM-4.7 family model for efficient agentic workloads.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 0.07,
            costPer1MOutput = 0.4,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.6" to ModelDefinition(
            id = "glm-4.6",
            name = "GLM-4.6",
            provider = "zai",
            description = "Broad upgrade across coding, long-context processing, reasoning, search, writing, and agentic applications.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = 128_000,
            costPer1MInput = 0.6,
            costPer1MOutput = 2.2,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.5" to ModelDefinition(
            id = "glm-4.5",
            name = "GLM-4.5",
            provider = "zai",
            description = "Most powerful reasoning model in the GLM-4.5 family, optimized for tool invocation, browsing, software engineering, and front-end development.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 96_000,
            costPer1MInput = 0.6,
            costPer1MOutput = 2.2,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.5-air" to ModelDefinition(
            id = "glm-4.5-air",
            name = "GLM-4.5 Air",
            provider = "zai",
            description = "Cost-effective lightweight GLM-4.5 model with strong performance for coding, reasoning, and agents.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 96_000,
            costPer1MInput = 0.2,
            costPer1MOutput = 1.1,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.5-flash" to ModelDefinition(
            id = "glm-4.5-flash",
            name = "GLM-4.5 Flash",
            provider = "zai",
            description = "Free GLM-4.5 family model focused on reasoning, coding, and agents.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 96_000,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.5-x" to ModelDefinition(
            id = "glm-4.5-x",
            name = "GLM-4.5 X",
            provider = "zai",
            description = "High-performance GLM-4.5 variant with strong reasoning and ultra-fast response.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 96_000,
            costPer1MInput = 2.2,
            costPer1MOutput = 8.9,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4.5-airx" to ModelDefinition(
            id = "glm-4.5-airx",
            name = "GLM-4.5 AirX",
            provider = "zai",
            description = "Lightweight GLM-4.5 variant with strong performance and ultra-fast response.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 96_000,
            costPer1MInput = 1.1,
            costPer1MOutput = 4.5,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = true,
            reasoningTokensMultiplier = 2.5,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        ),
        "glm-4-32b-0414-128k" to ModelDefinition(
            id = "glm-4-32b-0414-128k",
            name = "GLM-4 32B 128K",
            provider = "zai",
            description = "Highly cost-effective foundation language model with strong tool use, online search, and code-task support.",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = 16_000,
            costPer1MInput = 0.1,
            costPer1MOutput = 0.1,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            supportsThinking = false,
            endpointType = ApiEndpointType.CHAT_COMPLETIONS,
            apiFormat = ApiFormat.CHAT_COMPLETIONS,
            active = true
        )
    )

    /**
     * Ollama Models Registry (local models)
     *
     * Updated: 2025-11-19
     * Source: https://ollama.com/library
     *
     * Note: These are common Ollama models. Users can pull custom models,
     * which will use the fallback configuration.
     */

    val OLLAMA_MODELS = mapOf(
        // ═══════════════════════════════════════════════════════════════════
        // QWEN FAMILY
        // ═══════════════════════════════════════════════════════════════════

        // Qwen 3 - Latest generation with tool use
        "qwen3:0.6b" to ModelDefinition(
            id = "qwen3:0.6b",
            name = "Qwen 3 0.6B",
            provider = "ollama",
            description = "Ultra-lightweight model with tools and thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3:1.7b" to ModelDefinition(
            id = "qwen3:1.7b",
            name = "Qwen 3 1.7B",
            provider = "ollama",
            description = "Compact model with tools and thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3:4b" to ModelDefinition(
            id = "qwen3:4b",
            name = "Qwen 3 4B",
            provider = "ollama",
            description = "Balanced size with tools and thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3:7b" to ModelDefinition(
            id = "qwen3:7b",
            name = "Qwen 3 7B",
            provider = "ollama",
            description = "Latest generation with tools, thinking, multi-capability",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3:8b" to ModelDefinition(
            id = "qwen3:8b",
            name = "Qwen 3 8B",
            provider = "ollama",
            description = "High-capability model with tools and thinking",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3:14b" to ModelDefinition(
            id = "qwen3:14b",
            name = "Qwen 3 14B",
            provider = "ollama",
            description = "Large model with enhanced reasoning",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3:30b" to ModelDefinition(
            id = "qwen3:30b",
            name = "Qwen 3 30B",
            provider = "ollama",
            description = "Very large model with superior reasoning",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3:32b" to ModelDefinition(
            id = "qwen3:32b",
            name = "Qwen 3 32B",
            provider = "ollama",
            description = "Flagship size with excellent performance",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3:235b" to ModelDefinition(
            id = "qwen3:235b",
            name = "Qwen 3 235B",
            provider = "ollama",
            description = "Massive model with state-of-the-art capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-vl:2b" to ModelDefinition(
            id = "qwen3-vl:2b",
            name = "Qwen 3 VL 2B",
            provider = "ollama",
            description = "Compact vision-language model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-vl:4b" to ModelDefinition(
            id = "qwen3-vl:4b",
            name = "Qwen 3 VL 4B",
            provider = "ollama",
            description = "Balanced vision-language model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-vl:7b" to ModelDefinition(
            id = "qwen3-vl:7b",
            name = "Qwen 3 VL 7B",
            provider = "ollama",
            description = "Most powerful vision-language model in Qwen family",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-vl:8b" to ModelDefinition(
            id = "qwen3-vl:8b",
            name = "Qwen 3 VL 8B",
            provider = "ollama",
            description = "High-performance vision-language model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-vl:30b" to ModelDefinition(
            id = "qwen3-vl:30b",
            name = "Qwen 3 VL 30B",
            provider = "ollama",
            description = "Large vision-language model with superior understanding",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-vl:32b" to ModelDefinition(
            id = "qwen3-vl:32b",
            name = "Qwen 3 VL 32B",
            provider = "ollama",
            description = "Flagship vision model with excellent multimodal capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-vl:235b" to ModelDefinition(
            id = "qwen3-vl:235b",
            name = "Qwen 3 VL 235B",
            provider = "ollama",
            description = "Massive vision-language model with state-of-the-art performance",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        //
        // Qwen 3.5 - Multimodal (vision+language), 256K context, tool use
        //
        "qwen3.5:0.8b" to ModelDefinition(
            id = "qwen3.5:0.8b",
            name = "Qwen 3.5 0.8B",
            provider = "ollama",
            description = "Ultra-lightweight multimodal model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3.5:2b" to ModelDefinition(
            id = "qwen3.5:2b",
            name = "Qwen 3.5 2B",
            provider = "ollama",
            description = "Compact multimodal model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3.5:4b" to ModelDefinition(
            id = "qwen3.5:4b",
            name = "Qwen 3.5 4B",
            provider = "ollama",
            description = "Balanced multimodal model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3.5:9b" to ModelDefinition(
            id = "qwen3.5:9b",
            name = "Qwen 3.5 9B",
            provider = "ollama",
            description = "High-capability multimodal model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3.5:27b" to ModelDefinition(
            id = "qwen3.5:27b",
            name = "Qwen 3.5 27B",
            provider = "ollama",
            description = "Large multimodal model with strong coding and reasoning",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3.5:35b" to ModelDefinition(
            id = "qwen3.5:35b",
            name = "Qwen 3.5 35B MoE (A3B)",
            provider = "ollama",
            description = "MoE multimodal model (35B total, 3B active) with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3.5:122b" to ModelDefinition(
            id = "qwen3.5:122b",
            name = "Qwen 3.5 122B MoE (A10B)",
            provider = "ollama",
            description = "Large MoE multimodal model (122B total, 10B active) with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        //
        // Qwen 3.6 - 35B MoE (3B active), 256K context, multimodal with tool use
        //
        "qwen3.6:latest" to ModelDefinition(
            id = "qwen3.6:latest",
            name = "Qwen 3.6 35B MoE (A3B)",
            provider = "ollama",
            description = "Latest Qwen 3.6 MoE (35B total, 3B active) multimodal model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3.6:35b-a3b" to ModelDefinition(
            id = "qwen3.6:35b-a3b",
            name = "Qwen 3.6 35B MoE (A3B)",
            provider = "ollama",
            description = "Qwen 3.6 MoE (35B total, 3B active) multimodal model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3.6:35b" to ModelDefinition(
            id = "qwen3.6:35b",
            name = "Qwen 3.6 35B MoE (A3B)",
            provider = "ollama",
            description = "Qwen 3.6 MoE (35B total, 3B active) multimodal model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),


        "qwen3.6:27b" to ModelDefinition(
            id = "qwen3.6:27b",
            name = "Qwen 3.6 27B",
            provider = "ollama",
            description = "Qwen 3.6 27B multimodal model with 256K context and native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-next:80b" to ModelDefinition(
            id = "qwen3-next:80b",
            name = "Qwen 3 Next 80B",
            provider = "ollama",
            description = "Qwen 3 Next 80B with native tool use and long context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gpt-oss-safeguard:20b" to ModelDefinition(
            id = "gpt-oss-safeguard:20b",
            name = "GPT-OSS Safeguard 20B",
            provider = "ollama",
            description = "OpenAI gpt-oss-safeguard 20B with native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gpt-oss-safeguard:120b" to ModelDefinition(
            id = "gpt-oss-safeguard:120b",
            name = "GPT-OSS Safeguard 120B",
            provider = "ollama",
            description = "OpenAI gpt-oss-safeguard 120B with native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek-v3.2:latest" to ModelDefinition(
            id = "deepseek-v3.2:latest",
            name = "DeepSeek V3.2",
            provider = "ollama",
            description = "DeepSeek V3.2 with native tool use and reasoning",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "kimi-k2-thinking:latest" to ModelDefinition(
            id = "kimi-k2-thinking:latest",
            name = "Kimi K2 Thinking",
            provider = "ollama",
            description = "Moonshot Kimi K2 Thinking with native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "minimax-m2:latest" to ModelDefinition(
            id = "minimax-m2:latest",
            name = "MiniMax M2",
            provider = "ollama",
            description = "MiniMax M2 with native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "nemotron-3-super:120b" to ModelDefinition(
            id = "nemotron-3-super:120b",
            name = "Nemotron 3 Super 120B",
            provider = "ollama",
            description = "NVIDIA Nemotron 3 Super 120B with native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "nemotron-3-nano:4b" to ModelDefinition(
            id = "nemotron-3-nano:4b",
            name = "Nemotron 3 Nano 4B",
            provider = "ollama",
            description = "NVIDIA Nemotron 3 Nano 4B with native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "nemotron-3-nano:30b" to ModelDefinition(
            id = "nemotron-3-nano:30b",
            name = "Nemotron 3 Nano 30B",
            provider = "ollama",
            description = "NVIDIA Nemotron 3 Nano 30B with native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "nemotron-cascade-2:30b" to ModelDefinition(
            id = "nemotron-cascade-2:30b",
            name = "Nemotron Cascade 2 30B",
            provider = "ollama",
            description = "NVIDIA Nemotron Cascade 2 30B with native tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-coder:30b" to ModelDefinition(
            id = "qwen3-coder:30b",
            name = "Qwen 3 Coder 30B",
            provider = "ollama",
            description = "Performant long context model for agentic and coding tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen3-coder:480b" to ModelDefinition(
            id = "qwen3-coder:480b",
            name = "Qwen 3 Coder 480B",
            provider = "ollama",
            description = "Massive coding model with exceptional long context capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // Qwen 3 Coder Next - 80B MoE (3B active), 256K context, agentic coding
        "qwen3-coder-next:latest" to ModelDefinition(
            id = "qwen3-coder-next:latest",
            name = "Qwen 3 Coder Next 80B MoE",
            provider = "ollama",
            description = "80B MoE (3B active) agentic coding model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // Qwen 2.5 family
        "qwen2.5:7b" to ModelDefinition(
            id = "qwen2.5:7b",
            name = "Qwen 2.5 7B",
            provider = "ollama",
            description = "Multilingual support, 128K context, tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwen2.5-coder:7b" to ModelDefinition(
            id = "qwen2.5-coder:7b",
            name = "Qwen 2.5 Coder 7B",
            provider = "ollama",
            description = "Code-specific with tool use, reasoning, and fixing",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // DEEPSEEK FAMILY
        // ═══════════════════════════════════════════════════════════════════

        "deepseek-r1:1.5b" to ModelDefinition(
            id = "deepseek-r1:1.5b",
            name = "DeepSeek R1 1.5B",
            provider = "ollama",
            description = "Compact reasoning model with excellent efficiency",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 64_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek-r1:7b" to ModelDefinition(
            id = "deepseek-r1:7b",
            name = "DeepSeek R1 7B",
            provider = "ollama",
            description = "Open reasoning model approaching O3/Gemini 2.5 Pro performance",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 64_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek-r1:8b" to ModelDefinition(
            id = "deepseek-r1:8b",
            name = "DeepSeek R1 8B",
            provider = "ollama",
            description = "High-performance reasoning model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 64_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek-r1:14b" to ModelDefinition(
            id = "deepseek-r1:14b",
            name = "DeepSeek R1 14B",
            provider = "ollama",
            description = "Large reasoning model with superior capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 64_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek-r1:32b" to ModelDefinition(
            id = "deepseek-r1:32b",
            name = "DeepSeek R1 32B",
            provider = "ollama",
            description = "Very large reasoning model with excellent performance",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 64_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek-r1:70b" to ModelDefinition(
            id = "deepseek-r1:70b",
            name = "DeepSeek R1 70B",
            provider = "ollama",
            description = "Flagship reasoning model with state-of-the-art capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 64_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek-r1:671b" to ModelDefinition(
            id = "deepseek-r1:671b",
            name = "DeepSeek R1 671B",
            provider = "ollama",
            description = "Massive MoE reasoning model rivaling closed-source models",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 64_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek-v3.1:671b" to ModelDefinition(
            id = "deepseek-v3.1:671b",
            name = "DeepSeek V3.1 Terminus 671B",
            provider = "ollama",
            description = "Hybrid thinking/non-thinking MoE model, 37B activated per token",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "deepseek:7b" to ModelDefinition(
            id = "deepseek:7b",
            name = "DeepSeek 7B",
            provider = "ollama",
            description = "General tasks model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 16_384,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // MISTRAL FAMILY
        // ═══════════════════════════════════════════════════════════════════

        "devstral-2:123b" to ModelDefinition(
            id = "devstral-2:123b",
            name = "Devstral 2 123B",
            provider = "ollama",
            description = "Excels at codebase exploration, multi-file editing, software agents",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "devstral-small-2:24b" to ModelDefinition(
            id = "devstral-small-2:24b",
            name = "Devstral Small 2 24B",
            provider = "ollama",
            description = "Smaller version with vision, tools for software engineering agents",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "devstral:24b" to ModelDefinition(
            id = "devstral:24b",
            name = "Devstral 24B",
            provider = "ollama",
            description = "Best open source model for coding agents with tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "ministral-3:3b" to ModelDefinition(
            id = "ministral-3:3b",
            name = "Ministral 3 3B",
            provider = "ollama",
            description = "Ultra-compact edge model with vision and tools",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "ministral-3:8b" to ModelDefinition(
            id = "ministral-3:8b",
            name = "Ministral 3 8B",
            provider = "ollama",
            description = "Edge deployment, vision + tools, runs on laptops/tablets/phones",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "ministral-3:14b" to ModelDefinition(
            id = "ministral-3:14b",
            name = "Ministral 3 14B",
            provider = "ollama",
            description = "Larger edge model with enhanced vision and reasoning",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "mistral-small3.2:24b" to ModelDefinition(
            id = "mistral-small3.2:24b",
            name = "Mistral Small 3.2 24B",
            provider = "ollama",
            description = "Enhanced vision understanding and long context up to 128k",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "mistral:7b" to ModelDefinition(
            id = "mistral:7b",
            name = "Mistral 7B",
            provider = "ollama",
            description = "General purpose with tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "devstral:latest" to ModelDefinition(
            id = "devstral:latest",
            name = "Devstral",
            provider = "ollama",
            description = "Specialized coding model with tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // LLAMA FAMILY
        // ═══════════════════════════════════════════════════════════════════

        "llama4:16x17b" to ModelDefinition(
            id = "llama4:16x17b",
            name = "Llama 4 16x17B",
            provider = "ollama",
            description = "Meta's latest multimodal MoE model with vision and tools",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama4:128x17b" to ModelDefinition(
            id = "llama4:128x17b",
            name = "Llama 4 128x17B",
            provider = "ollama",
            description = "Massive MoE variant with exceptional capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama3.1:8b" to ModelDefinition(
            id = "llama3.1:8b",
            name = "Llama 3.1 8B",
            provider = "ollama",
            description = "State-of-the-art Meta model with tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama3.1:70b" to ModelDefinition(
            id = "llama3.1:70b",
            name = "Llama 3.1 70B",
            provider = "ollama",
            description = "Large variant with excellent performance",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama3.1:405b" to ModelDefinition(
            id = "llama3.1:405b",
            name = "Llama 3.1 405B",
            provider = "ollama",
            description = "Flagship model with state-of-the-art capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama3.2:1b" to ModelDefinition(
            id = "llama3.2:1b",
            name = "Llama 3.2 1B",
            provider = "ollama",
            description = "Ultra-compact model with tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama3.2:3b" to ModelDefinition(
            id = "llama3.2:3b",
            name = "Llama 3.2 3B",
            provider = "ollama",
            description = "Small model with tool use, efficient for edge devices",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama3.2-vision:11b" to ModelDefinition(
            id = "llama3.2-vision:11b",
            name = "Llama 3.2 Vision 11B",
            provider = "ollama",
            description = "Image reasoning generative model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama3.2-vision:90b" to ModelDefinition(
            id = "llama3.2-vision:90b",
            name = "Llama 3.2 Vision 90B",
            provider = "ollama",
            description = "Large vision model with superior image understanding",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "llama3:8b" to ModelDefinition(
            id = "llama3:8b",
            name = "Llama 3 8B",
            provider = "ollama",
            description = "Most capable openly available LLM (original)",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            active = true
        ),

        "llama3:70b" to ModelDefinition(
            id = "llama3:70b",
            name = "Llama 3 70B",
            provider = "ollama",
            description = "Large Llama 3 model with excellent performance",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // GEMMA FAMILY
        // ═══════════════════════════════════════════════════════════════════

        "gemma3:270m" to ModelDefinition(
            id = "gemma3:270m",
            name = "Gemma 3 270M",
            provider = "ollama",
            description = "Ultra-compact model for edge devices with vision",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma3:1b" to ModelDefinition(
            id = "gemma3:1b",
            name = "Gemma 3 1B",
            provider = "ollama",
            description = "Compact model with vision for efficient deployment",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma3:4b" to ModelDefinition(
            id = "gemma3:4b",
            name = "Gemma 3 4B",
            provider = "ollama",
            description = "Balanced model with vision capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma3:12b" to ModelDefinition(
            id = "gemma3:12b",
            name = "Gemma 3 12B",
            provider = "ollama",
            description = "Most capable model that runs on single GPU, with vision",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma3:27b" to ModelDefinition(
            id = "gemma3:27b",
            name = "Gemma 3 27B",
            provider = "ollama",
            description = "Large Gemma model with excellent vision understanding",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // Gemma 4 - Latest generation with vision, audio, and function calling
        "gemma4:e2b" to ModelDefinition(
            id = "gemma4:e2b",
            name = "Gemma 4 E2B",
            provider = "ollama",
            description = "Edge model, 2.3B effective (5.1B with embeddings), multimodal with function calling",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.AUDIO,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma4:e4b" to ModelDefinition(
            id = "gemma4:e4b",
            name = "Gemma 4 E4B",
            provider = "ollama",
            description = "Edge model, 4.5B effective (8B with embeddings), multimodal with function calling",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.AUDIO,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma4:26b" to ModelDefinition(
            id = "gemma4:26b",
            name = "Gemma 4 26B MoE",
            provider = "ollama",
            description = "Mixture-of-Experts, 25.2B total / 3.8B active, vision with function calling",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma4:31b" to ModelDefinition(
            id = "gemma4:31b",
            name = "Gemma 4 31B",
            provider = "ollama",
            description = "Dense 30.7B model, vision with function calling, 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // MedGemma - Gemma 3 variants trained for medical text and image comprehension, 128K context
        "medgemma:latest" to ModelDefinition(
            id = "medgemma:latest",
            name = "MedGemma 4B",
            provider = "ollama",
            description = "Gemma 3 variant fine-tuned for medical text and image comprehension",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "medgemma:4b" to ModelDefinition(
            id = "medgemma:4b",
            name = "MedGemma 4B",
            provider = "ollama",
            description = "4B Gemma 3 variant for medical text and image comprehension",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "medgemma:27b" to ModelDefinition(
            id = "medgemma:27b",
            name = "MedGemma 27B",
            provider = "ollama",
            description = "27B Gemma 3 variant for medical text and image comprehension",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // MedGemma 1.5 - updated MedGemma 4B with improved medical performance, 128K context
        "medgemma1.5:latest" to ModelDefinition(
            id = "medgemma1.5:latest",
            name = "MedGemma 1.5 4B",
            provider = "ollama",
            description = "Updated MedGemma 4B with improved medical text and image comprehension",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "medgemma1.5:4b" to ModelDefinition(
            id = "medgemma1.5:4b",
            name = "MedGemma 1.5 4B",
            provider = "ollama",
            description = "4B updated MedGemma with improved medical text and image comprehension",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.VISION
            ),
            modelType = ModelType.MULTIMODAL,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = true,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma2:2b" to ModelDefinition(
            id = "gemma2:2b",
            name = "Gemma 2 2B",
            provider = "ollama",
            description = "Compact high-performing model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma2:9b" to ModelDefinition(
            id = "gemma2:9b",
            name = "Gemma 2 9B",
            provider = "ollama",
            description = "High-performing and efficient model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gemma2:27b" to ModelDefinition(
            id = "gemma2:27b",
            name = "Gemma 2 27B",
            provider = "ollama",
            description = "Large Gemma 2 model with superior capabilities",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // ZHIPU GLM FAMILY (Ollama cloud variants)
        // ═══════════════════════════════════════════════════════════════════

        // GLM-5.1 - next-generation flagship for agentic engineering with strong coding
        "glm-5.1:cloud" to ModelDefinition(
            id = "glm-5.1:cloud",
            name = "GLM-5.1 (Cloud)",
            provider = "ollama",
            description = "Flagship agentic engineering model with strong coding capabilities, 198K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 198_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // MINIMAX FAMILY (Ollama cloud variants)
        // ═══════════════════════════════════════════════════════════════════

        // MiniMax M2.7 - coding, agentic workflows, and professional productivity
        "minimax-m2.7:cloud" to ModelDefinition(
            id = "minimax-m2.7:cloud",
            name = "MiniMax M2.7 (Cloud)",
            provider = "ollama",
            description = "MiniMax M2-series model for coding, agentic workflows, and professional productivity, 200K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.CODE_COMPLETION,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 200_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // LIQUID AI FAMILY
        // ═══════════════════════════════════════════════════════════════════

        // LFM2 - 24B MoE (2B active), 32K context, hybrid architecture for on-device deployment
        "lfm2:24b" to ModelDefinition(
            id = "lfm2:24b",
            name = "LFM2 24B MoE",
            provider = "ollama",
            description = "24B MoE (2B active) hybrid model for efficient on-device deployment",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // REASONING MODELS
        // ═══════════════════════════════════════════════════════════════════

        // Nemotron Cascade 2 - NVIDIA 30B MoE (3B active), 256K context, reasoning
        "nemotron-cascade-2:30b" to ModelDefinition(
            id = "nemotron-cascade-2:30b",
            name = "Nemotron Cascade 2 30B MoE",
            provider = "ollama",
            description = "NVIDIA 30B MoE (3B active) reasoning model with 256K context",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 256_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gpt-oss:20b" to ModelDefinition(
            id = "gpt-oss:20b",
            name = "GPT-OSS 20B",
            provider = "ollama",
            description = "OpenAI's open-weight reasoning model for agentic tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "gpt-oss:120b" to ModelDefinition(
            id = "gpt-oss:120b",
            name = "GPT-OSS 120B",
            provider = "ollama",
            description = "Large OpenAI open-weight reasoning model",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "qwq:32b" to ModelDefinition(
            id = "qwq:32b",
            name = "QwQ 32B",
            provider = "ollama",
            description = "Reasoning model from Qwen series with tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "phi4-reasoning:14b" to ModelDefinition(
            id = "phi4-reasoning:14b",
            name = "Phi-4 Reasoning 14B",
            provider = "ollama",
            description = "Rivals much larger models on complex reasoning tasks",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING
            ),
            modelType = ModelType.TEXT,
            maxContext = 16_384,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "phi4:14b" to ModelDefinition(
            id = "phi4:14b",
            name = "Phi-4 14B",
            provider = "ollama",
            description = "State-of-the-art 14B model from Microsoft",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.TEXT_COMPLETION
            ),
            modelType = ModelType.TEXT,
            maxContext = 16_384,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        "magistral:24b" to ModelDefinition(
            id = "magistral:24b",
            name = "Magistral 24B",
            provider = "ollama",
            description = "Small, efficient reasoning model with tool use",
            capabilities = listOf(
                ModelCapability.CHAT_COMPLETION,
                ModelCapability.REASONING,
                ModelCapability.TOOL_USE
            ),
            modelType = ModelType.TEXT,
            maxContext = 128_000,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = true,
            supportsStreaming = true,
            supportsFunctionCalling = true,
            defaultParams = mapOf("temperature" to 0.7),
            active = true
        ),

        // ═══════════════════════════════════════════════════════════════════
        // EMBEDDING MODELS
        // ═══════════════════════════════════════════════════════════════════

        "nomic-embed-text:latest" to ModelDefinition(
            id = "nomic-embed-text:latest",
            name = "Nomic Embed Text",
            provider = "ollama",
            description = "High-performing embedding with large token context window",
            capabilities = listOf(ModelCapability.EMBEDDINGS),
            modelType = ModelType.EMBEDDING,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            active = true
        ),

        "mxbai-embed-large:latest" to ModelDefinition(
            id = "mxbai-embed-large:latest",
            name = "mxbai Embed Large",
            provider = "ollama",
            description = "State-of-the-art large embedding model from mixedbread.ai",
            capabilities = listOf(ModelCapability.EMBEDDINGS),
            modelType = ModelType.EMBEDDING,
            maxContext = 512,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            active = true
        ),

        "bge-m3:latest" to ModelDefinition(
            id = "bge-m3:latest",
            name = "BGE-M3",
            provider = "ollama",
            description = "Multi-Functionality, Multi-Linguality, Multi-Granularity embedding",
            capabilities = listOf(ModelCapability.EMBEDDINGS),
            modelType = ModelType.EMBEDDING,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            active = true
        ),

        "embeddinggemma:300m" to ModelDefinition(
            id = "embeddinggemma:300m",
            name = "Embedding Gemma 300M",
            provider = "ollama",
            description = "Compact 300M parameter embedding model from Google",
            capabilities = listOf(ModelCapability.EMBEDDINGS),
            modelType = ModelType.EMBEDDING,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            active = true
        ),
        "embeddinggemma:latest" to ModelDefinition(
            id = "embeddinggemma:latest",
            name = "Embedding Gemma",
            provider = "ollama",
            description = "Embedding model from Google",
            capabilities = listOf(ModelCapability.EMBEDDINGS),
            modelType = ModelType.EMBEDDING,
            maxContext = 8_192,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            active = true
        ),

        "qwen3-embedding:0.6b" to ModelDefinition(
            id = "qwen3-embedding:0.6b",
            name = "Qwen 3 Embedding 0.6B",
            provider = "ollama",
            description = "Ultra-compact text embedding model",
            capabilities = listOf(ModelCapability.EMBEDDINGS),
            modelType = ModelType.EMBEDDING,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            active = true
        ),

        "qwen3-embedding:4b" to ModelDefinition(
            id = "qwen3-embedding:4b",
            name = "Qwen 3 Embedding 4B",
            provider = "ollama",
            description = "Balanced text embedding model",
            capabilities = listOf(ModelCapability.EMBEDDINGS),
            modelType = ModelType.EMBEDDING,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            active = true
        ),

        "qwen3-embedding:8b" to ModelDefinition(
            id = "qwen3-embedding:8b",
            name = "Qwen 3 Embedding 8B",
            provider = "ollama",
            description = "High-performance text embedding model",
            capabilities = listOf(ModelCapability.EMBEDDINGS),
            modelType = ModelType.EMBEDDING,
            maxContext = 32_768,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = false,
            supportsFunctionCalling = false,
            active = true
        )
    )

    /**
     * OpenRouter Models Registry (curated)
     *
     * OpenRouter exposes 200+ models via dynamic listing. This registry contains
     * static definitions for the popular families where we know native
     * function-calling is reliable — so AgentTurnLoop takes the native tool_calls
     * path instead of the JSON-in-text envelope. Unknown OpenRouter model IDs
     * still fall back to null (JSON envelope) — that preserves backwards
     * compatibility for exotic community models.
     *
     * Matching is prefix-based in [getDefinition] (e.g. "amazon/nova-lite-v1"
     * matches entry "amazon/nova-").
     */
    // Family-level baseline pricing (USD per 1M tokens). Sourced from openrouter.ai/models
     // 2026-05; revisit if OpenRouter restructures tiers. Per-model prices from the live
     // /models endpoint (parsed in OpenRouterAdapter) override these for each specific
     // model id when the cache is warm — see getModelPricing() fallback chain.
    val OPENROUTER_MODELS = mapOf(
        // Anthropic — Opus tier $15/$75, Sonnet $3/$15, Haiku $0.80/$4
        "anthropic/claude-opus" to openrouterDef("anthropic/claude-opus-*", maxContext = 1_000_000, vision = true, inPrice = 15.0, outPrice = 75.0),
        "anthropic/claude-sonnet" to openrouterDef("anthropic/claude-sonnet-*", maxContext = 1_000_000, vision = true, inPrice = 3.0, outPrice = 15.0),
        "anthropic/claude-haiku" to openrouterDef("anthropic/claude-haiku-*", maxContext = 200_000, vision = true, inPrice = 0.80, outPrice = 4.0),
        "anthropic/claude-" to openrouterDef("anthropic/claude-*", maxContext = 200_000, vision = true, inPrice = 3.0, outPrice = 15.0),
        // OpenAI — gpt-5.4-pro $30/$180, gpt-5.4 $2.50/$15, gpt-5* $5/$30, gpt-* $0.50/$1.50
        "openai/gpt-5.4-pro" to openrouterDef("openai/gpt-5.4-pro", maxContext = 1_050_000, vision = true, inPrice = 30.0, outPrice = 180.0),
        "openai/gpt-5.4" to openrouterDef("openai/gpt-5.4-*", maxContext = 400_000, vision = true, inPrice = 2.50, outPrice = 15.0),
        "openai/gpt-5" to openrouterDef("openai/gpt-5-*", maxContext = 400_000, vision = true, inPrice = 5.0, outPrice = 30.0),
        "openai/gpt-audio" to openrouterDef("openai/gpt-audio*", maxContext = 128_000, inPrice = 2.50, outPrice = 10.0),
        "openai/gpt-" to openrouterDef("openai/gpt-*", maxContext = 128_000, vision = true, inPrice = 0.50, outPrice = 1.50),
        // o-series reasoning — premium pricing
        "openai/o" to openrouterDef("openai/o*", maxContext = 200_000, reasoning = true, inPrice = 15.0, outPrice = 60.0),
        // Google — gemini-pro $2/$12, gemini-flash $0.50/$3
        "google/gemini-3" to openrouterDef("google/gemini-3*", maxContext = 1_048_576, vision = true, inPrice = 2.0, outPrice = 12.0),
        "google/gemini-" to openrouterDef("google/gemini-*", maxContext = 1_000_000, vision = true, inPrice = 1.25, outPrice = 5.0),
        "google/gemma-" to openrouterDef("google/gemma-*", maxContext = 262_144, vision = true, inPrice = 0.05, outPrice = 0.10),
        // Amazon Nova — lite $0.06/$0.24, pro $0.80/$3.20 (mid-tier baseline)
        "amazon/nova-" to openrouterDef("amazon/nova-*", maxContext = 300_000, vision = true, inPrice = 0.80, outPrice = 3.20),
        // Meta Llama — 3.3-70b ~$0.18/$0.18
        "meta-llama/llama-" to openrouterDef("meta-llama/llama-*", maxContext = 128_000, inPrice = 0.20, outPrice = 0.60),
        "meta-llama/" to openrouterDef("meta-llama/*", maxContext = 128_000, inPrice = 0.20, outPrice = 0.60),
        // Mistral — small $0.15/$0.60 baseline
        "mistralai/" to openrouterDef("mistralai/*", maxContext = 262_144, inPrice = 0.15, outPrice = 0.60),
        // Qwen — 3.6-max $1.04/$6.24, 3.5-plus $0.40/$2.40, 3-* small models
        "qwen/qwen3.6" to openrouterDef("qwen/qwen3.6-*", maxContext = 1_000_000, vision = true, inPrice = 1.05, outPrice = 6.25),
        "qwen/qwen3.5" to openrouterDef("qwen/qwen3.5-*", maxContext = 262_144, vision = true, inPrice = 0.40, outPrice = 2.40),
        // qwen3-coder-* variants emit XML pseudo-tags instead of native tool_calls
        // (observed: `<create_new_file><path>...</path><content>...</content></create_new_file>`).
        // Prefix entry must come before "qwen/qwen3" to win the prefix match.
        "qwen/qwen3-coder" to openrouterDef("qwen/qwen3-coder-*", maxContext = 262_144, functionCalling = false, inPrice = 0.50, outPrice = 2.0),
        "qwen/qwen3" to openrouterDef("qwen/qwen3-*", maxContext = 262_144, inPrice = 0.20, outPrice = 0.60),
        "qwen/" to openrouterDef("qwen/*", maxContext = 128_000, inPrice = 0.20, outPrice = 0.60),
        // DeepSeek — v4-flash $0.14/$0.28, v4-pro $0.43/$0.87, R1 $0.27/$1.10
        "deepseek/" to openrouterDef("deepseek/*", maxContext = 128_000, inPrice = 0.27, outPrice = 1.10),
        // xAI Grok — grok-4 $5/$15, grok-3 $5/$25, grok-fast variants cheaper
        "x-ai/grok-" to openrouterDef("x-ai/grok-*", maxContext = 2_000_000, vision = true, inPrice = 3.0, outPrice = 15.0),
        // Cohere Command — command-r ~$0.50/$1.50
        "cohere/command-" to openrouterDef("cohere/command-*", maxContext = 128_000, inPrice = 0.50, outPrice = 1.50),
        // Moonshot Kimi — k2 $0.74/$3.49
        "moonshotai/kimi" to openrouterDef("moonshotai/kimi-*", maxContext = 262_144, vision = true, inPrice = 0.74, outPrice = 3.49),
        "moonshotai/" to openrouterDef("moonshotai/*", maxContext = 128_000, inPrice = 0.50, outPrice = 2.0),
        // MiniMax — m2.5 $0.15/$1.15
        // minimax-m2.7 emits `<minimax:tool_call><invoke name="...">...</invoke></minimax:tool_call>`
        // pseudo-XML instead of native tool_calls. Force JSON envelope mode.
        "minimax/minimax-m2.7" to openrouterDef("minimax/minimax-m2.7*", maxContext = 196_608, functionCalling = false, inPrice = 0.15, outPrice = 1.15),
        "minimax/minimax-m2" to openrouterDef("minimax/minimax-m2*", maxContext = 196_608, inPrice = 0.15, outPrice = 1.15),
        "minimax/" to openrouterDef("minimax/*", maxContext = 128_000, inPrice = 0.15, outPrice = 1.15),
        // Z.AI GLM — glm-5.1 $1.05/$3.50
        "z-ai/glm-5v" to openrouterDef("z-ai/glm-5v*", maxContext = 202_752, vision = true, inPrice = 1.20, outPrice = 4.0),
        "z-ai/glm-" to openrouterDef("z-ai/glm-*", maxContext = 202_752, inPrice = 1.05, outPrice = 3.50),
        "z-ai/" to openrouterDef("z-ai/*", maxContext = 128_000, inPrice = 1.0, outPrice = 3.0),
        // ByteDance Seed
        "bytedance-seed/" to openrouterDef("bytedance-seed/*", maxContext = 262_144, vision = true, inPrice = 0.40, outPrice = 1.40),
        // NVIDIA Nemotron — super-120b $0.09/$0.45
        "nvidia/nemotron" to openrouterDef("nvidia/nemotron-*", maxContext = 262_144, inPrice = 0.09, outPrice = 0.45),
        "nvidia/" to openrouterDef("nvidia/*", maxContext = 128_000, inPrice = 0.09, outPrice = 0.45),
        // InclusionAI (Ling family) — small open models
        "inclusionai/" to openrouterDef("inclusionai/*", maxContext = 262_144, inPrice = 0.10, outPrice = 0.20),
        // Arcee
        "arcee-ai/" to openrouterDef("arcee-ai/*", maxContext = 262_144, inPrice = 0.30, outPrice = 0.80),
        // Kwai
        "kwaipilot/" to openrouterDef("kwaipilot/*", maxContext = 256_000, inPrice = 0.30, outPrice = 0.80),
        // Reka
        "rekaai/" to openrouterDef("rekaai/*", maxContext = 16_384, vision = true, inPrice = 0.40, outPrice = 1.20),
        // Xiaomi
        "xiaomi/mimo" to openrouterDef("xiaomi/mimo*", maxContext = 1_048_576, vision = true, inPrice = 0.30, outPrice = 0.80),
        // Upstage
        "upstage/solar" to openrouterDef("upstage/solar-*", maxContext = 128_000, inPrice = 0.30, outPrice = 0.30),
        // Inception
        "inception/mercury" to openrouterDef("inception/mercury*", maxContext = 128_000, inPrice = 0.50, outPrice = 1.50),
        // Tencent Hunyuan
        "tencent/" to openrouterDef("tencent/*", maxContext = 262_144, inPrice = 0.10, outPrice = 0.20),
        // Writer Palmyra — premium B2B writing
        "writer/palmyra" to openrouterDef("writer/palmyra-*", maxContext = 1_040_000, inPrice = 5.0, outPrice = 15.0),
        // Allen AI
        "allenai/olmo" to openrouterDef("allenai/olmo-*", maxContext = 65_536, inPrice = 0.10, outPrice = 0.20),
        // StepFun
        "stepfun/step" to openrouterDef("stepfun/step-*", maxContext = 262_144, inPrice = 0.20, outPrice = 0.40),
        // Liquid LFM — small efficient models
        "liquid/lfm" to openrouterDef("liquid/lfm-*", maxContext = 32_768, inPrice = 0.10, outPrice = 0.10),
        // AION Labs
        "aion-labs/" to openrouterDef("aion-labs/*", maxContext = 131_072, inPrice = 0.30, outPrice = 0.80),
        // Baidu Ernie
        "baidu/" to openrouterDef("baidu/*", maxContext = 65_536, vision = true, inPrice = 0.20, outPrice = 0.60),
        // OpenRouter meta-models (auto-router)
        "openrouter/" to openrouterDef("openrouter/*", maxContext = 200_000, vision = true, inPrice = 1.0, outPrice = 3.0)
    )

    private fun openrouterDef(
        id: String,
        maxContext: Int,
        vision: Boolean = false,
        reasoning: Boolean = false,
        /**
         * Whether the upstream model reliably emits native OpenAI-format
         * `tool_calls`. When false, AgentTurnLoop falls back to the
         * `response-contract-json` system prompt and parses JSON envelopes
         * out of plain text — that is the right path for models which
         * advertise function calling but actually emit pseudo-XML
         * (e.g. `<minimax:tool_call>`, `<create_new_file>...</...>`).
         */
        functionCalling: Boolean = true,
        /**
         * USD per 1M input tokens. Family-level baseline used when no live
         * pricing is available from OpenRouter's `/models` endpoint.
         * Default 0.0 → "unknown / will be overridden by live data".
         * Live prices from [OpenRouterAdapter.parseModelsPayload] override
         * these via [getModelPricing] cache fallback.
         */
        inPrice: Double = 0.0,
        outPrice: Double = 0.0,
    ): ModelDefinition = ModelDefinition(
        id = id,
        name = id,
        provider = "openrouter",
        description = if (functionCalling)
            "OpenRouter curated family with native function-calling"
        else
            "OpenRouter family that does NOT emit native tool_calls — JSON envelope only",
        capabilities = buildList {
            add(ModelCapability.CHAT_COMPLETION)
            add(ModelCapability.TEXT_COMPLETION)
            if (vision) add(ModelCapability.VISION)
        },
        modelType = ModelType.TEXT,
        maxContext = maxContext,
        maxOutputTokens = null,
        costPer1MInput = inPrice,
        costPer1MOutput = outPrice,
        supportsVision = vision,
        supportsReasoning = reasoning,
        supportsStreaming = true,
        supportsFunctionCalling = functionCalling,
        endpointType = ApiEndpointType.CHAT_COMPLETIONS,
        apiFormat = ApiFormat.CHAT_COMPLETIONS,
        active = true
    )

    /**
     * Get model definition by ID and provider.
     * Returns null if not found (use fallback).
     *
     * @param provider Provider name (openai, anthropic, ollama)
     * @param modelId Model identifier
     * @return ModelDefinition if found, null otherwise
     */
    fun getDefinition(provider: String, modelId: String): ModelDefinition? {
        return when (provider.lowercase()) {
            "openai" -> OPENAI_MODELS[modelId]
            "anthropic" -> ANTHROPIC_MODELS[modelId]
            "ollama" -> OLLAMA_MODELS[modelId]
            "gemini" -> GEMINI_MODELS[modelId]
            "lmstudio" -> LM_STUDIO_MODELS[modelId]
            "zai" -> ZAI_MODELS[modelId]
            "openrouter" -> OPENROUTER_MODELS.entries
                .firstOrNull { (prefix, _) -> modelId.startsWith(prefix) }
                ?.value
                ?.copy(id = modelId, name = modelId)
            else -> null
        }
    }

    /**
     * Get all definitions for a provider.
     *
     * @param provider Provider name
     * @return Map of model ID to ModelDefinition
     */
    fun getProviderDefinitions(provider: String): Map<String, ModelDefinition> {
        return when (provider.lowercase()) {
            "openai" -> OPENAI_MODELS
            "anthropic" -> ANTHROPIC_MODELS
            "ollama" -> OLLAMA_MODELS
            "gemini" -> GEMINI_MODELS
            "lmstudio" -> LM_STUDIO_MODELS
            "zai" -> ZAI_MODELS
            else -> emptyMap()
        }
    }

    /**
     * Create synthetic ModelDefinition for models not yet in registry.
     *
     * Semantyka: provider API zwraca model którego nie ma w naszym statycznym rejestrze
     * (nowy release). Tworzymy best-effort definicję z defaultami — **nie jest to
     * silent default**, callery muszą zalogować WARN. Zgodne z regułą "no fallbacks":
     * to nie fallback w execution path, tylko enumeracja zewnętrznych zasobów.
     *
     * @param provider Provider name
     * @param modelId Model identifier
     * @param maxContext Context window size (default: 32768)
     * @return ModelDefinition with conservative defaults
     */
    fun syntheticDefinitionFor(
        provider: String,
        modelId: String,
        maxContext: Int = DEFAULT_CONTEXT_SIZE
    ): ModelDefinition {
        return ModelDefinition(
            id = modelId,
            name = modelId,
            provider = provider,
            description = "Unknown model (synthetic definition)",
            capabilities = listOf(ModelCapability.CHAT_COMPLETION),
            modelType = ModelType.TEXT,
            maxContext = maxContext,
            maxOutputTokens = null,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsVision = false,
            supportsReasoning = false,
            supportsStreaming = true,
            supportsFunctionCalling = false,
            active = true
        )
    }
}

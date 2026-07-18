package pl.jclab.refio.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeneralConfig(
    val formatMarkdown: Boolean? = null,
    val streamingEnabled: Boolean? = null,
    val advancedView: Boolean? = null,
    // Legacy boolean, kept for backward compatibility with older config.yaml files.
    // Superseded by [reasoningEffort]; mapped on import when reasoningEffort is absent.
    val thinkingEnabled: Boolean? = null,
    val reasoningEffort: String? = null,
    val noEgressEnabled: Boolean? = null,
    val executionMode: String? = null,
    val nativeToolsMode: String? = null
)

@Serializable
data class ProvidersConfig(
    val ollama: OllamaConfig? = null,
    val anthropic: AnthropicConfig? = null,
    val openai: OpenAIConfig? = null,
    val openrouter: OpenRouterConfig? = null,
    val gemini: GeminiConfig? = null,
    val lmstudio: LMStudioConfig? = null,
    @SerialName("generic_openai")
    val genericOpenai: GenericOpenAIConfig? = null,
    val zai: ZAIConfig? = null
)

@Serializable
data class OllamaConfig(
    val endpoint: String? = null,
    val contextSize: Int? = null,
    val keepAlive: Int? = null
)

@Serializable
data class AnthropicConfig(val apiKey: String? = null)

@Serializable
data class OpenAIConfig(val apiKey: String? = null)

@Serializable
data class OpenRouterConfig(val apiKey: String? = null)

@Serializable
data class GeminiConfig(val apiKey: String? = null)

@Serializable
data class LMStudioConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val contextSize: Int? = null
)

@Serializable
data class GenericOpenAIConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val model: String? = null
)

@Serializable
data class ZAIConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null
)

@Serializable
data class ModelsConfig(
    val default: String? = null,
    val defaults: ModelDefaultsConfig? = null,
    val visibility: Map<String, Boolean>? = null,
    val presets: List<ModelPresetConfig>? = null
)

@Serializable
data class ModelDefaultsConfig(
    val chat: String? = null,
    val plan: String? = null,
    val coding: String? = null,
    val weak: String? = null,
    val embedding: String? = null,
    val strong: String? = null
)

@Serializable
data class ModelPresetConfig(
    val name: String,
    val description: String? = null,
    val defaultModel: String,
    val planModel: String? = null,
    val codingModel: String? = null,
    val weakModel: String? = null,
    val strongModel: String? = null,
    val visibleModels: List<String>? = null
)

@Serializable
data class LimitsConfig(
    val apiCallTimeout: Int? = null,
    val toolExecutionTimeout: Int? = null,
    val streamingReadTimeout: Int? = null,
    val streamingRequestTimeout: Int? = null,
    val maxContextSize: Int? = null,
    val maxOutputSize: Int? = null,
    val maxFileSize: Int? = null
)

/**
 * Deterministic post-turn verification (project build/test run by the agent loop after a
 * file-writing AGENT turn completes). Typically set in the project config
 * (`<project>/.refio/config.yaml`) since the command is project-specific.
 */
@Serializable
data class VerifyConfig(
    val enabled: Boolean? = null,
    val command: String? = null,
    val maxRepairRounds: Int? = null
)

@Serializable
data class AdvancedConfig(
    val readOnlyMode: Boolean? = null,
    val autoOptimizePercentage: Int? = null
)

@Serializable
data class ToolsConfig(val permissions: Map<String, ToolPermissionConfig>? = null)

@Serializable
data class ToolPermissionConfig(
    val planMode: String? = null,
    val agentMode: String? = null
)

@Serializable
data class RagConfig(
    val enabled: Boolean? = null,
    val indexOnStartup: Boolean? = null,
    val autoIndexOnContextBuild: Boolean? = null,
    val maxFileSizeMB: Long? = null,
    val maxChunksPerFile: Int? = null,
    val indexBatchSize: Int? = null,
    val embeddingsBatchSize: Int? = null,
    val cacheTtlMs: Long? = null,
    val maxConcurrentJobs: Int? = null,
    val ignoredDirectories: List<String>? = null,
    val searchSimilarityThreshold: Float? = null,
    val searchTopK: Int? = null,
    val searchHybridEnabled: Boolean? = null,
    val searchSemanticWeight: Float? = null,
    val searchIncludeContextChunks: Boolean? = null
)

@Serializable
data class UiConfig(
    val selectedMode: String? = null,
    val selectedModel: String? = null
)

@Serializable
data class PromptsConfig(
    val systemChat: String? = null,
    val systemPlan: String? = null,
    val systemAgent: String? = null,
    val commands: List<CommandConfig>? = null,
    val rules: List<RuleConfig>? = null
)

@Serializable
data class CommandConfig(
    val name: String,
    val description: String? = null,
    val content: String,
    val enabled: Boolean = true
)

@Serializable
data class RuleConfig(
    val name: String,
    val content: String,
    val enabled: Boolean = true
)

@Serializable
data class McpConfig(val servers: List<McpServerConfig>? = null)

@Serializable
data class McpServerConfig(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
    val type: String = "STDIO",
    val command: String? = null,
    val args: List<String>? = null,
    val workingDirectory: String? = null,
    val url: String? = null,
    val accessMode: String = "READ",
    val enabled: Boolean = true,
    val env: List<McpEnvConfig>? = null,
    val httpHeaders: List<McpHeaderConfig>? = null,
    val timeout: Int? = null,
    val retryAttempts: Int? = null,
    val auth: McpAuthYamlConfig? = null,
    val serverInstructions: String? = null,
    val resourcesEnabled: Boolean? = null,
    val toolsEnabled: Boolean? = null,
    val promptsEnabled: Boolean? = null,
    val toolsExposureMode: String? = null,
    val contextToolName: String? = null,
    val contextToolQueryParam: String? = null,
    val toolParamMapping: Map<String, String>? = null
)

@Serializable
data class McpAuthYamlConfig(
    val type: String,
    val apiKey: String? = null
)

@Serializable
data class McpEnvConfig(
    val name: String,
    val value: String,
    val isSecret: Boolean = false
)

@Serializable
data class McpHeaderConfig(
    val name: String,
    val value: String,
    val isSecret: Boolean = false
)

@Serializable
data class ContextConfig(
    val recentWorkFullDataLimit: Int? = null,
    val recentWorkSummaryMaxLength: Int? = null,
    val budgetTotalTokens: Int? = null,
    val budgetInputRatio: Double? = null,
    val workingMemoryMaxFacts: Int? = null,
    val budgetSections: Map<String, Int>? = null
)

@Serializable
data class DocsConfig(val sources: List<DocsSourceConfig>? = null)

@Serializable
data class DocsSourceConfig(
    val url: String,
    val sourceType: String = "URL",
    val filePath: String? = null,
    val title: String? = null,
    val description: String? = null,
    val crawlDepth: Int? = null,
    val status: String? = null,
    val pagesIndexed: Int? = null,
    val totalPages: Int? = null,
    val lastIndexed: Long? = null
)

@Serializable
data class HooksConfig(
    @SerialName("before_turn_loop")
    val beforeTurnLoop: List<HookDefinition>? = null,
    @SerialName("after_turn_loop")
    val afterTurnLoop: List<HookDefinition>? = null,
    @SerialName("before_tool")
    val beforeTool: List<HookDefinition>? = null,
    @SerialName("after_tool")
    val afterTool: List<HookDefinition>? = null,
    @SerialName("on_agent_complete")
    val onAgentComplete: List<HookDefinition>? = null,
    @SerialName("on_agent_error")
    val onAgentError: List<HookDefinition>? = null
)

@Serializable
data class HookDefinition(
    val action: String,
    val command: String? = null,
    val message: String? = null,
    val match: String? = null,
    val modes: List<String>? = null,
    val timeout: Long? = null
)

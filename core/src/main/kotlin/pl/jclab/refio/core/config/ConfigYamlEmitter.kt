package pl.jclab.refio.core.config

/**
 * Pretty-printers for [ConfigYaml]:
 *  - [createCommentedYaml] serialises an in-memory config with section-header
 *    comments for the Settings UI "Export" flow.
 *  - [createExampleConfig] returns a static, fully-documented template shown
 *    to users in the "Example config" panel.
 *
 * Pulled out of [ConfigYaml] so the data class stays focused on (de)serialisation.
 * Both functions are pure (no I/O).
 */
internal object ConfigYamlEmitter {

    fun createCommentedYaml(config: ConfigYaml): String {
        val sb = StringBuilder()

        sb.appendLine("# ═══════════════════════════════════════════════════════════════════════════════")
        sb.appendLine("# Refio Configuration File")
        sb.appendLine("# Generated: ${java.time.LocalDateTime.now()}")
        sb.appendLine("# ═══════════════════════════════════════════════════════════════════════════════")
        sb.appendLine()

        config.general?.let { general ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# General Settings")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("general:")
            general.formatMarkdown?.let { sb.appendLine("  formatMarkdown: $it") }
            general.streamingEnabled?.let { sb.appendLine("  streamingEnabled: $it") }
            general.advancedView?.let { sb.appendLine("  advancedView: $it") }
            general.thinkingEnabled?.let { sb.appendLine("  thinkingEnabled: $it") }
            general.reasoningEffort?.let { sb.appendLine("  reasoningEffort: \"$it\"") }
            general.noEgressEnabled?.let { sb.appendLine("  noEgressEnabled: $it") }
            general.executionMode?.let { sb.appendLine("  executionMode: \"$it\"") }
            general.nativeToolsMode?.let { sb.appendLine("  nativeToolsMode: \"$it\"") }
            sb.appendLine()
        }

        config.providers?.let { providers ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# LLM Provider Configuration")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("providers:")

            providers.ollama?.let { ollama ->
                sb.appendLine("  ollama:")
                ollama.endpoint?.let { sb.appendLine("    endpoint: \"$it\"") }
                ollama.contextSize?.let { sb.appendLine("    contextSize: $it") }
            }
            providers.anthropic?.let { it.apiKey?.let { k -> sb.appendLine("  anthropic:"); sb.appendLine("    apiKey: \"$k\"") } }
            providers.openai?.let { it.apiKey?.let { k -> sb.appendLine("  openai:"); sb.appendLine("    apiKey: \"$k\"") } }
            providers.openrouter?.let { it.apiKey?.let { k -> sb.appendLine("  openrouter:"); sb.appendLine("    apiKey: \"$k\"") } }
            providers.gemini?.let { it.apiKey?.let { k -> sb.appendLine("  gemini:"); sb.appendLine("    apiKey: \"$k\"") } }
            providers.lmstudio?.let { lmstudio ->
                sb.appendLine("  lmstudio:")
                lmstudio.apiKey?.let { sb.appendLine("    apiKey: \"$it\"") }
                lmstudio.baseUrl?.let { sb.appendLine("    baseUrl: \"$it\"") }
                lmstudio.contextSize?.let { sb.appendLine("    contextSize: $it") }
            }
            providers.genericOpenai?.let { gen ->
                sb.appendLine("  generic_openai:")
                gen.apiKey?.let { sb.appendLine("    apiKey: \"$it\"") }
                gen.baseUrl?.let { sb.appendLine("    baseUrl: \"$it\"") }
                gen.model?.let { sb.appendLine("    model: \"$it\"") }
                gen.contextSize?.let { sb.appendLine("    contextSize: $it") }
                gen.rawRequest?.let { sb.appendLine("    rawRequest: $it") }
            }
            providers.zai?.let { zai ->
                sb.appendLine("  zai:")
                zai.apiKey?.let { sb.appendLine("    apiKey: \"$it\"") }
                zai.baseUrl?.let { sb.appendLine("    baseUrl: \"$it\"") }
            }
            providers.embeddings?.let { embeddings ->
                if (embeddings.baseUrl != null || embeddings.apiKey != null) {
                    sb.appendLine("  embeddings:")
                    embeddings.baseUrl?.let { sb.appendLine("    baseUrl: \"$it\"") }
                    embeddings.apiKey?.let { sb.appendLine("    apiKey: \"$it\"") }
                }
            }
            sb.appendLine()
        }

        config.models?.let { models ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# Model Configuration")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("models:")
            models.defaults?.let { defaults ->
                sb.appendLine("  defaults:")
                defaults.chat?.let { sb.appendLine("    chat: \"$it\"") }
                defaults.plan?.let { sb.appendLine("    plan: \"$it\"") }
                defaults.coding?.let { sb.appendLine("    coding: \"$it\"") }
                defaults.weak?.let { sb.appendLine("    weak: \"$it\"") }
                defaults.embedding?.let { sb.appendLine("    embedding: \"$it\"") }
            }
            models.visibility?.takeIf { it.isNotEmpty() }?.let { visibility ->
                sb.appendLine("  visibility:")
                visibility.forEach { (model, visible) ->
                    sb.appendLine("    \"$model\": $visible")
                }
            }
            sb.appendLine()
        }

        config.limits?.let { limits ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# System Limits")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("limits:")
            limits.apiCallTimeout?.let { sb.appendLine("  apiCallTimeout: $it") }
            limits.toolExecutionTimeout?.let { sb.appendLine("  toolExecutionTimeout: $it") }
            limits.streamingReadTimeout?.let { sb.appendLine("  streamingReadTimeout: $it") }
            limits.streamingRequestTimeout?.let { sb.appendLine("  streamingRequestTimeout: $it") }
            limits.maxContextSize?.let { sb.appendLine("  maxContextSize: $it") }
            limits.maxOutputSize?.let { sb.appendLine("  maxOutputSize: $it") }
            limits.maxFileSize?.let { sb.appendLine("  maxFileSize: $it") }
            sb.appendLine()
        }

        config.advanced?.let { advanced ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# Advanced Settings")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("advanced:")
            advanced.readOnlyMode?.let { sb.appendLine("  readOnlyMode: $it") }
            advanced.autoOptimizePercentage?.let { sb.appendLine("  autoOptimizePercentage: $it") }
            sb.appendLine()
        }

        config.tools?.permissions?.takeIf { it.isNotEmpty() }?.let { permissions ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# Tool Permissions")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("tools:")
            sb.appendLine("  permissions:")
            permissions.forEach { (tool, perm) ->
                sb.appendLine("    $tool:")
                perm.planMode?.let { sb.appendLine("      planMode: \"$it\"") }
                perm.agentMode?.let { sb.appendLine("      agentMode: \"$it\"") }
            }
            sb.appendLine()
        }

        config.rag?.let { rag ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# RAG Configuration")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("rag:")
            rag.enabled?.let { sb.appendLine("  enabled: $it") }
            rag.indexOnStartup?.let { sb.appendLine("  indexOnStartup: $it") }
            rag.autoIndexOnContextBuild?.let { sb.appendLine("  autoIndexOnContextBuild: $it") }
            rag.maxFileSizeMB?.let { sb.appendLine("  maxFileSizeMB: $it") }
            rag.maxChunksPerFile?.let { sb.appendLine("  maxChunksPerFile: $it") }
            rag.indexBatchSize?.let { sb.appendLine("  indexBatchSize: $it") }
            rag.embeddingsBatchSize?.let { sb.appendLine("  embeddingsBatchSize: $it") }
            rag.cacheTtlMs?.let { sb.appendLine("  cacheTtlMs: $it") }
            rag.maxConcurrentJobs?.let { sb.appendLine("  maxConcurrentJobs: $it") }
            rag.ignoredDirectories?.takeIf { it.isNotEmpty() }?.let { dirs ->
                sb.appendLine("  ignoredDirectories:")
                dirs.forEach { sb.appendLine("    - \"$it\"") }
            }
            sb.appendLine()
        }

        config.ui?.let { ui ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# UI State")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("ui:")
            ui.selectedMode?.let { sb.appendLine("  selectedMode: \"$it\"") }
            ui.selectedModel?.let { sb.appendLine("  selectedModel: \"$it\"") }
            sb.appendLine()
        }

        config.prompts?.let { prompts ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# Custom Prompts (project-specific)")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("prompts:")

            prompts.systemChat?.let {
                sb.appendLine("  systemChat: |")
                it.lines().forEach { line -> sb.appendLine("    $line") }
            }
            prompts.systemPlan?.let {
                sb.appendLine("  systemPlan: |")
                it.lines().forEach { line -> sb.appendLine("    $line") }
            }
            prompts.systemAgent?.let {
                sb.appendLine("  systemAgent: |")
                it.lines().forEach { line -> sb.appendLine("    $line") }
            }

            prompts.commands?.takeIf { it.isNotEmpty() }?.let { commands ->
                sb.appendLine("  commands:")
                commands.forEach { cmd ->
                    sb.appendLine("    - name: \"${cmd.name}\"")
                    cmd.description?.let { sb.appendLine("      description: \"$it\"") }
                    sb.appendLine("      content: \"${cmd.content.replace("\"", "\\\"")}\"")
                    sb.appendLine("      enabled: ${cmd.enabled}")
                }
            }
            prompts.rules?.takeIf { it.isNotEmpty() }?.let { rules ->
                sb.appendLine("  rules:")
                rules.forEach { rule ->
                    sb.appendLine("    - name: \"${rule.name}\"")
                    sb.appendLine("      content: \"${rule.content.replace("\"", "\\\"")}\"")
                    sb.appendLine("      enabled: ${rule.enabled}")
                }
            }
            sb.appendLine()
        }

        config.mcp?.servers?.takeIf { it.isNotEmpty() }?.let { servers ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# MCP Server Configuration (project-specific)")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("mcp:")
            sb.appendLine("  servers:")
            servers.forEach { server ->
                sb.appendLine("    - id: \"${server.id}\"")
                server.displayName?.let { sb.appendLine("      displayName: \"$it\"") }
                server.description?.let { sb.appendLine("      description: \"${it.replace("\"", "\\\"")}\"") }
                sb.appendLine("      type: \"${server.type}\"")
                server.command?.let { sb.appendLine("      command: \"$it\"") }
                server.args?.takeIf { it.isNotEmpty() }?.let { args ->
                    sb.appendLine("      args: [${args.joinToString(", ") { "\"$it\"" }}]")
                }
                server.workingDirectory?.let { sb.appendLine("      workingDirectory: \"${it.replace("\\", "\\\\")}\"") }
                server.url?.let { sb.appendLine("      url: \"$it\"") }
                sb.appendLine("      accessMode: \"${server.accessMode}\"")
                sb.appendLine("      enabled: ${server.enabled}")
                server.auth?.let { auth ->
                    sb.appendLine("      auth:")
                    sb.appendLine("        type: \"${auth.type}\"")
                    auth.apiKey?.let { sb.appendLine("        apiKey: \"$it\"") }
                }
                server.httpHeaders?.takeIf { it.isNotEmpty() }?.let { headers ->
                    sb.appendLine("      httpHeaders:")
                    headers.forEach { h ->
                        sb.appendLine("        - name: \"${h.name}\"")
                        sb.appendLine("          value: \"${h.value}\"")
                        if (h.isSecret) sb.appendLine("          isSecret: true")
                    }
                }
                server.env?.takeIf { it.isNotEmpty() }?.let { envs ->
                    sb.appendLine("      env:")
                    envs.forEach { env ->
                        sb.appendLine("        - name: \"${env.name}\"")
                        sb.appendLine("          value: \"${env.value}\"")
                        if (env.isSecret) sb.appendLine("          isSecret: true")
                    }
                }
                server.serverInstructions?.let {
                    val escaped = it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    sb.appendLine("      serverInstructions: \"$escaped\"")
                }
                server.resourcesEnabled?.let { sb.appendLine("      resourcesEnabled: $it") }
                server.toolsEnabled?.let { sb.appendLine("      toolsEnabled: $it") }
                server.promptsEnabled?.let { sb.appendLine("      promptsEnabled: $it") }
                server.toolsExposureMode?.let { sb.appendLine("      toolsExposureMode: \"$it\"") }
                server.contextToolName?.let { sb.appendLine("      contextToolName: \"$it\"") }
                server.contextToolQueryParam?.let { sb.appendLine("      contextToolQueryParam: \"$it\"") }
                server.toolParamMapping?.takeIf { it.isNotEmpty() }?.let { mapping ->
                    sb.appendLine("      toolParamMapping:")
                    mapping.forEach { (k, v) -> sb.appendLine("        \"$k\": \"$v\"") }
                }
                server.timeout?.let { sb.appendLine("      timeout: $it") }
                server.retryAttempts?.let { sb.appendLine("      retryAttempts: $it") }
            }
            sb.appendLine()
        }

        config.context?.let { ctx ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# Context / Budget Settings")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("context:")
            ctx.recentWorkFullDataLimit?.let { sb.appendLine("  recentWorkFullDataLimit: $it") }
            ctx.recentWorkSummaryMaxLength?.let { sb.appendLine("  recentWorkSummaryMaxLength: $it") }
            ctx.budgetTotalTokens?.let { sb.appendLine("  budgetTotalTokens: $it") }
            ctx.budgetInputRatio?.let { sb.appendLine("  budgetInputRatio: $it") }
            ctx.workingMemoryMaxFacts?.let { sb.appendLine("  workingMemoryMaxFacts: $it") }
            ctx.budgetSections?.takeIf { it.isNotEmpty() }?.let { sections ->
                sb.appendLine("  budgetSections:")
                sections.toSortedMap().forEach { (k, v) -> sb.appendLine("    \"$k\": $v") }
            }
            sb.appendLine()
        }

        config.docs?.sources?.takeIf { it.isNotEmpty() }?.let { sources ->
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("# Documentation Sources (project-specific)")
            sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
            sb.appendLine("docs:")
            sb.appendLine("  sources:")
            sources.forEach { s ->
                sb.appendLine("    - url: \"${s.url}\"")
                sb.appendLine("      sourceType: \"${s.sourceType}\"")
                s.filePath?.let { sb.appendLine("      filePath: \"${it.replace("\\", "\\\\")}\"") }
                s.title?.let { sb.appendLine("      title: \"${it.replace("\"", "\\\"")}\"") }
                s.description?.let { sb.appendLine("      description: \"${it.replace("\"", "\\\"")}\"") }
                s.crawlDepth?.let { sb.appendLine("      crawlDepth: $it") }
                s.status?.let { sb.appendLine("      status: \"$it\"") }
                s.pagesIndexed?.let { sb.appendLine("      pagesIndexed: $it") }
                s.totalPages?.let { sb.appendLine("      totalPages: $it") }
                s.lastIndexed?.let { sb.appendLine("      lastIndexed: $it") }
            }
            sb.appendLine()
        }

        config.hooks?.let { hooks ->
            val groups = listOf(
                "before_turn_loop" to hooks.beforeTurnLoop,
                "after_turn_loop" to hooks.afterTurnLoop,
                "before_tool" to hooks.beforeTool,
                "after_tool" to hooks.afterTool,
                "on_agent_complete" to hooks.onAgentComplete,
                "on_agent_error" to hooks.onAgentError
            ).filter { !it.second.isNullOrEmpty() }
            if (groups.isNotEmpty()) {
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("# Hooks")
                sb.appendLine("# ─────────────────────────────────────────────────────────────────────────────")
                sb.appendLine("hooks:")
                groups.forEach { (name, list) ->
                    sb.appendLine("  $name:")
                    list!!.forEach { h ->
                        sb.appendLine("    - action: \"${h.action}\"")
                        h.command?.let { sb.appendLine("      command: \"${it.replace("\"", "\\\"")}\"") }
                        h.message?.let { sb.appendLine("      message: \"${it.replace("\"", "\\\"")}\"") }
                        h.match?.let { sb.appendLine("      match: \"${it.replace("\"", "\\\"")}\"") }
                        h.modes?.takeIf { it.isNotEmpty() }?.let { modes ->
                            sb.appendLine("      modes: [${modes.joinToString(", ") { "\"$it\"" }}]")
                        }
                        h.timeout?.let { sb.appendLine("      timeout: $it") }
                    }
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    private const val EXAMPLE_CONFIG_RESOURCE = "/config/example-config.yaml"

    /**
     * Loads the static example config YAML from [EXAMPLE_CONFIG_RESOURCE].
     * Centralizing the ~230 LOC template as a resource removes boilerplate from
     * this file and lets editors treat it as actual YAML.
     */
    fun createExampleConfig(): String {
        val resource = ConfigYamlEmitter::class.java.getResourceAsStream(EXAMPLE_CONFIG_RESOURCE)
            ?: error("Missing classpath resource: $EXAMPLE_CONFIG_RESOURCE")
        return resource.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

}

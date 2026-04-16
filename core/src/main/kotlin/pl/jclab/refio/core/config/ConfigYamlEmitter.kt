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
            }
            providers.zai?.let { zai ->
                sb.appendLine("  zai:")
                zai.apiKey?.let { sb.appendLine("    apiKey: \"$it\"") }
                zai.baseUrl?.let { sb.appendLine("    baseUrl: \"$it\"") }
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
            advanced.noEgressDefault?.let { sb.appendLine("  noEgressDefault: $it") }
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
            ui.thinkingEnabled?.let { sb.appendLine("  thinkingEnabled: $it") }
            ui.noEgressEnabled?.let { sb.appendLine("  noEgressEnabled: $it") }
            ui.executionMode?.let { sb.appendLine("  executionMode: \"$it\"") }
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
                sb.appendLine("      type: \"${server.type}\"")
                server.command?.let { sb.appendLine("      command: \"$it\"") }
                server.args?.takeIf { it.isNotEmpty() }?.let { args ->
                    sb.appendLine("      args: [${args.joinToString(", ") { "\"$it\"" }}]")
                }
                server.url?.let { sb.appendLine("      url: \"$it\"") }
                sb.appendLine("      accessMode: \"${server.accessMode}\"")
                sb.appendLine("      enabled: ${server.enabled}")
                server.env?.takeIf { it.isNotEmpty() }?.let { envs ->
                    sb.appendLine("      env:")
                    envs.forEach { env ->
                        sb.appendLine("        - name: \"${env.name}\"")
                        val value = if (env.isSecret) "***" else env.value
                        sb.appendLine("          value: \"$value\"")
                        if (env.isSecret) sb.appendLine("          isSecret: true")
                    }
                }
            }
            sb.appendLine()
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

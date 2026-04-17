package pl.jclab.refio.cli.tui.screens

import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiViewModel

/**
 * Settings screen with 11 sub-tabs.
 * Reads/writes real config via ConfigRouter through TuiViewModel.
 *
 * Config keys follow ConfigService.kt conventions:
 * - getConfig(section) returns keys with section prefix stripped
 * - e.g. getConfig("providers") returns "ollama.ollama_endpoint" (from "providers.ollama.ollama_endpoint")
 *
 * Sub-tabs:
 * [1] General    [2] Providers  [3] Models    [4] Prompts
 * [5] Context    [6] MCP        [7] Docs      [8] Tools
 * [9] Subagents  [10] Advanced  [11] Theme
 */
object TuiSettingsScreen {

    private val settingsTabs = listOf(
        "General", "Providers", "Models", "Prompts", "Context",
        "MCP", "Docs", "Tools", "Subagents", "Advanced", "Theme"
    )

    /** Cached config sections - refreshed on tab switch. */
    private var cachedSection: String = ""
    private var cachedConfig: Map<String, String> = emptyMap()
    private var viewModel: TuiViewModel? = null

    /** Fields in current tab for navigation. Each entry = (section.key, label, type). */
    private var currentFields: List<SettingsField> = emptyList()

    data class SettingsField(
        val sectionKey: String, // e.g. "general.streaming_enabled"
        val label: String,
        val type: FieldType
    )

    enum class FieldType { BOOL, TEXT }

    fun setViewModel(vm: TuiViewModel) {
        viewModel = vm
    }

    /**
     * Render settings screen into a list of exactly [contentHeight] lines,
     * each padded to [width] visible characters.
     */
    fun renderToLines(state: TuiState, width: Int, contentHeight: Int): List<String> {
        val buf = TuiRenderBuffer(width, contentHeight)

        buf.addLine(bold("Settings"))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))

        // Settings tab bar
        val tabBar = settingsTabs.mapIndexed { i, name ->
            val label = " ${i + 1}:$name "
            if (i == state.settingsTab) TuiColors.tabActive(label) else TuiColors.tabInactive(label)
        }.joinToString("")
        buf.addLine(tabBar)
        buf.addLine()

        // Load config for active tab
        val activeTab = settingsTabs.getOrElse(state.settingsTab) { "General" }
        val section = tabToSection(activeTab)
        if (section != cachedSection) {
            cachedSection = section
            cachedConfig = viewModel?.getConfigSection(section) ?: emptyMap()
        }

        // Build and render settings content with field tracking
        currentFields = emptyList()
        renderSettingsContent(buf, activeTab, cachedConfig, state)

        buf.addLine()
        if (state.settingsEditingField != null) {
            buf.addLine(TuiColors.accent("Editing: ") + state.settingsEditBuffer + TuiColors.muted("_"))
            buf.addLine(TuiColors.muted("[Enter] Save  [Esc] Cancel"))
        } else {
            buf.addLine(TuiColors.muted("Nav: [←→] tab  [↑↓] field  [Enter] toggle/edit  [R]eset [E]xport [L]oad  Esc=back"))
        }

        return buf.getLines()
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        for (line in renderToLines(state, 200, contentHeight)) {
            terminal.println(line)
        }
    }

    /** Force config reload on next render. */
    fun invalidateCache() {
        cachedSection = ""
        cachedConfig = emptyMap()
    }

    /**
     * Map tab name to config section name.
     * Must match the section prefixes in ConfigService.kt KEY_* constants.
     */
    private fun tabToSection(tab: String): String = when (tab) {
        "General" -> "general"       // general.format_markdown, general.streaming_enabled, ...
        "Providers" -> "providers"   // providers.ollama.ollama_endpoint, ...
        "Models" -> "default_model"  // default_model.chat, default_model.plan, ...
        "Prompts" -> "prompts"
        "Context" -> "rag"           // rag.search_similarity_threshold, rag.enabled, ...
        "MCP" -> "mcp"
        "Docs" -> "docs"
        "Tools" -> "tools"
        "Subagents" -> "subagents"
        "Advanced" -> "advanced"     // advanced.no_egress_default, ...
        "Theme" -> "theme"
        else -> "general"
    }

    private fun renderSettingsContent(buf: TuiRenderBuffer, tab: String, config: Map<String, String>, state: TuiState) {
        when (tab) {
            "General" -> renderGeneral(buf, config)
            "Providers" -> renderProviders(buf, config)
            "Models" -> renderModels(buf, config)
            "Prompts" -> renderPrompts(buf, config)
            "Context" -> renderContext(buf, config)
            "MCP" -> renderMcp(buf, config)
            "Docs" -> renderDocs(buf, config)
            "Tools" -> renderTools(buf, config)
            "Subagents" -> renderSubagents(buf, config)
            "Advanced" -> renderAdvanced(buf, config)
            "Theme" -> renderTheme(buf)
        }
    }

    /** Get the field at the currently selected index. */
    fun getSelectedField(index: Int): SettingsField? = currentFields.getOrNull(index)

    /** Number of navigable fields in current tab. */
    fun fieldCount(): Int = currentFields.size

    // ── General ──────────────────────────────────────────────────────────
    // Keys from ConfigService: general.format_markdown, general.streaming_enabled, general.advanced_view
    // After getConfig("general") → short keys: format_markdown, streaming_enabled, advanced_view

    private fun renderGeneral(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Display")}")
        renderBool(buf, "format_markdown", "Markdown rendering", config)
        renderBool(buf, "streaming_enabled", "Stream responses", config)
        renderBool(buf, "advanced_view", "Advanced view (show all tabs)", config)
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Execution")}")
        renderBool(buf, "thinking_enabled", "Thinking mode", config)
        renderBool(buf, "no_egress_enabled", "No-egress (block network)", config)
        renderValue(buf, "execution_mode", "Execution mode (AUTO/INTERACTIVE)", config, "AUTO")
    }

    // ── Providers ────────────────────────────────────────────────────────
    // Keys from ConfigService: providers.ollama.ollama_endpoint, providers.anthropic.anthropic_api_key, etc.
    // After getConfig("providers") → short keys: ollama.ollama_endpoint, anthropic.anthropic_api_key, etc.

    private fun renderProviders(buf: TuiRenderBuffer, config: Map<String, String>) {
        val providers = listOf(
            "Ollama" to listOf(
                "ollama.ollama_endpoint" to "Endpoint",
                "ollama.ollama_context_size" to "Context size",
                "ollama.ollama_keep_alive" to "Keep alive (s)"
            ),
            "Anthropic" to listOf("anthropic.anthropic_api_key" to "API Key"),
            "OpenAI" to listOf("openai.openai_api_key" to "API Key"),
            "OpenRouter" to listOf("openrouter.openrouter_api_key" to "API Key"),
            "Gemini" to listOf("gemini.gemini_api_key" to "API Key"),
            "LM Studio" to listOf(
                "lmstudio.lmstudio_base_url" to "Base URL",
                "lmstudio.lmstudio_api_key" to "API Key",
                "lmstudio.lmstudio_context_size" to "Context size"
            ),
            "Custom OpenAI" to listOf(
                "generic_openai.generic_openai_base_url" to "Base URL",
                "generic_openai.generic_openai_api_key" to "API Key",
                "generic_openai.generic_openai_model" to "Model"
            ),
            "Z.AI" to listOf(
                "zai.zai_base_url" to "Base URL",
                "zai.zai_api_key" to "API Key"
            )
        )
        for ((name, fields) in providers) {
            val hasKey = fields.any { (key, _) ->
                val v = config[key] ?: ""
                v.isNotBlank() && v != "null"
            }
            val status = if (hasKey) TuiColors.statusSuccess("●") else TuiColors.muted("○")
            buf.addLine("  $status ${TuiColors.highlight(name)}")
            for ((key, label) in fields) {
                val value = config[key] ?: ""
                val display = if (key.contains("api_key") && value.length > 8) {
                    value.take(4) + "****" + value.takeLast(4)
                } else value.ifBlank { TuiColors.muted("(not set)") }
                renderValue(buf, key, label, config, "(not set)")
            }
        }
    }

    // ── Models ───────────────────────────────────────────────────────────
    // Keys from ConfigService:
    //   default_model.chat, default_model.plan, default_model.agent, default_model.weak
    //   models.embedding_model, models.visibility
    // After getConfig("default_model") → short keys: chat, plan, agent, weak

    private fun renderModels(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Model Assignments (Enter to edit)")}")
        val assignments = listOf(
            "chat" to "Default (chat)",
            "plan" to "Planning",
            "agent" to "Coding / Agent",
            "weak" to "Auxiliary (summaries)"
        )
        for ((key, label) in assignments) {
            renderModelAssignment(buf, key, label, config)
        }
        buf.addLine()

        // Embedding model is in "models" section
        val modelsConfig = viewModel?.getConfigSection("models") ?: emptyMap()
        buf.addLine("  ${TuiColors.highlight("Embedding")}")
        renderValueFrom(buf, "models", "embedding_model", "Embedding model", modelsConfig, "(auto)")

        buf.addLine()

        // Available models table
        buf.addLine("  ${TuiColors.highlight("Available Models")}  ${TuiColors.muted("[F] Refresh from providers")}")
        val models = cachedModels
        if (models == null) {
            buf.addLine("    ${TuiColors.muted("Press [F] to load models from providers")}")
        } else if (models.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("No models found. Check provider configuration.")}")
        } else {
            // Table header
            val provCol = 12
            val nameCol = 30
            val ctxCol = 10
            val priceCol = 14
            val visCol = 7
            val header = "    ${"Provider".padEnd(provCol)} ${"Model".padEnd(nameCol)} ${"Context".padEnd(ctxCol)} ${"Price IN".padEnd(priceCol)} ${"Price OUT".padEnd(priceCol)} ${"Vis".padEnd(visCol)}"
            buf.addLine(TuiColors.muted(header))
            buf.addLine(TuiColors.muted("    ${"─".repeat(provCol + nameCol + ctxCol + priceCol * 2 + visCol + 5)}"))

            for (model in models.take(30)) {
                val vis = if (model.showInDropdown) TuiColors.statusSuccess("✓") else TuiColors.muted("·")
                val ctx = formatContextSize(model.contextSize)
                val priceIn = model.pricing?.let { formatPrice(it.inputPer1M) } ?: TuiColors.muted("—")
                val priceOut = model.pricing?.let { formatPrice(it.outputPer1M) } ?: TuiColors.muted("—")
                val provDisplay = model.provider.take(provCol).padEnd(provCol)
                val nameDisplay = model.id.take(nameCol).padEnd(nameCol)
                buf.addLine("    $provDisplay ${TuiColors.accent(nameDisplay)} ${ctx.padEnd(ctxCol)} ${priceIn.padEnd(priceCol)} ${priceOut.padEnd(priceCol)} $vis")
            }
            if (models.size > 30) {
                buf.addLine("    ${TuiColors.muted("... and ${models.size - 30} more (${models.size} total)")}")
            }
        }

        buf.addLine()

        // Model visibility overrides
        buf.addLine("  ${TuiColors.highlight("Model Visibility (Enter to toggle)")}")
        val visibilityKeys = modelsConfig.keys.filter { it.startsWith("visibility_") }.sorted()
        if (visibilityKeys.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("All models visible (no overrides)")}")
        } else {
            for (key in visibilityKeys.take(20)) {
                val modelName = key.removePrefix("visibility_")
                renderBoolFrom(buf, "models", key, modelName, modelsConfig)
            }
        }
    }

    /**
     * Render model assignment value, parsing JSON format to human-readable provider/model.
     */
    private fun renderModelAssignment(buf: TuiRenderBuffer, key: String, label: String, config: Map<String, String>) {
        val fieldIdx = currentFields.size
        currentFields = currentFields + SettingsField("$cachedSection.$key", label, FieldType.TEXT)
        val rawValue = config[key]?.ifBlank { null }
        val display = if (rawValue != null) {
            formatModelValue(rawValue)
        } else {
            "(auto)"
        }
        val cursor = if (fieldIdx == viewModel?.stateFlow?.value?.settingsSelectedField) "> " else "  "
        buf.addLine("  ${cursor}${label.padEnd(30)} ${TuiColors.accent(display)}")
    }

    /**
     * Parse model config value. Handles:
     * - JSON: {"modelId":"gpt-oss:20b","provider":"ollama"} -> ollama/gpt-oss:20b
     * - Already formatted: ollama/gpt-oss:20b -> ollama/gpt-oss:20b
     * - Plain model ID: gpt-oss:20b -> gpt-oss:20b
     */
    private fun formatModelValue(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("{")) {
            // Parse JSON manually (avoid Gson dependency in render)
            val modelId = extractJsonField(trimmed, "modelId")
            val provider = extractJsonField(trimmed, "provider")
            return if (provider != null && modelId != null) {
                "$provider/$modelId"
            } else {
                modelId ?: trimmed
            }
        }
        return trimmed
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = """"$field"\s*:\s*"([^"]*)""""
        return Regex(pattern).find(json)?.groupValues?.get(1)
    }

    private fun formatContextSize(size: Int): String = when {
        size <= 0 -> "—"
        size >= 1_000_000 -> "${size / 1_000_000}M"
        size >= 1_000 -> "${size / 1_000}K"
        else -> size.toString()
    }

    private fun formatPrice(price: Double): String = when {
        price <= 0.0 -> TuiColors.muted("free")
        price < 0.01 -> "$${String.format("%.4f", price)}"
        price < 1.0 -> "$${String.format("%.3f", price)}"
        else -> "$${String.format("%.2f", price)}"
    }

    /** Cached model list (loaded on demand via [F] key). */
    private var cachedModels: List<CachedModelEntry>? = null

    data class CachedModelEntry(
        val id: String,
        val provider: String,
        val name: String,
        val contextSize: Int,
        val pricing: CachedPricing?,
        val showInDropdown: Boolean
    )

    data class CachedPricing(val inputPer1M: Double, val outputPer1M: Double)

    /** Refresh models from providers. Called from TuiViewModel on [F] key press or on startup. */
    fun refreshModels(models: List<CachedModelEntry>) {
        cachedModels = models.sortedWith(compareBy({ it.provider }, { it.id }))
        invalidateCache()
    }

    /** Get count of cached models (for logging). */
    fun getCachedModelCount(): Int = cachedModels?.size ?: 0

    // ── Prompts ──────────────────────────────────────────────────────────

    private fun renderPrompts(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("System Prompts")}")
        buf.addLine()
        if (config.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("Prompts are managed via /explain, /refactor, etc.")}")
            buf.addLine("    ${TuiColors.muted("Type / in chat to see all available prompt commands.")}")
        } else {
            for ((key, value) in config.entries.take(15)) {
                val preview = value.take(60) + if (value.length > 60) "..." else ""
                buf.addLine("    ${TuiColors.accent(key)}: $preview")
            }
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("Use slash commands in chat: /explain, /refactor, /test, ...")}")
    }

    // ── Context (RAG) ────────────────────────────────────────────────────
    // Keys from ConfigService: rag.search_similarity_threshold, rag.search_top_k, etc.
    // After getConfig("rag") → short keys: search_similarity_threshold, search_top_k, etc.

    private fun renderContext(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("RAG Settings")}")
        renderBool(buf, "enabled", "RAG enabled", config)
        renderBool(buf, "index_on_startup", "Index on startup", config)
        renderBool(buf, "auto_index_on_context_build", "Auto-index on context build", config)
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("RAG Search")}")
        renderValue(buf, "search_similarity_threshold", "Similarity threshold", config, "0.5")
        renderValue(buf, "search_top_k", "Top K results", config, "5")
        renderValue(buf, "search_semantic_weight", "Semantic weight", config, "0.7")
        renderBool(buf, "search_hybrid_enabled", "Hybrid search (BM25+semantic)", config)
        renderBool(buf, "search_include_context_chunks", "Include context chunks", config)
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Indexing")}")
        renderValue(buf, "max_file_size_mb", "Max file size (MB)", config, "2")
        renderValue(buf, "max_chunks_per_file", "Max chunks per file", config, "100")
        renderValue(buf, "chunking_mode", "Chunking mode", config, "semantic")
        renderValue(buf, "max_concurrent_jobs", "Max concurrent jobs", config, "4")
    }

    // ── MCP ──────────────────────────────────────────────────────────────

    private fun renderMcp(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("MCP Servers")}")
        if (config.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("No MCP servers configured.")}")
            buf.addLine()
            buf.addLine("  ${TuiColors.muted("Use /mcp-add to add servers:")}")
            buf.addLine("    ${TuiColors.muted("/mcp-add stdio <name> <command> [args...]")}")
            buf.addLine("    ${TuiColors.muted("/mcp-add http <name> <url>")}")
            buf.addLine("    ${TuiColors.muted("/mcp-list  /mcp-edit <id> <field> <value>  /mcp-remove <id>")}")
        } else {
            val serverIds = config.keys.mapNotNull { key ->
                val parts = key.split(".")
                if (parts.size >= 2) parts[0] else null
            }.distinct()
            for (id in serverIds.take(10)) {
                val name = config["$id.displayName"] ?: config["$id.display_name"] ?: id
                val type = config["$id.type"] ?: "?"
                val enabled = config["$id.enabled"]
                val status = if (enabled == "true") TuiColors.statusSuccess("●") else TuiColors.muted("○")
                buf.addLine("    $status $name (${type.uppercase()}) ${TuiColors.muted(id.take(8))}")
            }
        }
    }

    // ── Docs ─────────────────────────────────────────────────────────────

    private fun renderDocs(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Documentation Sources")}")
        buf.addLine()
        if (config.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("No documentation sources configured.")}")
        } else {
            for ((key, value) in config.entries.take(15)) {
                buf.addLine("    ${TuiColors.accent(key)}: $value")
            }
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("Commands: /docs-add <url> [depth]  /docs-delete <id>  /docs-reindex <id>")}")
    }

    // ── Tools ────────────────────────────────────────────────────────────

    private fun renderTools(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Tool Permissions (Enter to cycle: ON → ASK → OFF)")}")
        val tools = listOf(
            "read_file", "read_directory", "file_search", "grep_search", "view_diff",
            "create_new_file", "code_editing", "advance_code_editing",
            "multi_line_editor", "multi_edit", "run_terminal_command",
            "http_request", "run_code", "invoke_subagent"
        )
        buf.addLine("    ${"Tool".padEnd(26)} ${"Plan".padEnd(8)} Agent")
        buf.addLine("    ${"─".repeat(26)} ${"─".repeat(8)} ${"─".repeat(8)}")
        for (tool in tools) {
            val planKey = "permission_${tool}_plan_mode"
            val agentKey = "permission_${tool}_agent_mode"
            val planValue = config[planKey]?.uppercase() ?: "ON"
            val agentValue = config[agentKey]?.uppercase() ?: "ON"
            val fieldIdx = currentFields.size
            currentFields = currentFields + SettingsField("tools.$planKey", "$tool (plan)", FieldType.BOOL)
            currentFields = currentFields + SettingsField("tools.$agentKey", "$tool (agent)", FieldType.BOOL)
            val planIcon = when (planValue) {
                "ON", "TRUE" -> TuiColors.statusSuccess("ON ")
                "ASK" -> TuiColors.statusPending("ASK")
                else -> TuiColors.statusFailed("OFF")
            }
            val agentIcon = when (agentValue) {
                "ON", "TRUE" -> TuiColors.statusSuccess("ON ")
                "ASK" -> TuiColors.statusPending("ASK")
                else -> TuiColors.statusFailed("OFF")
            }
            val selected = viewModel?.stateFlow?.value?.settingsSelectedField
            val cursorPlan = if (selected == fieldIdx) ">" else " "
            val cursorAgent = if (selected == fieldIdx + 1) ">" else " "
            buf.addLine("  ${cursorPlan} ${tool.padEnd(26)} $planIcon${cursorAgent.padStart(5)} $agentIcon")
        }
    }

    // ── Subagents ────────────────────────────────────────────────────────

    private fun renderSubagents(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Subagents")}")
        renderBool(buf, "builtin_enabled", "Built-in subagents enabled", config)
        buf.addLine()

        val agents = config.keys.filter { it.contains("enabled") && it != "builtin_enabled" }.map {
            it.removeSuffix(".enabled").removeSuffix("_enabled").removePrefix("enabled_")
        }.filter { it.isNotBlank() }.distinct()

        if (agents.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("Using default subagent profiles (21 built-in).")}")
            buf.addLine("    Built-in: api-designer, architect-reviewer, code-reviewer,")
            buf.addLine("    documentation-engineer, frontend-developer, fullstack-developer,")
            buf.addLine("    refactoring-specialist, security-engineer, sre-engineer, ...")
        } else {
            for (name in agents.take(15)) {
                val key = "enabled_$name"
                renderBool(buf, key, name, config)
            }
        }
    }

    // ── Advanced ─────────────────────────────────────────────────────────
    // Keys: advanced.no_egress_default, advanced.read_only_mode, advanced.auto_optimize_percentage
    // Limits: limits.tool_execution_timeout, limits.api_call_timeout, limits.max_*

    private fun renderAdvanced(buf: TuiRenderBuffer, config: Map<String, String>) {
        val limitsConfig = viewModel?.getConfigSection("limits") ?: emptyMap()

        buf.addLine("  ${TuiColors.highlight("Security")}")
        renderBool(buf, "read_only_mode", "Read-only mode (no file writes)", config)
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Timeouts")}")
        renderValueFrom(buf, "limits", "tool_execution_timeout", "Tool execution (s)", limitsConfig, "360")
        renderValueFrom(buf, "limits", "api_call_timeout", "API call (s)", limitsConfig, "360")
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Limits")}")
        renderValueFrom(buf, "limits", "max_file_size", "Max file size (MB)", limitsConfig, "10")
        renderValueFrom(buf, "limits", "max_context_size", "Max context (tokens)", limitsConfig, "128000")
        renderValueFrom(buf, "limits", "max_output_size", "Max output (tokens)", limitsConfig, "8192")
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Performance")}")
        renderValue(buf, "auto_optimize_percentage", "Auto-optimize threshold (%)", config, "85")
    }

    // ── Theme ────────────────────────────────────────────────────────────

    private fun renderTheme(buf: TuiRenderBuffer) {
        buf.addLine("  ${TuiColors.highlight("ANSI Color Preview")}")
        buf.addLine("    ${TuiColors.user("User message")}")
        buf.addLine("    ${TuiColors.assistant("Assistant message")}")
        buf.addLine("    ${TuiColors.tool("Tool output")}")
        buf.addLine("    ${TuiColors.system("System/Error")}")
        buf.addLine()
        buf.addLine("    ${TuiColors.statusRunning("Running")} ${TuiColors.statusSuccess("Success")} ${TuiColors.statusFailed("Failed")} ${TuiColors.statusPending("Pending")}")
        buf.addLine("    ${TuiColors.logDebug("DEBUG")} ${TuiColors.logInfo("INFO")} ${TuiColors.logWarn("WARN")} ${TuiColors.logError("ERROR")}")
        buf.addLine()
        buf.addLine("    Agent colors:")
        val sb = StringBuilder("    ")
        TuiColors.agentColors.forEachIndexed { i, c -> sb.append("${c("Agent $i")}  ") }
        buf.addLine(sb.toString())
    }

    // ── Helper renderers ─────────────────────────────────────────────────

    /**
     * Render a boolean toggle using the current cached section.
     * @param key short key after section prefix removal (e.g. "format_markdown")
     */
    private fun renderBool(buf: TuiRenderBuffer, key: String, label: String, config: Map<String, String>) {
        renderBoolFrom(buf, cachedSection, key, label, config)
    }

    /**
     * Render a boolean toggle for an explicit section (for multi-section tabs).
     */
    private fun renderBoolFrom(buf: TuiRenderBuffer, section: String, key: String, label: String, config: Map<String, String>) {
        val fieldIdx = currentFields.size
        currentFields = currentFields + SettingsField("$section.$key", label, FieldType.BOOL)
        val value = config[key]?.lowercase()
        val checked = value == "true" || value == "1" || value == "yes"
        val icon = if (checked) TuiColors.statusSuccess("[x]") else TuiColors.muted("[ ]")
        val cursor = if (fieldIdx == viewModel?.stateFlow?.value?.settingsSelectedField) "> " else "  "
        buf.addLine("  ${cursor}$icon $label")
    }

    /**
     * Render a text value using the current cached section.
     * @param key short key after section prefix removal
     */
    private fun renderValue(buf: TuiRenderBuffer, key: String, label: String, config: Map<String, String>, default: String) {
        renderValueFrom(buf, cachedSection, key, label, config, default)
    }

    /**
     * Render a text value for an explicit section.
     */
    private fun renderValueFrom(buf: TuiRenderBuffer, section: String, key: String, label: String, config: Map<String, String>, default: String) {
        val fieldIdx = currentFields.size
        currentFields = currentFields + SettingsField("$section.$key", label, FieldType.TEXT)
        val value = config[key]?.ifBlank { null } ?: default
        // Mask API keys
        val display = if (key.contains("api_key") && value.length > 8) {
            value.take(4) + "****" + value.takeLast(4)
        } else {
            value
        }
        val cursor = if (fieldIdx == viewModel?.stateFlow?.value?.settingsSelectedField) "> " else "  "
        buf.addLine("  ${cursor}${label.padEnd(30)} ${TuiColors.accent(display)}")
    }
}

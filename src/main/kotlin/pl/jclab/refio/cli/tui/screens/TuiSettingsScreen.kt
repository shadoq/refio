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
 * Renders into a line buffer (not directly to terminal) to ensure
 * proper screen filling and avoid ghost artifacts.
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

        // Render settings content
        renderSettingsContent(buf, activeTab, cachedConfig)

        buf.addLine()
        buf.addLine(TuiColors.muted("Nav: 1-9,0=tab  /set <key> <value>  /settings-reset  Esc=back"))

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

    private fun tabToSection(tab: String): String = when (tab) {
        "General" -> "general"
        "Providers" -> "providers"
        "Models" -> "models"
        "Prompts" -> "prompts"
        "Context" -> "index"
        "MCP" -> "mcp"
        "Docs" -> "docs"
        "Tools" -> "tools"
        "Subagents" -> "subagents"
        "Advanced" -> "advanced"
        "Theme" -> "theme"
        else -> "general"
    }

    private fun renderSettingsContent(buf: TuiRenderBuffer, tab: String, config: Map<String, String>) {
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

    private fun renderGeneral(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Display")}")
        renderBool(buf, "format_markdown", "Markdown rendering", config)
        renderBool(buf, "streaming_enabled", "Stream responses", config)
        renderBool(buf, "advanced_view", "Advanced view (show all tabs)", config)
    }

    private fun renderProviders(buf: TuiRenderBuffer, config: Map<String, String>) {
        val providers = listOf(
            "Ollama" to listOf("ollama_endpoint" to "Endpoint", "ollama_context_size" to "Context size", "ollama_keep_alive" to "Keep alive (s)"),
            "Anthropic" to listOf("anthropic_api_key" to "API Key"),
            "OpenAI" to listOf("openai_api_key" to "API Key"),
            "OpenRouter" to listOf("openrouter_api_key" to "API Key"),
            "Gemini" to listOf("gemini_api_key" to "API Key"),
            "LM Studio" to listOf("lmstudio_base_url" to "Base URL", "lmstudio_context_size" to "Context size"),
            "Custom OpenAI" to listOf("custom_openai_api_key" to "API Key", "custom_openai_base_url" to "Base URL", "custom_openai_model" to "Model"),
            "Z.AI" to listOf("zai_api_key" to "API Key", "zai_base_url" to "Base URL")
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
                buf.addLine("      $label: $display")
            }
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/set providers.<key> <value>")}")
    }

    private fun renderModels(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Model Assignments")}")
        val assignments = listOf(
            "default_model" to "Default (chat)",
            "plan_model" to "Planning",
            "coding_model" to "Coding",
            "weak_model" to "Auxiliary (summaries)",
            "embedding_model" to "Embeddings"
        )
        for ((key, label) in assignments) {
            val value = config[key]?.ifBlank { null } ?: TuiColors.muted("(auto)")
            buf.addLine("    ${label.padEnd(22)} $value")
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/set models.<key> provider/model-name")}")
    }

    private fun renderPrompts(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("System Prompts")}")
        buf.addLine("    Custom system prompts override built-in defaults per mode.")
        buf.addLine()
        val promptKeys = config.keys.filter { it.startsWith("system_prompt") || it.startsWith("custom_") }.sorted()
        if (promptKeys.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("No custom prompts configured.")}")
        } else {
            for (key in promptKeys.take(10)) {
                val value = config[key] ?: ""
                buf.addLine("    ${TuiColors.accent(key)}: ${value.take(60)}${if (value.length > 60) "..." else ""}")
            }
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Slash Commands")}")
        val cmdKeys = config.keys.filter { it.startsWith("command_") }.sorted()
        if (cmdKeys.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("No custom commands.")}")
        } else {
            for (key in cmdKeys.take(10)) {
                buf.addLine("    /${key.removePrefix("command_")}: ${config[key]?.take(50)}")
            }
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/set prompts.<key> <value>")}")
    }

    private fun renderContext(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("RAG Search")}")
        renderValue(buf, "rag_search_similarity_threshold", "Similarity threshold", config, "0.5")
        renderValue(buf, "rag_search_top_k", "Top K results", config, "5")
        renderValue(buf, "rag_search_semantic_weight", "Semantic weight", config, "0.7")
        renderBool(buf, "rag_search_hybrid_enabled", "Hybrid search (BM25+semantic)", config)
        renderBool(buf, "rag_search_include_context_chunks", "Include context chunks", config)
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Indexing")}")
        renderValue(buf, "max_file_size", "Max file size (MB)", config, "2")
        renderValue(buf, "chunk_size", "Chunk size (tokens)", config, "512")
        renderValue(buf, "chunking_strategy", "Chunking strategy", config, "default")
        buf.addLine()
        val ignorePaths = config["ignore_paths"] ?: ""
        if (ignorePaths.isNotBlank()) {
            buf.addLine("  ${TuiColors.highlight("Ignored Paths")}")
            for (p in ignorePaths.split("\n").take(5)) {
                buf.addLine("    $p")
            }
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/reindex /stop-index /set index.<key> <value>")}")
    }

    private fun renderMcp(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("MCP Servers")}")
        val serverKeys = config.keys.filter { it.contains("displayName") || it.contains("display_name") }
        if (serverKeys.isEmpty() && config.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("No MCP servers configured.")}")
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
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/mcp-add /mcp-remove <id> /mcp-test <id>")}")
    }

    private fun renderDocs(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Documentation Sources")}")
        if (config.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("No documentation sources configured.")}")
        } else {
            for ((key, value) in config.entries.take(10)) {
                buf.addLine("    ${TuiColors.accent(key)}: $value")
            }
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/docs-add <url> /docs-reindex /docs-delete <id>")}")
    }

    private fun renderTools(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Tool Permissions")}")
        val tools = listOf(
            "read_file", "read_directory", "file_search", "grep_search", "view_diff",
            "create_new_file", "code_editing", "advance_code_editing",
            "multi_line_editor", "multi_edit", "run_terminal_command", "invoke_subagent"
        )
        buf.addLine("    ${"Tool".padEnd(26)} ${"Plan".padEnd(8)} Agent")
        buf.addLine("    ${"─".repeat(26)} ${"─".repeat(8)} ${"─".repeat(8)}")
        for (tool in tools) {
            val planEnabled = config["permission_${tool}_plan_mode"]?.let { it == "true" } ?: true
            val agentEnabled = config["permission_${tool}_agent_mode"]?.let { it == "true" } ?: true
            val planIcon = if (planEnabled) TuiColors.statusSuccess("✓") else TuiColors.statusFailed("✗")
            val agentIcon = if (agentEnabled) TuiColors.statusSuccess("✓") else TuiColors.statusFailed("✗")
            buf.addLine("    ${tool.padEnd(26)} $planIcon${" ".repeat(7)} $agentIcon")
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/set tools.permission_<tool>_<mode>_mode true|false")}")
    }

    private fun renderSubagents(buf: TuiRenderBuffer, config: Map<String, String>) {
        buf.addLine("  ${TuiColors.highlight("Subagents")}")
        val agents = config.keys.filter { it.contains("enabled") }.map {
            it.removeSuffix(".enabled").removeSuffix("_enabled").removePrefix("enabled_")
        }.filter { it.isNotBlank() }.distinct()

        if (agents.isEmpty()) {
            buf.addLine("    ${TuiColors.muted("Using default subagent profiles (21 built-in).")}")
            buf.addLine("    Built-in: api-designer, architect-reviewer, code-reviewer,")
            buf.addLine("    documentation-engineer, frontend-developer, fullstack-developer,")
            buf.addLine("    refactoring-specialist, security-engineer, sre-engineer, ...")
        } else {
            for (name in agents.take(15)) {
                val enabled = config["enabled_$name"] ?: config["$name.enabled"] ?: "true"
                val icon = if (enabled == "true") TuiColors.statusSuccess("●") else TuiColors.muted("○")
                buf.addLine("    $icon $name")
            }
        }
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/set subagents.enabled_<name> true|false")}")
    }

    private fun renderAdvanced(buf: TuiRenderBuffer, config: Map<String, String>) {
        val limitsConfig = viewModel?.getConfigSection("limits") ?: emptyMap()

        buf.addLine("  ${TuiColors.highlight("Security")}")
        renderBool(buf, "no_egress_default", "No-egress mode (block external calls)", config)
        renderBool(buf, "read_only_mode", "Read-only mode (no file writes)", config)
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Timeouts")}")
        renderValue(buf, "tool_execution_timeout", "Tool execution (s)", limitsConfig, "360")
        renderValue(buf, "api_call_timeout", "API call (s)", limitsConfig, "360")
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Limits")}")
        renderValue(buf, "max_file_size", "Max file size (MB)", limitsConfig, "10")
        renderValue(buf, "max_context_size", "Max context (tokens)", limitsConfig, "128000")
        renderValue(buf, "max_output_size", "Max output (tokens)", limitsConfig, "8192")
        buf.addLine()
        buf.addLine("  ${TuiColors.highlight("Performance")}")
        renderValue(buf, "auto_optimize_percentage", "Auto-optimize threshold (%)", limitsConfig, "85")
        buf.addLine()
        buf.addLine("  ${TuiColors.muted("/set advanced.<key> <value> | /set limits.<key> <value>")}")
    }

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

    // --- Helper renderers ---

    private fun renderBool(buf: TuiRenderBuffer, key: String, label: String, config: Map<String, String>) {
        val value = config[key]?.lowercase()
        val checked = value == "true" || value == "1" || value == "yes"
        val icon = if (checked) TuiColors.statusSuccess("[x]") else TuiColors.muted("[ ]")
        buf.addLine("    $icon $label ${TuiColors.muted("($key)")}")
    }

    private fun renderValue(buf: TuiRenderBuffer, key: String, label: String, config: Map<String, String>, default: String) {
        val value = config[key]?.ifBlank { null } ?: default
        buf.addLine("    ${label.padEnd(30)} ${TuiColors.accent(value)} ${TuiColors.muted("($key)")}")
    }
}

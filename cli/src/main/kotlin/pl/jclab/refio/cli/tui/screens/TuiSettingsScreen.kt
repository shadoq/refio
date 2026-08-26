package pl.jclab.refio.cli.tui.screens

import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import java.util.Locale
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
 *
 * The field list for each tab is built by [buildSpec], a pure function of the
 * current config. Both the renderer and the input handler derive fields from it
 * ([fieldsFor]), so navigation and rendering can never disagree about which
 * field is at which index.
 */
object TuiSettingsScreen {

    private val settingsTabs = listOf(
        "General", "Providers", "Models", "Prompts", "Context",
        "MCP", "Docs", "Tools", "Subagents", "Advanced", "Theme"
    )

    private var viewModel: TuiViewModel? = null

    /** Prefix marking fields persisted through the tool-permissions API, not raw config. */
    const val TOOL_PERMISSION_PREFIX = "toolperm."

    data class SettingsField(
        val sectionKey: String, // e.g. "general.streaming_enabled" or "toolperm.read_file.plan"
        val label: String,
        val type: FieldType,
        /** Effective default shown when the config has no explicit value. */
        val default: String = "",
        /** Allowed values for CYCLE fields; Enter advances to the next one. */
        val options: List<String> = emptyList(),
        /** True for fields that make a provider "configured" (API key / explicit endpoint). */
        val credential: Boolean = false
    )

    enum class FieldType { BOOL, TEXT, CYCLE }

    fun setViewModel(vm: TuiViewModel) {
        viewModel = vm
    }

    // ── Spec model ───────────────────────────────────────────────────────
    // One item per rendered line (except ToolRowItem which holds two fields on
    // one line). Values are resolved at build time from a single config snapshot.

    private sealed interface Item
    private data class HeaderItem(val text: String) : Item
    private object BlankItem : Item
    private data class TextItem(val text: String) : Item
    private data class FieldItem(val field: SettingsField, val display: String, val checked: Boolean? = null) : Item
    private data class ProviderItem(val name: String, val configured: Boolean) : Item
    private data class ToolRowItem(
        val tool: String,
        val plan: SettingsField,
        val agent: SettingsField,
        val planValue: String,
        val agentValue: String
    ) : Item

    private fun Item.fields(): List<SettingsField> = when (this) {
        is FieldItem -> listOf(field)
        is ToolRowItem -> listOf(plan, agent)
        else -> emptyList()
    }

    /** Navigable fields of a tab, in render order. Safe to call from the input thread. */
    fun fieldsFor(tabIndex: Int): List<SettingsField> = buildSpec(tabIndex).flatMap { it.fields() }

    /** Field at [index] on the given tab, or null when out of range. */
    fun getSelectedField(tabIndex: Int, index: Int): SettingsField? = fieldsFor(tabIndex).getOrNull(index)

    /** Number of navigable fields on the given tab. */
    fun fieldCount(tabIndex: Int): Int = fieldsFor(tabIndex).size

    // ── Rendering ────────────────────────────────────────────────────────

    /**
     * Render settings screen into a list of exactly [contentHeight] lines,
     * each padded to [width] visible characters. Content longer than the
     * screen scrolls so the selected field stays visible.
     */
    fun renderToLines(state: TuiState, width: Int, contentHeight: Int): List<String> {
        val buf = TuiRenderBuffer(width, contentHeight)

        buf.addLine(bold("Settings"))
        buf.addLine(TuiColors.border("─".repeat((width - 2).coerceAtLeast(10))))

        val tabBar = settingsTabs.mapIndexed { i, name ->
            val label = " ${i + 1}:$name "
            if (i == state.settingsTab) TuiColors.tabActive(label) else TuiColors.tabInactive(label)
        }.joinToString("")
        buf.addLine(tabBar)
        buf.addLine()

        val footer = footerLines(state)
        val headerLines = 4
        val window = (contentHeight - headerLines - footer.size).coerceAtLeast(1)

        val spec = buildSpec(state.settingsTab)
        val content = mutableListOf<String>()
        var cursorLine = -1
        var fieldIdx = 0
        for (item in spec) {
            when (item) {
                is HeaderItem -> content.add("  ${TuiColors.highlight(item.text)}")
                is BlankItem -> content.add("")
                is TextItem -> content.add(item.text)
                is ProviderItem -> {
                    val status = if (item.configured) TuiColors.statusSuccess("●") else TuiColors.muted("○")
                    content.add("  $status ${TuiColors.highlight(item.name)}")
                }
                is FieldItem -> {
                    if (fieldIdx == state.settingsSelectedField) cursorLine = content.size
                    content.add(renderFieldLine(item, fieldIdx == state.settingsSelectedField))
                    fieldIdx++
                }
                is ToolRowItem -> {
                    val planSelected = fieldIdx == state.settingsSelectedField
                    val agentSelected = fieldIdx + 1 == state.settingsSelectedField
                    if (planSelected || agentSelected) cursorLine = content.size
                    val cursorPlan = if (planSelected) ">" else " "
                    val cursorAgent = if (agentSelected) ">" else " "
                    content.add("  $cursorPlan ${item.tool.padEnd(26)} ${permissionIcon(item.planValue)}${cursorAgent.padStart(5)} ${permissionIcon(item.agentValue)}")
                    fieldIdx += 2
                }
            }
        }

        // Scroll the content window so the cursor line is always visible.
        val offset = when {
            cursorLine < 0 -> 0
            cursorLine < window -> 0
            else -> (cursorLine - window + 1).coerceAtMost((content.size - window).coerceAtLeast(0))
        }
        val visible = content.drop(offset).take(window).toMutableList()
        if (offset > 0 && visible.isNotEmpty()) {
            visible[0] = TuiColors.muted("  ↑ $offset more")
        }
        val below = content.size - offset - window
        if (below > 0 && visible.isNotEmpty()) {
            visible[visible.size - 1] = TuiColors.muted("  ↓ $below more")
        }
        visible.forEach { buf.addLine(it) }

        // Pin the footer to the bottom of the screen.
        repeat((window - visible.size).coerceAtLeast(0)) { buf.addLine() }
        footer.forEach { buf.addLine(it) }

        return buf.getLines()
    }

    private fun footerLines(state: TuiState): List<String> = when {
        state.settingsEditingField != null -> listOf(
            TuiColors.accent("Editing: ") + state.settingsEditBuffer + TuiColors.muted("_"),
            TuiColors.muted("[Enter] Save  [Esc] Cancel")
        )
        state.settingsResetArmed -> listOf(
            "",
            TuiColors.statusFailed("Press R again to reset ALL settings, Esc to cancel")
        )
        else -> listOf(
            "",
            TuiColors.muted("Nav: [←→] tab  [↑↓] field  [Enter] toggle/edit  [r]eset [e]xport [l]oad  Esc=back")
        )
    }

    private fun renderFieldLine(item: FieldItem, selected: Boolean): String {
        val cursor = if (selected) "> " else "  "
        return when (item.field.type) {
            FieldType.BOOL -> {
                val icon = if (item.checked == true) TuiColors.statusSuccess("[x]") else TuiColors.muted("[ ]")
                "  ${cursor}$icon ${item.field.label}"
            }
            else -> "  ${cursor}${item.field.label.padEnd(30)} ${TuiColors.accent(item.display)}"
        }
    }

    private fun permissionIcon(value: String): String = when (value.uppercase()) {
        "ON", "TRUE" -> TuiColors.statusSuccess("ON ")
        "ASK" -> TuiColors.statusPending("ASK")
        else -> TuiColors.statusFailed("OFF")
    }

    fun render(terminal: Terminal, state: TuiState, contentHeight: Int) {
        val width = terminal.size.width.takeIf { it > 0 } ?: 120
        for (line in renderToLines(state, width, contentHeight)) {
            terminal.println(line)
        }
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

    // ── Spec builders ────────────────────────────────────────────────────

    private fun buildSpec(tabIndex: Int): List<Item> {
        val tab = settingsTabs.getOrElse(tabIndex) { "General" }
        val section = tabToSection(tab)
        val config = viewModel?.getConfigSection(section) ?: emptyMap()
        return when (tab) {
            "General" -> generalSpec(section, config)
            "Providers" -> providersSpec(section, config)
            "Models" -> modelsSpec(section, config)
            "Prompts" -> promptsSpec(config)
            "Context" -> contextSpec(section, config)
            "MCP" -> mcpSpec(config)
            "Docs" -> docsSpec(config)
            "Tools" -> toolsSpec()
            "Subagents" -> subagentsSpec(section, config)
            "Advanced" -> advancedSpec(section, config)
            "Theme" -> themeSpec()
            else -> emptyList()
        }
    }

    private fun boolItem(section: String, key: String, label: String, config: Map<String, String>): FieldItem {
        val checked = config[key]?.lowercase() in listOf("true", "1", "yes")
        return FieldItem(SettingsField("$section.$key", label, FieldType.BOOL), display = "", checked = checked)
    }

    private fun cycleItem(
        section: String,
        key: String,
        label: String,
        config: Map<String, String>,
        options: List<String>,
        default: String
    ): FieldItem {
        val current = config[key]?.trim()?.uppercase()?.takeIf { it in options } ?: default
        return FieldItem(
            SettingsField("$section.$key", label, FieldType.CYCLE, default = default, options = options),
            display = current
        )
    }

    private fun valueItem(
        section: String,
        key: String,
        label: String,
        config: Map<String, String>,
        default: String,
        credential: Boolean = false
    ): FieldItem {
        val raw = config[key]?.ifBlank { null }?.takeIf { it != "null" }
        // Mask only values that actually come from the config; defaults and
        // placeholders like "(not set)" are never masked.
        val display = when {
            raw != null && key.contains("api_key") -> maskSecret(raw)
            raw != null -> raw
            default.isNotBlank() -> TuiColors.muted("$default (default)")
            else -> TuiColors.muted("(not set)")
        }
        return FieldItem(
            SettingsField("$section.$key", label, FieldType.TEXT, default = default, credential = credential),
            display = display
        )
    }

    private fun maskSecret(value: String): String =
        if (value.length > 8) value.take(4) + "****" + value.takeLast(4) else "****"

    // ── General ──────────────────────────────────────────────────────────

    private fun generalSpec(section: String, config: Map<String, String>): List<Item> = listOf(
        HeaderItem("Display"),
        boolItem(section, "format_markdown", "Markdown rendering", config),
        boolItem(section, "streaming_enabled", "Stream responses", config),
        boolItem(section, "advanced_view", "Advanced view (show all tabs)", config),
        BlankItem,
        HeaderItem("Execution"),
        cycleItem(section, "reasoning_effort", "Reasoning effort (Enter to cycle)", config, REASONING_EFFORT_OPTIONS, "OFF"),
        boolItem(section, "no_egress_enabled", "No-egress (block network)", config),
        valueItem(section, "execution_mode", "Execution mode (AUTO/INTERACTIVE)", config, "AUTO")
    )

    // ── Providers ────────────────────────────────────────────────────────

    private data class ProviderFieldDef(val key: String, val label: String, val credential: Boolean = false, val default: String = "")

    private val providerDefs: List<Pair<String, List<ProviderFieldDef>>> = listOf(
        "Ollama" to listOf(
            ProviderFieldDef("ollama.ollama_endpoint", "Endpoint", credential = true, default = "http://localhost:11434"),
            ProviderFieldDef("ollama.ollama_context_size", "Context size"),
            ProviderFieldDef("ollama.ollama_keep_alive", "Keep alive (s)")
        ),
        "Anthropic" to listOf(ProviderFieldDef("anthropic.anthropic_api_key", "API Key", credential = true)),
        "OpenAI" to listOf(ProviderFieldDef("openai.openai_api_key", "API Key", credential = true)),
        "OpenRouter" to listOf(ProviderFieldDef("openrouter.openrouter_api_key", "API Key", credential = true)),
        "Gemini" to listOf(ProviderFieldDef("gemini.gemini_api_key", "API Key", credential = true)),
        "LM Studio" to listOf(
            ProviderFieldDef("lmstudio.lmstudio_base_url", "Base URL", credential = true),
            ProviderFieldDef("lmstudio.lmstudio_api_key", "API Key"),
            ProviderFieldDef("lmstudio.lmstudio_context_size", "Context size")
        ),
        "Custom OpenAI" to listOf(
            ProviderFieldDef("generic_openai.generic_openai_base_url", "Base URL", credential = true),
            ProviderFieldDef("generic_openai.generic_openai_api_key", "API Key"),
            ProviderFieldDef("generic_openai.generic_openai_model", "Model"),
            ProviderFieldDef("generic_openai.generic_openai_context_size", "Context size"),
            ProviderFieldDef("generic_openai.generic_openai_raw_request", "Raw request (true/false)")
        ),
        "Z.AI" to listOf(
            ProviderFieldDef("zai.zai_base_url", "Base URL"),
            ProviderFieldDef("zai.zai_api_key", "API Key", credential = true)
        )
    )

    private fun providersSpec(section: String, config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        for ((name, fields) in providerDefs) {
            // A provider counts as configured only when a credential field
            // (API key, or explicit endpoint for local providers) is set.
            val configured = fields.any { def ->
                def.credential && (config[def.key]?.takeIf { it.isNotBlank() && it != "null" } != null)
            }
            items.add(ProviderItem(name, configured))
            for (def in fields) {
                items.add(valueItem(section, def.key, def.label, config, def.default, def.credential))
            }
        }
        return items
    }

    // ── Models ───────────────────────────────────────────────────────────

    private fun modelsSpec(section: String, config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("Model Assignments (Enter to edit)"))
        val assignments = listOf(
            "chat" to "Default (chat)",
            "plan" to "Planning",
            "agent" to "Coding / Agent",
            "weak" to "Auxiliary (summaries)"
        )
        for ((key, label) in assignments) {
            val raw = config[key]?.ifBlank { null }
            val display = if (raw != null) formatModelValue(raw) else TuiColors.muted("(auto)")
            items.add(FieldItem(SettingsField("$section.$key", label, FieldType.TEXT), display = display))
        }
        items.add(BlankItem)

        val modelsConfig = viewModel?.getConfigSection("models") ?: emptyMap()
        items.add(HeaderItem("Embedding"))
        items.add(valueItem("models", "embedding_model", "Embedding model", modelsConfig, "(auto)"))
        items.add(BlankItem)

        items.add(TextItem("  ${TuiColors.highlight("Available Models")}  ${TuiColors.muted("[F] Refresh from providers")}"))
        val models = cachedModels
        if (models == null) {
            items.add(TextItem("    ${TuiColors.muted("Press [F] to load models from providers")}"))
        } else if (models.isEmpty()) {
            items.add(TextItem("    ${TuiColors.muted("No models found. Check provider configuration.")}"))
        } else {
            val provCol = 12
            val nameCol = 30
            val ctxCol = 10
            val priceCol = 14
            val visCol = 7
            val header = "    ${"Provider".padEnd(provCol)} ${"Model".padEnd(nameCol)} ${"Context".padEnd(ctxCol)} ${"Price IN".padEnd(priceCol)} ${"Price OUT".padEnd(priceCol)} ${"Vis".padEnd(visCol)}"
            items.add(TextItem(TuiColors.muted(header)))
            items.add(TextItem(TuiColors.muted("    ${"─".repeat(provCol + nameCol + ctxCol + priceCol * 2 + visCol + 5)}")))
            for (model in models.take(30)) {
                val vis = if (model.showInDropdown) TuiColors.statusSuccess("✓") else TuiColors.muted("·")
                val ctx = formatContextSize(model.contextSize)
                val priceIn = model.pricing?.let { formatPrice(it.inputPer1M) } ?: TuiColors.muted("-")
                val priceOut = model.pricing?.let { formatPrice(it.outputPer1M) } ?: TuiColors.muted("-")
                val provDisplay = model.provider.take(provCol).padEnd(provCol)
                val nameDisplay = model.id.take(nameCol).padEnd(nameCol)
                items.add(TextItem("    $provDisplay ${TuiColors.accent(nameDisplay)} ${ctx.padEnd(ctxCol)} ${priceIn.padEnd(priceCol)} ${priceOut.padEnd(priceCol)} $vis"))
            }
            if (models.size > 30) {
                items.add(TextItem("    ${TuiColors.muted("... and ${models.size - 30} more (${models.size} total)")}"))
            }
        }
        items.add(BlankItem)

        items.add(HeaderItem("Model Visibility (Enter to toggle)"))
        val visibilityKeys = modelsConfig.keys.filter { it.startsWith("visibility_") }.sorted()
        if (visibilityKeys.isEmpty()) {
            items.add(TextItem("    ${TuiColors.muted("All models visible (no overrides)")}"))
        } else {
            for (key in visibilityKeys.take(20)) {
                items.add(boolItem("models", key, key.removePrefix("visibility_"), modelsConfig))
            }
        }
        return items
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
        size <= 0 -> "-"
        size >= 1_000_000 -> "${size / 1_000_000}M"
        size >= 1_000 -> "${size / 1_000}K"
        else -> size.toString()
    }

    private fun formatPrice(price: Double): String = when {
        price <= 0.0 -> TuiColors.muted("free")
        price < 0.01 -> "$${String.format(Locale.US, "%.4f", price)}"
        price < 1.0 -> "$${String.format(Locale.US, "%.3f", price)}"
        else -> "$${String.format(Locale.US, "%.2f", price)}"
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
    }

    /** Get count of cached models (for logging). */
    fun getCachedModelCount(): Int = cachedModels?.size ?: 0

    // ── Prompts ──────────────────────────────────────────────────────────

    private fun promptsSpec(config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("System Prompts"))
        items.add(BlankItem)
        if (config.isEmpty()) {
            items.add(TextItem("    ${TuiColors.muted("Prompts are managed via /explain, /refactor, etc.")}"))
            items.add(TextItem("    ${TuiColors.muted("Type / in chat to see all available prompt commands.")}"))
        } else {
            for ((key, value) in config.entries.take(15)) {
                val preview = value.take(60) + if (value.length > 60) "..." else ""
                items.add(TextItem("    ${TuiColors.accent(key)}: $preview"))
            }
        }
        items.add(BlankItem)
        items.add(TextItem("  ${TuiColors.muted("Use slash commands in chat: /explain, /refactor, /test, ...")}"))
        return items
    }

    // ── Context (RAG) ────────────────────────────────────────────────────

    private fun contextSpec(section: String, config: Map<String, String>): List<Item> = listOf(
        HeaderItem("RAG Settings"),
        boolItem(section, "enabled", "RAG enabled", config),
        boolItem(section, "index_on_startup", "Index on startup", config),
        boolItem(section, "auto_index_on_context_build", "Auto-index on context build", config),
        BlankItem,
        HeaderItem("RAG Search"),
        valueItem(section, "search_similarity_threshold", "Similarity threshold", config, "0.5"),
        valueItem(section, "search_top_k", "Top K results", config, "5"),
        valueItem(section, "search_semantic_weight", "Semantic weight", config, "0.7"),
        boolItem(section, "search_hybrid_enabled", "Hybrid search (BM25+semantic)", config),
        boolItem(section, "search_include_context_chunks", "Include context chunks", config),
        BlankItem,
        HeaderItem("Indexing"),
        valueItem(section, "max_file_size_mb", "Max file size (MB)", config, "2"),
        valueItem(section, "max_chunks_per_file", "Max chunks per file", config, "100"),
        valueItem(section, "chunking_mode", "Chunking mode", config, "semantic"),
        valueItem(section, "max_concurrent_jobs", "Max concurrent jobs", config, "4")
    )

    // ── MCP ──────────────────────────────────────────────────────────────

    private fun mcpSpec(config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("MCP Servers"))
        if (config.isEmpty()) {
            items.add(TextItem("    ${TuiColors.muted("No MCP servers configured.")}"))
            items.add(BlankItem)
            items.add(TextItem("  ${TuiColors.muted("Use /mcp-add to add servers:")}"))
            items.add(TextItem("    ${TuiColors.muted("/mcp-add stdio <name> <command> [args...]")}"))
            items.add(TextItem("    ${TuiColors.muted("/mcp-add http <name> <url>")}"))
            items.add(TextItem("    ${TuiColors.muted("/mcp-list  /mcp-edit <id> <field> <value>  /mcp-remove <id>")}"))
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
                items.add(TextItem("    $status $name (${type.uppercase()}) ${TuiColors.muted(id.take(8))}"))
            }
        }
        return items
    }

    // ── Docs ─────────────────────────────────────────────────────────────

    private fun docsSpec(config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("Documentation Sources"))
        items.add(BlankItem)
        if (config.isEmpty()) {
            items.add(TextItem("    ${TuiColors.muted("No documentation sources configured.")}"))
        } else {
            for ((key, value) in config.entries.take(15)) {
                items.add(TextItem("    ${TuiColors.accent(key)}: $value"))
            }
        }
        items.add(BlankItem)
        items.add(TextItem("  ${TuiColors.muted("Commands: /docs-add <url> [depth]  /docs-delete <id>  /docs-reindex <id>")}"))
        return items
    }

    // ── Tools ────────────────────────────────────────────────────────────

    private val PERMISSION_OPTIONS = listOf("ON", "ASK", "OFF")
    private val REASONING_EFFORT_OPTIONS = listOf("OFF", "LOW", "MEDIUM", "HIGH")

    private fun toolsSpec(): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("Tool Permissions (Enter to cycle: ON / ASK / OFF)"))
        val permissions = viewModel?.getToolPermissions() ?: emptyList()
        if (permissions.isEmpty()) {
            items.add(TextItem("    ${TuiColors.muted("Tool registry not available.")}"))
            return items
        }
        items.add(TextItem("    ${"Tool".padEnd(26)} ${"Plan".padEnd(8)} Agent"))
        items.add(TextItem("    ${"─".repeat(26)} ${"─".repeat(8)} ${"─".repeat(8)}"))
        for (perm in permissions.sortedBy { it.toolName }) {
            items.add(
                ToolRowItem(
                    tool = perm.toolName,
                    plan = SettingsField(
                        "$TOOL_PERMISSION_PREFIX${perm.toolName}.plan", "${perm.toolName} (plan)",
                        FieldType.CYCLE, options = PERMISSION_OPTIONS
                    ),
                    agent = SettingsField(
                        "$TOOL_PERMISSION_PREFIX${perm.toolName}.agent", "${perm.toolName} (agent)",
                        FieldType.CYCLE, options = PERMISSION_OPTIONS
                    ),
                    planValue = perm.planMode,
                    agentValue = perm.agentMode
                )
            )
        }
        return items
    }

    // ── Subagents ────────────────────────────────────────────────────────

    private fun subagentsSpec(section: String, config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("Subagents"))
        items.add(boolItem(section, "builtin_enabled", "Built-in subagents enabled", config))
        items.add(BlankItem)

        val agents = config.keys.filter { it.contains("enabled") && it != "builtin_enabled" }.map {
            it.removeSuffix(".enabled").removeSuffix("_enabled").removePrefix("enabled_")
        }.filter { it.isNotBlank() }.distinct()

        if (agents.isEmpty()) {
            items.add(TextItem("    ${TuiColors.muted("Using default subagent profiles (21 built-in).")}"))
            items.add(TextItem("    Built-in: api-designer, architect-reviewer, code-reviewer,"))
            items.add(TextItem("    documentation-engineer, frontend-developer, fullstack-developer,"))
            items.add(TextItem("    refactoring-specialist, security-engineer, sre-engineer, ..."))
        } else {
            for (name in agents.take(15)) {
                items.add(boolItem(section, "enabled_$name", name, config))
            }
        }
        return items
    }

    // ── Advanced ─────────────────────────────────────────────────────────

    private fun advancedSpec(section: String, config: Map<String, String>): List<Item> {
        val limitsConfig = viewModel?.getConfigSection("limits") ?: emptyMap()
        return listOf(
            HeaderItem("Security"),
            boolItem(section, "read_only_mode", "Read-only mode (no file writes)", config),
            BlankItem,
            HeaderItem("Timeouts"),
            valueItem("limits", "tool_execution_timeout", "Tool execution (s)", limitsConfig, "360"),
            valueItem("limits", "api_call_timeout", "API call (s)", limitsConfig, "360"),
            BlankItem,
            HeaderItem("Limits"),
            valueItem("limits", "max_file_size", "Max file size (MB)", limitsConfig, "10"),
            valueItem("limits", "max_context_size", "Max context (tokens)", limitsConfig, "128000"),
            valueItem("limits", "max_output_size", "Max output (tokens)", limitsConfig, "8192"),
            BlankItem,
            HeaderItem("Performance"),
            valueItem(section, "auto_optimize_percentage", "Auto-optimize threshold (%)", config, "85")
        )
    }

    // ── Theme ────────────────────────────────────────────────────────────

    private fun themeSpec(): List<Item> {
        val agentColorsLine = StringBuilder("    ")
        TuiColors.agentColors.forEachIndexed { i, c -> agentColorsLine.append("${c("Agent $i")}  ") }
        return listOf(
            HeaderItem("ANSI Color Preview"),
            TextItem("    ${TuiColors.user("User message")}"),
            TextItem("    ${TuiColors.assistant("Assistant message")}"),
            TextItem("    ${TuiColors.tool("Tool output")}"),
            TextItem("    ${TuiColors.system("System/Error")}"),
            BlankItem,
            TextItem("    ${TuiColors.statusRunning("Running")} ${TuiColors.statusSuccess("Success")} ${TuiColors.statusFailed("Failed")} ${TuiColors.statusPending("Pending")}"),
            TextItem("    ${TuiColors.logDebug("DEBUG")} ${TuiColors.logInfo("INFO")} ${TuiColors.logWarn("WARN")} ${TuiColors.logError("ERROR")}"),
            BlankItem,
            TextItem("    Agent colors:"),
            TextItem(agentColorsLine.toString())
        )
    }
}

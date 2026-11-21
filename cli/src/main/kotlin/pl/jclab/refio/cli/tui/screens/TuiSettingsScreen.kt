package pl.jclab.refio.cli.tui.screens

import java.util.Locale
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.cli.tui.rendering.Frame
import pl.jclab.refio.cli.tui.rendering.FrameState
import pl.jclab.refio.cli.tui.rendering.LayoutMode
import pl.jclab.refio.cli.tui.rendering.TextFit
import pl.jclab.refio.cli.tui.rendering.TuiColors
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiViewModel
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor
import pl.jclab.refio.cli.tui.views.TuiRagView

/**
 * Settings screen: the section list on the left (one line on a narrow screen) and the section's settings as
 * one frame per group, laid out in up to three columns. Every change is saved at once; the status bar says
 * "saved" or why it was not.
 *
 * Reads/writes real config via ConfigRouter through TuiViewModel. Config keys follow ConfigService.kt
 * conventions: getConfig(section) returns keys with the section prefix stripped, e.g. getConfig("providers")
 * returns "ollama.ollama_endpoint" (from "providers.ollama.ollama_endpoint").
 *
 * The field list for each tab is built by [buildSpec], a pure function of the current config. Both the
 * renderer and the input handler derive fields from it ([fieldsFor]), so navigation and rendering can never
 * disagree about which field is at which index.
 */
object TuiSettingsScreen {

    private val settingsTabs = listOf(
        "General", "Providers", "Models", "Prompts", "Context / RAG",
        "MCP", "Docs", "Tools", "Subagents", "Advanced", "Theme"
    )

    /** Key that opens each section, in the order of [settingsTabs]. */
    private val TAB_KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-")

    private const val NAV_WIDTH = 24
    private const val CONFIG_PATH = "~/.refio/config.yaml"
    private const val FILES_IN_SETTINGS = 12

    /** Name of the settings tab at [index], as shown in the section list. */
    fun tabTitle(index: Int): String = settingsTabs.getOrElse(index) { settingsTabs.first() }

    /** `section › field` for the status bar. */
    fun breadcrumb(state: TuiState): String {
        val section = tabTitle(state.settingsTab).lowercase()
        val field = getSelectedField(state.settingsTab, state.settingsSelectedField)?.label?.lowercase()
        return listOfNotNull(section, field).joinToString(" › ")
    }

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
    // A header or a provider starts a group (one frame). Values are resolved at build time from a single
    // config snapshot.

    private sealed interface Item
    private data class HeaderItem(val text: String, val meta: String? = null, val wide: Boolean = false) : Item
    /** One line above the groups, outside any frame (legends, notices). */
    private data class NoteItem(val text: String) : Item
    private data class TextItem(val text: String) : Item
    private data class FieldItem(val field: SettingsField, val display: String, val checked: Boolean? = null, val isDefault: Boolean = false) : Item
    private data class ProviderItem(val name: String, val configured: Boolean) : Item
    private data class ToolRowItem(
        val tool: String,
        val plan: SettingsField,
        val agent: SettingsField,
        val planValue: String,
        val agentValue: String,
        val description: String
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

    // ── Groups ───────────────────────────────────────────────────────────

    private class Group(val title: String, val meta: String?, val wide: Boolean, val configured: Boolean?) {
        val items = mutableListOf<Item>()
        var firstField = 0
        val fieldCount: Int get() = items.sumOf { it.fields().size }
    }

    private fun groups(tab: String, spec: List<Item>): List<Group> {
        val out = mutableListOf<Group>()
        var current: Group? = null
        var fieldIndex = 0
        for (item in spec) {
            when (item) {
                is NoteItem -> continue
                is HeaderItem -> current = Group(item.text, item.meta, item.wide, null).also { it.firstField = fieldIndex; out += it }
                is ProviderItem -> current = Group(item.name, if (item.configured) "configured" else "not set", false, item.configured).also { it.firstField = fieldIndex; out += it }
                else -> {
                    val group = current ?: Group(tab, null, false, null).also { it.firstField = fieldIndex; out += it; current = it }
                    group.items += item
                    fieldIndex += item.fields().size
                }
            }
        }
        return out
    }

    /** Field index ranges of the groups on a tab, in order; groups without fields are left out. */
    fun groupFieldRanges(tabIndex: Int): List<IntRange> =
        groups(tabTitle(tabIndex), buildSpec(tabIndex)).filter { it.fieldCount > 0 }.map { it.firstField until it.firstField + it.fieldCount }

    /** First field of the group after the one holding [fieldIndex], wrapping to the first group (`Tab`). */
    fun nextGroupField(tabIndex: Int, fieldIndex: Int): Int? {
        val ranges = groupFieldRanges(tabIndex)
        if (ranges.isEmpty()) return null
        val current = ranges.indexOfFirst { fieldIndex in it }
        return ranges[(current + 1).mod(ranges.size)].first
    }

    /** Tools of the group holding [fieldIndex] on the Tools tab, and whether the agent column has focus. */
    fun toolGroupAt(tabIndex: Int, fieldIndex: Int): Pair<List<String>, Boolean>? {
        val group = groups(tabTitle(tabIndex), buildSpec(tabIndex)).firstOrNull { fieldIndex in it.firstField until it.firstField + it.fieldCount }
            ?: return null
        val tools = group.items.filterIsInstance<ToolRowItem>().map { it.tool }
        if (tools.isEmpty()) return null
        val agentColumn = (fieldIndex - group.firstField) % 2 == 1
        return tools to agentColumn
    }

    // ── Rendering ────────────────────────────────────────────────────────

    /** Exactly [contentHeight] lines of [width] columns. Content taller than the screen scrolls with the selected field. */
    fun renderToLines(
        state: TuiState,
        width: Int,
        contentHeight: Int,
        theme: Theme = TuiColors.theme,
        glyphs: Glyphs = Glyphs.UNICODE
    ): List<String> {
        val frame = Frame(theme, glyphs)
        val withNav = width >= LayoutMode.MEDIUM_MIN_WIDTH
        val contentWidth = if (withNav) width - NAV_WIDTH - 1 else width
        val navLine = if (withNav) emptyList() else listOf(inlineNav(state, width, theme, glyphs))

        val footer = footerLines(state, contentWidth, frame, theme)
        val bodyHeight = (contentHeight - navLine.size - footer.size).coerceAtLeast(1)
        val body = contentLines(state, contentWidth, bodyHeight, frame, theme, glyphs)
        val right = navLine + body + footer

        if (!withNav) return right.take(contentHeight).map { TuiRenderBuffer.fitToWidth(it, width) }
        val nav = navFrame(state, contentHeight, frame, theme, glyphs)
        return (0 until contentHeight).map { r ->
            TuiRenderBuffer.fitToWidth(nav.getOrElse(r) { "" }, NAV_WIDTH) + " " + TuiRenderBuffer.fitToWidth(right.getOrElse(r) { "" }, contentWidth)
        }
    }

    private fun navFrame(state: TuiState, height: Int, frame: Frame, theme: Theme, glyphs: Glyphs): List<String> {
        val rows = settingsTabs.mapIndexed { i, name ->
            val selected = i == state.settingsTab
            val marker = if (selected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
            marker + " " + theme.style(TAB_KEYS[i], fg = ThemeColor.DIM) + " " +
                theme.style(name, fg = if (selected) ThemeColor.ACCENT else ThemeColor.TEXT, bold = selected)
        }
        val tail = listOfNotNull(
            "",
            theme.style(CONFIG_PATH, fg = ThemeColor.DIM),
            state.settingsNotice?.let { theme.style(it.text, fg = if (it.error) ThemeColor.ERR else ThemeColor.OK) }
        )
        return frame.render(NAV_WIDTH, height, "settings", null, FrameState.INACTIVE, rows + tail)
    }

    /** Narrow screens: the sections on one line, scrolled so the current one shows. */
    private fun inlineNav(state: TuiState, width: Int, theme: Theme, glyphs: Glyphs): String {
        val labels = settingsTabs.mapIndexed { i, name -> "${TAB_KEYS[i]} $name" }
        var start = 0
        while (start < state.settingsTab && labels.subList(start, state.settingsTab + 1).sumOf { it.length + 2 } > width - 2) start++
        val shown = labels.withIndex().drop(start).joinToString("  ") { (i, label) ->
            if (i == state.settingsTab) theme.style(label, fg = ThemeColor.ACCENT, bold = true, reverse = true) else theme.style(label, fg = ThemeColor.DIM)
        }
        return TuiRenderBuffer.fitToWidth((if (start > 0) glyphs.ellipsis + " " else "") + shown, width)
    }

    private fun footerLines(state: TuiState, width: Int, frame: Frame, theme: Theme): List<String> = when {
        state.settingsEditingField != null -> {
            val label = fieldsFor(state.settingsTab).firstOrNull { it.sectionKey == state.settingsEditingField }?.label ?: state.settingsEditingField
            frame.render(width, 3, "edit", "Enter save · Esc cancel", FrameState.FOCUSED, listOf("$label: ${state.settingsEditBuffer}_"))
        }
        state.settingsResetArmed -> listOf(theme.style("Press R again to reset ALL settings, Esc to cancel", fg = ThemeColor.ERR, bold = true))
        else -> {
            val extra = when (tabTitle(state.settingsTab)) {
                "Models" -> " · F refresh models"
                "Tools" -> " · Tab next group · a/k/x whole group ON/ASK/OFF"
                else -> ""
            }
            listOf(theme.style(TextFit.truncateEnd("r reset · e export · p export to project · l reload$extra", width), fg = ThemeColor.DIM))
        }
    }

    private fun contentLines(state: TuiState, width: Int, height: Int, frame: Frame, theme: Theme, glyphs: Glyphs): List<String> {
        val tab = tabTitle(state.settingsTab)
        val spec = buildSpec(state.settingsTab)
        val lines = mutableListOf<String>()
        var selectedLine = -1

        contextNotice(state, tab, width, frame, theme)?.let { lines += it }
        spec.filterIsInstance<NoteItem>().forEach { lines += TextFit.truncateEnd(it.text, width) }

        val columns = when {
            width >= 150 -> 3
            width >= 96 -> 2
            else -> 1
        }
        val rows = mutableListOf<List<Group>>()
        for (group in groups(tab, spec)) {
            val last = rows.lastOrNull()
            if (group.wide || last == null || last.size >= columns || last.any { it.wide }) rows += mutableListOf(group) else (last as MutableList).add(group)
        }
        for (row in rows) {
            val widths = splitWidth(width, row.size)
            val bodies = row.mapIndexed { i, g -> groupBody(g, state, frame.innerSize(widths[i], 3).first, theme, glyphs) }
            val rowHeight = bodies.maxOf { it.lines.size } + 2
            val framed = row.mapIndexed { i, g ->
                val focused = bodies[i].selectedLine >= 0
                val frameState = when {
                    focused -> FrameState.FOCUSED
                    else -> FrameState.INACTIVE
                }
                frame.render(widths[i], rowHeight, groupTitle(g, glyphs), g.meta, frameState, bodies[i].lines)
            }
            bodies.firstOrNull { it.selectedLine >= 0 }?.let { selectedLine = lines.size + 1 + it.selectedLine }
            for (r in 0 until rowHeight) lines += framed.joinToString(" ") { it[r] }
        }
        if (tab == "Context / RAG") lines += indexedFilesFrame(state, width, frame, theme, glyphs)
        return window(lines, height, selectedLine, theme, glyphs)
    }

    private fun groupTitle(group: Group, glyphs: Glyphs): String = when (group.configured) {
        true -> "${glyphs.dot} ${group.title}"
        false -> "${glyphs.pending} ${group.title}"
        null -> group.title
    }

    private fun splitWidth(width: Int, parts: Int): List<Int> {
        val each = (width - (parts - 1)) / parts
        return List(parts) { i -> if (i == parts - 1) width - (parts - 1) - each * (parts - 1) else each }
    }

    private fun window(lines: List<String>, height: Int, selectedLine: Int, theme: Theme, glyphs: Glyphs): List<String> {
        if (lines.size <= height) return lines + List(height - lines.size) { "" }
        val start = when {
            selectedLine < 0 -> 0
            else -> (selectedLine - height / 2).coerceIn(0, lines.size - height)
        }
        val shown = lines.subList(start, start + height).toMutableList()
        if (start > 0) shown[0] = theme.style("${glyphs.arrowUp} $start more", fg = ThemeColor.DIM)
        val below = lines.size - start - height
        if (below > 0) shown[shown.lastIndex] = theme.style("${glyphs.arrowDown} $below more", fg = ThemeColor.DIM)
        return shown
    }

    private class GroupBody(val lines: List<String>, val selectedLine: Int)

    private fun groupBody(group: Group, state: TuiState, inner: Int, theme: Theme, glyphs: Glyphs): GroupBody {
        val lines = mutableListOf<String>()
        var selectedLine = -1
        var field = group.firstField
        val labelWidth = group.items.filterIsInstance<FieldItem>().filter { it.field.type != FieldType.BOOL }
            .maxOfOrNull { it.field.label.length }?.coerceAtMost((inner * 55 / 100).coerceAtLeast(8)) ?: 0
        val toolWidth = group.items.filterIsInstance<ToolRowItem>().maxOfOrNull { it.tool.length }?.coerceAtMost((inner / 2).coerceAtLeast(8)) ?: 0
        if (toolWidth > 0) {
            lines += theme.style("  " + "tool".padEnd(toolWidth) + "  plan  agent", fg = ThemeColor.DIM)
        }
        for (item in group.items) {
            when (item) {
                is TextItem -> lines += item.text
                is FieldItem -> {
                    val selected = field == state.settingsSelectedField
                    if (selected) selectedLine = lines.size
                    lines += fieldLine(item, selected, labelWidth, inner, theme, glyphs)
                    field++
                }
                is ToolRowItem -> {
                    val column = when (state.settingsSelectedField) {
                        field -> 0
                        field + 1 -> 1
                        else -> -1
                    }
                    if (column >= 0) selectedLine = lines.size
                    lines += toolLine(item, column, toolWidth, inner, theme, glyphs)
                    field += 2
                }
                else -> {}
            }
        }
        if (lines.isEmpty()) lines += theme.style("nothing to set here", fg = ThemeColor.DIM)
        return GroupBody(lines, selectedLine)
    }

    private fun fieldLine(item: FieldItem, selected: Boolean, labelWidth: Int, inner: Int, theme: Theme, glyphs: Glyphs): String {
        val marker = if (selected) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
        val labelColour = if (selected) ThemeColor.ACCENT else ThemeColor.TEXT
        return when (item.field.type) {
            FieldType.BOOL -> {
                val box = if (item.checked == true) theme.style("[x]", fg = ThemeColor.OK) else theme.style("[ ]", fg = ThemeColor.DIM)
                "$marker $box " + theme.style(TextFit.truncateEnd(item.field.label, (inner - 6).coerceAtLeast(1), glyphs.ellipsis), fg = labelColour, bold = selected)
            }
            else -> {
                val label = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(item.field.label, labelWidth, glyphs.ellipsis), labelWidth)
                val valueWidth = (inner - labelWidth - 4).coerceAtLeast(1)
                val value = if (item.isDefault) {
                    theme.style(TextFit.truncateEnd(item.display, (valueWidth - 8).coerceAtLeast(1), glyphs.ellipsis), fg = ThemeColor.TEXT) + theme.style("  default", fg = ThemeColor.DIM)
                } else {
                    TextFit.truncateEnd(item.display, valueWidth, glyphs.ellipsis)
                }
                "$marker " + theme.style(label, fg = labelColour, bold = selected) + "  " + value
            }
        }
    }

    private fun toolLine(item: ToolRowItem, column: Int, toolWidth: Int, inner: Int, theme: Theme, glyphs: Glyphs): String {
        val marker = if (column >= 0) theme.style(glyphs.selectionBar, fg = ThemeColor.ACCENT) else " "
        val name = TuiRenderBuffer.fitToWidth(TextFit.truncateEnd(item.tool, toolWidth, glyphs.ellipsis), toolWidth)
        val rest = inner - 2 - toolWidth - 2 - 4 - 2 - 5
        val description = if (rest >= 12 && item.description.isNotBlank()) {
            "  " + theme.style(TextFit.truncateEnd(item.description.lineSequence().first(), rest - 2, glyphs.ellipsis), fg = ThemeColor.DIM)
        } else ""
        return "$marker " + theme.style(name, fg = if (column >= 0) ThemeColor.ACCENT else ThemeColor.TEXT, bold = column >= 0) + "  " +
            permissionCell(item.planValue, column == 0, theme) + "   " + permissionCell(item.agentValue, column == 1, theme) + "  " + description
    }

    private fun permissionCell(value: String, selected: Boolean, theme: Theme): String {
        val (text, colour) = when (value.uppercase()) {
            "ON", "TRUE" -> "ON " to ThemeColor.OK
            "ASK" -> "ASK" to ThemeColor.WARN
            else -> "OFF" to ThemeColor.ERR
        }
        return theme.style(text, fg = colour, bold = selected, reverse = selected)
    }

    /** Index present but RAG off (or the reverse): say it above the groups, from the same list the table shows. */
    private fun contextNotice(state: TuiState, tab: String, width: Int, frame: Frame, theme: Theme): List<String>? {
        if (tab != "Context / RAG") return null
        val files = state.ragIndexedFiles.size
        val text = when {
            files > 0 && state.ragEnabled == false ->
                "$files files are indexed, but RAG is disabled, so @codebase: and rag_search return nothing. Turn on \"RAG enabled\" below; F4 browses the index."
            files == 0 && state.ragEnabled == true && state.ragIndexingProgress < 0 ->
                "RAG is enabled but nothing is indexed yet, so @codebase: and rag_search return nothing. Start indexing in the RAG panel (F4, r)."
            else -> return null
        }
        val title = if (files > 0) "! index ready, RAG off" else "! RAG on, no index"
        val inner = frame.innerSize(width, 3).first
        val body = TextFit.wrapWords(text, inner).map { theme.style(it, fg = ThemeColor.TEXT) }
        return frame.render(width, body.size + 2, title, null, FrameState.WARNING, body)
    }

    private fun indexedFilesFrame(state: TuiState, width: Int, frame: Frame, theme: Theme, glyphs: Glyphs): List<String> {
        val files = state.ragIndexedFiles
        val inner = frame.innerSize(width, 3).first
        val body = if (files.isEmpty()) {
            listOf(theme.style(TuiRagView.headline(state), fg = ThemeColor.DIM))
        } else {
            TuiRagView.fileTable(files, -1, inner, FILES_IN_SETTINGS + 2, theme, glyphs)
        }
        return frame.render(width, body.size + 2, "indexed files", "${files.size} · F4 browse, r reindex", FrameState.INACTIVE, body)
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
        "Context / RAG" -> "rag"     // rag.search_similarity_threshold, rag.enabled, ...
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
            "Context / RAG" -> contextSpec(section, config)
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
        // placeholders like "not set" are never masked.
        val display = when {
            raw != null && key.contains("api_key") -> maskSecret(raw)
            raw != null -> raw
            default.isNotBlank() -> default
            else -> TuiColors.muted("not set")
        }
        return FieldItem(
            SettingsField("$section.$key", label, FieldType.TEXT, default = default, credential = credential),
            display = display,
            isDefault = raw == null && default.isNotBlank()
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
        HeaderItem("Execution"),
        cycleItem(section, "reasoning_effort", "Reasoning effort", config, REASONING_EFFORT_OPTIONS, "OFF"),
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
            // An unconfigured provider shows only what makes it configured; the rest appears once it is set.
            val shown = if (configured) fields else fields.filter { it.credential }
            for (def in shown) {
                items.add(valueItem(section, def.key, def.label, config, def.default, def.credential))
            }
        }
        return items
    }

    // ── Models ───────────────────────────────────────────────────────────

    private val assignments = listOf(
        Triple("chat", "Default", "chat"),
        Triple("plan", "Planning", "PLAN mode"),
        Triple("agent", "Coding / Agent", "AGENT mode, file edits"),
        Triple("weak", "Auxiliary", "summaries"),
        Triple("decision", "Decision", "completion judge")
    )

    private fun modelsSpec(section: String, config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("Model assignments", "Enter edit"))
        for ((key, label, usedFor) in assignments) {
            val raw = config[key]?.ifBlank { null }
            // The decision slot never inherits a chat model: "inherit" there means none is set.
            val unsetDecision = key == "decision" && (raw == null || raw.contains("inherit"))
            val display = when {
                unsetDecision -> TuiColors.muted("not set")
                raw != null -> formatModelValue(raw)
                else -> TuiColors.muted("auto")
            }
            items.add(FieldItem(SettingsField("$section.$key", label, FieldType.TEXT), display = display + TuiColors.muted("  $usedFor")))
        }
        val modelsConfig = viewModel?.getConfigSection("models") ?: emptyMap()
        items.add(valueItem("models", "embedding_model", "Embedding", modelsConfig, "auto"))

        val generalConfig = viewModel?.getConfigSection("general") ?: emptyMap()
        items.add(HeaderItem("Completion judge"))
        items.add(cycleItem("general", "judge_backend", "Strategy", generalConfig, JUDGE_BACKEND_OPTIONS, "LLM"))
        items.add(valueItem("general", "judge_decision_threshold", "Continue at p >=", generalConfig, "0.5"))
        items.add(valueItem("general", "judge_hybrid_band_low", "Hybrid: ask LLM from p", generalConfig, "0.15"))
        items.add(valueItem("general", "judge_hybrid_band_high", "Hybrid: ask LLM up to p", generalConfig, "0.85"))
        items.add(TextItem(TuiColors.muted("LLM: the auxiliary model decides. DECISION: only the decision model.")))
        items.add(TextItem(TuiColors.muted("HYBRID: the decision model, and the LLM when it is unsure.")))

        val visibilityKeys = modelsConfig.keys.filter { it.startsWith("visibility_") }.sorted()
        if (visibilityKeys.isNotEmpty()) {
            items.add(HeaderItem("Model visibility", "Enter toggle"))
            for (key in visibilityKeys.take(20)) {
                items.add(boolItem("models", key, key.removePrefix("visibility_"), modelsConfig))
            }
        }

        val models = cachedModels
        items.add(HeaderItem("Available models", "F refresh", wide = true))
        when {
            models == null -> items.add(TextItem(TuiColors.muted("Press F to load models from the providers.")))
            models.isEmpty() -> items.add(TextItem(TuiColors.muted("No models found. Check the provider settings.")))
            else -> {
                val assigned = assignments.mapNotNull { (key, label, _) -> config[key]?.ifBlank { null }?.let { formatModelValue(it) to label.lowercase() } }
                items.add(TextItem(TuiColors.muted(modelRow("provider", "model", "context", "in /1M", "out /1M", "shown", "assigned to"))))
                for (model in models.take(30)) {
                    val local = model.provider.lowercase() in LOCAL_PROVIDERS
                    val (priceIn, priceOut) = when {
                        local -> "local" to "local"
                        model.pricing == null -> "-" to "-"
                        else -> formatPrice(model.pricing.inputPer1M) to formatPrice(model.pricing.outputPer1M)
                    }
                    val roles = assigned.filter { it.first == "${model.provider}/${model.id}" }.joinToString(", ") { it.second }.ifEmpty { "-" }
                    items.add(TextItem(modelRow(model.provider, model.id, formatContextSize(model.contextSize), priceIn, priceOut, if (model.showInDropdown) "yes" else "no", roles)))
                }
                if (models.size > 30) items.add(TextItem(TuiColors.muted("… and ${models.size - 30} more (${models.size} in all)")))
                if (models.any { it.contextSize <= 0 }) items.add(TextItem(TuiColors.muted("context -: not known; for Ollama set the context size in Providers.")))
            }
        }
        return items
    }

    private fun modelRow(provider: String, model: String, context: String, priceIn: String, priceOut: String, shown: String, assigned: String): String =
        "${provider.take(12).padEnd(12)} ${model.take(36).padEnd(36)} ${context.padEnd(8)} ${priceIn.padEnd(9)} ${priceOut.padEnd(9)} ${shown.padEnd(6)} $assigned"

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
        price <= 0.0 -> "free"
        price < 0.01 -> "$${String.format(Locale.US, "%.4f", price)}"
        price < 1.0 -> "$${String.format(Locale.US, "%.3f", price)}"
        else -> "$${String.format(Locale.US, "%.2f", price)}"
    }

    /** Providers whose models run on this machine. */
    private val LOCAL_PROVIDERS = setOf("ollama", "lmstudio")

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
        items.add(HeaderItem("System prompts"))
        if (config.isEmpty()) {
            items.add(TextItem(TuiColors.muted("Prompts are managed via /explain, /refactor, etc.")))
            items.add(TextItem(TuiColors.muted("Type / in chat to see all available prompt commands.")))
        } else {
            for ((key, value) in config.entries.take(15)) {
                items.add(TextItem("${TuiColors.accent(key)}: ${value.lineSequence().first()}"))
            }
        }
        return items
    }

    // ── Context (RAG) ────────────────────────────────────────────────────

    private fun contextSpec(section: String, config: Map<String, String>): List<Item> = listOf(
        HeaderItem("RAG"),
        boolItem(section, "enabled", "RAG enabled", config),
        boolItem(section, "index_on_startup", "Index on startup", config),
        boolItem(section, "auto_index_on_context_build", "Auto-index on context build", config),
        HeaderItem("Search"),
        valueItem(section, "search_similarity_threshold", "Similarity threshold", config, "0.5"),
        valueItem(section, "search_top_k", "Top K results", config, "5"),
        valueItem(section, "search_semantic_weight", "Semantic weight", config, "0.7"),
        boolItem(section, "search_hybrid_enabled", "Hybrid search (BM25 + semantic)", config),
        boolItem(section, "search_include_context_chunks", "Include context chunks", config),
        HeaderItem("Indexing"),
        valueItem(section, "max_file_size_mb", "Max file size (MB)", config, "2"),
        valueItem(section, "max_chunks_per_file", "Max chunks per file", config, "100"),
        valueItem(section, "chunking_mode", "Chunking mode", config, "semantic"),
        valueItem(section, "max_concurrent_jobs", "Max concurrent jobs", config, "4")
    )

    // ── MCP ──────────────────────────────────────────────────────────────

    private fun mcpSpec(config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("MCP servers"))
        if (config.isEmpty()) {
            items.add(TextItem(TuiColors.muted("No MCP servers configured.")))
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
                items.add(TextItem("$status $name (${type.uppercase()}) ${TuiColors.muted(id.take(8))}"))
            }
        }
        items.add(HeaderItem("Commands"))
        items.add(TextItem(TuiColors.muted("/mcp-add stdio <name> <command> [args...]")))
        items.add(TextItem(TuiColors.muted("/mcp-add http <name> <url>")))
        items.add(TextItem(TuiColors.muted("/mcp-list  /mcp-edit <id> <field> <value>  /mcp-remove <id>")))
        return items
    }

    // ── Docs ─────────────────────────────────────────────────────────────

    private fun docsSpec(config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("Documentation sources"))
        if (config.isEmpty()) {
            items.add(TextItem(TuiColors.muted("No documentation sources configured.")))
        } else {
            for ((key, value) in config.entries.take(15)) {
                items.add(TextItem("${TuiColors.accent(key)}: $value"))
            }
        }
        items.add(HeaderItem("Commands"))
        items.add(TextItem(TuiColors.muted("/docs-add <url> [depth]  /docs-delete <id>  /docs-reindex <id>")))
        return items
    }

    // ── Tools ────────────────────────────────────────────────────────────

    private val PERMISSION_OPTIONS = listOf("ON", "ASK", "OFF")
    private val REASONING_EFFORT_OPTIONS = listOf("OFF", "LOW", "MEDIUM", "HIGH")
    private val JUDGE_BACKEND_OPTIONS = ConfigKeys.JUDGE_BACKENDS.map { it.uppercase() }

    private fun toolsSpec(): List<Item> {
        val items = mutableListOf<Item>()
        val permissions = viewModel?.getToolPermissions() ?: emptyList()
        if (permissions.isEmpty()) {
            items.add(HeaderItem("Tools"))
            items.add(TextItem(TuiColors.muted("Tool registry not available.")))
            return items
        }
        items.add(NoteItem("${TuiColors.statusSuccess("ON")} runs freely · ${TuiColors.statusPending("ASK")} needs approval · ${TuiColors.statusFailed("OFF")} hidden from the model"))
        val noEgress = viewModel?.getConfigSection("general")?.get("no_egress_enabled")?.lowercase() in listOf("true", "1", "yes")
        if (noEgress) items.add(NoteItem(TuiColors.statusPending("No-egress is on: network calls from tools are blocked.")))
        val definitions = viewModel?.getToolDefinitions() ?: emptyMap()
        for (group in ToolGroups.group(permissions, definitions)) {
            items.add(HeaderItem(group.label, "${group.tools.size}"))
            for (perm in group.tools) {
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
                        agentValue = perm.agentMode,
                        description = definitions[perm.toolName]?.description.orEmpty()
                    )
                )
            }
        }
        return items
    }

    // ── Subagents ────────────────────────────────────────────────────────

    private fun subagentsSpec(section: String, config: Map<String, String>): List<Item> {
        val items = mutableListOf<Item>()
        items.add(HeaderItem("Subagents"))
        items.add(boolItem(section, "builtin_enabled", "Built-in subagents enabled", config))

        val agents = config.keys.filter { it.contains("enabled") && it != "builtin_enabled" }.map {
            it.removeSuffix(".enabled").removeSuffix("_enabled").removePrefix("enabled_")
        }.filter { it.isNotBlank() }.distinct()

        items.add(HeaderItem("Profiles"))
        if (agents.isEmpty()) {
            items.add(TextItem(TuiColors.muted("Using the built-in subagent profiles.")))
            items.add(TextItem("api-designer, architect-reviewer, code-reviewer,"))
            items.add(TextItem("documentation-engineer, frontend-developer, fullstack-developer,"))
            items.add(TextItem("refactoring-specialist, security-engineer, sre-engineer, …"))
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
            HeaderItem("Timeouts"),
            valueItem("limits", "tool_execution_timeout", "Tool execution (s)", limitsConfig, "360"),
            valueItem("limits", "api_call_timeout", "API call (s)", limitsConfig, "360"),
            HeaderItem("Limits"),
            valueItem("limits", "max_file_size", "Max file size (MB)", limitsConfig, "10"),
            valueItem("limits", "max_context_size", "Max context (tokens)", limitsConfig, "128000"),
            valueItem("limits", "max_output_size", "Max output (tokens)", limitsConfig, ConfigKeys.MAX_OUTPUT_SIZE.default.toString()),
            HeaderItem("Performance"),
            valueItem(section, "auto_optimize_percentage", "Auto-optimize threshold (%)", config, "85")
        )
    }

    // ── Theme ────────────────────────────────────────────────────────────

    private fun themeSpec(): List<Item> = listOf(
        HeaderItem("Colours"),
        TextItem("${TuiColors.user("User message")}  ${TuiColors.assistant("Assistant message")}"),
        TextItem("${TuiColors.tool("Tool output")}  ${TuiColors.system("System/Error")}"),
        TextItem("${TuiColors.statusRunning("Running")} ${TuiColors.statusSuccess("Success")} ${TuiColors.statusFailed("Failed")} ${TuiColors.statusPending("Pending")}"),
        TextItem("${TuiColors.logDebug("DEBUG")} ${TuiColors.logInfo("INFO")} ${TuiColors.logWarn("WARN")} ${TuiColors.logError("ERROR")}"),
        HeaderItem("Agent colours"),
        TextItem(TuiColors.agentColors.mapIndexed { i, c -> c("Agent $i") }.joinToString("  ")),
        HeaderItem("Terminal"),
        TextItem(TuiColors.muted("NO_COLOR=1 turns colours off; REFIO_ASCII=1 draws with plain ASCII.")),
    )
}

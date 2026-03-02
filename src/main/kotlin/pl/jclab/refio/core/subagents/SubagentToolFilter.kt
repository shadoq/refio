package pl.jclab.refio.core.subagents

import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.services.PermissionLevel
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("SubagentToolFilter")

/**
 * Serwis filtrowania narzędzi dla subagentów.
 *
 * Odpowiedzialności:
 * - Filtrowanie narzędzi na podstawie definicji subagenta (whitelist/blacklist)
 * - Filtrowanie na podstawie trybu wykonania (CHAT/PLAN = read-only, AGENT = read/write)
 * - Mapowanie nazw narzędzi między Claude Code a Refio
 * - Wymuszanie zabezpieczeń (blokowanie niebezpiecznych narzędzi)
 */
class SubagentToolFilter(
    private val toolPermissionsService: ToolPermissionsService? = null
) {
    companion object {
        /**
         * Narzędzia READ_ONLY (bezpieczne dla wszystkich subagentów).
         */
        val READ_ONLY_TOOLS = setOf(
            "read_file",
            "read_directory",
            "file_search",
            "grep_search",
            "view_diff"
        )

        /**
         * Narzędzia zawsze zablokowane ze względów bezpieczeństwa.
         * Nawet jeśli subagent je explicite wymieni, nie będą dostępne.
         */
        val ALWAYS_BLOCKED_TOOLS = setOf(
            "run_terminal_command"
        )

        /**
         * Mapowanie nazw Claude Code -> Refio.
         */
        private val CLAUDE_TO_REFIO = mapOf(
            "read" to "read_file",
            "grep" to "grep_search",
            "glob" to "file_search",
            "bash" to "run_terminal_command",
            "edit" to "code_editing",
            "write" to "create_new_file",
            "multiedit" to "multi_edit"
        )

        /**
         * Mapowanie nazw Refio -> Claude Code.
         */
        private val REFIO_TO_CLAUDE = CLAUDE_TO_REFIO.entries.associate { (k, v) -> v to k }
    }

    /**
     * Filtruje narzędzia na podstawie definicji subagenta i kontekstu wykonania.
     *
     * Strategie:
     * 1. Filtruj po trybie wykonania (CHAT/PLAN = tylko READ_ONLY, AGENT = wszystkie)
     * 2. Whitelist (allowedTools): tylko wymienione narzędzia
     * 3. Blacklist (disallowedTools): wszystkie oprócz wymienionych
     * 4. Inherit (brak konfiguracji): wszystkie z parentTools
     *
     * @param allTools Wszystkie dostępne narzędzia
     * @param definition Definicja subagenta
     * @param parentTools Narzędzia z głównej konwersacji (dla inherit)
     * @param taskMode Tryb wykonania (CHAT/PLAN/AGENT) - wpływa na dostępność narzędzi WRITE
     * @return Lista dozwolonych narzędzi
     */
    fun filterTools(
        allTools: List<Tool>,
        definition: SubagentDefinition,
        parentTools: List<Tool>? = null,
        taskMode: TaskMode = TaskMode.AGENT
    ): List<Tool> {
        // Bazowe narzędzia: z rodzica (inherit) lub wszystkie dostępne
        var filtered = (parentTools ?: allTools).toList()

        // KROK 1: Filtruj po trybie wykonania
        // W trybie CHAT i PLAN dozwolone są tylko narzędzia READ_ONLY
        // W trybie AGENT dozwolone są wszystkie narzędzia (READ_ONLY + WRITE)
        filtered = when (taskMode) {
            TaskMode.CHAT, TaskMode.PLAN -> {
                filtered.filter { it.mode == ToolMode.READ_ONLY }
            }
            TaskMode.AGENT -> {
                // W trybie AGENT wszystkie narzędzia (READ_ONLY + WRITE) są dozwolone
                filtered
            }
        }

        logger.debug {
            "[SubagentToolFilter] After taskMode=$taskMode filter: ${filtered.map { it.name }}"
        }

        // KROK 2: Zawsze blokuj niebezpieczne narzędzia
        filtered = filtered.filter { it.name !in ALWAYS_BLOCKED_TOOLS }

        // KROK 3: Strategia whitelist/blacklist/inherit

        // Strategia 1: Whitelist (allowedTools)
        if (definition.usesToolWhitelist()) {
            val whitelist = definition.allowedTools!!.map { normalizeToolName(it) }.toSet()
            // Filtruj tylko te, które są na whitelist I przeszły filtr trybu
            filtered = filtered.filter { normalizeToolName(it.name) in whitelist }

            logger.debug {
                "[SubagentToolFilter] Whitelist for ${definition.name}: $whitelist -> ${filtered.map { it.name }}"
            }
            return filtered
        }

        // Strategia 2: Blacklist (disallowedTools)
        if (definition.usesToolBlacklist()) {
            val blacklist = definition.disallowedTools!!.map { normalizeToolName(it) }.toSet()
            filtered = filtered.filter { normalizeToolName(it.name) !in blacklist }

            logger.debug {
                "[SubagentToolFilter] Blacklist for ${definition.name}: $blacklist -> ${filtered.map { it.name }}"
            }
            return filtered
        }

        // Strategia 3: Inherit (brak konfiguracji = wszystkie z rodzica)
        // Dodatkowo stosuj domyślne uprawnienia z ToolPermissionsService
        if (toolPermissionsService != null) {
            filtered = filtered.filter { tool ->
                toolPermissionsService.getPermission(tool.name, taskMode) != PermissionLevel.OFF
            }
        }

        logger.debug {
            "[SubagentToolFilter] Inherit for ${definition.name}: ${filtered.map { it.name }}"
        }
        return filtered
    }

    /**
     * Sprawdza czy narzędzie jest dozwolone dla subagenta.
     */
    fun isToolAllowed(toolName: String, definition: SubagentDefinition): Boolean {
        val normalized = normalizeToolName(toolName)

        // Zawsze blokuj niebezpieczne
        if (normalized in ALWAYS_BLOCKED_TOOLS) return false

        // Whitelist check
        if (definition.usesToolWhitelist()) {
            return definition.allowedTools!!.any { normalizeToolName(it) == normalized }
        }

        // Blacklist check
        if (definition.usesToolBlacklist()) {
            return definition.disallowedTools!!.none { normalizeToolName(it) == normalized }
        }

        // Inherit = dozwolone
        return true
    }

    /**
     * Normalizuje nazwę narzędzia (obsługuje aliasy Claude Code).
     */
    fun normalizeToolName(name: String): String {
        val lower = name.lowercase()
        return CLAUDE_TO_REFIO[lower] ?: lower
    }

    /**
     * Konwertuje nazwę Refio na Claude Code.
     */
    fun toClaudeCodeName(refioName: String): String {
        val lower = refioName.lowercase()
        return REFIO_TO_CLAUDE[lower]?.replaceFirstChar { it.uppercase() } ?: refioName
    }

    /**
     * Pobiera listę narzędzi READ_ONLY.
     */
    fun getReadOnlyTools(allTools: List<Tool>): List<Tool> {
        return allTools.filter { it.name in READ_ONLY_TOOLS }
    }
}

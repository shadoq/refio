package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("ToolPermissionsService")

/**
 * Levels uprawnień dla narzędzi
 */
enum class PermissionLevel {
    ON,      // Zawsze dozwolone
    ASK,     // Wymaga zatwierdzenia użytkownika
    OFF      // Wyłączone
}

/**
 * Konfiguracja uprawnień dla pojedynczego narzędzia
 */
data class ToolPermissionConfig(
    val planMode: PermissionLevel = PermissionLevel.OFF,
    val agentMode: PermissionLevel = PermissionLevel.OFF
)

/**
 * Pełna konfiguracja uprawnień dla wszystkich narzędzi
 */
data class ToolPermissions(
    val tools: Map<String, ToolPermissionConfig> = emptyMap()
)

/**
 * Serwis zarządzania uprawnieniami narzędzi.
 *
 * Odpowiedzialności:
 * - Przechowywanie i ładowanie uprawnień z DB
 * - Filtrowanie narzędzi na podstawie trybu i uprawnień
 * - Sprawdzanie czy narzędzie wymaga approval
 * - Wyznaczanie smart defaults na podstawie ToolRegistry (single source of truth)
 *
 * Defaults są wyznaczane z `Tool.mode`:
 * - READ_ONLY  → ON w PLAN, ON w AGENT
 * - WRITE      → OFF w PLAN, ON w AGENT
 *
 * Wyjątki od tej reguły (np. `run_terminal_command` = ASK w AGENT, `run_code`
 * całkowicie OFF) są zadeklarowane w [DEFAULT_OVERRIDES]. Nowo rejestrowane
 * narzędzia (w tym MCP / plugin) automatycznie dziedziczą defaults z mode –
 * nie trzeba ich już wpisywać do mapy.
 */
class ToolPermissionsService(
    private val configRepository: ConfigRepository,
    private val toolRegistry: ToolRegistry? = null
) {

    companion object {
        const val CONFIG_KEY = ConfigService.KEY_TOOLS_PERMISSIONS

        /**
         * Override'y dla narzędzi, których defaulty odbiegają od reguły
         * wyznaczanej na bazie [ToolMode]. Wszystko poza tą mapą liczone
         * jest automatycznie z mode narzędzia.
         */
        private val DEFAULT_OVERRIDES: Map<String, ToolPermissionConfig> = mapOf(
            "run_terminal_command" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ASK),
            "run_code" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.OFF)
        )
    }

    /**
     * Wyznacza domyślną konfigurację uprawnień dla danego narzędzia
     * (single source of truth dla defaults – używane też przez ToolRouter
     * i UI Settings do populacji tabelki).
     */
    fun getDefaultPermissionConfig(tool: Tool): ToolPermissionConfig {
        DEFAULT_OVERRIDES[tool.name]?.let { return it }
        return when (tool.mode) {
            ToolMode.READ_ONLY -> ToolPermissionConfig(PermissionLevel.ON, PermissionLevel.ON)
            ToolMode.WRITE -> ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ON)
        }
    }

    /**
     * Wyznacza default dla narzędzia po nazwie.
     * Zwraca null gdy narzędzie nie jest w rejestrze (i nie ma też override'u).
     */
    private fun getDefaultPermissionConfig(toolName: String): ToolPermissionConfig? {
        toolRegistry?.getTool(toolName)?.let { return getDefaultPermissionConfig(it) }
        return DEFAULT_OVERRIDES[toolName]
    }

    /**
     * Buduje pełną mapę default permissions z wszystkich zarejestrowanych tooli.
     * Gdy brak registry (np. testy bez mocka) – pusta mapa.
     */
    private fun buildDefaultsFromRegistry(): Map<String, ToolPermissionConfig> {
        val tools = toolRegistry?.getAllTools() ?: return emptyMap()
        return tools.associate { it.name to getDefaultPermissionConfig(it) }
    }

    /**
     * Pobiera uprawnienia dla wszystkich narzędzi.
     * Zwraca smart defaults dla narzędzi, które nie mają ustawień.
     *
     * @param taskId Opcjonalne ID taska dla task-level config
     * @return Mapa narzędzi i ich uprawnień
     */
    fun getPermissions(taskId: String? = null): Map<String, ToolPermissionConfig> {
        val config = configRepository.getWithPrecedence(
            key = CONFIG_KEY,
            taskId = taskId
        )

        val stored = if (config != null) {
            try {
                val permissions = gson.fromJson(config.value, ToolPermissions::class.java)
                permissions.tools
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse tool permissions, using defaults" }
                emptyMap()
            }
        } else {
            emptyMap()
        }

        // Merge with defaults (stored values have priority)
        return buildDefaultsFromRegistry() + stored
    }

    /**
     * Pobiera uprawnienie dla konkretnego narzędzia i trybu.
     *
     * @param toolName Nazwa narzędzia
     * @param taskMode Tryb zadania (CHAT/PLAN/AGENT)
     * @param taskId Opcjonalne ID taska
     * @return Level uprawnień (ASK/ON/OFF)
     */
    fun getPermission(
        toolName: String,
        taskMode: TaskMode,
        taskId: String? = null
    ): PermissionLevel {
        val permissions = getPermissions(taskId)
        val config = permissions[toolName] ?: return getDefaultPermission(toolName, taskMode)

        return when (taskMode) {
            TaskMode.CHAT, TaskMode.PLAN -> config.planMode
            TaskMode.AGENT -> config.agentMode
        }
    }

    /**
     * Ustawia uprawnienie dla narzędzia i trybu.
     *
     * @param toolName Nazwa narzędzia
     * @param planMode Uprawnienie dla PLAN mode
     * @param agentMode Uprawnienie dla AGENT mode
     * @param taskId Opcjonalne ID taska (domyślnie APP scope)
     */
    fun setPermission(
        toolName: String,
        planMode: PermissionLevel,
        agentMode: PermissionLevel,
        taskId: String? = null
    ) {
        val current = getPermissions(taskId).toMutableMap()

        current[toolName] = ToolPermissionConfig(
            planMode = planMode,
            agentMode = agentMode
        )

        val permissions = ToolPermissions(tools = current)
        val json = gson.toJson(permissions)

        configRepository.set(
            key = CONFIG_KEY,
            value = json,
            scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP,
            taskId = taskId
        )

        logger.info { "Updated permissions for $toolName: plan=$planMode, agent=$agentMode" }
    }

    /**
     * Filtruje listę narzędzi na podstawie trybu i uprawnień.
     * Zwraca tylko narzędzia, które mają uprawnienie ON lub ASK (nie OFF).
     *
     * @param tools Lista wszystkich narzędzi
     * @param taskMode Tryb zadania (CHAT/PLAN/AGENT)
     * @param taskId Opcjonalne ID taska
     * @return Lista dozwolonych narzędzi
     */
    fun filterAvailableTools(
        tools: List<Tool>,
        taskMode: TaskMode,
        taskId: String? = null
    ): List<Tool> {
        return tools.filter { tool ->
            // W trybie CHAT/PLAN dozwolone są tylko READ_ONLY tools
            if (taskMode == TaskMode.CHAT || taskMode == TaskMode.PLAN) {
                if (tool.mode != ToolMode.READ_ONLY) {
                    return@filter false
                }
            }

            val permission = getPermission(tool.name, taskMode, taskId)
            permission != PermissionLevel.OFF
        }
    }

    /**
     * Resetuje uprawnienia do smart defaults.
     *
     * @param taskId Opcjonalne ID taska
     */
    fun resetToDefaults(taskId: String? = null) {
        val permissions = ToolPermissions(tools = buildDefaultsFromRegistry())
        val json = gson.toJson(permissions)

        configRepository.set(
            key = CONFIG_KEY,
            value = json,
            scope = if (taskId != null) ConfigScope.TASK else ConfigScope.APP,
            taskId = taskId
        )

        logger.info { "Reset tool permissions to defaults" }
    }

    /**
     * Pobiera domyślne uprawnienie dla narzędzia (używane gdy brak w DB).
     */
    private fun getDefaultPermission(toolName: String, taskMode: TaskMode): PermissionLevel {
        val config = getDefaultPermissionConfig(toolName) ?: return PermissionLevel.OFF

        return when (taskMode) {
            TaskMode.CHAT, TaskMode.PLAN -> config.planMode
            TaskMode.AGENT -> config.agentMode
        }
    }
}

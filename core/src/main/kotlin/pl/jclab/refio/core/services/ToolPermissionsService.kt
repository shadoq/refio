package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("ToolPermissionsService")

/**
 * Levels uprawnień dla narzędzi
 */
enum class PermissionLevel {
    ON,      // Zawsze dozwolone
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
 * - Smart defaults dla nowych narzędzi
 */
class ToolPermissionsService(
    private val configRepository: ConfigRepository
) {

    companion object {
        const val CONFIG_KEY = ConfigService.KEY_TOOLS_PERMISSIONS

        /**
         * Domyślne ustawienia dla narzędzi (Smart Defaults)
         */
        private val DEFAULT_PERMISSIONS = mapOf(
            // Read-only tools - zawsze ON w obu trybach
            "read_file" to ToolPermissionConfig(PermissionLevel.ON, PermissionLevel.ON),
            "read_directory" to ToolPermissionConfig(PermissionLevel.ON, PermissionLevel.ON),
            "file_search" to ToolPermissionConfig(PermissionLevel.ON, PermissionLevel.ON),
            "grep_search" to ToolPermissionConfig(PermissionLevel.ON, PermissionLevel.ON),
            "view_diff" to ToolPermissionConfig(PermissionLevel.ON, PermissionLevel.ON),
            "invoke_subagent" to ToolPermissionConfig(PermissionLevel.ON, PermissionLevel.ON),

            // Write tools - OFF w PLAN, ON w AGENT
            "create_new_file" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ON),
            "code_editing" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ON),
            "advance_code_editing" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ON),
            "multi_line_editor" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ON),
            "multi_edit" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ON),
            "run_terminal_command" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ON),
            "http_request" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.ON),
            "run_code" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.OFF)
        )
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
        return DEFAULT_PERMISSIONS + stored
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
        val permissions = ToolPermissions(tools = DEFAULT_PERMISSIONS)
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
        val config = DEFAULT_PERMISSIONS[toolName] ?: return PermissionLevel.OFF

        return when (taskMode) {
            TaskMode.CHAT, TaskMode.PLAN -> config.planMode
            TaskMode.AGENT -> config.agentMode
        }
    }
}

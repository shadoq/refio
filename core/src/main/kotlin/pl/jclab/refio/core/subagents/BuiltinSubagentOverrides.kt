package pl.jclab.refio.core.subagents

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.utils.GsonInstance.gson

/**
 * Persistence for per-name enable/disable overrides of built-in subagents.
 *
 * Stored as a single JSON blob under [ConfigKeys.SUBAGENTS_BUILTIN_ENABLED] in APP scope.
 */
class BuiltinSubagentOverrides(
    private val configRepository: ConfigRepository,
    private val invalidate: (String) -> Unit,
) {
    private val key: String get() = ConfigKeys.SUBAGENTS_BUILTIN_ENABLED.key

    fun getAll(): Map<String, Boolean> {
        val config = configRepository.get(key, ConfigScope.APP) ?: return emptyMap()
        val raw = gson.fromJson(config.value, Map::class.java) ?: return emptyMap()
        return raw.mapNotNull { (rawKey, rawValue) ->
            val name = rawKey as? String ?: return@mapNotNull null
            val enabled = when (rawValue) {
                is Boolean -> rawValue
                is String -> rawValue.toBoolean()
                else -> null
            } ?: return@mapNotNull null
            name to enabled
        }.toMap()
    }

    fun setOverride(name: String, enabled: Boolean) {
        val current = getAll().toMutableMap()
        current[name.lowercase()] = enabled
        configRepository.set(
            key = key,
            value = gson.toJson(current),
            scope = ConfigScope.APP,
            taskId = null,
            description = "Builtin subagent enabled overrides",
        )
        invalidate(key)
    }
}

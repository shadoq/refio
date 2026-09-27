package configdemo

/**
 * Lookup and write layer for configuration values.
 *
 * Stored values resolve task > project > application. [runOverrides] holds values passed
 * for the current run only (key to raw string): they have the highest priority, are
 * read-only and are never written to the repository. A blank override counts as absent.
 */
class ConfigResolver(
    private val repository: InMemoryConfigRepository,
    private val defaultProjectId: String?,
    private val runOverrides: Map<String, String> = emptyMap(),
) {

    /** Effective value of [key], or null when nothing is configured. */
    fun get(key: String, taskId: String? = null, projectId: String? = null): String? {
        runOverride(key)?.let { return it }
        return getConfigWithPrecedence(key, taskId, projectId)?.value
    }

    /** Effective configuration row for [key], for callers that need the whole row. */
    fun getConfigWithPrecedence(key: String, taskId: String? = null, projectId: String? = null): ConfigRow? {
        // Callers that read the row (model selection) must see the same run override as
        // get(); the row exists only for this run and is never stored.
        runOverride(key)?.let { raw ->
            return ConfigRow(key = key, value = raw, scope = ConfigScope.APP, description = RUN_OVERRIDE_DESCRIPTION)
        }
        return repository.getWithPrecedence(key = key, taskId = taskId, projectId = resolveProjectId(projectId))
    }

    fun set(
        key: String,
        value: String,
        scope: ConfigScope = ConfigScope.APP,
        taskId: String? = null,
        projectId: String? = null,
    ) {
        repository.set(
            key = key,
            value = value,
            scope = scope,
            projectId = if (scope == ConfigScope.PROJECT) resolveProjectId(projectId) else null,
            taskId = if (scope == ConfigScope.TASK) taskId else null,
        )
    }

    init {
        activeOverrides.putAll(runOverrides)
    }

    private fun runOverride(key: String): String? = activeOverrides[key]?.takeIf { it.isNotBlank() }

    private fun resolveProjectId(projectId: String?): String? = projectId ?: defaultProjectId

    private companion object {
        const val RUN_OVERRIDE_DESCRIPTION = "Run-scope override"
        val activeOverrides = mutableMapOf<String, String>()
    }
}

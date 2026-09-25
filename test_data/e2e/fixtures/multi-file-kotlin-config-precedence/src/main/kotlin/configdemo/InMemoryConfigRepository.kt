package configdemo

/**
 * Persistent configuration store. Kept in memory here; every call to [set] counts as a
 * write that would survive the current run.
 */
class InMemoryConfigRepository {
    private val rows = mutableListOf<ConfigRow>()

    var writeCount: Int = 0
        private set

    fun set(
        key: String,
        value: String,
        scope: ConfigScope,
        projectId: String? = null,
        taskId: String? = null,
        description: String? = null,
    ) {
        writeCount++
        rows.removeAll { it.key == key && it.scope == scope && it.projectId == projectId && it.taskId == taskId }
        rows.add(ConfigRow(key, value, scope, projectId, taskId, description))
    }

    fun get(key: String, scope: ConfigScope, projectId: String? = null, taskId: String? = null): ConfigRow? =
        rows.firstOrNull { it.key == key && it.scope == scope && it.projectId == projectId && it.taskId == taskId }

    /** Most specific stored row: task, then project, then application. */
    fun getWithPrecedence(key: String, taskId: String?, projectId: String?): ConfigRow? {
        if (taskId != null) get(key, ConfigScope.TASK, taskId = taskId)?.let { return it }
        if (projectId != null) get(key, ConfigScope.PROJECT, projectId = projectId)?.let { return it }
        return get(key, ConfigScope.APP)
    }

    fun allRows(): List<ConfigRow> = rows.toList()
}

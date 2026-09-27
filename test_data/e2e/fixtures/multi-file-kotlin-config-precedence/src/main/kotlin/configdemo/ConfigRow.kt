package configdemo

enum class ConfigScope { TASK, PROJECT, APP }

/** One stored configuration row. */
data class ConfigRow(
    val key: String,
    val value: String,
    val scope: ConfigScope,
    val projectId: String? = null,
    val taskId: String? = null,
    val description: String? = null,
)

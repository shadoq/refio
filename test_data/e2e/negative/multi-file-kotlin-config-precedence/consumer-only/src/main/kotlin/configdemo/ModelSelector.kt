package configdemo

/** Picks the model a task runs with. Reads the whole configuration row. */
class ModelSelector(private val resolver: ConfigResolver) {

    fun modelFor(taskId: String? = null): String {
        return resolver.get(MODEL_KEY, taskId = taskId)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    }

    companion object {
        const val MODEL_KEY = "model.default"
        const val DEFAULT_MODEL = "builtin/fallback-model"
    }
}

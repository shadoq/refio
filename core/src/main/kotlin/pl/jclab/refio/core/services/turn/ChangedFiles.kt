package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.services.ToolResultData

/**
 * Files a turn's tools reported changing, in the order they were first changed. Includes what a
 * subagent the turn started changed, so writes delegated to a subagent count for the turn that
 * delegated them.
 */
internal class ChangedFiles {
    private val paths = LinkedHashSet<String>()

    @Synchronized
    fun record(results: List<ToolResultData>) {
        results.forEach { paths.addAll(it.filesChanged) }
    }

    @Synchronized
    fun isNotEmpty(): Boolean = paths.isNotEmpty()

    @Synchronized
    fun toList(): List<String> = paths.toList()
}

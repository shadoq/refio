package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.services.ConfigService
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Working memory keeps compact, high-value facts across turns.
 */
data class WorkingMemoryEntry(
    val iteration: Int,
    val key: String,
    val value: String,
    val importance: Int = 5,
    val timestamp: Instant = Instant.now(),
    val lastAccessedAt: Instant = Instant.now()
)

class WorkingMemoryService(
    private val maxEntriesPerTask: Int = ConfigService.DEFAULT_WORKING_MEMORY_MAX_FACTS
) {
    private val entriesByTask = ConcurrentHashMap<String, ConcurrentHashMap<String, WorkingMemoryEntry>>()

    fun recordEntries(taskId: String, entries: List<WorkingMemoryEntry>) {
        if (entries.isEmpty()) return
        val taskEntries = entriesByTask.computeIfAbsent(taskId) { ConcurrentHashMap() }

        entries.forEach { entry ->
            val id = buildEntryId(entry)
            taskEntries[id] = entry.copy(lastAccessedAt = Instant.now())
        }

        trimEntries(taskEntries)
    }

    fun extractKnowledge(
        toolName: String,
        args: Map<String, Any?>,
        output: String,
        iteration: Int
    ): List<WorkingMemoryEntry> {
        return when (toolName) {
            "read_file" -> buildReadFileEntries(args, output, iteration)
            "read_directory" -> buildReadDirectoryEntries(args, output, iteration)
            "file_search" -> buildFileSearchEntries(args, output, iteration)
            "grep_search" -> buildGrepSearchEntries(args, output, iteration)
            "create_new_file", "code_editing", "multi_edit", "multi_line_editor", "advance_code_editing" ->
                buildWriteEntries(args, iteration)
            else -> emptyList()
        }
    }

    fun buildWorkingMemorySection(taskId: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        val taskEntries = entriesByTask[taskId] ?: return ""
        if (taskEntries.isEmpty()) return ""

        val grouped = taskEntries.values.groupBy { it.key }
        val sortedKeys = grouped.keys.sortedByDescending { key ->
            grouped[key]?.maxOfOrNull { it.importance } ?: 0
        }

        val sb = StringBuilder()
        sb.append("<WORKING_MEMORY>\n")
        var tokensUsed = ContextTokenEstimator.estimateTokens(sb.toString())

        for (key in sortedKeys) {
            val entries = grouped[key].orEmpty().sortedWith(
                compareByDescending<WorkingMemoryEntry> { it.importance }.thenByDescending { it.lastAccessedAt }
            )

            val header = "## $key"
            val headerTokens = ContextTokenEstimator.estimateTokens(header)
            if (tokensUsed + headerTokens > maxTokens) break
            sb.append(header).append('\n')
            tokensUsed += headerTokens

            for (entry in entries) {
                val line = "- ${entry.value}"
                val lineTokens = ContextTokenEstimator.estimateTokens(line)
                if (tokensUsed + lineTokens > maxTokens) {
                    break
                }
                sb.append(line).append('\n')
                tokensUsed += lineTokens
                taskEntries[buildEntryId(entry)] = entry.copy(lastAccessedAt = Instant.now())
            }

            sb.append('\n')
        }

        sb.append("</WORKING_MEMORY>")
        return sb.toString().trim()
    }

    private fun buildReadFileEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int
    ): List<WorkingMemoryEntry> {
        val path = firstStringArg(args, "path", "file", "file_path", "target") ?: "(unknown file)"
        val value = normalizeValue("read_file: $path (${output.length} chars)")
        return listOf(WorkingMemoryEntry(iteration, "files_read", value, importance = 7))
    }

    private fun buildReadDirectoryEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int
    ): List<WorkingMemoryEntry> {
        val path = firstStringArg(args, "path", "dir", "folder") ?: "(unknown dir)"
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        val sample = lines.take(5).joinToString(", ")
        val value = normalizeValue("read_directory: $path (${lines.size} entries)${if (sample.isNotBlank()) ", sample: $sample" else ""}")
        return listOf(WorkingMemoryEntry(iteration, "directory_structure", value, importance = 5))
    }

    private fun buildFileSearchEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int
    ): List<WorkingMemoryEntry> {
        val pattern = firstStringArg(args, "pattern") ?: "(pattern)"
        val path = firstStringArg(args, "path", "dir", "folder")
        val count = output.lineSequence().count { it.isNotBlank() }
        val value = normalizeValue("file_search '$pattern'${path?.let { " in $it" } ?: ""}: $count result(s)")
        return listOf(WorkingMemoryEntry(iteration, "search_results", value, importance = 6))
    }

    private fun buildGrepSearchEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int
    ): List<WorkingMemoryEntry> {
        val pattern = firstStringArg(args, "pattern", "query") ?: "(query)"
        val path = firstStringArg(args, "path", "dir", "folder")
        val count = output.lineSequence().count { it.isNotBlank() }
        val value = normalizeValue("grep_search '$pattern'${path?.let { " in $it" } ?: ""}: $count match(es)")
        return listOf(WorkingMemoryEntry(iteration, "search_results", value, importance = 6))
    }

    private fun buildWriteEntries(args: Map<String, Any?>, iteration: Int): List<WorkingMemoryEntry> {
        val paths = mutableListOf<String>()
        paths.addAll(listStringArg(args, "path", "file", "file_path", "target", "source"))
        paths.addAll(listStringArg(args, "files"))

        if (paths.isEmpty()) return emptyList()
        val unique = paths.distinct().take(5)
        val value = normalizeValue("files_modified: ${unique.joinToString(", ")}")
        return listOf(WorkingMemoryEntry(iteration, "changes", value, importance = 8))
    }

    private fun firstStringArg(args: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = args[key]
            if (value is String && value.isNotBlank()) {
                return value.trim()
            }
        }
        return null
    }

    private fun listStringArg(args: Map<String, Any?>, vararg keys: String): List<String> {
        val results = mutableListOf<String>()
        for (key in keys) {
            when (val value = args[key]) {
                is String -> if (value.isNotBlank()) results.add(value.trim())
                is List<*> -> value.filterIsInstance<String>().filter { it.isNotBlank() }.forEach { results.add(it.trim()) }
            }
        }
        return results
    }

    private fun normalizeValue(text: String, maxChars: Int = 400): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= maxChars) return compact
        return compact.take(maxChars - 3) + "..."
    }

    private fun buildEntryId(entry: WorkingMemoryEntry): String {
        return "${entry.key}:${entry.value.hashCode()}"
    }

    private fun trimEntries(taskEntries: ConcurrentHashMap<String, WorkingMemoryEntry>) {
        if (taskEntries.size <= maxEntriesPerTask) return
        val sorted = taskEntries.values.sortedWith(
            compareByDescending<WorkingMemoryEntry> { it.importance }.thenByDescending { it.lastAccessedAt }
        )
        val toKeep = sorted.take(maxEntriesPerTask)
        val keepIds = toKeep.map { buildEntryId(it) }.toSet()

        taskEntries.keys.filterNot { keepIds.contains(it) }.forEach { taskEntries.remove(it) }
    }
}

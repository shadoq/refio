package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.analysis.CodeElements
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
        iteration: Int,
        codeElementsProvider: ((String) -> CodeElements?)? = null
    ): List<WorkingMemoryEntry> {
        return when (toolName) {
            "read_file" -> buildReadFileEntries(args, output, iteration, codeElementsProvider)
            "read_directory" -> buildReadDirectoryEntries(args, output, iteration)
            "file_search" -> buildFileSearchEntries(args, output, iteration)
            "grep_search" -> buildGrepSearchEntries(args, output, iteration)
            "create_new_file", "code_editing", "multi_edit", "multi_line_editor", "advance_code_editing" ->
                buildWriteEntries(args, output, iteration)
            else -> emptyList()
        }
    }

    fun buildWorkingMemorySection(taskId: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        val taskEntries = entriesByTask[taskId] ?: return ""
        if (taskEntries.isEmpty()) return ""

        val maxIteration = taskEntries.values.maxOf { it.iteration }
        val grouped = taskEntries.values.groupBy { it.key }
        val sortedKeys = grouped.keys.sortedByDescending { key ->
            grouped[key]?.maxOfOrNull { effectiveImportance(it, maxIteration) } ?: 0
        }

        val sb = StringBuilder()
        sb.append("<WORKING_MEMORY>\n")
        var tokensUsed = ContextTokenEstimator.estimateTokens(sb.toString())

        for (key in sortedKeys) {
            val entries = grouped[key].orEmpty().sortedWith(
                compareByDescending<WorkingMemoryEntry> { effectiveImportance(it, maxIteration) }.thenByDescending { it.lastAccessedAt }
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
        iteration: Int,
        codeElementsProvider: ((String) -> CodeElements?)? = null
    ): List<WorkingMemoryEntry> {
        val path = firstStringArg(args, "path", "file", "file_path", "target") ?: "(unknown file)"
        val fileName = path.substringAfterLast('/')

        val codeElements = codeElementsProvider?.invoke(path)

        val value = if (codeElements != null && codeElements.hasContent()) {
            buildRichReadFileSummary(fileName, codeElements)
        } else {
            buildFallbackReadFileSummary(fileName, output)
        }

        return listOf(WorkingMemoryEntry(iteration, "files_read", normalizeValue(value), importance = 7))
    }

    private fun buildRichReadFileSummary(fileName: String, elements: CodeElements): String {
        val parts = mutableListOf<String>()
        parts.add("read_file $fileName:")

        // Key annotations at file level
        val topAnnotations = elements.annotations.take(3)
        if (topAnnotations.isNotEmpty()) {
            parts.add(topAnnotations.joinToString(", ") { "@$it" })
        }

        // Classes with their types
        if (elements.classes.isNotEmpty()) {
            val classDescriptions = elements.classes.take(3).map { cls ->
                val classAnnotations = cls.annotations.take(2)
                val prefix = if (classAnnotations.isNotEmpty()) {
                    classAnnotations.joinToString(" ") { "@${it.substringAfterLast('.')}" } + " "
                } else ""
                val typeLabel = when (cls.type) {
                    "class" -> ""
                    else -> "(${cls.type}) "
                }
                "$prefix$typeLabel${cls.name}"
            }
            parts.add("classes: [${classDescriptions.joinToString(", ")}]")
        }

        // Top-level functions and methods from classes
        val allMethods = mutableListOf<String>()
        elements.classes.forEach { cls ->
            cls.methods.take(4).forEach { method ->
                val params = method.parameters.joinToString(", ") { it.name }
                allMethods.add("${method.name}($params)")
            }
        }
        elements.functions.take(4).forEach { fn ->
            val params = fn.parameters.joinToString(", ") { it.name }
            allMethods.add("${fn.name}($params)")
        }
        if (allMethods.isNotEmpty()) {
            parts.add("methods: [${allMethods.take(6).joinToString(", ")}]")
        }

        // Dependencies from imports
        val keyDependencies = elements.imports
            .map { it.module.substringAfterLast('.') }
            .filter { it.length > 2 && !it.startsWith("*") }
            .distinct()
            .take(4)
        if (keyDependencies.isNotEmpty()) {
            parts.add("Depends on: ${keyDependencies.joinToString(", ")}")
        }

        return parts.joinToString(", ")
    }

    private fun buildFallbackReadFileSummary(fileName: String, output: String): String {
        val lines = output.lines()
        val lineCount = lines.size

        val classPattern = Regex("""(?:class|interface|object|enum|data class|sealed class|abstract class)\s+(\w+)""")
        val funPattern = Regex("""(?:fun|function|def|async\s+def)\s+(\w+)\s*\(""")

        val detectedClasses = classPattern.findAll(output).map { it.groupValues[1] }.distinct().take(3).toList()
        val detectedFunctions = funPattern.findAll(output).map { it.groupValues[1] }.distinct().take(5).toList()

        val parts = mutableListOf<String>()
        parts.add("read_file $fileName: $lineCount lines")

        if (detectedClasses.isNotEmpty()) {
            parts.add("classes: [${detectedClasses.joinToString(", ")}]")
        }
        if (detectedFunctions.isNotEmpty()) {
            parts.add("functions: [${detectedFunctions.joinToString(", ")}]")
        }

        return parts.joinToString(", ")
    }

    private fun buildReadDirectoryEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int
    ): List<WorkingMemoryEntry> {
        val path = firstStringArg(args, "path", "dir", "folder") ?: "(unknown dir)"
        val dirName = path.trimEnd('/').substringAfterLast('/')
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()

        // Extract file names from the output, stripping tree-formatting characters
        val fileNames = lines.mapNotNull { line ->
            val cleaned = line.replace(Regex("[│├└─\\s]+"), "").trim()
            if (cleaned.isNotBlank()) cleaned else null
        }

        // Rank files by importance based on extension
        val extensionPriority = mapOf(
            "kt" to 1, "java" to 2, "py" to 3, "ts" to 4, "tsx" to 5,
            "js" to 6, "jsx" to 7, "go" to 8, "rs" to 9, "scala" to 10,
            "yaml" to 11, "yml" to 11, "json" to 12, "toml" to 12,
            "xml" to 13, "gradle" to 13, "properties" to 14
        )

        val keyFiles = fileNames
            .filter { it.contains('.') }
            .sortedBy { name ->
                val ext = name.substringAfterLast('.')
                extensionPriority[ext] ?: 20
            }
            .take(5)

        val value = if (keyFiles.isNotEmpty()) {
            "read_directory $dirName/: ${lines.size} files, key: ${keyFiles.joinToString(", ")}"
        } else {
            "read_directory $dirName/: ${lines.size} entries"
        }

        return listOf(WorkingMemoryEntry(iteration, "directory_structure", normalizeValue(value), importance = 5))
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
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        val totalMatches = lines.size

        // Parse file:line matches from output
        val fileLinePattern = Regex("""^(.+?):(\d+)[:\s]""")
        val topMatches = lines
            .mapNotNull { line ->
                fileLinePattern.find(line)?.let { match ->
                    val filePath = match.groupValues[1]
                    val lineNum = match.groupValues[2]
                    val fileName = filePath.substringAfterLast('/')
                    "$fileName:$lineNum"
                }
            }
            .distinct()
            .take(5)

        val value = if (topMatches.isNotEmpty()) {
            "grep '$pattern': found in ${topMatches.joinToString(", ")} ($totalMatches total matches)"
        } else {
            "grep '$pattern': $totalMatches match(es)"
        }

        return listOf(WorkingMemoryEntry(iteration, "search_results", normalizeValue(value), importance = 6))
    }

    private fun buildWriteEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int
    ): List<WorkingMemoryEntry> {
        val paths = mutableListOf<String>()
        paths.addAll(listStringArg(args, "path", "file", "file_path", "target", "source"))
        paths.addAll(listStringArg(args, "files"))

        if (paths.isEmpty()) return emptyList()
        val unique = paths.distinct().take(5)
        val fileNames = unique.map { it.substringAfterLast('/') }

        val value = if (output.isNotBlank()) {
            buildRichWriteSummary(fileNames, output)
        } else {
            "files_modified: ${unique.joinToString(", ")}"
        }

        return listOf(WorkingMemoryEntry(iteration, "changes", normalizeValue(value), importance = 8))
    }

    private fun buildRichWriteSummary(fileNames: List<String>, output: String): String {
        // Try to extract change descriptions from diff-like output
        val addedLines = output.lines().filter { it.startsWith("+") && !it.startsWith("+++") }
        val removedLines = output.lines().filter { it.startsWith("-") && !it.startsWith("---") }

        // Detect what kind of changes happened from added/removed lines
        val changeHints = mutableListOf<String>()

        val funPattern = Regex("""(?:fun|function|def)\s+(\w+)""")
        val classPattern = Regex("""(?:class|interface|object)\s+(\w+)""")

        val addedFunctions = addedLines.flatMap { funPattern.findAll(it).map { m -> m.groupValues[1] } }.distinct().take(3)
        val addedClasses = addedLines.flatMap { classPattern.findAll(it).map { m -> m.groupValues[1] } }.distinct().take(2)
        val removedFunctions = removedLines.flatMap { funPattern.findAll(it).map { m -> m.groupValues[1] } }.distinct().take(2)

        if (addedClasses.isNotEmpty()) changeHints.add("added class ${addedClasses.joinToString(", ")}")
        if (addedFunctions.isNotEmpty()) changeHints.add("added/changed ${addedFunctions.joinToString(", ")}")
        if (removedFunctions.isNotEmpty()) changeHints.add("removed ${removedFunctions.joinToString(", ")}")

        return if (changeHints.isNotEmpty()) {
            "Modified ${fileNames.joinToString(", ")}: ${changeHints.joinToString("; ")}"
        } else {
            "files_modified: ${fileNames.joinToString(", ")}"
        }
    }

    private fun CodeElements.hasContent(): Boolean {
        return classes.isNotEmpty() || functions.isNotEmpty() || imports.isNotEmpty()
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

    private fun normalizeValue(text: String, maxChars: Int = 600): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= maxChars) return compact
        return compact.take(maxChars - 3) + "..."
    }

    private fun buildEntryId(entry: WorkingMemoryEntry): String {
        return "${entry.key}:${entry.value.hashCode()}"
    }

    private fun trimEntries(taskEntries: ConcurrentHashMap<String, WorkingMemoryEntry>) {
        if (taskEntries.size <= maxEntriesPerTask) return
        val maxIteration = taskEntries.values.maxOfOrNull { it.iteration } ?: 0
        val sorted = taskEntries.values.sortedWith(
            compareByDescending<WorkingMemoryEntry> { effectiveImportance(it, maxIteration) }.thenByDescending { it.lastAccessedAt }
        )
        val toKeep = sorted.take(maxEntriesPerTask)
        val keepIds = toKeep.map { buildEntryId(it) }.toSet()

        taskEntries.keys.filterNot { keepIds.contains(it) }.forEach { taskEntries.remove(it) }
    }

    /**
     * Calculate effective importance with age-based decay.
     * Importance decays by 1 for every 5 iterations since the entry was created.
     * Minimum effective importance is 1.
     */
    private fun effectiveImportance(entry: WorkingMemoryEntry, maxIterationInCollection: Int): Int {
        val iterationsSinceCreated = (maxIterationInCollection - entry.iteration).coerceAtLeast(0)
        val decay = iterationsSinceCreated / 5
        return (entry.importance - decay).coerceAtLeast(1)
    }
}

package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.analysis.CodeElements
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Working memory keeps compact, high-value facts across turns.
 *
 * @param originId Optional identifier of the source (e.g. subtask id or tool_call id).
 *                 Surfaced in the rendered section so the LLM can tell which entry came
 *                 from which run and recognise superseded values across iterations.
 */
data class WorkingMemoryEntry(
    val iteration: Int,
    val key: String,
    val value: String,
    val outputExcerpt: String? = null,
    val importance: Int = 5,
    val timestamp: Instant = Instant.now(),
    val lastAccessedAt: Instant = Instant.now(),
    val originId: String? = null
)

class WorkingMemoryService(
    private val maxEntriesPerTask: Int = ConfigService.DEFAULT_WORKING_MEMORY_MAX_FACTS
) {
    private val entriesByTask = ConcurrentHashMap<String, ConcurrentHashMap<String, WorkingMemoryEntry>>()
    private val entriesBySession = ConcurrentHashMap<String, ConcurrentHashMap<String, WorkingMemoryEntry>>()

    fun recordEntries(taskId: String, entries: List<WorkingMemoryEntry>, sessionId: String? = null) {
        if (entries.isEmpty()) return
        val taskEntries = entriesByTask.computeIfAbsent(taskId) { ConcurrentHashMap() }

        entries.forEach { entry ->
            val id = buildEntryId(entry)
            taskEntries[id] = entry.copy(lastAccessedAt = Instant.now())
        }

        trimEntries(taskEntries)

        // Also record under sessionId so orchestrator sees all subagent memory
        if (sessionId != null) {
            val sessionEntries = entriesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
            entries.forEach { entry ->
                val id = buildEntryId(entry)
                sessionEntries[id] = entry.copy(lastAccessedAt = Instant.now())
            }
            trimEntries(sessionEntries)
        }
    }

    /**
     * Build working memory section from session-scoped entries (all agents).
     * Falls back to task-scoped if sessionId not found.
     */
    fun buildSessionMemorySection(sessionId: String, maxTokens: Int): String {
        val sessionEntries = entriesBySession[sessionId]
        if (sessionEntries == null || sessionEntries.isEmpty()) return ""
        return formatEntriesAsSection(sessionEntries, maxTokens)
    }

    /** Remove session-scoped entries (call on session close). */
    fun clearSession(sessionId: String) {
        entriesBySession.remove(sessionId)
    }

    fun extractKnowledge(
        toolName: String,
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null,
        codeElementsProvider: ((String) -> CodeElements?)? = null,
        originId: String? = null
    ): List<WorkingMemoryEntry> {
        val entries = when (toolName) {
            "read_file" -> buildReadFileEntries(args, output, iteration, metadata, codeElementsProvider)
            "read_directory" -> buildReadDirectoryEntries(args, output, iteration, metadata)
            "file_search" -> buildFileSearchEntries(args, output, iteration, metadata)
            "grep_search" -> buildGrepSearchEntries(args, output, iteration, metadata)
            "view_diff" -> buildViewDiffEntries(args, output, iteration, metadata)
            "http_request" -> buildHttpRequestEntries(args, output, iteration, metadata)
            "run_code" -> buildRunCodeEntries(args, output, iteration, metadata)
            "run_terminal_command" -> buildRunTerminalCommandEntries(args, output, iteration, metadata)
            "invoke_subagent" -> buildInvokeSubagentEntries(args, output, iteration, metadata)
            "create_new_file", "code_editing", "multi_edit", "multi_line_editor", "advance_code_editing" ->
                buildWriteEntries(toolName, args, output, iteration, metadata)
            else -> buildGenericToolEntries(toolName, args, output, iteration, metadata)
        }
        return if (originId != null) entries.map { it.copy(originId = originId) } else entries
    }

    fun buildWorkingMemorySection(taskId: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        val taskEntries = entriesByTask[taskId] ?: return ""
        if (taskEntries.isEmpty()) return ""
        return formatEntriesAsSection(taskEntries, maxTokens)
    }

    private fun formatEntriesAsSection(
        entries: ConcurrentHashMap<String, WorkingMemoryEntry>,
        maxTokens: Int
    ): String {
        if (entries.isEmpty()) return ""

        val maxIteration = entries.values.maxOf { it.iteration }
        val grouped = entries.values.groupBy { it.key }
        val sortedKeys = grouped.keys.sortedByDescending { key ->
            grouped[key]?.maxOfOrNull { effectiveImportance(it, maxIteration) } ?: 0
        }

        val sb = StringBuilder()
        sb.append("<WORKING_MEMORY>\n")
        sb.append("<!-- [it#N]=iteration, [ref#X]=tool-call id; newer it# supersedes older -->\n")
        var tokensUsed = ContextTokenEstimator.estimateTokens(sb.toString())

        for (key in sortedKeys) {
            // Sort newest-first within a key so the model sees the most recent fact at the top.
            val sortedEntries = grouped[key].orEmpty().sortedWith(
                compareByDescending<WorkingMemoryEntry> { it.iteration }
                    .thenByDescending { effectiveImportance(it, maxIteration) }
                    .thenByDescending { it.lastAccessedAt }
            )

            val header = "## $key"
            val headerTokens = ContextTokenEstimator.estimateTokens(header)
            if (tokensUsed + headerTokens > maxTokens) break
            sb.append(header).append('\n')
            tokensUsed += headerTokens

            for (entry in sortedEntries) {
                val tag = buildEntryTag(entry)
                val line = "- $tag ${entry.value}"
                val lineTokens = ContextTokenEstimator.estimateTokens(line)
                if (tokensUsed + lineTokens > maxTokens) {
                    break
                }
                sb.append(line).append('\n')
                tokensUsed += lineTokens
                entry.outputExcerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                    val excerptLine = "  output: $excerpt"
                    val excerptTokens = ContextTokenEstimator.estimateTokens(excerptLine)
                    if (tokensUsed + excerptTokens <= maxTokens) {
                        sb.append(excerptLine).append('\n')
                        tokensUsed += excerptTokens
                    }
                }
                entries[buildEntryId(entry)] = entry.copy(lastAccessedAt = Instant.now())
            }

            sb.append('\n')
        }

        sb.append("</WORKING_MEMORY>")
        return sb.toString().trim()
    }

    /**
     * Build a compact origin tag for a working-memory entry.
     * Format: `[it#N ref#XXXXXXXX]` where XXXXXXXX is the first 8 chars of the originId.
     * If no originId is set: `[it#N]`.
     */
    private fun buildEntryTag(entry: WorkingMemoryEntry): String {
        val ref = entry.originId?.take(8)
        return if (ref != null) "[it#${entry.iteration} ref#$ref]" else "[it#${entry.iteration}]"
    }

    private fun buildReadFileEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null,
        codeElementsProvider: ((String) -> CodeElements?)? = null
    ): List<WorkingMemoryEntry> {
        val path = metadataString(metadata, "path")
            ?: firstStringArg(args, "path", "file", "file_path", "target")
            ?: "(unknown file)"
        val fileName = path.substringAfterLast('/')

        val codeElements = codeElementsProvider?.invoke(path)

        val value = if (codeElements != null && codeElements.hasContent()) {
            buildRichReadFileSummary(fileName, codeElements)
        } else {
            buildFallbackReadFileSummary(fileName, output, metadata)
        }

        return listOf(
            WorkingMemoryEntry(
                iteration,
                "files_read",
                normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 7
            )
        )
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

    private fun buildFallbackReadFileSummary(fileName: String, output: String, metadata: Map<String, Any?>? = null): String {
        val lines = output.lines()
        val lineCount = metadataInt(metadata, "lines_read", "total_lines") ?: lines.size

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
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val path = metadataString(metadata, "path")
            ?: firstStringArg(args, "path", "dir", "folder")
            ?: "(unknown dir)"
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

        val fileCount = metadataInt(metadata, "file_count") ?: lines.size
        val directoryCount = metadataInt(metadata, "directory_count")
        val value = if (keyFiles.isNotEmpty()) {
            buildString {
                append("read_directory $dirName/: $fileCount entries")
                if (directoryCount != null) append(", dirs: $directoryCount")
                append(", key: ${keyFiles.joinToString(", ")}")
            }
        } else {
            buildString {
                append("read_directory $dirName/: $fileCount entries")
                if (directoryCount != null) append(", dirs: $directoryCount")
            }
        }

        return listOf(
            WorkingMemoryEntry(
                iteration,
                "directory_structure",
                normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 5
            )
        )
    }

    private fun buildFileSearchEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val pattern = firstStringArg(args, "pattern") ?: "(pattern)"
        val path = metadataString(metadata, "search_path")
            ?: firstStringArg(args, "path", "dir", "folder")
        val count = metadataInt(metadata, "result_count") ?: output.lineSequence().count { it.isNotBlank() }
        val hasMore = metadataBoolean(metadata, "has_more") == true
        val value = normalizeValue(
            "file_search '$pattern'${path?.let { " in $it" } ?: ""}: $count result(s)" +
                if (hasMore) ", more available" else ""
        )
        return listOf(
            WorkingMemoryEntry(
                iteration,
                "search_results",
                value,
                outputExcerpt = buildOutputExcerpt(output),
                importance = 6
            )
        )
    }

    private fun buildGrepSearchEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val pattern = metadataString(metadata, "pattern")
            ?: firstStringArg(args, "pattern", "query")
            ?: "(query)"
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        val totalMatches = metadataInt(metadata, "match_count") ?: lines.size

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

        return listOf(
            WorkingMemoryEntry(
                iteration,
                "search_results",
                normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 6
            )
        )
    }

    private fun buildViewDiffEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val file1 = metadataString(metadata, "file1") ?: firstStringArg(args, "file1") ?: "(unknown file)"
        val file2 = metadataString(metadata, "file2") ?: firstStringArg(args, "file2") ?: "<content>"
        val added = metadataInt(metadata, "added_lines") ?: countPrefixedLines(output, "+")
        val removed = metadataInt(metadata, "removed_lines") ?: countPrefixedLines(output, "-")
        val unchanged = metadataInt(metadata, "unchanged_lines")
        val value = buildString {
            append("view_diff ${file1.substringAfterLast('/')} vs ${file2.substringAfterLast('/')}: +$added, -$removed")
            if (unchanged != null) append(", =$unchanged")
        }
        return listOf(
            WorkingMemoryEntry(
                iteration,
                "diffs",
                normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 5
            )
        )
    }

    private fun buildHttpRequestEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val url = metadataString(metadata, "url") ?: firstStringArg(args, "url") ?: "(unknown url)"
        val method = metadataString(metadata, "method") ?: firstStringArg(args, "method") ?: "GET"
        val status = metadataInt(metadata, "status_code")
        val length = metadataInt(metadata, "response_length")
        val savePath = metadataString(metadata, "saved_to_file") ?: firstStringArg(args, "save_to_file")
        val binary = metadataBoolean(metadata, "binary")
        val truncated = metadataBoolean(metadata, "truncated")
        val host = url.substringAfter("://", url).substringBefore('/')
        val value = buildString {
            append("http_request $method $host")
            status?.let { append(": status=$it") }
            length?.let { append(", size=$it") }
            if (binary == true) append(", binary")
            if (truncated == true) append(", truncated")
            savePath?.let { append(", saved=${it.substringAfterLast('/')}") }
        }
        val entries = mutableListOf(
            WorkingMemoryEntry(
                iteration,
                "network",
                normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 6
            )
        )
        extractStructuredFacts(output, maxFacts = 2).takeIf { it.isNotEmpty() }?.let { facts ->
            entries += WorkingMemoryEntry(
                iteration,
                "network_results",
                normalizeValue("http_request facts: ${facts.joinToString(" | ")}"),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 5
            )
        }
        return entries
    }

    private fun buildRunCodeEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val language = metadataString(metadata, "language") ?: firstStringArg(args, "language") ?: "unknown"
        val exitCode = metadataInt(metadata, "exit_code")
        val timedOut = metadataBoolean(metadata, "timed_out")
        val outputLength = metadataInt(metadata, "output_length", "partial_output_length")
        val truncated = metadataBoolean(metadata, "truncated")
        val value = buildString {
            append("run_code $language")
            exitCode?.let { append(": exit=$it") }
            if (timedOut == true) append(", timed_out")
            outputLength?.let { append(", output=$it chars") }
            if (truncated == true) append(", truncated")
        }
        val entries = mutableListOf(
            WorkingMemoryEntry(
                iteration,
                "code_execution",
                normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 7
            )
        )
        extractStructuredFacts(output, maxFacts = 4).takeIf { it.isNotEmpty() }?.let { facts ->
            entries += WorkingMemoryEntry(
                iteration,
                "analysis_results",
                normalizeValue("run_code facts: ${facts.joinToString(" | ")}"),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 8
            )
        }
        return entries
    }

    private fun buildRunTerminalCommandEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val command = metadataString(metadata, "command") ?: firstStringArg(args, "command") ?: "(command)"
        val exitCode = metadataInt(metadata, "exit_code")
        val timedOut = metadataBoolean(metadata, "timed_out")
        val outputLength = metadataInt(metadata, "output_length", "partial_output_length")
        val value = buildString {
            append("run_terminal_command ${truncateInline(command, 80)}")
            exitCode?.let { append(": exit=$it") }
            if (timedOut == true) append(", timed_out")
            outputLength?.let { append(", output=$it chars") }
        }
        val entries = mutableListOf(
            WorkingMemoryEntry(
                iteration,
                "command_execution",
                normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 6
            )
        )
        extractStructuredFacts(output, maxFacts = 3).takeIf { it.isNotEmpty() }?.let { facts ->
            entries += WorkingMemoryEntry(
                iteration,
                "command_results",
                normalizeValue("command facts: ${facts.joinToString(" | ")}"),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 6
            )
        }
        return entries
    }

    private fun buildInvokeSubagentEntries(
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val name = metadataString(metadata, "subagent_name")
            ?: firstStringArg(args, "subagent_name")
            ?: "(subagent)"
        val depth = metadataInt(metadata, "depth")
        val childIterations = metadataInt(metadata, "iterations")
        val tokensOut = metadataInt(metadata, "tokens_out")
        val value = buildString {
            append("invoke_subagent $name")
            depth?.let { append(": depth=$it") }
            childIterations?.let { append(", iterations=$it") }
            tokensOut?.let { append(", tokens_out=$it") }
        }
        val entries = mutableListOf(
            WorkingMemoryEntry(
                iteration,
                "subagent_work",
                normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 7
            )
        )
        extractStructuredFacts(output, maxFacts = 2).takeIf { it.isNotEmpty() }?.let { facts ->
            entries += WorkingMemoryEntry(
                iteration,
                "subagent_results",
                normalizeValue("$name facts: ${facts.joinToString(" | ")}"),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 7
            )
        }
        return entries
    }

    private fun buildWriteEntries(
        toolName: String,
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val paths = extractPaths(args, metadata)

        if (paths.isEmpty()) return emptyList()
        val unique = paths.distinct().take(5)
        val fileNames = unique.map { it.substringAfterLast('/') }

        val value = if (output.isNotBlank() || metadata != null) {
            buildRichWriteSummary(toolName, fileNames, output, metadata)
        } else {
            "files_modified: ${unique.joinToString(", ")}"
        }

        return listOf(WorkingMemoryEntry(iteration, "changes", normalizeValue(value), importance = 8))
            .map { it.copy(outputExcerpt = buildOutputExcerpt(output)) }
    }

    private fun buildGenericToolEntries(
        toolName: String,
        args: Map<String, Any?>,
        output: String,
        iteration: Int,
        metadata: Map<String, Any?>? = null
    ): List<WorkingMemoryEntry> {
        val summary = buildGenericToolSummary(toolName, args, output, metadata)
        return if (summary.isBlank()) emptyList() else {
            listOf(
                WorkingMemoryEntry(
                    iteration,
                    "tool_activity",
                    normalizeValue(summary),
                    outputExcerpt = buildOutputExcerpt(output),
                    importance = 4
                )
            )
        }
    }

    private fun buildGenericToolSummary(
        toolName: String,
        args: Map<String, Any?>,
        output: String,
        metadata: Map<String, Any?>? = null
    ): String {
        val interestingArgs = sequenceOf(
            firstStringArg(args, "path", "file", "target", "url"),
            metadataString(metadata, "path", "url")
        ).filterNotNull().firstOrNull()
        val facts = extractStructuredFacts(output, maxFacts = 2)
        return buildString {
            append(toolName)
            interestingArgs?.let { append(" ${truncateInline(it, 80)}") }
            if (facts.isNotEmpty()) append(": ${facts.joinToString(" | ")}")
        }
    }

    private fun buildRichWriteSummary(
        toolName: String,
        fileNames: List<String>,
        output: String,
        metadata: Map<String, Any?>? = null
    ): String {
        val mode = metadataString(metadata, "mode")
        val replacements = metadataInt(metadata, "replacements", "total_replacements")
        val addedLinesMeta = metadataInt(metadata, "added_lines")
        val removedLinesMeta = metadataInt(metadata, "removed_lines")
        val diffLines = metadataInt(metadata, "diff_lines")
        val model = metadataString(metadata, "model")

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
        replacements?.let { changeHints.add("replacements=$it") }
        if (addedLinesMeta != null || removedLinesMeta != null) {
            changeHints.add("lines +${addedLinesMeta ?: 0}/-${removedLinesMeta ?: 0}")
        }
        diffLines?.let { changeHints.add("diff_lines=$it") }
        mode?.let { changeHints.add("mode=$it") }
        model?.let { changeHints.add("model=$it") }

        return if (changeHints.isNotEmpty()) {
            "$toolName ${fileNames.joinToString(", ")}: ${changeHints.joinToString("; ")}"
        } else {
            "$toolName ${fileNames.joinToString(", ")}"
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

    private fun extractPaths(args: Map<String, Any?>, metadata: Map<String, Any?>? = null): List<String> {
        val results = mutableListOf<String>()
        results.addAll(listStringArg(args, "path", "file", "file_path", "target", "source", "file1", "file2", "save_to_file"))
        results.addAll(listStringArg(args, "files"))
        metadataString(metadata, "path", "file1", "file2", "saved_to_file")?.let { results.add(it) }
        val edits = args["edits"] as? List<*>
        edits.orEmpty().mapNotNull { it as? Map<*, *> }.forEach { edit ->
            val path = edit["path"] as? String
            if (!path.isNullOrBlank()) results.add(path)
        }
        return results.distinct()
    }

    private fun extractStructuredFacts(output: String, maxFacts: Int = 3): List<String> {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("```") || it.startsWith("[NOTE:") || it.startsWith("Diff:") }
            .take(30)
            .toList()

        val scored = lines.mapNotNull { line ->
            val normalized = normalizeValue(line, maxChars = 160)
            val score = when {
                normalized.contains("saved to", ignoreCase = true) -> 5
                normalized.contains("written to", ignoreCase = true) -> 5
                Regex("""^[A-Za-z][A-Za-z0-9 _/\-().]{1,50}:\s+.+$""").matches(normalized) -> 4
                Regex("""^[A-Za-z][A-Za-z0-9 _/\-().]{1,50}=\S.+$""").matches(normalized) -> 4
                normalized.contains("total", ignoreCase = true) -> 3
                normalized.contains("count", ignoreCase = true) -> 3
                normalized.contains("found", ignoreCase = true) -> 3
                normalized.contains("error", ignoreCase = true) -> 3
                normalized.contains("warning", ignoreCase = true) -> 3
                else -> 0
            }
            if (score > 0) score to normalized else null
        }

        return scored
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
            .take(maxFacts)
    }

    private fun truncateInline(text: String, maxChars: Int): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxChars) compact else compact.take(maxChars - 3) + "..."
    }

    private fun buildOutputExcerpt(output: String, maxChars: Int = 220): String? {
        val cleaned = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("```") ||
                    it.startsWith("Diff:") ||
                    it.startsWith("Base64:") ||
                    it.startsWith("[NOTE:")
            }
            .take(8)
            .joinToString(" | ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isBlank()) return null
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars - 3) + "..."
    }

    private fun metadataString(metadata: Map<String, Any?>?, vararg keys: String): String? {
        if (metadata == null) return null
        return keys.firstNotNullOfOrNull { key ->
            (metadata[key] as? String)?.takeIf { it.isNotBlank() }?.trim()
        }
    }

    private fun metadataInt(metadata: Map<String, Any?>?, vararg keys: String): Int? {
        if (metadata == null) return null
        return keys.firstNotNullOfOrNull { key ->
            when (val value = metadata[key]) {
                is Int -> value
                is Long -> value.toInt()
                is Double -> value.toInt()
                is Float -> value.toInt()
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }
    }

    private fun metadataBoolean(metadata: Map<String, Any?>?, vararg keys: String): Boolean? {
        if (metadata == null) return null
        return keys.firstNotNullOfOrNull { key ->
            when (val value = metadata[key]) {
                is Boolean -> value
                is String -> value.toBooleanStrictOrNull()
                else -> null
            }
        }
    }

    private fun countPrefixedLines(text: String, prefix: String): Int {
        return text.lineSequence().count { it.startsWith(prefix) }
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

package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.config.ConfigKeys
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
    private val maxEntriesPerTask: Int = ConfigKeys.WORKING_MEMORY_MAX_FACTS.default
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

    /**
     * Build the rendered WORKING_MEMORY section for an agent turn prompt.
     *
     * @param taskId task whose entries should be rendered
     * @param maxTokens token budget for this section
     * @param skipExcerptForOriginIds set of subtask / tool-call ids that are ALREADY
     *   rendered in full in the RECENT_WORK section of the same prompt. Entries whose
     *   `originId` is in this set will have their `outputExcerpt` suppressed so the
     *   same head-of-output doesn't appear twice in the prompt (Bug: WORKING_MEMORY ↔
     *   RECENT_WORK duplication). The entry's meta line (key + value) is still shown —
     *   that one is useful as a compact index even when the full result is nearby.
     */
    fun buildWorkingMemorySection(
        taskId: String,
        maxTokens: Int,
        skipExcerptForOriginIds: Set<String> = emptySet()
    ): String {
        if (maxTokens <= 0) return ""
        val taskEntries = entriesByTask[taskId] ?: return ""
        if (taskEntries.isEmpty()) return ""
        return formatEntriesAsSection(taskEntries, maxTokens, skipExcerptForOriginIds)
    }

    private fun formatEntriesAsSection(
        entries: ConcurrentHashMap<String, WorkingMemoryEntry>,
        maxTokens: Int,
        skipExcerptForOriginIds: Set<String> = emptySet()
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
            // Sort chronologically (iteration ascending) within a key so the model sees
            // the narrative flow: "first I tried X → it failed → I tried Y → it worked".
            // Newest entries sit at the bottom of each group but carry the highest
            // `it#N` tag, so they're trivially locatable — and reconstructing WHY the
            // newest entry is correct (by reading older attempts above) is the signal
            // the agent most often loses when the order is reversed. `effectiveImportance`
            // and `lastAccessedAt` remain as tie-breakers for entries on the same iteration.
            val sortedEntries = grouped[key].orEmpty().sortedWith(
                compareBy<WorkingMemoryEntry> { it.iteration }
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
                val linePrefix = "- $tag "
                val fullLine = "$linePrefix${entry.value}"
                val fullLineTokens = ContextTokenEstimator.estimateTokens(fullLine)

                // If the full line fits, use it directly. Otherwise try to shrink
                // the entry's value with head+tail truncation so the agent at
                // least sees that the entry exists and the start/end of its
                // value — instead of the previous behaviour of silently dropping
                // the entry on the first overflow. Only break (giving up on
                // the rest of this key's entries) when even a minimally useful
                // truncated form would not fit.
                val (effectiveLine, effectiveLineTokens) = if (tokensUsed + fullLineTokens <= maxTokens) {
                    fullLine to fullLineTokens
                } else {
                    val fitted = fitLineWithHeadTailTruncation(
                        prefix = linePrefix,
                        value = entry.value,
                        tokenBudgetLeft = maxTokens - tokensUsed
                    )
                    if (fitted == null) break
                    fitted
                }

                sb.append(effectiveLine).append('\n')
                tokensUsed += effectiveLineTokens

                // Skip the outputExcerpt if the same tool-call is already rendered in
                // RECENT_WORK — otherwise the head of the output would appear twice in
                // the same prompt. `api_failure` entries are an exception: they're
                // pinned precisely because the model needs to SEE the failure reason
                // even when it's also in RECENT_WORK, and api_failure excerpts contain
                // the specific server error message that wouldn't be summarized into
                // plain `buildOutputExcerpt` output.
                val shouldSkipExcerpt = entry.originId != null &&
                    entry.key != "api_failure" &&
                    entry.originId in skipExcerptForOriginIds

                entry.outputExcerpt?.takeIf { it.isNotBlank() && !shouldSkipExcerpt }?.let { excerpt ->
                    val excerptPrefix = "  output: "
                    val fullExcerptLine = "$excerptPrefix$excerpt"
                    val fullExcerptTokens = ContextTokenEstimator.estimateTokens(fullExcerptLine)
                    if (tokensUsed + fullExcerptTokens <= maxTokens) {
                        sb.append(fullExcerptLine).append('\n')
                        tokensUsed += fullExcerptTokens
                    } else {
                        val fitted = fitLineWithHeadTailTruncation(
                            prefix = excerptPrefix,
                            value = excerpt,
                            tokenBudgetLeft = maxTokens - tokensUsed
                        )
                        if (fitted != null) {
                            sb.append(fitted.first).append('\n')
                            tokensUsed += fitted.second
                        }
                        // If even the truncated excerpt doesn't fit, drop only
                        // the excerpt — the parent entry's main line is already
                        // appended above so the agent still knows it exists.
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
     * Format: `[it#N ref#<originId>]`. The full originId is used (not a prefix)
     * so it matches 1:1 the `id:` shown in tool-result message headers and the
     * subtask id accepted by `memory(action="get_subtask_output")` — the model
     * can copy-paste the same identifier across all three places.
     * If no originId is set: `[it#N]`.
     */
    private fun buildEntryTag(entry: WorkingMemoryEntry): String {
        val ref = entry.originId
        return if (ref != null) "[it#${entry.iteration} ref#$ref]" else "[it#${entry.iteration}]"
    }

    /**
     * Try to fit `prefix + value` into the remaining token budget by head+tail
     * truncating `value`. Returns the rendered line + its token count if a
     * minimally useful truncation fits, or null when even that won't fit (in
     * which case the caller should drop the entry rather than render garbage).
     *
     * Replaces the previous "if it doesn't fit, silently drop" behaviour that
     * caused entries to disappear from WORKING_MEMORY without the agent ever
     * being told they existed.
     */
    private fun fitLineWithHeadTailTruncation(
        prefix: String,
        value: String,
        tokenBudgetLeft: Int
    ): Pair<String, Int>? {
        if (tokenBudgetLeft <= 0) return null
        // Conservative chars-per-token (real ratio is closer to 4 for English /
        // code; using 3 leaves headroom so we don't overshoot the budget after
        // re-estimating tokens on the truncated line).
        val charBudget = (tokenBudgetLeft * 3) - prefix.length
        if (charBudget < MIN_USEFUL_TRUNCATED_VALUE_CHARS) return null

        val truncatedValue = ToolResultCompression.headTailTruncate(value, charBudget)
        val candidate = "$prefix$truncatedValue"
        val candidateTokens = ContextTokenEstimator.estimateTokens(candidate)
        return if (candidateTokens <= tokenBudgetLeft) candidate to candidateTokens else null
    }

    companion object {
        /**
         * Below this many characters a head+tail truncated working-memory value
         * conveys nothing useful to the agent — better to drop the line entirely
         * than render `[head=10]...[tail=10]`.
         */
        private const val MIN_USEFUL_TRUNCATED_VALUE_CHARS = 60

        /** Default character budget for `buildOutputExcerpt` when caller doesn't override. */
        internal const val DEFAULT_OUTPUT_EXCERPT_CHARS = 220

        /**
         * Character budget for excerpts of pinnable data files (see [isPinnableDataFile]).
         * Chosen to be ~5× the default so that short-to-medium data files (task inputs,
         * small CSVs, markdown notes) land in WORKING_MEMORY almost in full, while still
         * capping the entry so that one giant file can't eat the whole section.
         */
        internal const val DATA_FILE_EXCERPT_CHARS = 1_200

        /**
         * File extensions treated as task-input data (not source code). These get a
         * higher importance and a larger excerpt budget in WORKING_MEMORY. Source code
         * extensions are intentionally excluded — they're already covered by RAG.
         */
        internal val PINNABLE_DATA_EXTENSIONS: Set<String> = setOf(
            "txt", "md", "markdown",
            "csv", "tsv",
            "json", "jsonl", "ndjson",
            "yaml", "yml",
            "toml", "ini", "cfg", "conf",
            "xml"
        )
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

        // Data files (.txt/.md/.csv/.json/.yaml/...) carry the actual task input that
        // the agent will need to reference across MANY turns — like Natan's notes in
        // the S04E04 filesystem task, where the agent was supposed to parse `rozmowy.txt`,
        // `transakcje.txt`, `ogłoszenia.txt` and remember what's in them. Code files
        // already have RAG + project_context as backup, so for them the default
        // importance=7 is fine.
        //
        // For data files we pin at importance=9 (one below the hardcoded api_failure
        // max of 10) so they survive longer when WORKING_MEMORY is tight, AND we
        // deliberately give them a LONGER rendered value via a bigger excerpt so the
        // model can see more of the actual content — not just a skeleton summary.
        val isDataFile = isPinnableDataFile(fileName)
        val importance = if (isDataFile) 9 else 7
        val excerptCharBudget = if (isDataFile) DATA_FILE_EXCERPT_CHARS else null
        val keyName = if (isDataFile) "data_files_read" else "files_read"

        return listOf(
            WorkingMemoryEntry(
                iteration = iteration,
                key = keyName,
                value = normalizeValue(value),
                outputExcerpt = buildOutputExcerpt(
                    output,
                    maxChars = excerptCharBudget ?: DEFAULT_OUTPUT_EXCERPT_CHARS
                ),
                importance = importance
            )
        )
    }

    /**
     * Returns true for file extensions that we want to pin in WORKING_MEMORY with
     * higher importance and a larger excerpt budget. These are files that typically
     * contain TASK INPUT DATA the agent has to reference repeatedly — not source code
     * (which the project RAG already indexes).
     */
    private fun isPinnableDataFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in PINNABLE_DATA_EXTENSIONS
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
        // Single consolidated `network` entry: metadata on the first line, structured
        // facts appended on the same entry instead of a separate `network_results` row.
        // Previously both entries carried the same `outputExcerpt`, triggering 2×
        // duplication against RECENT_WORK. See ADR / session analysis for Bug: WM
        // duplication.
        val metadataLine = buildString {
            append("http_request $method $host")
            status?.let { append(": status=$it") }
            length?.let { append(", size=$it") }
            if (binary == true) append(", binary")
            if (truncated == true) append(", truncated")
            savePath?.let { append(", saved=${it.substringAfterLast('/')}") }
        }
        val facts = extractStructuredFacts(output, maxFacts = 2)
        val fullValue = if (facts.isNotEmpty()) {
            "$metadataLine | facts: ${facts.joinToString(" | ")}"
        } else {
            metadataLine
        }
        val entries = mutableListOf(
            WorkingMemoryEntry(
                iteration,
                "network",
                normalizeValue(fullValue),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 6
            )
        )
        // Auto-record API failures so the agent doesn't keep retrying the same
        // rejected request. We pin these with importance=10 (never evicted) and
        // a dedicated key so they can't be merged into generic `network` noise.
        // Includes the response excerpt so the rejection reason is visible —
        // critical for verify-style endpoints that explain why the answer was wrong.
        if (status != null && status >= 400) {
            val failureValue = "FAILED $method $host: status=$status. " +
                "Do NOT retry the same payload — the server rejected it. " +
                "Read the response excerpt below for the rejection reason and adjust before retrying."
            entries += WorkingMemoryEntry(
                iteration,
                "api_failure",
                normalizeValue(failureValue),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 10
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
        // Single consolidated `code_execution` entry: metadata + facts on one row,
        // one outputExcerpt. The previous two-entry design (code_execution + analysis_results)
        // rendered the same outputExcerpt TWICE for the same subtaskId and triggered
        // redundant "run_code facts: ... | output: ..." blocks against RECENT_WORK.
        val metadataLine = buildString {
            append("run_code $language")
            exitCode?.let { append(": exit=$it") }
            if (timedOut == true) append(", timed_out")
            outputLength?.let { append(", output=$it chars") }
            if (truncated == true) append(", truncated")
        }
        val facts = extractStructuredFacts(output, maxFacts = 4)
        val fullValue = if (facts.isNotEmpty()) {
            "$metadataLine | facts: ${facts.joinToString(" | ")}"
        } else {
            metadataLine
        }
        return listOf(
            WorkingMemoryEntry(
                iteration,
                "code_execution",
                normalizeValue(fullValue),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 8  // matches former analysis_results importance — this is the HIGH-value entry
            )
        )
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
        // Single consolidated `command_execution` entry — mirror of run_code consolidation.
        val metadataLine = buildString {
            append("run_terminal_command ${truncateInline(command, 80)}")
            exitCode?.let { append(": exit=$it") }
            if (timedOut == true) append(", timed_out")
            outputLength?.let { append(", output=$it chars") }
        }
        val facts = extractStructuredFacts(output, maxFacts = 3)
        val fullValue = if (facts.isNotEmpty()) {
            "$metadataLine | facts: ${facts.joinToString(" | ")}"
        } else {
            metadataLine
        }
        return listOf(
            WorkingMemoryEntry(
                iteration,
                "command_execution",
                normalizeValue(fullValue),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 6
            )
        )
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
        // Single consolidated `subagent_work` entry — mirror of run_code consolidation.
        val metadataLine = buildString {
            append("invoke_subagent $name")
            depth?.let { append(": depth=$it") }
            childIterations?.let { append(", iterations=$it") }
            tokensOut?.let { append(", tokens_out=$it") }
        }
        val facts = extractStructuredFacts(output, maxFacts = 2)
        val fullValue = if (facts.isNotEmpty()) {
            "$metadataLine | facts: ${facts.joinToString(" | ")}"
        } else {
            metadataLine
        }
        return listOf(
            WorkingMemoryEntry(
                iteration,
                "subagent_work",
                normalizeValue(fullValue),
                outputExcerpt = buildOutputExcerpt(output),
                importance = 7
            )
        )
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

    /**
     * Render a compact excerpt of a tool output suitable for WORKING_MEMORY.
     *
     * Takes the first non-blank, non-noise lines of [output] and joins them with ` | `
     * separators. For small budgets (~220 chars) ~8 lines are enough; for pinned
     * data-file excerpts (~1200 chars) we allow up to 40 lines so a short `.txt` /
     * `.csv` task-input file can land in WORKING_MEMORY almost in full.
     */
    private fun buildOutputExcerpt(output: String, maxChars: Int = DEFAULT_OUTPUT_EXCERPT_CHARS): String? {
        // Scale maxLines with the char budget so data-file excerpts can carry more
        // lines of context — important for task-input files (notes, transaction lists)
        // which have many short lines.
        val maxLines = when {
            maxChars >= 1_000 -> 40
            maxChars >= 500 -> 20
            else -> 8
        }
        val cleaned = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("```") ||
                    it.startsWith("Diff:") ||
                    it.startsWith("Base64:") ||
                    it.startsWith("[NOTE:")
            }
            .take(maxLines)
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

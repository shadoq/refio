package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.models.context.ExecutedStepDTO
import pl.jclab.refio.core.models.context.ProjectContextDTO
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("ContextFormatter")

private const val CONVERSATION_SUMMARY_METADATA_TYPE = ConversationContextBuilder.CONVERSATION_SUMMARY_METADATA_TYPE

// CONVERSATION_HISTORY limits
// We no longer hard-cap by message count (see buildCompressedConversationSection for
// the rationale — Bug 2B). The per-message truncation budget is derived from a soft
// cap so each message gets a reasonable slice, but the loop itself runs until the
// token budget is exhausted, not until a message counter hits a magic number.
private const val CONVERSATION_MIN_PER_MESSAGE_TOKENS = 128
private const val CONVERSATION_SOFT_MAX_MESSAGES_FOR_PER_MESSAGE_CAP = 60

/**
 * Configuration for RECENT_WORK section generation.
 *
 * `fullDataLimit` is read from [ConfigKeys.RECENT_WORK_FULL_DATA_LIMIT] for backwards
 * compatibility but no longer drives the FULL/DETAILED/SUMMARY tier split — the adaptive
 * builder tries FULL for every step and only compresses older entries when the token
 * budget runs out. The field stays so existing `config.yaml` files keep loading cleanly.
 */
data class RecentWorkConfig(
    val fullDataLimit: Int = 2,
    val detailedMaxLength: Int = 800,
    val summaryMaxLength: Int = 300,
    val includeMetadata: Boolean = true
)

/**
 * Formatting methods extracted from ContextService.
 * Responsible for building text sections from ProjectContextDTO for LLM prompts.
 */
class ContextFormatter(
    private val configService: ConfigService
) {

    fun buildCompactProjectOverview(context: ProjectContextDTO): String {
        val projectName = context.metaData.projectName
        val lang = context.summary.mainLanguage
        val type = context.projectType
        val files = context.structure.totalFiles
        val tech = context.technologies.take(5).joinToString(", ")

        // Jedna linia podsumowania
        val summary = when {
            type.contains("Game") || tech.contains("Canvas") ->
                "Game/graphics project with $files files"

            type.contains("API") || type.contains("Backend") ->
                "Backend service with $files files"

            type.contains("Frontend") || type.contains("React") ->
                "Frontend application with $files files"

            else -> "$type with $files files"
        }

        // Code analysis summary (inline)
        val codeLines = mutableListOf<String>()
        context.codeAnalysis.kotlin.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("Kotlin: $f files, ${m["classes"]} classes, ${m["functions"]} functions")
        }
        context.codeAnalysis.java.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("Java: $f files, ${m["classes"]} classes")
        }
        context.codeAnalysis.python.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("Python: $f files, ${m["classes"]} classes, ${m["functions"]} functions")
        }
        context.codeAnalysis.javascript.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("JS: $f files, ${m["classes"]} classes, ${m["functions"]} functions")
        }
        context.codeAnalysis.typescript.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("TS: $f files, ${m["classes"]} classes, ${m["functions"]} functions")
        }
        context.codeAnalysis.html.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) codeLines.add("HTML: $f files")
        }
        context.codeAnalysis.css.let { m ->
            val f = m["files"] as? Int ?: 0
            if (f > 0) {
                val classesCount = m["classes_count"] as? Int ?: 0
                val extra = if (classesCount > 0) ", $classesCount selectors" else ""
                codeLines.add("CSS: $f files$extra")
            }
        }
        val codeAnalysisSummary = if (codeLines.isNotEmpty()) "\nCode: ${codeLines.joinToString("; ")}" else ""

        // Architecture notes
        val archNotes = context.summary.architectureNotes?.let { "\nArchitecture: $it" } ?: ""

        // File types summary
        val fileTypesSummary = context.structure.fileTypes.entries
            .sortedByDescending { it.value }
            .take(8)
            .joinToString(", ") { ".${it.key}(${it.value})" }
        val fileTypesLine = if (fileTypesSummary.isNotBlank()) "\nFile types: $fileTypesSummary" else ""

        val frameworkSection = buildFrameworkAnalysisSection(context)

        return """
            |<PROJECT_CONTEXT>
            |$projectName: $summary
            |Stack: $lang | $tech
            |Complexity: ${context.summary.complexity}$codeAnalysisSummary$archNotes$fileTypesLine
            |</PROJECT_CONTEXT>
            |$frameworkSection
        """.trimMargin().trim()
    }

    fun buildProjectInstructionsSection(context: ProjectContextDTO): String {
        return "<PROJECT_INSTRUCTIONS>\n${context.projectInstructions}\n</PROJECT_INSTRUCTIONS>"
    }

    fun buildDependenciesSection(context: ProjectContextDTO): String? {
        val depLines = mutableListOf<String>()

        fun addDeps(label: String, deps: List<String>) {
            if (deps.isNotEmpty()) {
                val shown = deps.take(15).joinToString(", ")
                val more = if (deps.size > 15) " (+${deps.size - 15} more)" else ""
                depLines.add("$label: $shown$more")
            }
        }

        addDeps("Kotlin/Java", (context.dependencies.kotlin + context.dependencies.java).distinct())
        addDeps("Python", context.dependencies.python)
        addDeps("JavaScript", context.dependencies.javascript)
        addDeps("TypeScript", context.dependencies.typescript.filter { it !in context.dependencies.javascript })
        addDeps("C/C++", context.dependencies.cpp)

        if (depLines.isEmpty()) return null

        if (context.dependencies.packageManagers.isNotEmpty()) {
            depLines.add("Package managers: ${context.dependencies.packageManagers.joinToString(", ")}")
        }

        return "<PROJECT_DEPENDENCIES>\n${depLines.joinToString("\n")}\n</PROJECT_DEPENDENCIES>"
    }

    fun buildKeyComponentsSection(context: ProjectContextDTO): String {
        return """
            |<KEY_COMPONENTS>
            |${context.keyComponents.joinToString("\n") { "- $it" }}
            |</KEY_COMPONENTS>
        """.trimMargin()
    }

    /**
     * Build user requirements section.
     * Displays technologies, services, and notes extracted from task description.
     */
    fun buildUserRequirementsSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<USER_REQUIREMENTS>")

        @Suppress("UNCHECKED_CAST")
        val technologies = context.userRequirements["technologies"] as? List<String>
        if (!technologies.isNullOrEmpty()) {
            parts.add("Required Technologies: ${technologies.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val services = context.userRequirements["services"] as? List<String>
        if (!services.isNullOrEmpty()) {
            parts.add("Required Services: ${services.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val notes = context.userRequirements["notes"] as? List<String>
        if (!notes.isNullOrEmpty()) {
            parts.add("Additional Notes:")
            notes.take(5).forEach { note -> parts.add("- $note") }
        }

        parts.add("</USER_REQUIREMENTS>")
        return parts.joinToString("\n")
    }

    fun buildCurrentTaskSection(context: ProjectContextDTO): String {
        val task = context.currentTask ?: return "<CURRENT_TASK>\nNo task information available\n</CURRENT_TASK>"

        val statusCounts = context.subtasks.groupingBy { it.status }.eachCount()
        val completedCount = statusCounts["SUCCESS"] ?: 0
        val failedCount = statusCounts["FAILED"] ?: 0
        val runningCount = statusCounts["RUNNING"] ?: 0
        val pendingCount = (statusCounts["PENDING"] ?: 0) + (statusCounts["PLANNED"] ?: 0)

        val statusSummary = buildString {
            if (completedCount > 0) append("$completedCount completed")
            if (runningCount > 0) {
                if (isNotEmpty()) append(", ")
                append("$runningCount running")
            }
            if (pendingCount > 0) {
                if (isNotEmpty()) append(", ")
                append("$pendingCount pending")
            }
            if (failedCount > 0) {
                if (isNotEmpty()) append(", ")
                append("$failedCount failed")
            }
        }

        val statusTag = when (task.status) {
            "SUCCESS" -> " [completed]"
            "RUNNING" -> " [in progress]"
            else -> ""
        }
        val subtasksLine = if (context.subtasks.isNotEmpty()) {
            "\nSubtasks: ${context.subtasks.size} total ($statusSummary)"
        } else ""

        return "<CURRENT_TASK>\n${task.description}$statusTag$subtasksLine\n</CURRENT_TASK>"
    }

    /**
     * Build subtasks status section.
     * Shows ALL subtasks with their current status, not just completed ones.
     * Format: "description - STATUS" sorted by execution order.
     */
    fun buildSubtasksStatusSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<SUBTASKS_STATUS>")

        // Sort by execution order (ascending)
        val sortedSubtasks = context.subtasks.sortedBy { it.order }

        // Build formatted lines: "N. description - STATUS"
        val formattedLines = sortedSubtasks.mapIndexed { index, subtask ->
            val statusSymbol = when (subtask.status) {
                "SUCCESS" -> "SUCCESS"
                "FAILED" -> "ERROR"
                "RUNNING" -> "RUNNING"
                "PENDING", "PLANNED", "NEW" -> "PENDING"
                else -> subtask.status
            }
            "${index + 1}. ${subtask.description} - $statusSymbol"
        }

        parts.addAll(formattedLines)
        parts.add("</SUBTASKS_STATUS>")
        return parts.joinToString("\n")
    }

    /**
     * Build compressed conversation section.
     * More aggressive compression for smaller context.
     */
    fun buildCompressedConversationSection(context: ProjectContextDTO, budgetTokens: Int): String {
        if (budgetTokens <= 0) return ""

        val history = context.conversationHistory
        if (history.isEmpty()) return ""

        val parts = mutableListOf<String>()
        parts.add("<CONVERSATION_HISTORY>")

        var tokensUsed = ContextTokenEstimator.estimateTokens(parts.joinToString("\n"))

        fun appendLine(line: String): Boolean {
            val tokens = ContextTokenEstimator.estimateTokens(line)
            if (tokensUsed + tokens > budgetTokens) return false
            parts.add(line)
            tokensUsed += tokens
            return true
        }

        // Budget-governed message inclusion.
        //
        // Bug 2B (observed in the filesystem AGENT session): the previous implementation
        // capped the number of messages by a tier-based maxMessages constant (25..100)
        // *even when the token budget could fit hundreds of short messages*. With a 58k
        // CONVERSATION budget the loop stopped at 25 messages × ~75 tokens ≈ 1875 tokens
        // used and ~56k tokens of budget wasted — the model saw almost no history and
        // kept re-deciding the same thing turn after turn.
        //
        // New behaviour: iterate most-recent-first until the token budget is exhausted;
        // no hard message-count ceiling. Per-message truncation still applies so a single
        // very long message cannot eat the entire budget — each message is capped at
        // `perMessageTokens` which is sized so that the section can hold at least
        // `CONVERSATION_SOFT_MAX_MESSAGES` messages even under small budgets.
        val softMaxMessagesForCap = CONVERSATION_SOFT_MAX_MESSAGES_FOR_PER_MESSAGE_CAP
        val perMessageTokens = maxOf(CONVERSATION_MIN_PER_MESSAGE_TOKENS, budgetTokens / softMaxMessagesForCap)

        val firstMessage = history.firstOrNull()
        val summaryMessage = firstMessage?.takeIf {
            it.metadata?.get("type") == CONVERSATION_SUMMARY_METADATA_TYPE
        }

        if (summaryMessage != null) {
            val summaryBudget = minOf((budgetTokens * 0.5).toInt(), budgetTokens)
            val summaryContent = ContextTokenEstimator.truncateToTokens(summaryMessage.content.trim(), summaryBudget)
            appendLine("=== SUMMARY ===")
            appendLine(summaryContent)
            appendLine("")
        }

        val remaining = if (summaryMessage != null) history.drop(1) else history
        // Walk newest-first so that when we hit the budget ceiling we keep the most
        // recent context and drop the oldest, then reverse the kept slice back into
        // chronological order for the model.
        val rendered = ArrayDeque<String>()
        for (msg in remaining.asReversed()) {
            val content = ContextTokenEstimator.truncateToTokens(msg.content.trim(), perMessageTokens)
            val line = "[${msg.role.uppercase()}]\n${content.trim()}\n"
            val lineTokens = ContextTokenEstimator.estimateTokens(line)
            if (tokensUsed + lineTokens > budgetTokens) break
            rendered.addFirst(line)
            tokensUsed += lineTokens
        }
        parts.addAll(rendered)

        parts.add("</CONVERSATION_HISTORY>")
        return parts.joinToString("\n")
    }

    /**
     * Build recent work section.
     * Splits executed steps into: summary for older steps, full data for latest N.
     * Uses RecentWorkConfig for configuration.
     *
     * @param context Project context DTO
     * @param budgetTokens Token budget for this section
     * @param config Configuration for recent work generation
     * @return Formatted recent work section
     */
    fun buildRecentWorkSection(
        context: ProjectContextDTO,
        budgetTokens: Int,
        config: RecentWorkConfig = buildRecentWorkConfig()
    ): String {
        if (budgetTokens <= 0) return ""

        val executedSteps = context.executedSteps
        if (executedSteps.isEmpty() && context.completedFiles.isEmpty()) {
            return ""
        }

        val parts = mutableListOf<String>()
        parts.add("<RECENT_WORK>")

        val compressionConfig = ToolResultCompressionConfig(
            detailedMaxChars = config.detailedMaxLength,
            summaryMaxChars = config.summaryMaxLength
        )

        val entries = buildAdaptiveRecentWork(
            steps = executedSteps,
            budgetTokens = budgetTokens,
            config = config,
            compressionConfig = compressionConfig
        )
        parts.addAll(entries)

        parts.add("</RECENT_WORK>")
        return parts.joinToString("\n")
    }

    fun buildRecentWorkConfig(): RecentWorkConfig {
        val summaryMax = configService.getTyped(ConfigKeys.RECENT_WORK_SUMMARY_MAX_LENGTH)
        val detailedMax = (summaryMax * 5).coerceAtLeast(1024)

        return RecentWorkConfig(
            fullDataLimit = configService.getTyped(ConfigKeys.RECENT_WORK_FULL_DATA_LIMIT),
            detailedMaxLength = detailedMax,
            summaryMaxLength = summaryMax,
            includeMetadata = true
        )
    }

    /**
     * Build user-provided context section (from @ mentions).
     */
    fun buildUserContextSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<USER_PROVIDED_CONTEXT>")
        parts.add("The user has provided the following additional context:")
        parts.add("")

        context.userContextRefs.forEach { ref ->
            val header = when {
                ref.providerId == "file" -> "File: ${ref.path}"
                ref.providerId == "selection" -> "Selection: ${ref.displayName}"
                ref.providerId != null -> "${ref.providerId}: ${ref.displayName}"
                else -> ref.displayName
            }

            parts.add("--- $header ---")
            parts.add(ref.content)
            parts.add("")
        }

        parts.add("</USER_PROVIDED_CONTEXT>")
        return parts.joinToString("\n")
    }

    fun buildMcpResourcesSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<MCP_RESOURCES>")
        parts.add("External context from configured MCP servers (read-only in PLAN).")
        parts.add("")

        context.mcpResources.take(20).forEach { res ->
            parts.add("--- ${res.serverId} :: ${res.name} ---")
            parts.add("URI: ${res.uri}")
            res.description?.let { parts.add(it) }
            parts.add("")
        }

        parts.add("</MCP_RESOURCES>")
        return parts.joinToString("\n")
    }

    fun buildFrameworkAnalysisSection(context: ProjectContextDTO): String {
        val fa = context.frameworkAnalysis ?: return ""
        if (fa.frameworks.isEmpty()) return ""

        val parts = mutableListOf<String>()
        parts.add("<FRAMEWORK_ANALYSIS>")

        // Detected frameworks with conventions
        val detected = fa.frameworks.joinToString(", ") { fw ->
            val conventionSuffix = fa.conventions
                .firstOrNull { it.startsWith(fw.name) }
                ?.substringAfter(": ", "")
                ?.let { " ($it)" } ?: ""
            val versionSuffix = fw.version?.let { " $it" } ?: ""
            "${fw.name}${versionSuffix}${conventionSuffix}"
        }
        parts.add("Detected: $detected")

        // Layers with example files
        if (fa.layers.isNotEmpty()) {
            parts.add("Layers:")
            for (layer in fa.layers) {
                val examples = layer.exampleFiles.take(3).joinToString(", ") {
                    it.substringAfterLast("/")
                }
                parts.add("- ${layer.name}: $examples")
            }
        }

        // Endpoints (if any)
        if (fa.endpoints.isNotEmpty()) {
            parts.add("Endpoints: ${fa.endpoints.take(5).joinToString(", ")}")
        }

        // Config files (if any)
        if (fa.configFiles.isNotEmpty()) {
            parts.add("Config: ${fa.configFiles.take(5).joinToString(", ")}")
        }

        parts.add("</FRAMEWORK_ANALYSIS>")
        return parts.joinToString("\n")
    }

    /**
     * Format tool output for recent work section with compression level.
     */
    fun formatToolOutput(
        step: ExecutedStepDTO,
        level: CompressionLevel,
        config: RecentWorkConfig,
        compressionConfig: ToolResultCompressionConfig
    ): String {
        val fileAttr = buildToolFileAttribute(step, config.includeMetadata)
        // Directory listings are grouped per directory BEFORE the budget-driven compression.
        // The raw listing lives in the subtask row, so without this the same 400 paths were
        // re-sent verbatim on every iteration (~15,5K tokens = 40 % of the context budget in the
        // observed session) while the conversation held only a compressed view of them. Grouping
        // is lossless for the decision the agent makes here ("which directory do I open next").
        val rawResult = if (step.tool in LISTING_TOOLS) {
            // The pointer is added here rather than left to ToolResultCompression: that helper
            // compares against the text it was GIVEN, which is already the grouped listing, so it
            // would never notice that names were dropped. The full listing stays in the subtask row.
            FileListingCompression.compress(step.result).let { grouped ->
                if (grouped.length < step.result.length) {
                    grouped + "\n[listing grouped by directory - full list: " +
                        "memory(action=\"get_subtask_output\", subtask_id=\"${step.subtaskId}\")]"
                } else {
                    grouped
                }
            }
        } else {
            step.result
        }
        val content = ToolResultCompression.compress(rawResult, step.summary, level, compressionConfig, step.subtaskId)
        val tagSuffix = if (fileAttr.isNotBlank()) " $fileAttr" else ""

        // Add compression level attribute (only show if not FULL)
        val compressionAttr = if (level != CompressionLevel.FULL) {
            " compressed=\"${level.name.lowercase()}\""
        } else {
            ""
        }

        // Mark failed steps so the agent can see in RECENT_WORK that a prior attempt
        // failed — otherwise it re-runs the same approach under the impression it
        // has not tried yet. Successful steps intentionally omit the attribute to
        // keep the tag short (success is the default).
        val statusAttr = if (!step.success) " status=\"failed\"" else ""

        // Add metadata: timestamp, params (truncated), summary
        val timestamp = step.timestamp.toString().take(19)  // ISO format, truncate milliseconds
        val paramsAttr = formatToolParamsAttribute(step.parameters)
        val summaryAttr = if (!step.summary.isNullOrBlank() && step.summary.length <= 100) {
            " summary=\"${step.summary.replace("\"", "'")}\""
        } else {
            ""
        }
        val subtaskIdAttr = " subtaskId=\"${step.subtaskId.replace("\"", "'")}\""

        return buildString {
            append("<tool name=\"")
            append(step.tool)
            append("\"")
            append(tagSuffix)
            append(compressionAttr)
            append(statusAttr)
            append(subtaskIdAttr)
            append(" timestamp=\"")
            append(timestamp)
            append("\"")
            if (paramsAttr.isNotBlank()) append(paramsAttr)
            if (summaryAttr.isNotBlank()) append(summaryAttr)
            append(">\n")
            append(wrapInMarkdownCodeBlock(content.ifBlank { "-" }))
            append("\n</tool>")
        }
    }

    /**
     * Intelligently truncate text, with special handling for code blocks.
     * Detects markdown code blocks and truncates them with summary instead of raw cut.
     */
    fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) {
            return text
        }

        // Detect code blocks (``` ... ```)
        val codeBlockRegex = Regex("```[\\w]*\\n([\\s\\S]*?)```", RegexOption.MULTILINE)
        val hasCodeBlocks = codeBlockRegex.containsMatchIn(text)

        if (hasCodeBlocks) {
            val parts = mutableListOf<String>()
            var lastIndex = 0
            var totalLength = 0

            codeBlockRegex.findAll(text).forEach { match ->
                // Add text before code block
                val beforeCode = text.substring(lastIndex, match.range.first)
                if (beforeCode.isNotBlank()) {
                    val available = maxLength - totalLength
                    if (available > 0) {
                        val truncated = if (beforeCode.length > available) {
                            beforeCode.take(available) + "..."
                        } else {
                            beforeCode
                        }
                        parts.add(truncated)
                        totalLength += truncated.length
                    }
                }

                // Process code block
                val codeBlock = match.value
                val codeContent = match.groupValues[1]
                val lines = codeContent.lines()
                val available = maxLength - totalLength

                if (available > 50) {  // Minimum space for code preview
                    if (lines.size <= 10) {
                        // Short code block - include it fully if space allows
                        if (codeBlock.length <= available) {
                            parts.add(codeBlock)
                            totalLength += codeBlock.length
                        } else {
                            val previewLines = lines.take(5).joinToString("\n")
                            val preview = "```\n$previewLines\n... (${lines.size - 5} more lines)\n```"
                            parts.add(preview)
                            totalLength += preview.length
                        }
                    } else {
                        // Large code block - show summary
                        val previewLines = lines.take(5).joinToString("\n")
                        val language = match.value.removePrefix("```").substringBefore("\n")
                        val preview =
                            "```$language\n$previewLines\n... (${lines.size - 5} more lines, ${codeContent.length} chars total)\n```"
                        parts.add(preview)
                        totalLength += preview.length
                    }
                } else {
                    // Not enough space - add summary only
                    parts.add("[Code block: ${lines.size} lines, ${codeContent.length} chars]")
                    totalLength += 50
                }

                lastIndex = match.range.last + 1
            }

            // Add remaining text after last code block
            if (lastIndex < text.length) {
                val remaining = text.substring(lastIndex)
                val available = maxLength - totalLength
                if (available > 0 && remaining.isNotBlank()) {
                    val truncated = if (remaining.length > available) {
                        remaining.take(available) + "..."
                    } else {
                        remaining
                    }
                    parts.add(truncated)
                }
            }

            return parts.joinToString("")
        }

        // No code blocks - simple truncation
        return "${text.take(maxLength)}..."
    }

    fun wrapInMarkdownCodeBlock(content: String): String {
        val fenceLength = maxOf(3, longestBacktickRun(content) + 1)
        val fence = "`".repeat(fenceLength)
        return buildString {
            append(fence)
            append("text\n")
            append(content)
            append("\n")
            append(fence)
        }
    }

    fun formatParameters(params: Map<String, Any>, gson: com.google.gson.Gson): String {
        if (params.isEmpty()) return "-"
        val json = gson.toJson(params)
        return if (json.length > 1024) "${json.take(1024)}..." else json
    }

    /**
     * Detect programming language from file path.
     */
    fun detectLanguage(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts", "tsx" -> "TypeScript"
            "jsx" -> "React"
            "html", "htm" -> "HTML"
            "css", "scss", "sass", "less" -> "CSS"
            "md", "markdown" -> "Markdown"
            "json" -> "JSON"
            "xml" -> "XML"
            "yaml", "yml" -> "YAML"
            "sql" -> "SQL"
            "sh", "bash" -> "Shell"
            "rs" -> "Rust"
            "go" -> "Go"
            "cpp", "cc", "cxx" -> "C++"
            "c", "h" -> "C"
            "cs" -> "C#"
            "rb" -> "Ruby"
            "php" -> "PHP"
            "swift" -> "Swift"
            else -> ext.uppercase().takeIf { it.isNotEmpty() } ?: "Unknown"
        }
    }

    fun buildTypeScriptAnalysisSection(context: ProjectContextDTO): String? {
        val ts = context.codeAnalysis.typescript
        val filesCount = ts["files"] as? Int ?: 0
        if (filesCount == 0) return null

        val parts = mutableListOf<String>()
        parts.add("<TYPESCRIPT_ANALYSIS>")
        parts.add("Files: $filesCount")

        val interfacesCount = ts["interfaces"] as? Int ?: 0
        val typesCount = ts["types"] as? Int ?: 0
        val classesCount = ts["classes"] as? Int ?: 0
        val functionsCount = ts["functions"] as? Int ?: 0

        if (interfacesCount > 0 || typesCount > 0) {
            parts.add("Interfaces: $interfacesCount, Types: $typesCount")
        }
        if (classesCount > 0) parts.add("Classes: $classesCount")
        if (functionsCount > 0) parts.add("Functions: $functionsCount")

        @Suppress("UNCHECKED_CAST")
        val interfaceNames = ts["interface_names"] as? List<String>
        if (!interfaceNames.isNullOrEmpty()) {
            parts.add("Key Interfaces: ${interfaceNames.take(10).joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val typeNames = ts["type_names"] as? List<String>
        if (!typeNames.isNullOrEmpty()) {
            parts.add("Key Types: ${typeNames.take(10).joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val decorators = ts["decorators"] as? List<String>
        if (!decorators.isNullOrEmpty()) {
            parts.add("Decorators: ${decorators.take(10).joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val reactComponents = ts["react_components"] as? Int ?: 0
        @Suppress("UNCHECKED_CAST")
        val reactHooks = ts["react_hooks"] as? Int ?: 0
        if (reactComponents > 0 || reactHooks > 0) {
            parts.add("React: $reactComponents components, $reactHooks hooks")
        }

        parts.add("</TYPESCRIPT_ANALYSIS>")
        return parts.joinToString("\n")
    }

    fun buildHtmlAnalysisSection(context: ProjectContextDTO): String? {
        val html = context.codeAnalysis.html
        val filesCount = html["files"] as? Int ?: 0
        if (filesCount == 0) return null

        val parts = mutableListOf<String>()
        parts.add("<HTML_ANALYSIS>")
        parts.add("HTML Files: $filesCount")

        @Suppress("UNCHECKED_CAST")
        val pages = html["pages"] as? List<Map<String, Any>>
        if (!pages.isNullOrEmpty()) {
            parts.add("Pages:")
            val displayPages = if (pages.size > 10) pages.take(10) else pages
            displayPages.forEach { page ->
                val file = page["file"] as? String ?: "unknown"
                val title = page["title"] as? String
                val hasCanvas = page["has_canvas"] as? Boolean ?: false
                val hasWebgl = page["has_webgl"] as? Boolean ?: false
                val formsCount = page["forms_count"] as? Int

                val pageInfo = buildString {
                    append("  - $file")
                    if (title != null) append(" (\"$title\")")
                    if (hasCanvas) append(" [Canvas]")
                    if (hasWebgl) append(" [WebGL]")
                    if (formsCount != null && formsCount > 0) append(" [Forms: $formsCount]")
                }
                parts.add(pageInfo)
            }
            if (pages.size > 10) {
                parts.add("  ... and ${pages.size - 10} more pages")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val canvasGames = html["canvas_games"] as? List<String>
        if (!canvasGames.isNullOrEmpty()) {
            parts.add("Canvas Games Detected: ${canvasGames.take(5).joinToString(", ")}")
        }

        parts.add("</HTML_ANALYSIS>")
        return parts.joinToString("\n")
    }

    fun buildCssAnalysisSection(context: ProjectContextDTO): String? {
        val css = context.codeAnalysis.css
        val filesCount = css["files"] as? Int ?: 0
        if (filesCount == 0) return null

        val parts = mutableListOf<String>()
        parts.add("<CSS_ANALYSIS>")
        parts.add("CSS Files: $filesCount")

        val classesCount = css["classes_count"] as? Int ?: 0
        val idsCount = css["ids_count"] as? Int ?: 0
        if (classesCount > 0 || idsCount > 0) {
            parts.add("Selectors: $classesCount classes, $idsCount IDs")
        }

        @Suppress("UNCHECKED_CAST")
        val variables = css["variables"] as? List<String>
        if (!variables.isNullOrEmpty()) {
            parts.add("CSS Variables: ${variables.take(10).joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val animations = css["animations"] as? List<String>
        if (!animations.isNullOrEmpty()) {
            parts.add("Animations: ${animations.joinToString(", ")}")
        }

        @Suppress("UNCHECKED_CAST")
        val mediaQueries = css["media_queries"] as? List<String>
        if (!mediaQueries.isNullOrEmpty()) {
            parts.add("Media Queries: ${mediaQueries.take(5).joinToString(", ")}")
        }

        parts.add("</CSS_ANALYSIS>")
        return parts.joinToString("\n")
    }

    // ===========================
    // Private helper methods
    // ===========================

    private fun buildAdaptiveRecentWork(
        steps: List<ExecutedStepDTO>,
        budgetTokens: Int,
        config: RecentWorkConfig,
        compressionConfig: ToolResultCompressionConfig
    ): List<String> {
        if (steps.isEmpty()) return emptyList()

        val entries = mutableListOf<Pair<Int, String>>()  // (original index, entry)
        val reversedSteps = steps.asReversed()

        // Strict budget handling: most recent tools first, with graceful fallback.
        // Steps that don't fit at any compression level are dropped; a one-line
        // summary marker at the end (chat-history style) lets the agent know
        // older tool calls existed but were compressed away. Computed after the
        // loop based on what actually ended up in `entries`.
        var tokensUsed = 0

        // Budget-driven compression: try FULL for every step, let the fallback loop
        // demote older entries to DETAILED/SUMMARY only when tokens run out. If the
        // section budget has headroom, the agent sees complete tool output — which
        // for write tools means the full diff, and for reads means the full file
        // content. Preemptive tier-based compression (the prior behavior) wasted
        // headroom on sessions with a handful of steps and plenty of budget.
        val candidates = reversedSteps.mapIndexed { index, step ->
            Triple(index, step, CompressionLevel.FULL)
        }

        // Always keep the latest executed step uncompressed (FULL) for maximum fidelity.
        // This is intentionally allowed to exceed the regular section budget.
        val latest = candidates.firstOrNull()
        if (latest != null) {
            val (latestIndex, latestStep, _) = latest
            val latestEntry = formatToolOutput(latestStep, CompressionLevel.FULL, config, compressionConfig)
            entries.add(Pair(latestIndex, latestEntry))
            tokensUsed += ContextTokenEstimator.estimateTokens(latestEntry)
        }

        for ((index, step, baseLevel) in candidates.drop(1)) {
            val fallbackLevels = compressionFallbacks(baseLevel)
            var chosen: String? = null

            for (level in fallbackLevels) {
                val entry = formatToolOutput(step, level, config, compressionConfig)
                val entryTokens = ContextTokenEstimator.estimateTokens(entry)
                if (tokensUsed + entryTokens <= budgetTokens) {
                    tokensUsed += entryTokens
                    chosen = entry
                    break
                }
            }

            if (chosen != null) {
                entries.add(Pair(index, chosen))
            }

            if (tokensUsed >= budgetTokens) break
        }

        // Compute final dropped count by comparing what we actually emitted against
        // the total pool. This is more reliable than tracking per-iteration because
        // the `break` above can exit mid-loop without touching every candidate.
        val emittedIndices = entries.map { it.first }.toSet()
        val allCandidates = candidates.map { it.first }
        val trulyDropped = allCandidates.count { it !in emittedIndices }
        if (trulyDropped > 0) {
            val failedDropped = candidates
                .filter { (i, _, _) -> i !in emittedIndices }
                .count { (_, step, _) -> !step.success }
            val failedNote = if (failedDropped > 0) " ($failedDropped failed)" else ""
            // Use the smallest possible index so the marker sorts last when we
            // reverse by index below (older = end of list).
            entries.add(
                Pair(
                    Int.MIN_VALUE,
                    "<!-- $trulyDropped older tool step(s) omitted due to budget$failedNote -->"
                )
            )
        }

        // Sort by original index (descending = most recent first) and extract entries.
        // The omitted-marker uses Int.MIN_VALUE so it ends up at the bottom (oldest).
        return entries
            .sortedByDescending { it.first }
            .map { it.second }
    }

    private fun compressionFallbacks(baseLevel: CompressionLevel): List<CompressionLevel> = when (baseLevel) {
        CompressionLevel.FULL -> listOf(
            CompressionLevel.FULL,
            CompressionLevel.DETAILED,
            CompressionLevel.SUMMARY
        )

        CompressionLevel.DETAILED -> listOf(
            CompressionLevel.DETAILED,
            CompressionLevel.SUMMARY
        )

        CompressionLevel.SUMMARY -> listOf(
            CompressionLevel.SUMMARY
        )
    }

    private fun longestBacktickRun(text: String): Int {
        var longest = 0
        var current = 0
        for (char in text) {
            if (char == '`') {
                current += 1
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }

    private fun formatToolParamsAttribute(
        parameters: Map<String, Any>,
        maxParams: Int = 5,
        maxValueLength: Int = 80,
        maxAttributeLength: Int = 320
    ): String {
        if (parameters.isEmpty()) return ""

        val visibleEntries = parameters.entries.take(maxParams)
        val paramsStr = visibleEntries.joinToString(",") { (key, value) ->
            val safeKey = sanitizeXmlAttributeValue(key)
            val safeValue = sanitizeXmlAttributeValue(truncateValue(value.toString(), maxValueLength))
            "$safeKey=$safeValue"
        }

        val withCountSuffix = if (parameters.size > maxParams) {
            "$paramsStr,+${parameters.size - maxParams}_more"
        } else {
            paramsStr
        }

        val trimmed = truncateValue(withCountSuffix, maxAttributeLength)
        return if (trimmed.isBlank()) "" else " params=\"$trimmed\""
    }

    private fun sanitizeXmlAttributeValue(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "'")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
    }

    private fun truncateValue(value: String, maxLength: Int): String {
        if (maxLength <= 0) return ""
        return if (value.length > maxLength) {
            "${value.take(maxLength)}..."
        } else {
            value
        }
    }

    private fun buildToolFileAttribute(step: ExecutedStepDTO, includeMetadata: Boolean): String {
        val filePath = step.file ?: return ""
        if (!includeMetadata) return "file=\"$filePath\""

        val path = Path.of(filePath)
        val size = try {
            val bytes = java.nio.file.Files.size(path)
            when {
                bytes < 1024 -> "${bytes}B"
                bytes < 1024 * 1024 -> "${bytes / 1024}KB"
                else -> "${bytes / (1024 * 1024)}MB"
            }
        } catch (e: Exception) {
            "?"
        }
        val ext = path.fileName.toString().substringAfterLast('.', "").takeIf { it.isNotEmpty() } ?: "txt"
        return "file=\"$filePath\" size=\"$size\" type=\"$ext\""
    }

    /**
     * Estimate code complexity based on lines and nesting level.
     */
    private fun estimateComplexity(content: String): String {
        val lines = content.lines().size
        val nestingLevel = content.count { it == '{' || it == '(' }

        return when {
            lines < 20 && nestingLevel < 5 -> "low"
            lines < 100 && nestingLevel < 20 -> "medium"
            else -> "high"
        }
    }

    private companion object {
        /** Tools whose output is a path listing - grouped per directory in RECENT_WORK. */
        val LISTING_TOOLS = setOf("read_directory", "file_search")
    }
}

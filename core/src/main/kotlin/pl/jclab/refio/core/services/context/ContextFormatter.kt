package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.models.context.CodeFragmentDTO
import pl.jclab.refio.core.models.context.ExecutedStepDTO
import pl.jclab.refio.core.models.context.ProjectContextDTO
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("ContextFormatter")

private const val MAX_RAG_FRAGMENTS = 15
private const val CONVERSATION_SUMMARY_METADATA_TYPE = ConversationContextBuilder.CONVERSATION_SUMMARY_METADATA_TYPE

// RECENT_WORK limits
private const val RECENT_WORK_BUDGET_TIER_1 = 12_000
private const val RECENT_WORK_BUDGET_TIER_2 = 8_000
private const val RECENT_WORK_BUDGET_TIER_3 = 5_000
private const val RECENT_WORK_BUDGET_TIER_4 = 3_500
private const val RECENT_WORK_BUDGET_TIER_5 = 3_000
private const val RECENT_WORK_BUDGET_TIER_6 = 2_500
private const val RECENT_WORK_FULL_LIMIT_TIER_1 = 10
private const val RECENT_WORK_FULL_LIMIT_TIER_2 = 8
private const val RECENT_WORK_FULL_LIMIT_TIER_3 = 6
private const val RECENT_WORK_FULL_LIMIT_TIER_4 = 5
private const val RECENT_WORK_FULL_LIMIT_DEFAULT = 4
private const val RECENT_WORK_DETAILED_LIMIT_TIER_1 = 10
private const val RECENT_WORK_DETAILED_LIMIT_TIER_2 = 8
private const val RECENT_WORK_DETAILED_LIMIT_TIER_3 = 6
private const val RECENT_WORK_DETAILED_LIMIT_TIER_4 = 5
private const val RECENT_WORK_DETAILED_LIMIT_TIER_5 = 4
private const val RECENT_WORK_DETAILED_LIMIT_DEFAULT = 3

// CONVERSATION_HISTORY limits
private const val CONVERSATION_BUDGET_TIER_HIGH = 5_000
private const val CONVERSATION_BUDGET_TIER_MEDIUM = 3_500
private const val CONVERSATION_BUDGET_TIER_LOW = 2_000
private const val CONVERSATION_MAX_MESSAGES_HIGH = 100
private const val CONVERSATION_MAX_MESSAGES_MEDIUM = 75
private const val CONVERSATION_MAX_MESSAGES_LOW = 50
private const val CONVERSATION_MAX_MESSAGES_DEFAULT = 25
private const val CONVERSATION_MIN_PER_MESSAGE_TOKENS = 128

/**
 * Configuration for RECENT_WORK section generation.
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

    /**
     * Build RAG fragments section with metadata.
     */
    fun buildRagFragmentsSection(context: ProjectContextDTO): String {
        val parts = mutableListOf<String>()
        parts.add("<RAG_FRAGMENTS>")

        context.ragFragments.take(MAX_RAG_FRAGMENTS).forEach { fragment ->
            // Enrich fragment with metadata
            val metadata = enrichFragmentWithMetadata(fragment)

            parts.add("")

            // Build fragment header with metadata
            val attrs = buildList {
                add("file=\"${fragment.filePath}\"")

                if (fragment.startLine != null && fragment.endLine != null) {
                    add("lines=\"${fragment.startLine}-${fragment.endLine}\"")
                }

                metadata["language"]?.let { add("lang=\"$it\"") }
                metadata["fileSize"]?.let { add("size=\"$it\"") }
                add("similarity=\"${String.format("%.2f", fragment.similarity)}\"")
                metadata["complexity"]?.let { add("complexity=\"$it\"") }
            }.joinToString(" ")

            parts.add("<fragment $attrs>")

            // Content with language hint
            val lang = metadata["language"] as? String ?: ""
            val langHint = when (lang.lowercase()) {
                "kotlin" -> "kotlin"
                "java" -> "java"
                "python" -> "python"
                "javascript", "typescript" -> "javascript"
                "html" -> "html"
                "css" -> "css"
                "json" -> "json"
                "yaml" -> "yaml"
                else -> ""
            }

            if (langHint.isNotEmpty()) {
                parts.add("```$langHint")
            } else {
                parts.add("```")
            }

            parts.add(fragment.content.trim())
            parts.add("```")
            parts.add("</fragment>")
        }

        parts.add("</RAG_FRAGMENTS>")
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

        val maxMessages = when {
            budgetTokens >= CONVERSATION_BUDGET_TIER_HIGH -> CONVERSATION_MAX_MESSAGES_HIGH
            budgetTokens >= CONVERSATION_BUDGET_TIER_MEDIUM -> CONVERSATION_MAX_MESSAGES_MEDIUM
            budgetTokens >= CONVERSATION_BUDGET_TIER_LOW -> CONVERSATION_MAX_MESSAGES_LOW
            else -> CONVERSATION_MAX_MESSAGES_DEFAULT
        }
        val perMessageTokens = maxOf(CONVERSATION_MIN_PER_MESSAGE_TOKENS, budgetTokens / maxMessages)

        val firstMessage = history.firstOrNull()
        val firstIsSummary = firstMessage?.metadata?.get("type") == CONVERSATION_SUMMARY_METADATA_TYPE

        if (firstIsSummary && firstMessage != null) {
            val summaryBudget = minOf((budgetTokens * 0.5).toInt(), budgetTokens)
            val summaryContent = ContextTokenEstimator.truncateToTokens(firstMessage.content.trim(), summaryBudget)
            appendLine("=== SUMMARY ===")
            appendLine(summaryContent)
            appendLine("")
        }

        val remaining = if (firstIsSummary) history.drop(1) else history
        val recentMessages = remaining.takeLast(maxMessages)
        for (msg in recentMessages) {
            val content = ContextTokenEstimator.truncateToTokens(msg.content.trim(), perMessageTokens)
            val line = "[${msg.role.uppercase()}]\n${content.trim()}\n"
            if (!appendLine(line)) break
        }

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

        val fullDataLimit = calculateFullDataLimit(executedSteps.size, budgetTokens, config.fullDataLimit)
        val detailedLimit = calculateDetailedLimit(budgetTokens)
        val compressionConfig = ToolResultCompressionConfig(
            detailedMaxChars = config.detailedMaxLength,
            summaryMaxChars = config.summaryMaxLength
        )

        val entries = buildAdaptiveRecentWork(
            steps = executedSteps,
            fullLimit = fullDataLimit,
            detailedLimit = detailedLimit,
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
        val content = ToolResultCompression.compress(step.result, step.summary, level, compressionConfig)
        val tagSuffix = if (fileAttr.isNotBlank()) " $fileAttr" else ""

        // Add compression level attribute (only show if not FULL)
        val compressionAttr = if (level != CompressionLevel.FULL) {
            " compressed=\"${level.name.lowercase()}\""
        } else {
            ""
        }

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

    /**
     * Enrich code fragment with metadata (language, file size, complexity).
     */
    fun enrichFragmentWithMetadata(fragment: CodeFragmentDTO): Map<String, Any> {
        val metadata = mutableMapOf<String, Any>()

        // File size and line count
        try {
            val path = Path.of(fragment.filePath)
            if (java.nio.file.Files.exists(path)) {
                val size = java.nio.file.Files.size(path)
                metadata["fileSize"] = when {
                    size < 1024 -> "${size}B"
                    size < 1024 * 1024 -> "${size / 1024}KB"
                    else -> "${size / (1024 * 1024)}MB"
                }

                val lines = java.nio.file.Files.readAllLines(path).size
                metadata["lineCount"] = lines

                val modified = java.nio.file.Files.getLastModifiedTime(path).toInstant()
                metadata["lastModified"] = modified.toString().take(10)
            }
        } catch (e: Exception) {
            // Ignore file metadata errors
        }

        // Language detection
        metadata["language"] = detectLanguage(fragment.filePath)

        // Complexity estimation
        metadata["complexity"] = estimateComplexity(fragment.content)

        return metadata
    }

    // ===========================
    // Private helper methods
    // ===========================

    private fun calculateFullDataLimit(stepsCount: Int, budgetTokens: Int, baseLimit: Int): Int {
        if (stepsCount <= 0) return 0
        val safeBase = baseLimit.coerceAtLeast(1)
        val budgetLimit = when {
            budgetTokens >= RECENT_WORK_BUDGET_TIER_1 -> RECENT_WORK_FULL_LIMIT_TIER_1
            budgetTokens >= RECENT_WORK_BUDGET_TIER_2 -> RECENT_WORK_FULL_LIMIT_TIER_2
            budgetTokens >= RECENT_WORK_BUDGET_TIER_3 -> RECENT_WORK_FULL_LIMIT_TIER_3
            budgetTokens >= RECENT_WORK_BUDGET_TIER_5 -> RECENT_WORK_FULL_LIMIT_TIER_4
            else -> RECENT_WORK_FULL_LIMIT_DEFAULT
        }
        val effective = minOf(safeBase, budgetLimit)
        return minOf(stepsCount, effective)
    }

    private fun calculateDetailedLimit(budgetTokens: Int): Int = when {
        budgetTokens >= RECENT_WORK_BUDGET_TIER_1 -> RECENT_WORK_DETAILED_LIMIT_TIER_1
        budgetTokens >= RECENT_WORK_BUDGET_TIER_2 -> RECENT_WORK_DETAILED_LIMIT_TIER_2
        budgetTokens >= RECENT_WORK_BUDGET_TIER_3 -> RECENT_WORK_DETAILED_LIMIT_TIER_3
        budgetTokens >= RECENT_WORK_BUDGET_TIER_4 -> RECENT_WORK_DETAILED_LIMIT_TIER_4
        budgetTokens >= RECENT_WORK_BUDGET_TIER_6 -> RECENT_WORK_DETAILED_LIMIT_TIER_5
        else -> RECENT_WORK_DETAILED_LIMIT_DEFAULT
    }

    private fun buildAdaptiveRecentWork(
        steps: List<ExecutedStepDTO>,
        fullLimit: Int,
        detailedLimit: Int,
        budgetTokens: Int,
        config: RecentWorkConfig,
        compressionConfig: ToolResultCompressionConfig
    ): List<String> {
        if (steps.isEmpty()) return emptyList()

        val entries = mutableListOf<Pair<Int, String>>()  // (original index, entry)
        val reversedSteps = steps.asReversed()
        val detailedStart = fullLimit + detailedLimit

        // Strict budget handling: most recent tools first, with graceful fallback
        var tokensUsed = 0

        val candidates = reversedSteps.mapIndexed { index, step ->
            val baseLevel = when {
                index < fullLimit -> CompressionLevel.FULL
                index < detailedStart -> CompressionLevel.DETAILED
                else -> CompressionLevel.SUMMARY
            }
            Triple(index, step, baseLevel)
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

        // Sort by original index (descending = most recent first) and extract entries
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
}

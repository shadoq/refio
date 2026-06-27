package pl.jclab.refio.core.services.context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import pl.jclab.refio.api.models.UserContextMetadata
import pl.jclab.refio.core.context.ContextProviderExtras
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.services.PromptTokenEstimator
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.models.context.ResolvedContextDTO
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.analysis.FileAnalysis
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.tools.PathSandbox
import java.nio.file.Path

private val logger = dualLogger("ContextReferenceResolver")

/**
 * Resolves user context references (@file, @folder, @selection, @docs mentions).
 * Extracted from ContextService — pure resolution logic with no orchestration.
 */
class ContextReferenceResolver(
    private val fileAnalyzerService: FileAnalyzerService? = null,
    private val configService: ConfigService,
    private val chatMessageRepository: ChatMessageRepository,
    /**
     * Opaque platform project handle (IntelliJ Project or null for CLI). Passed
     * down to IDE-specific context providers via [ContextProviderExtras].
     */
    private val platformProject: Any? = null,
) {

    /**
     * Resolve and convert user context references to DTOs.
     * This is called internally by buildProjectContext().
     *
     * @param refs Raw user context references from PromptInputPanel
     * @param projectRoot Project root path
     * @return List of ResolvedContextDTO ready for LLM
     */
    internal suspend fun resolveAndConvertUserContextRefs(
        refs: List<ContextReference>,
        projectRoot: Path,
        currentQuery: String?
    ): List<ResolvedContextDTO> {
        val resolved = resolveUserContextReferences(refs, projectRoot, currentQuery)

        return resolved.mapNotNull { ref ->
            val content = ref.content
            if (content.isNullOrBlank()) {
                logger.warn { "[CONTEXT] Skipping empty context ref: ${ref.displayName}" }
                null
            } else {
                ResolvedContextDTO(
                    type = ref.type.name,
                    providerId = ref.metadata["providerId"] as? String,
                    path = ref.path,
                    displayName = ref.displayName,
                    content = content,
                    sizeBytes = ref.sizeBytes,
                    estimatedTokens = ref.estimatedTokens
                )
            }
        }
    }

    internal fun deduplicateUserContextRefs(refs: List<ContextReference>): List<ContextReference> {
        if (refs.isEmpty()) return emptyList()
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<ContextReference>()
        refs.forEach { ref ->
            val providerId = ref.metadata["providerId"]?.toString() ?: ""
            val key = "${ref.type}:${ref.path}:$providerId"
            if (seen.add(key)) {
                deduped.add(ref)
            } else {
                logger.debug { "[CONTEXT_DEBUG] Deduplicated user context ref: $key" }
            }
        }
        return deduped
    }

    /**
     * Resolve user-provided context references (@file, @folder, @selection, etc.)
     * using ContextProviderRegistry as SINGLE SOURCE OF TRUTH.
     *
     * Flow:
     * 1. For each ContextReference, determine provider ID
     * 2. Get provider from ContextProviderRegistry
     * 3. Call provider.getContextItems()
     * 4. Update ref.content with results
     *
     * @param refs User context references from PromptInputPanel
     * @param projectRoot Project root path for PathSandbox validation
     * @return List of resolved references with loaded content
     */
    private suspend fun resolveUserContextReferences(
        refs: List<ContextReference>,
        projectRoot: Path,
        currentQuery: String? = null
    ): List<ContextReference> = withContext(Dispatchers.IO) {
        logger.info { "[CONTEXT] Resolving ${refs.size} user context reference(s)" }

        val pathSandbox = PathSandbox(projectRoot)

        refs.map { ref ->
            try {
                when (ref.type) {
                    ContextType.PROVIDER -> {
                        resolveProviderReference(ref, projectRoot, pathSandbox, currentQuery)
                    }
                    ContextType.FILE -> {
                        resolveLegacyFileReference(ref, projectRoot, pathSandbox)
                    }

                    ContextType.FOLDER -> {
                        resolveLegacyFolderReference(ref, projectRoot, pathSandbox)
                    }

                    ContextType.SELECTION -> {
                        ref.copy(
                            estimatedTokens = PromptTokenEstimator.estimateBase(ref.content ?: "")
                        )
                    }

                    ContextType.OPEN -> {
                        resolveLegacyOpenReference(ref, projectRoot, pathSandbox)
                    }

                    ContextType.RULES -> {
                        resolveLegacyRulesReference(ref, projectRoot, pathSandbox)
                    }

                    ContextType.DOCS -> {
                        resolveDocsReference(ref, projectRoot, currentQuery)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "[CONTEXT] Failed to resolve reference: ${ref.displayName}" }
                ref.copy(
                    content = "Error: ${e.message}",
                    estimatedTokens = 10
                )
            }
        }
    }

    /**
     * Resolve modern PROVIDER type reference using ContextProviderRegistry.
     */
    private suspend fun resolveProviderReference(
        ref: ContextReference,
        projectRoot: Path,
        pathSandbox: PathSandbox,
        currentQuery: String?
    ): ContextReference {
        val providerId = ref.metadata["providerId"] as? String
        if (providerId == null) {
            logger.warn { "[CONTEXT] Provider reference missing providerId in metadata" }
            return ref.copy(
                content = "Error: Provider ID not found in metadata",
                estimatedTokens = 10
            )
        }

        val provider = ContextProviderRegistry.getProvider(providerId)
        if (provider == null) {
            logger.warn { "[CONTEXT] Provider not found: $providerId" }
            return ref.copy(
                content = "Error: Provider not found: $providerId",
                estimatedTokens = 10
            )
        }

        logger.debug { "[CONTEXT] Calling provider: $providerId with query: ${ref.path}" }

        val fullInput = if (providerId == "docs") {
            currentQuery ?: ref.path
        } else {
            ref.path
        }

        val extras = ContextProviderExtras(
            project = platformProject,
            fullInput = fullInput,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = ref.path,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            logger.debug { "[CONTEXT] Provider $providerId returned no items" }
            return ref.copy(
                content = "No items returned from provider: $providerId",
                estimatedTokens = 10
            )
        }

        // Concatenate all context items
        val content = contextItems.joinToString("\n\n") { item ->
            buildString {
                appendLine("--- ${item.name} ---")
                appendLine(item.content)
            }
        }

        val fileAnalysisSummary = if (providerId == "file") {
            runCatching {
                val resolvedPath = pathSandbox.resolve(ref.path)
                analyzeAndSummarizeFile(projectRoot, resolvedPath)
            }.getOrNull()
        } else {
            null
        }

        val enrichedContent = buildString {
            if (!fileAnalysisSummary.isNullOrBlank()) {
                appendLine("### File Analysis")
                appendLine(fileAnalysisSummary)
                appendLine()
            }
            append(content)
        }.trim()

        val sizeBytes = enrichedContent.length.toLong()
        val estimatedTokens = PromptTokenEstimator.estimateBase(enrichedContent)

        logger.info { "[CONTEXT] Provider $providerId returned ${contextItems.size} item(s), ${enrichedContent.length} chars (analysis=${!fileAnalysisSummary.isNullOrBlank()})" }

        return ref.copy(
            content = enrichedContent,
            sizeBytes = sizeBytes,
            estimatedTokens = estimatedTokens
        )
    }

    private suspend fun resolveDocsReference(
        ref: ContextReference,
        projectRoot: Path,
        currentQuery: String?
    ): ContextReference {
        val provider = ContextProviderRegistry.getProvider("docs")
        if (provider == null) {
            logger.warn { "[CONTEXT] DocsContextProvider not registered" }
            return ref.copy(
                content = "Documentation provider is not available.",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = platformProject,
            fullInput = currentQuery ?: ref.path,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = ref.path,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            return ref.copy(
                content = "No documentation content found for: ${ref.path}",
                estimatedTokens = 10
            )
        }

        val content = contextItems.joinToString("\n\n") { item ->
            buildString {
                appendLine("--- ${item.description} ---")
                appendLine(item.content)
            }
        }.trim()

        return ref.copy(
            content = content,
            sizeBytes = content.length.toLong(),
            estimatedTokens = PromptTokenEstimator.estimateBase(content)
        )
    }

    /**
     * Resolve legacy FILE type by mapping to FileContextProvider.
     */
    private suspend fun resolveLegacyFileReference(
        ref: ContextReference,
        projectRoot: Path,
        pathSandbox: PathSandbox
    ): ContextReference {
        logger.debug { "[CONTEXT] Resolving legacy FILE reference: ${ref.path}" }

        val provider = ContextProviderRegistry.getProvider("file")
        if (provider == null) {
            logger.error { "[CONTEXT] FileContextProvider not registered!" }
            return ref.copy(
                content = "Error: FileContextProvider not available",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = platformProject,
            fullInput = ref.path,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = ref.path,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            return ref.copy(
                content = "Error: File not found or not readable: ${ref.path}",
                estimatedTokens = 10
            )
        }

        val item = contextItems.first()
        val analysisSummary = runCatching {
            val resolvedPath = pathSandbox.resolve(ref.path)
            analyzeAndSummarizeFile(projectRoot, resolvedPath)
        }.getOrNull()

        val content = buildString {
            if (!analysisSummary.isNullOrBlank()) {
                appendLine("### File Analysis")
                appendLine(analysisSummary)
                appendLine()
            }
            appendLine("### File Content")
            appendLine(item.content)
        }.trim()

        return ref.copy(
            content = content,
            sizeBytes = content.length.toLong(),
            estimatedTokens = PromptTokenEstimator.estimateBase(content)
        )
    }

    /**
     * Resolve legacy FOLDER type by mapping to FolderContextProvider.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun resolveLegacyFolderReference(
        ref: ContextReference,
        projectRoot: Path,
        pathSandbox: PathSandbox
    ): ContextReference {
        logger.debug { "[CONTEXT] Resolving legacy FOLDER reference: ${ref.path}" }

        val provider = ContextProviderRegistry.getProvider("folder")
        if (provider == null) {
            logger.error { "[CONTEXT] FolderContextProvider not registered!" }
            return ref.copy(
                content = "Error: FolderContextProvider not available",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = platformProject,
            fullInput = ref.path,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = ref.path,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            return ref.copy(
                content = "Error: Folder not found: ${ref.path}",
                estimatedTokens = 10
            )
        }

        val item = contextItems.first()
        return ref.copy(
            content = item.content,
            sizeBytes = item.content.length.toLong(),
            estimatedTokens = PromptTokenEstimator.estimateBase(item.content)
        )
    }

    /**
     * Resolve legacy OPEN type by mapping to OpenFilesContextProvider.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun resolveLegacyOpenReference(
        ref: ContextReference,
        projectRoot: Path,
        pathSandbox: PathSandbox
    ): ContextReference {
        logger.debug { "[CONTEXT] Resolving legacy OPEN reference" }

        val provider = ContextProviderRegistry.getProvider("open_files")
        if (provider == null) {
            logger.error { "[CONTEXT] OpenFilesContextProvider not registered!" }
            return ref.copy(
                content = "Error: OpenFilesContextProvider not available",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = platformProject,
            fullInput = "",
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = "",
            extras = extras
        )

        if (contextItems.isEmpty()) {
            logger.debug { "[CONTEXT] No open files found in editor" }
            return ref.copy(
                content = "No open files",
                estimatedTokens = 5
            )
        }

        logger.info { "[CONTEXT] Found ${contextItems.size} open files" }

        val content = contextItems.joinToString("\n\n") { item ->
            buildString {
                appendLine("--- ${item.description} ---")
                appendLine(item.content)
            }
        }

        return ref.copy(
            content = content,
            sizeBytes = content.length.toLong(),
            estimatedTokens = PromptTokenEstimator.estimateBase(content)
        )
    }

    /**
     * Resolve legacy RULES type by reading Agents.md or specified file.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun resolveLegacyRulesReference(
        ref: ContextReference,
        projectRoot: Path,
        pathSandbox: PathSandbox
    ): ContextReference {
        logger.debug { "[CONTEXT] Resolving legacy RULES reference" }

        val rulesPath = if (ref.path.isNotEmpty() && ref.path != "Agents.md") {
            ref.path
        } else {
            "Agents.md"
        }

        // Use FileContextProvider to read rules file
        val provider = ContextProviderRegistry.getProvider("file")
        if (provider == null) {
            logger.error { "[CONTEXT] FileContextProvider not registered!" }
            return ref.copy(
                content = "Error: FileContextProvider not available",
                estimatedTokens = 10
            )
        }

        val extras = ContextProviderExtras(
            project = platformProject,
            fullInput = rulesPath,
            workspacePath = projectRoot.toString()
        )

        val contextItems = provider.getContextItems(
            query = rulesPath,
            extras = extras
        )

        if (contextItems.isEmpty()) {
            return ref.copy(
                content = "Error: Rules file not found: $rulesPath",
                estimatedTokens = 10
            )
        }

        val rulesContent = contextItems.first().content
        val analysisSummary = runCatching {
            val resolvedPath = pathSandbox.resolve(rulesPath)
            analyzeAndSummarizeFile(projectRoot, resolvedPath)
        }.getOrNull()

        val content = buildString {
            if (!analysisSummary.isNullOrBlank()) {
                appendLine("### File Analysis")
                appendLine(analysisSummary)
                appendLine()
            }
            appendLine("### File Content")
            appendLine(rulesContent)
        }.trim()

        return ref.copy(
            content = content,
            sizeBytes = content.length.toLong(),
            estimatedTokens = PromptTokenEstimator.estimateBase(content)
        )
    }

    /**
     * Format resolved context references as string for LLM prompt.
     *
     * @param refs Resolved context references (with content loaded)
     * @return Formatted string with headers and content
     */
    fun formatContextReferencesForLLM(refs: List<ContextReference>): String {
        if (refs.isEmpty()) return ""

        val parts = mutableListOf<String>()

        refs.forEach { ref ->
            when (ref.type) {
                ContextType.FILE, ContextType.PROVIDER -> {
                    parts.add("=== ${ref.displayName} ===")
                    parts.add(ref.content ?: "Error: Content not loaded")
                    parts.add("=== End of ${ref.displayName} ===")
                }

                ContextType.FOLDER -> {
                    parts.add("=== Folder: ${ref.displayName} ===")
                    parts.add(ref.content ?: "Error: Content not loaded")
                    parts.add("=== End of folder listing ===")
                }

                ContextType.SELECTION -> {
                    parts.add("=== Current Selection ===")
                    parts.add(ref.content ?: "Error: No selection")
                    parts.add("=== End of selection ===")
                }

                ContextType.OPEN -> {
                    parts.add("=== Open Files ===")
                    parts.add(ref.content ?: "Error: Could not get open files")
                    parts.add("=== End of open files ===")
                }

                ContextType.RULES -> {
                    parts.add("=== Rules: ${ref.displayName} ===")
                    parts.add(ref.content ?: "Error: Rules not loaded")
                    parts.add("=== End of rules ===")
                }

                ContextType.DOCS -> {
                    parts.add("=== Documentation: ${ref.displayName} ===")
                    parts.add(ref.content ?: "Documentation not available")
                    parts.add("=== End of documentation ===")
                }
            }
        }

        return parts.joinToString("\n\n")
    }

    /**
     * Collect user context references from chat message history.
     * Extracts all @mention context refs from user messages for given task.
     *
     * @param taskId Task ID to collect context refs for
     * @return List of context references from user messages
     */
    fun collectUserContextRefs(taskId: String): List<ContextReference> {
        val refs = mutableListOf<ContextReference>()
        val history = chatMessageRepository.findByTaskId(taskId)

        history.filter { it.role == MessageRole.USER }.forEach { message ->
            val metadata = UserContextMetadata.fromJson(message.metadata)
            if (metadata != null && metadata.contextRefs.isNotEmpty()) {
                refs.addAll(metadata.contextRefs)
            }
        }

        return refs
    }

    /**
     * Collect all user context references from task history.
     * Used by AgentTurnLoop to gather @ mentions from previous messages.
     */
    fun collectAllUserContextRefs(taskId: String): List<ContextReference> {
        return collectUserContextRefs(taskId)
    }

    private suspend fun analyzeAndSummarizeFile(projectRoot: Path, absolutePath: Path): String? {
        val analyzer = fileAnalyzerService ?: return null
        return try {
            val analysis = analyzer.analyze(projectRoot, absolutePath)
            buildAnalysisSummary(analysis)
        } catch (e: Exception) {
            logger.warn(e) { "[CONTEXT] File analysis failed for $absolutePath" }
            null
        }
    }

    private fun buildAnalysisSummary(analysis: FileAnalysis): String {
        val builder = StringBuilder()
        builder.appendLine("Path: ${analysis.filePath}")
        builder.appendLine("Language: ${analysis.language ?: "unknown"}")

        if (analysis.language == "html" && !analysis.codeElements.documentation.isNullOrBlank()) {
            builder.appendLine(analysis.codeElements.documentation)
        }

        if (analysis.codeElements.classes.isNotEmpty()) {
            builder.appendLine("Classes:")
            analysis.codeElements.classes.take(5).forEach {
                builder.appendLine("  - ${it.name} (${it.startLine}-${it.endLine})")
            }
        }

        if (analysis.codeElements.functions.isNotEmpty()) {
            builder.appendLine("Functions:")
            analysis.codeElements.functions.take(5).forEach {
                builder.appendLine("  - ${it.name} (${it.startLine}-${it.endLine})")
            }
        }

        if (analysis.codeElements.frameworks.isNotEmpty()) {
            builder.appendLine("Frameworks: ${analysis.codeElements.frameworks.joinToString()}")
        }

        return builder.toString().trim()
    }
}

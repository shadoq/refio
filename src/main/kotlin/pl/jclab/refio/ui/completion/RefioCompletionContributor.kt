package pl.jclab.refio.ui.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.SlashCommand
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.LoadSubmenuItemsArgs
import pl.jclab.refio.core.context.ProviderType
import pl.jclab.refio.core.subagents.models.SubagentInfo
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.session.SessionManager

class RefioCompletionContributor : CompletionContributor() {

    companion object {
        private val log = logger<RefioCompletionContributor>()
        val PROMPT_EDITOR_KEY: Key<Boolean> = Key.create("refio.promptEditor.completion.enabled")
        val ADD_CONTEXT_REFERENCE_KEY: Key<(ContextReference) -> Unit> =
            Key.create("refio.promptEditor.addContextReference")
        val REPLACE_CONTEXT_PREFIX_KEY: Key<(String) -> Unit> =
            Key.create("refio.promptEditor.replaceContextPrefix")

        private val slashCommandsCacheLock = Any()
        @Volatile
        private var cachedSlashCommands: List<SlashCommand> = SlashCommand.BUILTINS
        @Volatile
        private var lastSlashCommandsLoadAt: Long = 0
        private const val SLASH_COMMANDS_CACHE_MS = 5_000L

        private val subagentsCacheLock = Any()
        @Volatile
        private var cachedSubagents: List<SubagentInfo> = emptyList()
        @Volatile
        private var lastSubagentsLoadAt: Long = 0
        private const val SUBAGENTS_CACHE_MS = 5_000L

        private val submenuItemsCache = java.util.concurrent.ConcurrentHashMap<String, List<pl.jclab.refio.core.context.ContextSubmenuItem>>()
        private val submenuLoadingKeys = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val submenuBackgroundScope = CoroutineScope(Dispatchers.IO)

        private fun extractVariablesFromTemplate(template: String): List<String> {
            return Regex("""\{(\w+)\}""").findAll(template).map { it.groupValues[1] }.toList()
        }

        @Suppress("UNUSED_PARAMETER")
        private fun loadSlashCommands(_project: Project): List<SlashCommand> {
            val now = System.currentTimeMillis()
            synchronized(slashCommandsCacheLock) {
                if (now - lastSlashCommandsLoadAt < SLASH_COMMANDS_CACHE_MS && cachedSlashCommands.isNotEmpty()) {
                    return cachedSlashCommands
                }

                return try {
                    val router = CoreConnectionManager.getInstance().getApiRouter()
                    val response = router.getPromptsByType(PromptType.SLASH_COMMAND)

                    val commands = response.prompts
                        .filter { it.isEnabled }
                        .map { prompt ->
                            SlashCommand(
                                id = prompt.id,
                                name = prompt.name.removePrefix("/"),
                                description = prompt.description ?: "Custom command",
                                template = prompt.content,
                                variables = extractVariablesFromTemplate(prompt.content),
                                category = "custom",
                                isBuiltin = false
                            )
                        }

                    val resolved = if (commands.isEmpty()) SlashCommand.BUILTINS else commands
                    cachedSlashCommands = resolved
                    lastSlashCommandsLoadAt = now
                    resolved
                } catch (e: Exception) {
                    log.warn("Failed to load slash commands for completion, using built-ins", e)
                    cachedSlashCommands = SlashCommand.BUILTINS
                    lastSlashCommandsLoadAt = now
                    cachedSlashCommands
                }
            }
        }

        private fun loadSubagents(project: Project): List<SubagentInfo> {
            val now = System.currentTimeMillis()
            synchronized(subagentsCacheLock) {
                if (now - lastSubagentsLoadAt < SUBAGENTS_CACHE_MS && cachedSubagents.isNotEmpty()) {
                    return cachedSubagents
                }

                return try {
                    // Use project-specific router to get subagents
                    val sessionManager = SessionManager.getInstance(project)
                    val router = sessionManager.apiRouter
                    val subagents = router.subagentRouter?.listSubagents(includeDisabled = false) ?: emptyList()

                    cachedSubagents = subagents
                    lastSubagentsLoadAt = now
                    subagents
                } catch (e: Exception) {
                    log.warn("Failed to load subagents for completion", e)
                    cachedSubagents = emptyList()
                    lastSubagentsLoadAt = now
                    emptyList()
                }
            }
        }
    }

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val editor = parameters.editor
                    if (editor.getUserData(PROMPT_EDITOR_KEY) != true) return

                    val project = parameters.originalFile.project
                    val offset = parameters.offset.coerceAtMost(editor.document.textLength)
                    val beforeCaret = editor.document.charsSequence.subSequence(0, offset).toString()
                    val token = beforeCaret.takeLastWhile { !it.isWhitespace() }

                    // Check for SUBMENU provider pattern: "@provider query"
                    val submenuMatch = Regex("""@(\w+)\s+(.*)$""").find(beforeCaret)

                    when {
                        // SUBMENU provider with query: @file Main, @recent test
                        submenuMatch != null -> {
                            val providerName = submenuMatch.groupValues[1].lowercase()
                            val query = submenuMatch.groupValues[2]
                            val provider = ContextProviderRegistry.getProvider(providerName)

                            if (provider?.description?.type == ProviderType.SUBMENU) {
                                val addContextRef = editor.getUserData(ADD_CONTEXT_REFERENCE_KEY)
                                val replacePrefix = editor.getUserData(REPLACE_CONTEXT_PREFIX_KEY)

                                // Load submenu items from cache; trigger async refresh in background
                                val cacheKey = "$providerName:$query"
                                val submenuItems = submenuItemsCache.getOrDefault(cacheKey, emptyList())
                                if (submenuLoadingKeys.putIfAbsent(cacheKey, true) == null) {
                                    submenuBackgroundScope.launch {
                                        try {
                                            val items = provider.loadSubmenuItems(
                                                LoadSubmenuItemsArgs(query = query, project = project)
                                            )
                                            submenuItemsCache[cacheKey] = items
                                        } catch (e: Exception) {
                                            log.warn("Failed to load submenu items for $providerName", e)
                                        } finally {
                                            submenuLoadingKeys.remove(cacheKey)
                                        }
                                    }
                                }

                                val prefixMatcher = result.withPrefixMatcher("@$providerName $query")
                                submenuItems
                                    .filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }
                                    .take(20)
                                    .forEach { item ->
                                        val icon = item.icon ?: provider.description.icon ?: ""
                                        prefixMatcher.addElement(
                                            LookupElementBuilder.create("@$providerName ${item.title}")
                                                .withPresentableText("$icon ${item.title}")
                                                .withTypeText(providerName, true)
                                                .withTailText(" ${item.description}", true)
                                                .withInsertHandler(
                                                    InsertHandler { _: InsertionContext, _ ->
                                                        val contextRef = ContextReference.provider(
                                                            providerId = providerName,
                                                            query = item.id,
                                                            displayName = item.title,
                                                            additionalMetadata = item.metadata
                                                        )
                                                        addContextRef?.invoke(contextRef)
                                                        replacePrefix?.invoke(item.title)
                                                    }
                                                ),
                                        )
                                    }
                            }
                        }

                        // Slash commands: /explain, /fix, etc.
                        token.startsWith("/") -> {
                            val cleanPrefix = token.removePrefix("/").lowercase()
                            val prefixMatcher = result.withPrefixMatcher(token)
                            loadSlashCommands(project)
                                .sortedBy { it.name.lowercase() }
                                .forEach { cmd ->
                                    if (cleanPrefix.isEmpty() || cmd.name.lowercase().startsWith(cleanPrefix)) {
                                        prefixMatcher.addElement(
                                            LookupElementBuilder.create("/${cmd.name} ")
                                                .withPresentableText("/${cmd.name}")
                                                .withTypeText("Command", true)
                                                .withTailText(" ${cmd.description}", true),
                                        )
                                    }
                                }
                        }

                        // Subagents: !security-reviewer, !code-reviewer, etc.
                        token.startsWith("!") -> {
                            val cleanPrefix = token.removePrefix("!").lowercase()
                            val prefixMatcher = result.withPrefixMatcher(token)
                            val subagents = loadSubagents(project)

                            if (subagents.isEmpty() && cleanPrefix.isEmpty()) {
                                // Show placeholder when no subagents available
                                prefixMatcher.addElement(
                                    LookupElementBuilder.create("!no-subagents")
                                        .withPresentableText("No subagents available")
                                        .withTypeText("Info", true)
                                        .withTailText(" Create one in Settings → Subagents", true)
                                        .withInsertHandler { _, _ -> /* No-op */ },
                                )
                            } else {
                                subagents
                                    .filter { cleanPrefix.isEmpty() || it.name.lowercase().startsWith(cleanPrefix) }
                                    .sortedBy { it.name }
                                    .forEach { subagent ->
                                        prefixMatcher.addElement(
                                            LookupElementBuilder.create("!${subagent.name} ")
                                                .withPresentableText("!${subagent.name}")
                                                .withTypeText("Subagent (${subagent.scope})", true)
                                                .withTailText(" ${subagent.description}", true),
                                        )
                                    }
                            }
                        }

                        // Context providers: @file, @codebase, @docs, etc.
                        token.startsWith("@") -> {
                            val cleanPrefix = token.removePrefix("@").lowercase()
                            val prefixMatcher = result.withPrefixMatcher(token)

                            val addContextRef = editor.getUserData(ADD_CONTEXT_REFERENCE_KEY)
                            val replacePrefix = editor.getUserData(REPLACE_CONTEXT_PREFIX_KEY)

                            fun addContextReference(contextRef: ContextReference) {
                                addContextRef?.invoke(contextRef)
                                replacePrefix?.invoke(contextRef.displayName)
                            }

                            // Add all registered context providers
                            ContextProviderRegistry.getAllProviders()
                                .sortedBy { it.description.title }
                                .forEach { provider ->
                                    val title = provider.description.title
                                    val displayTitle = provider.description.displayTitle
                                    val icon = provider.description.icon ?: ""
                                    val providerType = provider.description.type

                                    if (cleanPrefix.isEmpty() || title.lowercase().startsWith(cleanPrefix)) {
                                        prefixMatcher.addElement(
                                            LookupElementBuilder.create("@$title ")
                                                .withPresentableText("$icon @$title")
                                                .withTypeText("Context", true)
                                                .withTailText(" $displayTitle", true)
                                                .withInsertHandler(
                                                    InsertHandler { _: InsertionContext, _ ->
                                                        if (providerType == ProviderType.SUBMENU) return@InsertHandler
                                                        addContextReference(
                                                            ContextReference.provider(
                                                                providerId = title,
                                                                query = "",
                                                                displayName = displayTitle
                                                            )
                                                        )
                                                    }
                                                ),
                                        )
                                    }
                                }

                            // Add special context types
                            if (cleanPrefix.isEmpty() || "rules".startsWith(cleanPrefix)) {
                                prefixMatcher.addElement(
                                    LookupElementBuilder.create("@rules ")
                                        .withPresentableText("📋 @rules")
                                        .withPresentableText("@rules")
                                        .withTypeText("Context", true)
                                        .withTailText(" Project rules", true)
                                        .withInsertHandler(
                                            InsertHandler { _: InsertionContext, _ ->
                                                addContextReference(ContextReference.rules())
                                            }
                                        ),
                                )
                            }

                            if (hasEditorSelection(project) && (cleanPrefix.isEmpty() || "selection".startsWith(cleanPrefix))) {
                                prefixMatcher.addElement(
                                    LookupElementBuilder.create("@selection ")
                                        .withPresentableText("✂️ @selection")
                                        .withPresentableText("@selection")
                                        .withTypeText("Context", true)
                                        .withTailText(" Selected text", true)
                                        .withInsertHandler(
                                            InsertHandler { _: InsertionContext, _ ->
                                                val contextRef = selectionContextReference(project) ?: return@InsertHandler
                                                addContextReference(contextRef)
                                            }
                                        ),
                                )
                            }

                            // File search fallback: search files by name when prefix doesn't match providers
                            if (cleanPrefix.isNotEmpty()) {
                                val matchingProviders = ContextProviderRegistry.getAllProviders()
                                    .filter { it.description.title.lowercase().startsWith(cleanPrefix) }

                                // Only show file results if no providers match
                                if (matchingProviders.isEmpty()) {
                                    searchFilesByName(project, cleanPrefix).forEach { (fileName, filePath) ->
                                        prefixMatcher.addElement(
                                            LookupElementBuilder.create("@$fileName ")
                                                .withPresentableText("📄 $fileName")
                                                .withTypeText("File", true)
                                                .withTailText(" $filePath", true)
                                                .withInsertHandler(
                                                    InsertHandler { _: InsertionContext, _ ->
                                                        addContextReference(
                                                            ContextReference.file(filePath, fileName)
                                                        )
                                                    }
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    /**
     * Search files by name pattern for autocomplete fallback.
     * Returns list of (fileName, filePath) pairs.
     */
    private fun searchFilesByName(project: Project, pattern: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val scope = GlobalSearchScope.projectScope(project)

        FilenameIndex.processAllFileNames(
            { fileName ->
                if (fileName.contains(pattern, ignoreCase = true)) {
                    FilenameIndex.getVirtualFilesByName(fileName, scope)
                        .filter { it.isValid && !it.isDirectory }
                        .forEach { file ->
                            if (result.size < 15) {
                                result.add(fileName to file.path)
                            }
                        }
                }
                result.size < 15
            },
            scope,
            null
        )

        return result
    }

    private fun hasEditorSelection(project: Project): Boolean {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return false
        return editor.selectionModel.hasSelection()
    }

    private fun selectionContextReference(project: Project): ContextReference? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        if (!editor.selectionModel.hasSelection()) return null

        val selection = editor.selectionModel.selectedText ?: return null
        val fileName = editor.virtualFile?.name ?: "unknown"
        return ContextReference.selection(selection, fileName)
    }
}

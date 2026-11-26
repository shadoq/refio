package pl.jclab.refio.core.prompts

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.SlashPrompt
import pl.jclab.refio.core.api.routers.PromptsRouter
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.ProviderType
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.logging.dualLogger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val logger = dualLogger("PromptInputProcessor")

data class PromptTemplateContext(
    val modelRaw: String,
    val mode: String,
    val executionMode: String,
    val projectName: String,
    val sessionId: String,
)

/**
 * Shared prompt-input preprocessing used by both the IntelliJ plugin and the CLI TUI:
 * - loading enabled custom slash prompts from DB (falling back to built-ins)
 * - expanding `/name` slash prompts inline
 * - substituting the 10 standard `{{VAR}}` template variables
 * - extracting and deduplicating inline `@provider[:query]` context references
 */
object PromptInputProcessor {

    private val SLASH_REGEX = Regex("""(?<=\s|^)/([\w-]+)""")
    private val TEMPLATE_VAR_REGEX = Regex("""\{\{([A-Za-z0-9_]+)}}""")
    private val SINGLE_BRACE_VAR_REGEX = Regex("""\{(\w+)}""")
    private val INLINE_CONTEXT_REGEX = Regex("""(?<!\w)@([a-zA-Z0-9_]+)(?::([^\n@]+))?""")

    private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val TIME_FMT = DateTimeFormatter.ofPattern("HHmmss")

    fun loadSlashPrompts(promptsRouter: PromptsRouter?): List<SlashPrompt> {
        val fallback = SlashPrompt.BUILTINS
        if (promptsRouter == null) return fallback
        return try {
            val response = promptsRouter.getPromptsByType(PromptType.SLASH_PROMPT)
            val custom = response.prompts
                .filter { it.isEnabled }
                .map { prompt ->
                    SlashPrompt(
                        id = prompt.id,
                        name = prompt.name.removePrefix("/"),
                        description = prompt.description ?: "Custom prompt",
                        template = prompt.content,
                        variables = extractTemplateVariables(prompt.content),
                        category = "custom",
                        isBuiltin = false,
                    )
                }
            if (custom.isEmpty()) fallback else custom
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load slash prompts from database, falling back to built-ins" }
            fallback
        }
    }

    fun extractTemplateVariables(template: String): List<String> =
        SINGLE_BRACE_VAR_REGEX.findAll(template).map { it.groupValues[1] }.toList()

    fun expandSlashPrompts(
        text: String,
        slashPrompts: List<SlashPrompt>,
        selectionResolver: (() -> String)? = null,
    ): String {
        val matches = SLASH_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return text

        var result = text
        var offset = 0

        for (match in matches) {
            val promptName = match.groupValues[1]
            val slashPrompt = slashPrompts.find { it.name.equals(promptName, ignoreCase = true) } ?: continue

            var template = slashPrompt.template
            if ("selection" in slashPrompt.variables && selectionResolver != null) {
                template = template.replace("{selection}", selectionResolver())
            }

            val start = match.range.first + offset
            val end = match.range.last + 1 + offset
            result = result.substring(0, start) + template + result.substring(end)
            offset += template.length - match.value.length
        }

        return result
    }

    fun applyTemplateVariables(text: String, context: PromptTemplateContext): String {
        if (!text.contains("{{")) return text

        val now = LocalDateTime.now()
        val modelRaw = context.modelRaw.ifBlank { "auto" }
        val providerRaw = modelRaw.substringBefore("/", missingDelimiterValue = "auto")
        val variables = mapOf(
            "MODEL_ID" to sanitizeFileNameToken(modelRaw.replace("/", "_")),
            "MODEL_RAW" to modelRaw,
            "PROVIDER" to sanitizeFileNameToken(providerRaw),
            "MODE" to context.mode,
            "EXECUTION_MODE" to context.executionMode,
            "TIMESTAMP" to now.format(TIMESTAMP_FMT),
            "DATE" to LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
            "TIME" to LocalTime.now().format(TIME_FMT),
            "PROJECT_NAME" to sanitizeFileNameToken(context.projectName),
            "SESSION_ID" to sanitizeFileNameToken(context.sessionId.ifBlank { "no_session" }),
        )

        return TEMPLATE_VAR_REGEX.replace(text) { match ->
            val key = match.groupValues[1].uppercase()
            variables[key] ?: match.value
        }
    }

    fun sanitizeFileNameToken(input: String): String {
        val sanitized = input
            .trim()
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .replace(Regex("""_+"""), "_")
            .trim('_', '.')
        return sanitized.ifBlank { "unknown" }
    }

    /**
     * Extract inline `@provider[:query]` context references from user input text.
     * Supports standard file/folder/open/rules/docs/selection references as well as
     * any provider registered in [ContextProviderRegistry].
     */
    fun extractInlineContextRefs(
        text: String,
        selectionResolver: (() -> ContextReference?)? = null,
        missingQueryPrompter: ((providerId: String, displayTitle: String) -> String?)? = null,
    ): List<ContextReference> {
        val refs = mutableListOf<ContextReference>()
        INLINE_CONTEXT_REGEX.findAll(text).forEach { match ->
            val providerId = match.groupValues[1].lowercase()
            val rawQuery = match.groupValues.getOrNull(2)?.trim().orEmpty()
            // For file/folder/docs tokens followed by whitespace prose, keep only the token path/url.
            val tokenQuery = rawQuery.substringBefore(' ').trim()

            when (providerId) {
                "file" -> {
                    if (tokenQuery.isNotBlank()) {
                        refs.add(ContextReference.file(path = tokenQuery, displayName = tokenQuery.substringAfterLast('/')))
                    }
                    return@forEach
                }
                "folder" -> {
                    if (tokenQuery.isNotBlank()) {
                        refs.add(ContextReference.folder(path = tokenQuery))
                    }
                    return@forEach
                }
                "docs" -> {
                    if (tokenQuery.isNotBlank()) {
                        refs.add(ContextReference.docs(url = tokenQuery))
                    }
                    return@forEach
                }
                "open", "open_files", "open_file" -> {
                    refs.add(ContextReference.openFiles())
                    return@forEach
                }
                "rules" -> {
                    refs.add(ContextReference.rules())
                    return@forEach
                }
                "selection" -> {
                    selectionResolver?.invoke()?.let { refs.add(it) }
                    return@forEach
                }
            }

            val provider = ContextProviderRegistry.getProvider(providerId) ?: return@forEach
            val desc = provider.description

            var query = rawQuery
            if (desc.type == ProviderType.QUERY && query.isBlank()) {
                val prompted = missingQueryPrompter?.invoke(desc.title, desc.displayTitle)
                if (prompted.isNullOrBlank()) return@forEach
                query = prompted.trim()
            }

            val displayName = if (query.isNotBlank()) "${desc.displayTitle}: $query" else desc.displayTitle
            refs.add(
                ContextReference.provider(
                    providerId = desc.title,
                    query = query,
                    displayName = displayName,
                )
            )
        }
        return refs
    }

    fun mergeContextRefs(
        base: List<ContextReference>,
        extras: List<ContextReference>,
    ): List<ContextReference> {
        if (extras.isEmpty()) return base.toList()
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<ContextReference>()
        (base + extras).forEach { ref ->
            val providerId = ref.metadata["providerId"]?.toString() ?: ""
            val key = "${ref.type}:${ref.path}:$providerId"
            if (seen.add(key)) {
                merged.add(ref)
            }
        }
        return merged
    }
}

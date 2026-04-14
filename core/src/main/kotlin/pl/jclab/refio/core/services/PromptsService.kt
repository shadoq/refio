package pl.jclab.refio.core.services

import pl.jclab.refio.api.models.SlashPrompt
import pl.jclab.refio.core.db.Prompt
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.repositories.PromptsRepository
import pl.jclab.refio.core.prompts.PromptRegistry
import pl.jclab.refio.core.prompts.PromptTemplate
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("PromptsService")

/**
 * Service for managing prompts with {{variable}} substitution.
 *
 * System prompts are loaded from MD files via PromptRegistry with 3-layer hierarchy:
 * 1. BUILTIN - resources/prompts/ *.md (lowest priority)
 * 2. USER - DB isCustom=true (UI edits) > ~/.refio/prompts/ *.md
 * 3. PROJECT - .refio/prompts/ *.md (highest priority)
 *
 * Slash prompts and rules remain in the database.
 */
class PromptsService(
    private val promptsRepository: PromptsRepository,
    private val promptRegistry: PromptRegistry
) {

    companion object {
        // Default prompt names in DB (constants for DB lookups)
        const val SYSTEM_CHAT_NAME = "System Chat"
        const val SYSTEM_PLAN_NAME = "System Plan"
        const val SYSTEM_AGENT_NAME = "System Agent"
        const val SYSTEM_STEP_PLANNER_NAME = "System Step Planner"
        const val SYSTEM_STEP_SUMMARIZER_NAME = "System Step Summarizer"
        const val SYSTEM_ORCHESTRATOR_NAME = "System Orchestrator"
        const val SYSTEM_EXECUTION_SUMMARY_NAME = "System Execution Summary"
        const val SYSTEM_CONVERSATION_SUMMARY_NAME = "System Conversation Summary"
        const val SYSTEM_INTENT_CLASSIFIER_NAME = "System Intent Classifier"
        const val SYSTEM_TOOL_SUMMARY_NAME = "System Tool Summary"
        const val CODE_EDITING_SYSTEM_NAME = "code_editing_system"
        const val CODE_EDITING_USER_NAME = "code_editing_user"
        const val MULTI_LINE_EDITING_SYSTEM_NAME = "multi_line_editing_system"
        const val MULTI_LINE_EDITING_USER_NAME = "multi_line_editing_user"
    }

    private val systemPromptNames = mapOf(
        PromptType.SYSTEM_CHAT to SYSTEM_CHAT_NAME,
        PromptType.SYSTEM_PLAN to SYSTEM_PLAN_NAME,
        PromptType.SYSTEM_AGENT to SYSTEM_AGENT_NAME,
        PromptType.SYSTEM_STEP_PLANNER to SYSTEM_STEP_PLANNER_NAME,
        PromptType.SYSTEM_STEP_SUMMARIZER to SYSTEM_STEP_SUMMARIZER_NAME,
        PromptType.SYSTEM_ORCHESTRATOR to SYSTEM_ORCHESTRATOR_NAME,
        PromptType.SYSTEM_EXECUTION_SUMMARY to SYSTEM_EXECUTION_SUMMARY_NAME,
        PromptType.SYSTEM_CONVERSATION_SUMMARY to SYSTEM_CONVERSATION_SUMMARY_NAME,
        PromptType.SYSTEM_INTENT_CLASSIFIER to SYSTEM_INTENT_CLASSIFIER_NAME,
        PromptType.SYSTEM_TOOL_SUMMARY to SYSTEM_TOOL_SUMMARY_NAME,
        PromptType.CODE_EDITING_SYSTEM to CODE_EDITING_SYSTEM_NAME,
        PromptType.CODE_EDITING_USER to CODE_EDITING_USER_NAME,
        PromptType.MULTI_LINE_EDITING_SYSTEM to MULTI_LINE_EDITING_SYSTEM_NAME,
        PromptType.MULTI_LINE_EDITING_USER to MULTI_LINE_EDITING_USER_NAME
    )

    /**
     * Initialize defaults: seed slash prompts to DB and clean up stale system prompt records.
     * System prompts are now loaded from MD files - no DB seeding needed.
     */
    fun initializeDefaults() {
        logger.info { "Initializing default prompts" }

        initializeBuiltinSlashPrompts()
        cleanupNonCustomSystemPrompts()

        logger.info { "Default prompts initialized" }
    }

    /**
     * Get system prompt for given type with variable substitution.
     * Uses 3-layer resolution: project file > DB isCustom > user file > builtin.
     */
    fun getSystemPrompt(type: PromptType, variables: Map<String, Any> = emptyMap()): String {
        val name = promptTypeToName(type) ?: return ""

        val content = resolvePromptContent(name, type)

        return if (variables.isNotEmpty()) {
            val template = PromptTemplate(content)
            template.render(variables)
        } else {
            content
        }
    }

    /**
     * Get all system prompts.
     *
     * Merges DB custom overrides (isCustom=true) with file-based defaults from PromptRegistry
     * so the UI always sees the full set of system prompt types, with isCustom flagging overrides.
     */
    fun getSystemPrompts(): List<Prompt> {
        val dbByType = promptsRepository.findSystemPrompts(enabledOnly = false)
            .associateBy { it.type }

        return PromptType.SYSTEM_PROMPT_TYPES.mapNotNull { type ->
            val dbPrompt = dbByType[type]
            if (dbPrompt != null && dbPrompt.isCustom) {
                dbPrompt
            } else {
                buildDefaultSystemPrompt(type, dbPrompt)
            }
        }
    }

    private fun buildDefaultSystemPrompt(type: PromptType, existing: Prompt?): Prompt? {
        val displayName = systemPromptNames[type] ?: return null
        val fileName = promptTypeToName(type) ?: return null

        val projectDef = promptRegistry.getProjectFile(fileName)
        val userDef = promptRegistry.getUserFile(fileName)
        val builtin = promptRegistry.getBuiltin(fileName)
        val def = projectDef ?: userDef ?: builtin ?: return null

        val now = System.currentTimeMillis()
        return Prompt(
            id = existing?.id ?: "default:${type.name}",
            name = displayName,
            type = type,
            content = def.content,
            description = def.description.ifBlank { existing?.description ?: "Default system prompt" },
            isCustom = false,
            isEnabled = true,
            orderIndex = existing?.orderIndex ?: 0,
            createdAt = existing?.createdAt ?: now,
            updatedAt = existing?.updatedAt ?: now
        )
    }

    /**
     * Get all enabled rules (appended to prompts)
     */
    fun getEnabledRules(): List<Prompt> {
        return promptsRepository.findByType(PromptType.RULE, enabledOnly = true)
    }

    /**
     * Get all enabled slash prompts
     */
    fun getEnabledSlashPrompts(): List<Prompt> {
        return promptsRepository.findByType(PromptType.SLASH_PROMPT, enabledOnly = true)
    }

    /**
     * Find slash prompt by name (e.g., "/refactor")
     */
    fun findSlashPrompt(name: String): Prompt? {
        val normalizedName = if (name.startsWith("/")) name else "/$name"
        return promptsRepository.findByNameAndType(normalizedName, PromptType.SLASH_PROMPT)
    }

    /**
     * Create or update a rule
     */
    fun saveRule(
        id: String? = null,
        name: String,
        content: String,
        description: String? = null,
        isEnabled: Boolean = true
    ): Prompt {
        return if (id != null && promptsRepository.exists(id)) {
            promptsRepository.update(
                id = id,
                name = name,
                content = content,
                description = description,
                isEnabled = isEnabled
            )!!
        } else {
            promptsRepository.create(
                name = name,
                type = PromptType.RULE,
                content = content,
                description = description,
                isCustom = true,
                isEnabled = isEnabled
            )
        }
    }

    /**
     * Create or update a slash prompt
     */
    fun saveSlashPrompt(
        id: String? = null,
        name: String,
        content: String,
        description: String? = null,
        isEnabled: Boolean = true
    ): Prompt {
        val normalizedName = normalizeSlashPromptName(name)
        return if (id != null && promptsRepository.exists(id)) {
            promptsRepository.update(
                id = id,
                name = normalizedName,
                content = content,
                description = description,
                isEnabled = isEnabled,
                isCustom = true
            )!!
        } else {
            promptsRepository.create(
                name = normalizedName,
                type = PromptType.SLASH_PROMPT,
                content = content,
                description = description,
                isCustom = true,
                isEnabled = isEnabled
            )
        }
    }

    /**
     * Update system prompt content and mark as custom.
     * Creates a DB record with isCustom=true if it doesn't exist yet.
     */
    fun updateSystemPrompt(type: PromptType, content: String): Prompt? {
        if (!type.isSystemPrompt()) {
            logger.warn { "Attempt to update non-system prompt type: $type" }
            return null
        }

        val name = getSystemPromptName(type) ?: return null

        val existing = promptsRepository.findByNameAndType(name, type)
        return if (existing != null) {
            val updated = promptsRepository.update(
                id = existing.id,
                content = content,
                isCustom = true
            )
            logger.info { "Updated system prompt and marked as custom: $name" }
            updated
        } else {
            // Create new DB record for custom override
            val created = promptsRepository.create(
                name = name,
                type = type,
                content = content,
                description = "Custom system prompt",
                isCustom = true,
                isEnabled = true
            )
            logger.info { "Created custom system prompt override: $name" }
            created
        }
    }

    /**
     * Reset system prompt to default.
     * Removes any custom DB override so the prompt falls back to file-based layers.
     */
    fun resetSystemPromptToDefault(type: PromptType): Prompt? {
        if (!type.isSystemPrompt()) {
            logger.warn { "Attempt to reset non-system prompt type: $type" }
            return null
        }

        val name = getSystemPromptName(type) ?: return null

        val existing = promptsRepository.findByNameAndType(name, type)
        if (existing != null) {
            promptsRepository.delete(existing.id)
            logger.info { "Reset system prompt to default (removed DB override): $name" }
        }

        // Return a Prompt-like object with the default content for API compatibility
        val defaultContent = getDefaultPromptContent(type)
        return Prompt(
            id = existing?.id ?: "",
            name = name,
            type = type,
            content = defaultContent,
            description = "Default system prompt",
            isCustom = false,
            isEnabled = true,
            orderIndex = 0,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Delete rule or slash prompt by ID
     */
    fun delete(id: String): Boolean {
        return promptsRepository.delete(id)
    }

    /**
     * Get all prompts of given type
     */
    fun getAllByType(type: PromptType): List<Prompt> {
        return promptsRepository.findByType(type)
    }

    /**
     * Get prompt by ID
     */
    fun getById(id: String): Prompt? {
        return promptsRepository.findById(id)
    }

    /**
     * Get prompt by name (any type)
     * Used for retrieving specialized prompts like code editing prompts
     */
    fun getPrompt(name: String): String? {
        val prompt = PromptType.values().firstNotNullOfOrNull { type ->
            promptsRepository.findByNameAndType(name, type)
        }
        return prompt?.content
    }

    /**
     * Get default (builtin) content for system prompt type.
     * Used in UI to display default content alongside custom content.
     */
    fun getDefaultSystemPromptContent(type: PromptType): String {
        return getDefaultPromptContent(type)
    }

    // ============================================================================
    // PRIVATE HELPERS
    // ============================================================================

    /**
     * Resolve prompt content using 3-layer hierarchy:
     * 1. Project file (.refio/prompts/ *.md) - highest priority
     * 2. DB isCustom=true (UI edits) - wins over user files
     * 3. User file (~/.refio/prompts/ *.md)
     * 4. Builtin (resources/prompts/ *.md) - lowest priority
     */
    private fun resolvePromptContent(name: String, type: PromptType): String {
        // Layer: Project files (highest priority)
        promptRegistry.getProjectFile(name)?.let { return it.content }

        // Layer: DB isCustom=true (UI edits)
        val dbName = systemPromptNames[type]
        if (dbName != null) {
            val dbPrompt = promptsRepository.findByNameAndType(dbName, type)
            if (dbPrompt != null && dbPrompt.isCustom) {
                return dbPrompt.content
            }
        }

        // Layer: User files (~/.refio/prompts/ *.md)
        promptRegistry.getUserFile(name)?.let { return it.content }

        // Layer: Builtin (resources/prompts/ *.md) - lowest priority
        promptRegistry.getBuiltin(name)?.let { return it.content }

        logger.warn { "Prompt not found in any layer: $name" }
        return ""
    }

    private fun getDefaultPromptContent(type: PromptType): String {
        val name = promptTypeToName(type) ?: return ""
        return promptRegistry.getBuiltin(name)?.content ?: ""
    }

    private fun promptTypeToName(type: PromptType): String? = when (type) {
        PromptType.SYSTEM_CHAT -> "system-chat"
        PromptType.SYSTEM_PLAN -> "system-plan"
        PromptType.SYSTEM_AGENT -> "system-agent"
        PromptType.SYSTEM_STEP_PLANNER -> "system-step-planner"
        PromptType.SYSTEM_STEP_SUMMARIZER -> "system-step-summarizer"
        PromptType.SYSTEM_ORCHESTRATOR -> "system-orchestrator"
        PromptType.SYSTEM_EXECUTION_SUMMARY -> "system-execution-summary"
        PromptType.SYSTEM_CONVERSATION_SUMMARY -> "system-conversation-summary"
        PromptType.SYSTEM_INTENT_CLASSIFIER -> "system-intent-classifier"
        PromptType.SYSTEM_TOOL_SUMMARY -> "system-tool-summary"
        PromptType.CODE_EDITING_SYSTEM -> "code-editing-system"
        PromptType.CODE_EDITING_USER -> "code-editing-user"
        PromptType.MULTI_LINE_EDITING_SYSTEM -> "multi-line-editing-system"
        PromptType.MULTI_LINE_EDITING_USER -> "multi-line-editing-user"
        else -> null
    }

    private fun getSystemPromptName(type: PromptType): String? {
        return systemPromptNames[type]
    }

    private fun normalizeSlashPromptName(name: String): String {
        return if (name.startsWith("/")) name else "/$name"
    }

    /**
     * Remove stale non-custom system prompt records from DB.
     * These were previously seeded by initializeDefaults() but are now loaded from MD files.
     * Only isCustom=false records are removed - user customizations (isCustom=true) are preserved.
     */
    private fun cleanupNonCustomSystemPrompts() {
        val nonCustom = promptsRepository.findSystemPrompts(enabledOnly = false)
            .filter { !it.isCustom && it.type.isSystemPrompt() }
        for (prompt in nonCustom) {
            promptsRepository.delete(prompt.id)
            logger.info { "Removed non-custom system prompt from DB: ${prompt.name} (now loaded from files)" }
        }
    }

    private fun initializeBuiltinSlashPrompts() {
        SlashPrompt.BUILTINS.forEachIndexed { index, slashPrompt ->
            val normalizedName = normalizeSlashPromptName(slashPrompt.name)
            val existing = promptsRepository.findByNameAndType(normalizedName, PromptType.SLASH_PROMPT)

            if (existing == null) {
                promptsRepository.create(
                    name = normalizedName,
                    type = PromptType.SLASH_PROMPT,
                    content = slashPrompt.template,
                    description = slashPrompt.description,
                    isCustom = false,
                    isEnabled = true,
                    orderIndex = index
                )
                logger.info { "Created built-in slash prompt: ${slashPrompt.name}" }
            } else if (!existing.isCustom) {
                promptsRepository.update(
                    id = existing.id,
                    content = slashPrompt.template,
                    description = slashPrompt.description,
                    isEnabled = true,
                    orderIndex = index
                )
                logger.debug { "Updated built-in slash prompt: ${slashPrompt.name}" }
            } else {
                logger.debug { "Skipping built-in slash prompt update because user customized it: ${slashPrompt.name}" }
            }
        }
    }
}

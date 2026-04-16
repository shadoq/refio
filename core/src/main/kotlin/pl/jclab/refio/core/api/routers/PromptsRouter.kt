package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("PromptsRouter")

/**
 * Router for prompts management operations.
 * Handles system prompts, rules, and slash prompts.
 *
 * @property promptsService Prompts management service
 */
class PromptsRouter(
    private val promptsService: PromptsService
) : Router {

    override suspend fun initialize() {
        logger.info { "[PromptsRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[PromptsRouter] Shutting down" }
    }

    // ===== System Prompts =====

    /**
     * Get system prompt for given type with variable substitution.
     */
    fun getSystemPrompt(request: GetSystemPromptRequest): SystemPromptResponse {
        logger.info { "[PromptsRouter] Getting system prompt: type=${request.type}" }
        val content = promptsService.getSystemPrompt(request.type, request.variables)
        return SystemPromptResponse(
            type = request.type.name,
            content = content
        )
    }

    /**
     * Get all prompts of given type.
     */
    fun getPromptsByType(type: PromptType): PromptsListResponse {
        logger.info { "[PromptsRouter] Getting prompts by type: $type" }
        val prompts = promptsService.getAllByType(type)
        return PromptsListResponse(
            prompts = prompts.map { it.toDto() },
            count = prompts.size
        )
    }

    /**
     * Get all system prompts (all system PromptType values).
     */
    fun getSystemPrompts(): PromptsListResponse {
        logger.info { "[PromptsRouter] Getting system prompts" }
        val prompts = promptsService.getSystemPrompts()
        return PromptsListResponse(
            prompts = prompts.map { it.toDto() },
            count = prompts.size
        )
    }

    /**
     * Update system prompt content.
     */
    fun updateSystemPrompt(request: UpdateSystemPromptRequest): PromptResponse? {
        logger.info { "[PromptsRouter] Updating system prompt: type=${request.type}" }
        val prompt = promptsService.updateSystemPrompt(request.type, request.content)
        return prompt?.let { PromptResponse(prompt = it.toDto()) }
    }

    /**
     * Reset system prompt to default.
     */
    fun resetSystemPromptToDefault(type: PromptType): PromptResponse? {
        logger.info { "[PromptsRouter] Resetting system prompt to default: type=$type" }
        val prompt = promptsService.resetSystemPromptToDefault(type)
        return prompt?.let { PromptResponse(prompt = it.toDto()) }
    }

    /**
     * Get default (hardcoded) content for system prompt type.
     */
    fun getDefaultSystemPromptContent(type: PromptType): String {
        logger.info { "[PromptsRouter] Getting default system prompt content: type=$type" }
        return promptsService.getDefaultSystemPromptContent(type)
    }

    // ===== Rules =====

    /**
     * Get all enabled rules.
     */
    fun getEnabledRules(): PromptsListResponse {
        logger.info { "[PromptsRouter] Getting enabled rules" }
        val rules = promptsService.getEnabledRules()
        return PromptsListResponse(
            prompts = rules.map { it.toDto() },
            count = rules.size
        )
    }

    /**
     * Save (create or update) a rule.
     */
    fun saveRule(request: SaveRuleRequest): PromptResponse {
        logger.info { "[PromptsRouter] Saving rule: id=${request.id}, name=${request.name}" }
        val rule = promptsService.saveRule(
            id = request.id,
            name = request.name,
            content = request.content,
            description = request.description,
            isEnabled = request.isEnabled
        )
        return PromptResponse(prompt = rule.toDto())
    }

    // ===== Slash Prompts =====

    /**
     * Get all enabled slash prompts.
     */
    fun getEnabledSlashPrompts(): PromptsListResponse {
        logger.info { "[PromptsRouter] Getting enabled slash prompts" }
        val slashPrompts = promptsService.getEnabledSlashPrompts()
        return PromptsListResponse(
            prompts = slashPrompts.map { it.toDto() },
            count = slashPrompts.size
        )
    }

    /**
     * Find slash prompt by name.
     */
    fun findSlashPrompt(name: String): PromptResponse? {
        logger.info { "[PromptsRouter] Finding slash prompt: $name" }
        val slashPrompt = promptsService.findSlashPrompt(name)
        return slashPrompt?.let { PromptResponse(prompt = it.toDto()) }
    }

    /**
     * Save (create or update) a slash prompt.
     */
    fun saveSlashPrompt(request: SaveSlashPromptRequest): PromptResponse {
        logger.info { "[PromptsRouter] Saving slash prompt: id=${request.id}, name=${request.name}" }
        val slashPrompt = promptsService.saveSlashPrompt(
            id = request.id,
            name = request.name,
            content = request.content,
            description = request.description,
            isEnabled = request.isEnabled
        )
        return PromptResponse(prompt = slashPrompt.toDto())
    }

    // ===== General Operations =====

    /**
     * Delete rule or slash prompt by ID.
     */
    fun deletePrompt(id: String): DeletePromptResponse {
        logger.info { "[PromptsRouter] Deleting prompt: id=$id" }
        val success = promptsService.delete(id)
        return DeletePromptResponse(success = success, id = id)
    }

    /**
     * Get prompt by ID.
     */
    fun getPromptById(id: String): PromptResponse? {
        logger.info { "[PromptsRouter] Getting prompt by id: $id" }
        val prompt = promptsService.getById(id)
        return prompt?.let { PromptResponse(prompt = it.toDto()) }
    }

    // ===== Helper Functions =====

    /**
     * Convert Prompt entity to PromptDto.
     */
    private fun pl.jclab.refio.core.db.Prompt.toDto(): PromptDto {
        return PromptDto(
            id = this.id,
            name = this.name,
            type = this.type.name,
            content = this.content,
            description = this.description,
            isCustom = this.isCustom,
            isEnabled = this.isEnabled,
            orderIndex = this.orderIndex,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt
        )
    }
}

package pl.jclab.refio.api

import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.models.api.SetToolPermissionRequest
import pl.jclab.refio.core.tools.security.CommandWhitelistConfig
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("CoreApiClient")

/**
 * CoreApiClient - Thin wrapper around CoreApiRouter
 *
 * Provides simple pass-through API to CoreApiRouter for in-process calls (no HTTP).
 *
 * According to CLAUDE.md architecture:
 * Plugin → CoreApiClient → CoreApiRouter → Services → Database
 *
 * IMPORTANT: Plugin code should ALWAYS use CoreApiClient, NEVER call
 * services directly.
 *
 * Note: This client uses types from pl.jclab.refio.core.api.*
 */
class CoreApiClient(internal val router: CoreApiRouter) {

    // ========================================================================
    // Task Management
    // ========================================================================

    fun createTask(request: CreateTaskRequest): TaskResponse {
        logger.info { "[CoreApiClient] Creating task" }
        return router.createTask(request)
    }

    fun listTasks(): ListTasksResponse {
        logger.info { "[CoreApiClient] Listing tasks" }
        return router.listTasks()
    }

    fun getTasksForProject(projectId: String): List<TaskResponse> {
        logger.info { "[CoreApiClient] Listing tasks for project $projectId" }
        return router.getTasksForProject(projectId)
    }

    fun getLastSessionForProject(projectId: String): TaskResponse? {
        logger.info { "[CoreApiClient] Getting last session for project $projectId" }
        return router.getLastSessionForProject(projectId)
    }

    fun getTask(taskId: String): TaskResponse? {
        logger.info { "[CoreApiClient] Getting task: $taskId" }
        return router.getTask(taskId)
    }

    fun updateTask(taskId: String, request: UpdateTaskRequest): TaskResponse {
        logger.info { "[CoreApiClient] Updating task: $taskId" }
        return router.updateTask(taskId, request)
    }

    fun deleteTask(taskId: String): Boolean {
        logger.info { "[CoreApiClient] Deleting task: $taskId" }
        return router.deleteTask(taskId)
    }

    // ========================================================================
    // Messages
    // ========================================================================

    fun getMessages(taskId: String): GetMessagesResponse {
        logger.info { "[CoreApiClient] Getting messages for task: $taskId" }
        return router.getMessages(taskId)
    }

    // ========================================================================
    // Subtasks
    // ========================================================================

    fun getSubtasks(taskId: String): GetSubtasksResponse {
        logger.info { "[CoreApiClient] Getting subtasks for task: $taskId" }
        return router.getSubtasks(taskId)
    }

    fun getSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[CoreApiClient] Getting subtask: task=$taskId, subtask=$subtaskId" }
        return router.getSubtask(taskId, subtaskId)
    }

    fun updateSubtask(taskId: String, subtaskId: String, request: UpdateSubtaskRequest): SubtaskResponse {
        logger.info { "[CoreApiClient] Updating subtask: task=$taskId, subtask=$subtaskId" }
        return router.updateSubtask(taskId, subtaskId, request)
    }

    fun approveSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[CoreApiClient] Approving subtask: task=$taskId, subtask=$subtaskId" }
        return router.approveSubtask(taskId, subtaskId)
    }

    fun rejectSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[CoreApiClient] Rejecting subtask: task=$taskId, subtask=$subtaskId" }
        return router.rejectSubtask(taskId, subtaskId)
    }

    fun deletePendingSubtasks(taskId: String): DeleteSubtasksResponse {
        logger.info { "[CoreApiClient] Deleting pending subtasks: task=$taskId" }
        return router.deletePendingSubtasks(taskId)
    }

    // ========================================================================
    // Step Workflow (prepare/execute) - Aliases for planSubtaskStep/executeSubtaskStep
    // ========================================================================

    /**
     * Prepare step - plans the step execution
     * (Alias for planSubtaskStep for compatibility with old API)
     */
    suspend fun prepareStep(taskId: String, subtaskId: String): PlanStepResponse {
        logger.info { "[CoreApiClient] Preparing step: task=$taskId, subtask=$subtaskId" }
        return router.planSubtaskStep(taskId, subtaskId)
    }

    /**
     * Execute step - executes the prepared step
     * (Alias for executeSubtaskStep for compatibility with old API)
     */
    suspend fun executeStep(taskId: String, subtaskId: String): ExecuteStepResponse {
        logger.info { "[CoreApiClient] Executing step: task=$taskId, subtask=$subtaskId" }
        return router.executeSubtaskStep(taskId, subtaskId)
    }

    // ========================================================================
    // Chat
    // ========================================================================

    /**
     * Send chat message (RFC 0032: unified callback-based streaming).
     *
     * @param request Chat request
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return Chat response (always returned, regardless of streaming mode)
     */
    suspend fun chat(
        request: ChatRequest,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ChatResponse {
        logger.info { "[CoreApiClient] Sending chat: taskId=${request.taskId}, stream=$stream" }
        return router.chat(request, stream, onChunk)
    }

    // ========================================================================
    // Health & Models
    // ========================================================================

    fun health(): HealthResponse {
        return router.health()
    }

    suspend fun getModels(provider: String? = null): GetModelsResponse {
        return router.getModels(provider)
    }

    /**
     * Get models with visibility settings applied
     *
     * @param provider Optional provider filter
     * @return List of ModelInfo with visibility settings
     */
    suspend fun getModelsWithVisibility(provider: String? = null): List<ModelInfo> {
        logger.info { "[CoreApiClient] Getting models with visibility" }
        return router.getModelsWithVisibility(provider)
    }

    suspend fun getDefaultModel(operation: ModelOperation, taskId: String? = null): GetDefaultModelResponse {
        return router.getDefaultModel(operation, taskId)
    }

    suspend fun setDefaultModel(request: SetDefaultModelRequest, taskId: String? = null): SetDefaultModelResponse {
        return router.setDefaultModel(request, taskId)
    }

    // ========================================================================
    // Prompts Management
    // ========================================================================

    fun getSystemPrompt(request: GetSystemPromptRequest): SystemPromptResponse {
        logger.info { "[CoreApiClient] Getting system prompt: type=${request.type}" }
        return router.getSystemPrompt(request)
    }

    fun getPromptsByType(type: pl.jclab.refio.core.db.PromptType): PromptsListResponse {
        logger.info { "[CoreApiClient] Getting prompts by type: $type" }
        return router.getPromptsByType(type)
    }

    fun getSystemPrompts(): PromptsListResponse {
        logger.info { "[CoreApiClient] Getting system prompts" }
        return router.getSystemPrompts()
    }

    fun getEnabledRules(): PromptsListResponse {
        logger.info { "[CoreApiClient] Getting enabled rules" }
        return router.getEnabledRules()
    }

    fun getEnabledCommands(): PromptsListResponse {
        logger.info { "[CoreApiClient] Getting enabled commands" }
        return router.getEnabledCommands()
    }

    fun findCommand(commandName: String): PromptResponse? {
        logger.info { "[CoreApiClient] Finding command: $commandName" }
        return router.findCommand(commandName)
    }

    fun saveRule(request: SaveRuleRequest): PromptResponse {
        logger.info { "[CoreApiClient] Saving rule: ${request.name}" }
        return router.saveRule(request)
    }

    fun saveCommand(request: SaveCommandRequest): PromptResponse {
        logger.info { "[CoreApiClient] Saving command: ${request.name}" }
        return router.saveCommand(request)
    }

    fun updateSystemPrompt(request: UpdateSystemPromptRequest): PromptResponse? {
        logger.info { "[CoreApiClient] Updating system prompt: ${request.type}" }
        return router.updateSystemPrompt(request)
    }

    fun resetSystemPromptToDefault(type: pl.jclab.refio.core.db.PromptType): PromptResponse? {
        logger.info { "[CoreApiClient] Resetting system prompt to default: $type" }
        return router.resetSystemPromptToDefault(type)
    }

    fun deletePrompt(id: String): DeletePromptResponse {
        logger.info { "[CoreApiClient] Deleting prompt: $id" }
        return router.deletePrompt(id)
    }

    fun getPromptById(id: String): PromptResponse? {
        logger.info { "[CoreApiClient] Getting prompt by id: $id" }
        return router.getPromptById(id)
    }

    fun getDefaultSystemPromptContent(type: pl.jclab.refio.core.db.PromptType): String {
        logger.info { "[CoreApiClient] Getting default system prompt content: $type" }
        return router.getDefaultSystemPromptContent(type)
    }

    // ========================================================================
    // Configuration Management
    // ========================================================================

    /**
     * Update configuration setting
     *
     * @param section Configuration section (e.g., "general", "providers", "models")
     * @param scope Scope of configuration ("app", "task", "project")
     * @param taskId Optional task ID for task-scoped config
     * @param settings Map of setting key-value pairs to update
     * @return Update confirmation response
     */
    fun updateConfig(
        section: String,
        scope: String,
        taskId: String?,
        settings: Map<String, Any>
    ): UpdateConfigResponse {
        logger.info { "[CoreApiClient] Updating config: section=$section, scope=$scope" }
        return router.updateConfig(section, scope, taskId, settings)
    }

    /**
     * Reset all settings to defaults
     *
     * Resets all configuration settings across all sections to their default values.
     *
     * @return Reset confirmation response
     */
    fun resetAllSettingsToDefaults(): ResetConfigResponse {
        logger.info { "[CoreApiClient] Resetting all settings to defaults" }
        return router.resetAllSettingsToDefaults()
    }

    /**
     * Get configuration for a section
     *
     * @param section Configuration section (e.g., "general", "providers", "models")
     * @param scope Scope of configuration ("app", "task", "project")
     * @return Configuration settings for the section
     */
    fun getConfig(section: String, scope: String): GetConfigResponse {
        logger.info { "[CoreApiClient] Getting config: section=$section, scope=$scope" }
        return router.getConfig(section, scope)
    }

    /**
     * Get specific configuration value
     *
     * @param section Configuration section
     * @param key Configuration key
     * @return Configuration value or null if not found
     */
    fun getConfigValue(section: String, key: String): String? {
        logger.info { "[CoreApiClient] Getting config value: section=$section, key=$key" }
        return try {
            val configService = router.getConfigService()
            val fullKey = "$section.$key"
            configService.get(fullKey, pl.jclab.refio.core.db.ConfigScope.APP, null)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get config: $section.$key" }
            null
        }
    }

    /**
     * Set specific configuration value
     *
     * @param section Configuration section
     * @param key Configuration key
     * @param value Configuration value
     */
    fun setConfigValue(section: String, key: String, value: String) {
        logger.info { "[CoreApiClient] Setting config: section=$section, key=$key" }
        try {
            val configService = router.getConfigService()
            val fullKey = "$section.$key"
            configService.set(fullKey, value, pl.jclab.refio.core.db.ConfigScope.APP, null)
            logger.info { "Config saved: $section.$key = $value" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to set config: $section.$key" }
            throw e
        }
    }

    // ========================================================================
    // Provider Management
    // ========================================================================

    /**
     * Test connection to LLM provider
     *
     * @param provider Provider name (ollama, anthropic, openai, openrouter)
     * @param config Provider configuration (api_key, base_url, etc.)
     * @return Test result with success status and details
     */
    suspend fun testProviderConnection(
        provider: String,
        config: Map<String, String>
    ): TestConnectionResult {
        logger.info { "[CoreApiClient] Testing connection to provider: $provider" }
        return router.testProviderConnection(provider, config)
    }

    /**
     * Refresh list of available models for a provider
     *
     * @param provider Provider name (ollama, anthropic, openai, openrouter)
     * @return List of available models with details
     */
    suspend fun refreshProviderModels(provider: String): List<ModelInfo> {
        logger.info { "[CoreApiClient] Refreshing models for provider: $provider" }
        return router.refreshProviderModels(provider)
    }

    /**
     * Refresh list of available models for all providers
     *
     * @return List of available models with details from all providers
     */
    suspend fun refreshAllModels(): List<ModelInfo> {
        logger.info { "[CoreApiClient] Refreshing models for all providers" }
        return router.refreshAllModels()
    }

    /**
     * Update model visibility (show in dropdown)
     *
     * @param modelId Model ID to update
     * @param showInDropdown Whether to show model in dropdown
     */
    suspend fun updateModelVisibility(modelId: String, showInDropdown: Boolean) {
        logger.info { "[CoreApiClient] Updating model visibility: $modelId -> $showInDropdown" }
        router.updateModelVisibility(modelId, showInDropdown)
    }

    /**
     * Update visibility for all models in one operation.
     *
     * @param visibilityMap Map of modelId to showInDropdown setting
     */
    suspend fun updateModelsVisibility(visibilityMap: Map<String, Boolean>) {
        logger.info { "[CoreApiClient] Updating visibility for ${visibilityMap.size} models" }
        router.updateModelsVisibility(visibilityMap)
    }

    // ========================================================================
    // Tool Permissions
    // ========================================================================

    /**
     * Get permissions for all tools
     *
     * @param taskId Optional task ID for task-level permissions
     * @return Map of tool name to (planMode, agentMode) pairs
     */
    suspend fun getToolPermissions(taskId: String? = null): Map<String, Pair<String, String>> {
        logger.info { "[CoreApiClient] Getting tool permissions" }
        val response = router.getToolPermissions(taskId)
        return response.tools.associate { tool ->
            tool.toolName to (tool.planMode to tool.agentMode)
        }
    }

    suspend fun getAvailableToolDefinitions(): List<ToolDefinitionInfo> {
        logger.info { "[CoreApiClient] Getting available tool definitions" }
        return router.getAvailableToolDefinitions()
    }

    /**
     * Set permission for a specific tool
     *
     * @param toolName Name of the tool
     * @param planMode Permission level for PLAN mode (ASK/ON/OFF)
     * @param agentMode Permission level for AGENT mode (ASK/ON/OFF)
     * @param taskId Optional task ID for task-level permissions
     */
    suspend fun setToolPermission(
        toolName: String,
        planMode: String,
        agentMode: String,
        taskId: String? = null
    ) {
        logger.info { "[CoreApiClient] Setting tool permission: $toolName -> plan=$planMode, agent=$agentMode" }
        val request = SetToolPermissionRequest(
            planMode = planMode,
            agentMode = agentMode
        )
        router.setToolPermission(toolName, request, taskId)
    }

    /**
     * Reset tool permissions to smart defaults
     *
     * @param taskId Optional task ID for task-level permissions
     */
    suspend fun resetToolPermissions(taskId: String? = null) {
        logger.info { "[CoreApiClient] Resetting tool permissions to defaults" }
        router.resetToolPermissions(taskId)
    }

    fun getTerminalWhitelistConfig(): CommandWhitelistConfig {
        logger.info { "[CoreApiClient] Getting terminal whitelist config" }
        return router.getConfigService().getTerminalWhitelistConfig()
    }

    fun setTerminalWhitelistConfig(
        config: CommandWhitelistConfig,
        scope: String = "app"
    ) {
        logger.info { "[CoreApiClient] Setting terminal whitelist config: scope=$scope" }
        val configScope = when (scope.lowercase()) {
            "project" -> ConfigScope.PROJECT
            else -> ConfigScope.APP
        }
        router.getConfigService().setTerminalWhitelistConfig(config, configScope)
    }

    // ========================================================================
    // Subagents
    // ========================================================================

    /**
     * List all available subagents
     *
     * @param includeDisabled Include disabled subagents (for admin panel)
     * @return List of subagent info for UI display
     */
    fun listSubagents(includeDisabled: Boolean = false): List<pl.jclab.refio.core.subagents.models.SubagentInfo> {
        logger.info { "[CoreApiClient] Listing subagents (includeDisabled=$includeDisabled)" }
        return router.subagentRouter?.listSubagents(includeDisabled) ?: emptyList()
    }

    /**
     * Get subagent details
     *
     * @param name Subagent name
     * @return Subagent definition or null if not found
     */
    fun getSubagent(name: String): pl.jclab.refio.core.subagents.models.SubagentDefinition? {
        logger.info { "[CoreApiClient] Getting subagent: $name" }
        return router.subagentRouter?.getSubagent(name)
    }

    /**
     * Create a new subagent
     *
     * @param name Subagent name
     * @param description Description
     * @param systemPrompt System prompt content
     * @param allowedTools List of allowed tools (null = inherit)
     * @param model Model to use (default, plan, coding, weak, or specific model)
     * @param scope Where to save (PROJECT or USER)
     * @param enabled Whether subagent is enabled
     * @param priority Priority for auto-delegation
     * @return Created subagent definition
     */
    fun createSubagent(
        name: String,
        description: String,
        systemPrompt: String,
        allowedTools: List<String>? = null,
        model: String = "default",
        scope: pl.jclab.refio.core.subagents.models.SubagentScope = pl.jclab.refio.core.subagents.models.SubagentScope.PROJECT,
        enabled: Boolean = true,
        priority: Int = 0
    ): pl.jclab.refio.core.subagents.models.SubagentDefinition {
        logger.info { "[CoreApiClient] Creating subagent: $name" }
        return router.subagentRouter?.createSubagent(
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            allowedTools = allowedTools,
            model = model,
            scope = scope,
            enabled = enabled,
            priority = priority
        ) ?: throw IllegalStateException("SubagentRouter not available")
    }

    /**
     * Update existing subagent
     *
     * @param name Subagent name
     * @param description New description (null = no change)
     * @param systemPrompt New system prompt (null = no change)
     * @param allowedTools New tools list (null = no change)
     * @param model New model (null = no change)
     * @param enabled New enabled status (null = no change)
     * @param priority New priority (null = no change)
     * @return Updated subagent definition
     */
    fun updateSubagent(
        name: String,
        description: String? = null,
        systemPrompt: String? = null,
        allowedTools: List<String>? = null,
        model: String? = null,
        enabled: Boolean? = null,
        priority: Int? = null
    ): pl.jclab.refio.core.subagents.models.SubagentDefinition {
        logger.info { "[CoreApiClient] Updating subagent: $name" }
        return router.subagentRouter?.updateSubagent(
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            allowedTools = allowedTools,
            model = model,
            enabled = enabled,
            priority = priority
        ) ?: throw IllegalStateException("SubagentRouter not available")
    }

    /**
     * Delete a subagent
     *
     * @param name Subagent name
     * @return true if deleted
     */
    fun deleteSubagent(name: String): Boolean {
        logger.info { "[CoreApiClient] Deleting subagent: $name" }
        return router.subagentRouter?.deleteSubagent(name) ?: false
    }

    /**
     * Refresh subagent cache
     */
    fun refreshSubagents() {
        logger.info { "[CoreApiClient] Refreshing subagents" }
        router.subagentRouter?.refresh()
    }
}

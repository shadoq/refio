package pl.jclab.refio.api

import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.config.ModelPresetConfig
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.models.api.SetToolPermissionRequest
import pl.jclab.refio.core.tools.security.CommandWhitelistConfig
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("CoreApiClient")

/**
 * CoreApiClient - Thin wrapper delegating to domain routers.
 *
 * Plugin → CoreApiClient → Domain Routers → Services → Database
 *
 * IMPORTANT: Plugin code should ALWAYS use CoreApiClient, NEVER call
 * services directly.
 */
class CoreApiClient(internal val router: CoreApiRouter) {

    // ========================================================================
    // Task Management (via taskRouter)
    // ========================================================================

    fun createTask(request: CreateTaskRequest): TaskResponse {
        logger.info { "[CoreApiClient] Creating task" }
        return router.taskRouter.createTask(request)
    }

    fun listTasks(): ListTasksResponse {
        logger.info { "[CoreApiClient] Listing tasks" }
        return router.taskRouter.listTasks()
    }

    fun getTasksForProject(projectId: String): List<TaskResponse> {
        logger.info { "[CoreApiClient] Listing tasks for project $projectId" }
        return router.taskRouter.getTasksForProject(projectId)
    }

    fun getLastSessionForProject(projectId: String): TaskResponse? {
        logger.info { "[CoreApiClient] Getting last session for project $projectId" }
        return router.taskRouter.getLastSessionForProject(projectId)
    }

    fun getTask(taskId: String): TaskResponse? {
        logger.info { "[CoreApiClient] Getting task: $taskId" }
        return router.taskRouter.getTask(taskId)
    }

    fun updateTask(taskId: String, request: UpdateTaskRequest): TaskResponse {
        logger.info { "[CoreApiClient] Updating task: $taskId" }
        return router.taskRouter.updateTask(taskId, request)
    }

    fun deleteTask(taskId: String): Boolean {
        logger.info { "[CoreApiClient] Deleting task: $taskId" }
        return router.taskRouter.deleteTask(taskId)
    }

    // ========================================================================
    // Messages (via chatRouter)
    // ========================================================================

    fun getMessages(taskId: String): GetMessagesResponse {
        logger.info { "[CoreApiClient] Getting messages for task: $taskId" }
        return router.chatRouter.getMessages(taskId)
    }

    // ========================================================================
    // Subtasks (via subtaskRouter)
    // ========================================================================

    fun getSubtasks(taskId: String): GetSubtasksResponse {
        logger.info { "[CoreApiClient] Getting subtasks for task: $taskId" }
        return router.subtaskRouter.getSubtasks(taskId)
    }

    fun getSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[CoreApiClient] Getting subtask: task=$taskId, subtask=$subtaskId" }
        return router.subtaskRouter.getSubtask(taskId, subtaskId)
    }

    fun updateSubtask(taskId: String, subtaskId: String, request: UpdateSubtaskRequest): SubtaskResponse {
        logger.info { "[CoreApiClient] Updating subtask: task=$taskId, subtask=$subtaskId" }
        return router.subtaskRouter.updateSubtask(taskId, subtaskId, request)
    }

    fun approveSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[CoreApiClient] Approving subtask: task=$taskId, subtask=$subtaskId" }
        return router.subtaskRouter.approveSubtask(taskId, subtaskId)
    }

    fun rejectSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[CoreApiClient] Rejecting subtask: task=$taskId, subtask=$subtaskId" }
        return router.subtaskRouter.rejectSubtask(taskId, subtaskId)
    }

    fun deletePendingSubtasks(taskId: String): DeleteSubtasksResponse {
        logger.info { "[CoreApiClient] Deleting pending subtasks: task=$taskId" }
        val result = router.subtaskRouter.deletePendingSubtasks(taskId)
        return DeleteSubtasksResponse(
            deletedCount = result.deletedCount,
            message = "Successfully deleted ${result.deletedCount} pending/planned subtasks"
        )
    }

    // ========================================================================
    // Step Workflow (via agentRouter)
    // ========================================================================

    suspend fun prepareStep(taskId: String, subtaskId: String): PlanStepResponse {
        logger.info { "[CoreApiClient] Preparing step: task=$taskId, subtask=$subtaskId" }
        return router.agentRouter.planSubtaskStep(taskId, subtaskId)
    }

    suspend fun executeStep(taskId: String, subtaskId: String): ExecuteStepResponse {
        logger.info { "[CoreApiClient] Executing step: task=$taskId, subtask=$subtaskId" }
        return router.agentRouter.executeSubtaskStep(taskId, subtaskId)
    }

    // ========================================================================
    // Chat (via chatRouter)
    // ========================================================================

    suspend fun chat(
        request: ChatRequest,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ChatResponse {
        logger.info { "[CoreApiClient] Sending chat: taskId=${request.taskId}, stream=$stream" }
        return router.chatRouter.chat(request, stream, onChunk)
    }

    // ========================================================================
    // Health & Models (via taskRouter / configRouter)
    // ========================================================================

    fun health(): HealthResponse {
        return router.taskRouter.health()
    }

    suspend fun getModels(provider: String? = null): GetModelsResponse {
        return router.configRouter.getModels(provider)
    }

    suspend fun getModelsWithVisibility(provider: String? = null): List<ModelInfo> {
        logger.info { "[CoreApiClient] Getting models with visibility" }
        return router.configRouter.getModelsWithVisibility(provider)
    }

    suspend fun getDefaultModel(operation: ModelOperation, taskId: String? = null): GetDefaultModelResponse {
        return router.configRouter.getDefaultModel(operation, taskId)
    }

    suspend fun setDefaultModel(request: SetDefaultModelRequest, taskId: String? = null): SetDefaultModelResponse {
        return router.configRouter.setDefaultModel(request, taskId)
    }

    // ========================================================================
    // Prompts Management (via promptsRouter)
    // ========================================================================

    fun getSystemPrompt(request: GetSystemPromptRequest): SystemPromptResponse {
        logger.info { "[CoreApiClient] Getting system prompt: type=${request.type}" }
        return router.promptsRouter.getSystemPrompt(request)
    }

    fun getPromptsByType(type: pl.jclab.refio.core.db.PromptType): PromptsListResponse {
        logger.info { "[CoreApiClient] Getting prompts by type: $type" }
        return router.promptsRouter.getPromptsByType(type)
    }

    fun getSystemPrompts(): PromptsListResponse {
        return router.promptsRouter.getSystemPrompts()
    }

    fun getEnabledRules(): PromptsListResponse {
        return router.promptsRouter.getEnabledRules()
    }

    fun getEnabledCommands(): PromptsListResponse {
        return router.promptsRouter.getEnabledCommands()
    }

    fun findCommand(commandName: String): PromptResponse? {
        return router.promptsRouter.findCommand(commandName)
    }

    fun saveRule(request: SaveRuleRequest): PromptResponse {
        logger.info { "[CoreApiClient] Saving rule: ${request.name}" }
        return router.promptsRouter.saveRule(request)
    }

    fun saveCommand(request: SaveCommandRequest): PromptResponse {
        logger.info { "[CoreApiClient] Saving command: ${request.name}" }
        return router.promptsRouter.saveCommand(request)
    }

    fun updateSystemPrompt(request: UpdateSystemPromptRequest): PromptResponse? {
        logger.info { "[CoreApiClient] Updating system prompt: ${request.type}" }
        return router.promptsRouter.updateSystemPrompt(request)
    }

    fun resetSystemPromptToDefault(type: pl.jclab.refio.core.db.PromptType): PromptResponse? {
        return router.promptsRouter.resetSystemPromptToDefault(type)
    }

    fun deletePrompt(id: String): DeletePromptResponse {
        return router.promptsRouter.deletePrompt(id)
    }

    fun getPromptById(id: String): PromptResponse? {
        return router.promptsRouter.getPromptById(id)
    }

    fun getDefaultSystemPromptContent(type: pl.jclab.refio.core.db.PromptType): String {
        return router.promptsRouter.getDefaultSystemPromptContent(type)
    }

    // ========================================================================
    // Configuration Management (via configRouter)
    // ========================================================================

    fun updateConfig(section: String, scope: String, taskId: String?, settings: Map<String, Any>): UpdateConfigResponse {
        logger.info { "[CoreApiClient] Updating config: section=$section, scope=$scope" }
        return router.configRouter.updateConfig(section, scope, taskId, settings)
    }

    fun resetAllSettingsToDefaults(): ResetConfigResponse {
        logger.info { "[CoreApiClient] Resetting all settings to defaults" }
        return router.configRouter.resetAllSettingsToDefaults()
    }

    fun getConfig(section: String, scope: String): GetConfigResponse {
        return router.configRouter.getConfig(section, scope)
    }

    fun getConfigValue(section: String, key: String): String? {
        return try {
            val fullKey = "$section.$key"
            router.configService.get(fullKey, ConfigScope.APP, null)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get config: $section.$key" }
            null
        }
    }

    fun setConfigValue(section: String, key: String, value: String) {
        try {
            val fullKey = "$section.$key"
            router.configService.set(fullKey, value, ConfigScope.APP, null)
        } catch (e: Exception) {
            logger.error(e) { "Failed to set config: $section.$key" }
            throw e
        }
    }

    fun getYamlModelPresets(): List<ModelPresetConfig> {
        return router.configService.getYamlConfig().models?.presets ?: emptyList()
    }

    // ========================================================================
    // Provider Management (via configRouter)
    // ========================================================================

    suspend fun testProviderConnection(provider: String, config: Map<String, String>): TestConnectionResult {
        logger.info { "[CoreApiClient] Testing connection to provider: $provider" }
        return router.configRouter.testProviderConnection(provider, config)
    }

    suspend fun refreshProviderModels(provider: String): List<ModelInfo> {
        return router.configRouter.refreshProviderModels(provider)
    }

    suspend fun refreshAllModels(): List<ModelInfo> {
        return router.configRouter.refreshAllModels()
    }

    suspend fun updateModelVisibility(modelId: String, showInDropdown: Boolean) {
        router.configRouter.updateModelVisibility(modelId, showInDropdown)
    }

    suspend fun updateModelsVisibility(visibilityMap: Map<String, Boolean>) {
        router.configRouter.updateModelsVisibility(visibilityMap)
    }

    // ========================================================================
    // Tool Permissions (via toolRouter)
    // ========================================================================

    suspend fun getToolPermissions(taskId: String? = null): Map<String, Pair<String, String>> {
        val response = router.toolRouter.getToolPermissions(taskId)
        return response.tools.associate { tool ->
            tool.toolName to (tool.planMode to tool.agentMode)
        }
    }

    suspend fun getAvailableToolDefinitions(): List<ToolDefinitionInfo> {
        return router.toolRouter.getAvailableToolDefinitions()
    }

    suspend fun setToolPermission(toolName: String, planMode: String, agentMode: String, taskId: String? = null) {
        val request = SetToolPermissionRequest(planMode = planMode, agentMode = agentMode)
        router.toolRouter.setToolPermission(toolName, request, taskId)
    }

    suspend fun resetToolPermissions(taskId: String? = null) {
        router.toolRouter.resetToolPermissions(taskId)
    }

    fun getTerminalWhitelistConfig(): CommandWhitelistConfig {
        return router.configService.getTerminalWhitelistConfig()
    }

    fun setTerminalWhitelistConfig(config: CommandWhitelistConfig, scope: String = "app") {
        val configScope = when (scope.lowercase()) {
            "project" -> ConfigScope.PROJECT
            else -> ConfigScope.APP
        }
        router.configService.setTerminalWhitelistConfig(config, configScope)
    }

    // ========================================================================
    // Subagents (via subagentRouter)
    // ========================================================================

    fun listSubagents(includeDisabled: Boolean = false): List<pl.jclab.refio.core.subagents.models.SubagentInfo> {
        return router.subagentRouter?.listSubagents(includeDisabled) ?: emptyList()
    }

    fun getSubagent(name: String): pl.jclab.refio.core.subagents.models.SubagentDefinition? {
        return router.subagentRouter?.getSubagent(name)
    }

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
        return router.subagentRouter?.createSubagent(
            name = name, description = description, systemPrompt = systemPrompt,
            allowedTools = allowedTools, model = model, scope = scope,
            enabled = enabled, priority = priority
        ) ?: throw IllegalStateException("SubagentRouter not available")
    }

    fun updateSubagent(
        name: String,
        description: String? = null,
        systemPrompt: String? = null,
        allowedTools: List<String>? = null,
        model: String? = null,
        enabled: Boolean? = null,
        priority: Int? = null
    ): pl.jclab.refio.core.subagents.models.SubagentDefinition {
        return router.subagentRouter?.updateSubagent(
            name = name, description = description, systemPrompt = systemPrompt,
            allowedTools = allowedTools, model = model, enabled = enabled, priority = priority
        ) ?: throw IllegalStateException("SubagentRouter not available")
    }

    fun deleteSubagent(name: String): Boolean {
        return router.subagentRouter?.deleteSubagent(name) ?: false
    }

    fun refreshSubagents() {
        router.subagentRouter?.refresh()
    }
}

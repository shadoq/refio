package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.subagents.SubagentAlreadyExistsException
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.subagents.SubagentToolFilter
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.subagents.models.SubagentExecutionMode
import pl.jclab.refio.core.subagents.models.SubagentScope
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

/**
 * Tool for dynamically creating, updating, and deleting subagent definitions at runtime.
 *
 * Supports two scopes:
 * - temporary: in-memory only, session lifetime, no file I/O
 * - project: saved to .refio/agents/<name>.md, persists across sessions
 */
class ManageSubagentTool(
    private val subagentRouterProvider: () -> SubagentRouter?
) : Tool {
    override val name = "manage_subagent"
    override val description = """Create, update, or delete subagent definitions at runtime.
Use to spin up a specialized agent tailored to the current task.
Created agents are immediately available via invoke_subagent.

Scopes:
- temporary: In-memory only, exists for this session. Fast, no file I/O.
- project: Saved to .refio/agents/<name>.md in standard format. Persists across sessions.

Actions: create, update, delete, list"""
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "enum" to listOf("create", "update", "delete", "list"),
                "description" to "Action to perform"
            ),
            "name" to mapOf(
                "type" to "string",
                "description" to "Agent name (kebab-case, e.g. 'csv-parser'). Required for create/update/delete."
            ),
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("temporary", "project"),
                "default" to "temporary",
                "description" to "temporary = in-memory (session lifetime). project = saved to .refio/agents/"
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "Short description of what the agent does."
            ),
            "system_prompt" to mapOf(
                "type" to "string",
                "description" to "Full system prompt for the agent."
            ),
            "tools" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Allowed tools list (e.g. ['read_file', 'grep_search']). null = inherit from parent."
            ),
            "model" to mapOf(
                "type" to "string",
                "default" to "inherit",
                "description" to "Model: 'inherit', 'default', 'plan', 'coding', 'weak', or specific model id"
            ),
            "max_steps" to mapOf(
                "type" to "integer",
                "default" to 10,
                "description" to "Maximum iterations for the agent (1-50)"
            )
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val router = subagentRouterProvider()
            ?: return ToolResult.error("SubagentRouter not available")
        val action = params["action"] as? String
            ?: return ToolResult.error("action required")
        val parentMode = (params[pl.jclab.refio.core.tools.base.ToolInternalParams.MODE] as? String)?.let {
            try { TaskMode.valueOf(it) } catch (_: Exception) { null }
        } ?: TaskMode.PLAN

        return when (action) {
            "create" -> handleCreate(params, router, parentMode)
            "update" -> handleUpdate(params, router, parentMode)
            "delete" -> handleDelete(params, router)
            "list" -> handleList(router)
            else -> ToolResult.error("Unknown action: $action. Use: create, update, delete, list")
        }
    }

    private fun handleCreate(
        params: Map<String, Any>,
        router: SubagentRouter,
        parentMode: TaskMode
    ): ToolResult {
        val name = params["name"] as? String
            ?: return ToolResult.error("name required for create")
        val description = params["description"] as? String
            ?: return ToolResult.error("description required for create")
        val systemPrompt = params["system_prompt"] as? String
            ?: return ToolResult.error("system_prompt required for create")
        val scope = params["scope"] as? String ?: "temporary"
        val model = params["model"] as? String ?: "inherit"
        val maxSteps = (params["max_steps"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 10

        @Suppress("UNCHECKED_CAST")
        val tools = (params["tools"] as? List<*>)?.filterIsInstance<String>()?.let { requestedTools ->
            enforceToolCeiling(requestedTools, parentMode)
        }

        if (!name.matches(NAME_PATTERN)) {
            return ToolResult.error("Invalid name '$name'. Use kebab-case (e.g. 'csv-parser')")
        }

        if (router.getSubagent(name) != null) {
            return ToolResult.error("Subagent '$name' already exists. Use action='update' to modify.")
        }

        val definition = SubagentDefinition(
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            allowedTools = tools,
            model = model,
            enabled = true,
            priority = 5,
            maxSteps = maxSteps,
            executionMode = SubagentExecutionMode.MULTI_STEP
        )

        return when (scope) {
            "temporary" -> {
                router.registerTemporary(definition)
                ToolResult(
                    success = true,
                    output = buildString {
                        appendLine("Created temporary subagent '$name'.")
                        appendLine("  Description: $description")
                        appendLine("  Tools: ${tools?.joinToString(", ") ?: "inherited from parent"}")
                        appendLine("  Model: $model | Max steps: $maxSteps")
                        appendLine("  Scope: temporary (session only)")
                        appendLine("Use invoke_subagent(name='$name', goal='...') to run it.")
                    },
                    metadata = mapOf("scope" to "temporary", "agent_name" to name)
                )
            }
            "project" -> {
                try {
                    router.createSubagent(
                        name = name,
                        description = description,
                        systemPrompt = systemPrompt,
                        allowedTools = tools,
                        model = model,
                        scope = SubagentScope.PROJECT
                    )
                    ToolResult(
                        success = true,
                        output = buildString {
                            appendLine("Created project subagent '$name'.")
                            appendLine("  Saved to: .refio/agents/$name.md")
                            appendLine("  Description: $description")
                            appendLine("  Tools: ${tools?.joinToString(", ") ?: "inherited"}")
                            appendLine("  Model: $model | Max steps: $maxSteps")
                            appendLine("  Scope: project (persists across sessions)")
                            appendLine("Use invoke_subagent(name='$name', goal='...') to run it.")
                        },
                        metadata = mapOf("scope" to "project", "agent_name" to name)
                    )
                } catch (e: SubagentAlreadyExistsException) {
                    ToolResult.error("Subagent '$name' already exists at project level.")
                }
            }
            else -> ToolResult.error("Invalid scope: $scope. Use 'temporary' or 'project'.")
        }
    }

    private fun handleUpdate(
        params: Map<String, Any>,
        router: SubagentRouter,
        parentMode: TaskMode
    ): ToolResult {
        val name = params["name"] as? String
            ?: return ToolResult.error("name required for update")

        val existing = router.getSubagent(name)
            ?: return ToolResult.error("Subagent '$name' not found. Use action='create' first.")

        if (existing.scope == SubagentScope.BUILTIN) {
            return ToolResult.error("Cannot modify builtin subagent '$name'. Create a project override instead.")
        }

        @Suppress("UNCHECKED_CAST")
        val tools = (params["tools"] as? List<*>)?.filterIsInstance<String>()?.let {
            enforceToolCeiling(it, parentMode)
        }

        val updated = existing.copy(
            description = params["description"] as? String ?: existing.description,
            systemPrompt = params["system_prompt"] as? String ?: existing.systemPrompt,
            allowedTools = tools ?: existing.allowedTools,
            model = params["model"] as? String ?: existing.model,
            maxSteps = (params["max_steps"] as? Number)?.toInt()?.coerceIn(1, 50) ?: existing.maxSteps
        )

        return if (existing.scope == SubagentScope.TEMPORARY) {
            router.registerTemporary(updated)
            ToolResult(
                success = true,
                output = "Updated temporary subagent '$name'.",
                metadata = mapOf("agent_name" to name)
            )
        } else {
            router.updateSubagent(
                name = name,
                description = params["description"] as? String,
                systemPrompt = params["system_prompt"] as? String,
                allowedTools = tools,
                model = params["model"] as? String
            )
            ToolResult(
                success = true,
                output = "Updated subagent '$name' (scope: ${existing.scope}).",
                metadata = mapOf("agent_name" to name)
            )
        }
    }

    private fun handleDelete(params: Map<String, Any>, router: SubagentRouter): ToolResult {
        val name = params["name"] as? String
            ?: return ToolResult.error("name required for delete")

        val existing = router.getSubagent(name)
            ?: return ToolResult.error("Subagent '$name' not found.")

        if (existing.scope == SubagentScope.BUILTIN) {
            return ToolResult.error("Cannot delete builtin subagent '$name'.")
        }

        return try {
            router.deleteSubagent(name)
            ToolResult(
                success = true,
                output = "Deleted subagent '$name' (was: ${existing.scope}).",
                metadata = mapOf("agent_name" to name)
            )
        } catch (e: Exception) {
            ToolResult.error("Failed to delete '$name': ${e.message}")
        }
    }

    private fun handleList(router: SubagentRouter): ToolResult {
        val all = router.listSubagents(includeDisabled = true)
        if (all.isEmpty()) {
            return ToolResult(success = true, output = "No subagents available.")
        }

        val output = buildString {
            appendLine("## Available Subagents (${all.size})")
            appendLine()
            val grouped = all.groupBy { it.scope }
            for ((scope, agents) in grouped) {
                appendLine("### $scope")
                agents.sortedBy { it.name }.forEach { agent ->
                    val status = if (agent.enabled) "enabled" else "DISABLED"
                    appendLine("  - **${agent.name}**: ${agent.description} [$status]")
                }
                appendLine()
            }
        }

        return ToolResult(success = true, output = output)
    }

    /**
     * Security ceiling: filter requested tools by parent mode.
     * Subagent cannot have WRITE tools if parent is in PLAN.
     */
    private fun enforceToolCeiling(requestedTools: List<String>, parentMode: TaskMode): List<String> {
        if (parentMode == TaskMode.AGENT) return requestedTools

        return requestedTools.filter { it in READ_ONLY_AND_SYSTEM_TOOLS }
    }

    companion object {
        private val NAME_PATTERN = Regex("^[a-z][a-z0-9-]*[a-z0-9]$")

        private val READ_ONLY_AND_SYSTEM_TOOLS =
            SubagentToolFilter.READ_ONLY_TOOLS + SubagentToolFilter.SYSTEM_TOOLS + "invoke_subagent"
    }
}

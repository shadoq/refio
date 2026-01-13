package pl.jclab.refio.core.context.mcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser

internal data class MCPToolCallResult(
    val outputText: String,
    val isError: Boolean
)

internal data class MCPWorkflowStepResult(
    val toolName: String,
    val output: String,
    val isError: Boolean
)

internal data class MCPWorkflowExecution(
    val steps: List<MCPWorkflowStepResult>,
    val error: String? = null
)

internal object MCPToolWorkflowExecutor {
    suspend fun execute(
        workflow: MCPToolWorkflowConfig,
        tools: List<MCPToolDefinition>,
        config: MCPServerConfig,
        query: String,
        gson: Gson,
        toolCaller: suspend (String, Map<String, Any>) -> MCPToolCallResult
    ): MCPWorkflowExecution {
        if (workflow.steps.isEmpty()) {
            return MCPWorkflowExecution(emptyList(), "MCP workflow has no steps.")
        }

        val toolMap = tools.associateBy { it.name }
        val variables = mutableMapOf<String, String>()
        val stepResults = mutableListOf<MCPWorkflowStepResult>()

        for (step in workflow.steps) {
            val toolDef = toolMap[step.toolName]
            if (toolDef == null) {
                return MCPWorkflowExecution(stepResults, "MCP workflow tool not found: ${step.toolName}")
            }

            val argsResult = buildStepArguments(step, toolDef, config, query, variables, gson)
            if (argsResult.isFailure) {
                return MCPWorkflowExecution(stepResults, argsResult.exceptionOrNull()?.message)
            }

            val callResult = toolCaller(step.toolName, argsResult.getOrThrow())
            stepResults.add(MCPWorkflowStepResult(step.toolName, callResult.outputText, callResult.isError))
            if (callResult.isError) {
                return MCPWorkflowExecution(stepResults, "MCP workflow step ${step.toolName} failed.")
            }

            val outputsResult = extractOutputVariables(step, callResult.outputText, gson)
            if (outputsResult.isFailure) {
                return MCPWorkflowExecution(stepResults, outputsResult.exceptionOrNull()?.message)
            }
            variables.putAll(outputsResult.getOrThrow())
        }

        return MCPWorkflowExecution(stepResults)
    }

    private fun buildStepArguments(
        step: MCPToolWorkflowStep,
        toolDef: MCPToolDefinition,
        config: MCPServerConfig,
        query: String,
        variables: Map<String, String>,
        gson: Gson
    ): Result<Map<String, Any>> {
        if (step.inputMapping.isEmpty()) {
            return MCPToolArgumentResolver.buildArguments(query, toolDef, config, gson)
        }

        val args = mutableMapOf<String, Any>()
        for ((paramName, source) in step.inputMapping) {
            val resolved = resolveInputValue(source, query, variables)
                .getOrElse { return Result.failure(it) }
            args[paramName] = resolved
        }
        return Result.success(args)
    }

    private fun resolveInputValue(
        source: String,
        query: String,
        variables: Map<String, String>
    ): Result<String> {
        val trimmed = source.trim()
        if (trimmed.equals("query", ignoreCase = true)) {
            return Result.success(query)
        }
        if (trimmed.startsWith("var:", ignoreCase = true)) {
            val key = trimmed.substringAfter("var:").trim()
            val value = variables[key]
                ?: return Result.failure(IllegalStateException("Missing workflow variable: $key"))
            return Result.success(value)
        }
        if (trimmed.startsWith("literal:", ignoreCase = true)) {
            return Result.success(trimmed.substringAfter("literal:"))
        }
        return Result.success(trimmed)
    }

    private fun extractOutputVariables(
        step: MCPToolWorkflowStep,
        outputText: String,
        gson: Gson
    ): Result<Map<String, String>> {
        if (step.outputMapping.isEmpty()) {
            return Result.success(emptyMap())
        }
        if (outputText.isBlank()) {
            return Result.failure(IllegalStateException("Workflow output is empty for ${step.toolName}."))
        }

        val json = runCatching { JsonParser.parseString(outputText) }
            .getOrElse { return Result.failure(IllegalStateException("Workflow output is not valid JSON for ${step.toolName}.")) }

        val outputs = mutableMapOf<String, String>()
        for ((varName, path) in step.outputMapping) {
            val tokens = parseJsonPath(path).getOrElse { return Result.failure(it) }
            val value = extractJsonValue(json, tokens)
                ?: return Result.failure(IllegalStateException("Workflow output path not found: $path"))
            outputs[varName] = renderJsonValue(value, gson)
        }
        return Result.success(outputs)
    }

    private sealed interface JsonPathToken {
        data class Field(val name: String) : JsonPathToken
        data class Index(val index: Int) : JsonPathToken
    }

    private fun parseJsonPath(path: String): Result<List<JsonPathToken>> {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Workflow output path cannot be blank."))
        }

        val tokens = mutableListOf<JsonPathToken>()
        var i = 0
        while (i < trimmed.length) {
            when (val ch = trimmed[i]) {
                '.' -> i += 1
                '[' -> {
                    val end = trimmed.indexOf(']', startIndex = i + 1)
                    if (end == -1) {
                        return Result.failure(IllegalArgumentException("Invalid JSON path (missing ]): $path"))
                    }
                    val indexStr = trimmed.substring(i + 1, end).trim()
                    val index = indexStr.toIntOrNull()
                        ?: return Result.failure(IllegalArgumentException("Invalid JSON path index: $path"))
                    tokens.add(JsonPathToken.Index(index))
                    i = end + 1
                }
                else -> {
                    var j = i
                    while (j < trimmed.length && trimmed[j] != '.' && trimmed[j] != '[') {
                        j += 1
                    }
                    val name = trimmed.substring(i, j).trim()
                    if (name.isBlank()) {
                        return Result.failure(IllegalArgumentException("Invalid JSON path segment in: $path"))
                    }
                    tokens.add(JsonPathToken.Field(name))
                    i = j
                }
            }
        }

        if (tokens.isEmpty()) {
            return Result.failure(IllegalArgumentException("Invalid JSON path: $path"))
        }
        return Result.success(tokens)
    }

    private fun extractJsonValue(element: JsonElement, tokens: List<JsonPathToken>): JsonElement? {
        var current: JsonElement = element
        for (token in tokens) {
            current = when (token) {
                is JsonPathToken.Field -> {
                    if (!current.isJsonObject) return null
                    val obj = current.asJsonObject
                    if (!obj.has(token.name)) return null
                    obj.get(token.name)
                }
                is JsonPathToken.Index -> {
                    if (!current.isJsonArray) return null
                    val arr = current.asJsonArray
                    if (token.index !in 0 until arr.size()) return null
                    arr[token.index]
                }
            }
        }
        return current
    }

    private fun renderJsonValue(element: JsonElement, gson: Gson): String {
        val primitive = element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        if (primitive != null) {
            return if (primitive.isString) primitive.asString else primitive.toString()
        }
        return gson.toJson(element)
    }
}

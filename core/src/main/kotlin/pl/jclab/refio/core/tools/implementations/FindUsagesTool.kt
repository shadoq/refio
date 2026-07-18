package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.refactor.StructuralRefactorer

private val logger = dualLogger("FindUsagesTool")

/**
 * Find Usages Tool - lists all locations where a symbol is used across the project.
 *
 * Parameters:
 * - symbol_name: The symbol name to look up
 * - max_results: Maximum number of locations returned (default: 100)
 *
 * The actual engine is injected: IDE find-usages inside the IntelliJ plugin,
 * word-boundary text search in CLI/headless. The description states the active guarantee.
 */
class FindUsagesTool(
    private val refactorer: StructuralRefactorer
) : Tool {

    override val name = "find_usages"
    override val description =
        "Find all usages of a symbol across the project (engine: ${refactorer.engineDescription})."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint =
        "List every usage of a known symbol name. Prefer over grep_search when checking rename impact."

    override fun validateParams(params: Map<String, Any>) {
        if ((params["symbol_name"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'symbol_name' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()
        try {
            val symbolName = params["symbol_name"] as? String
                ?: return ToolResult.error("Missing required parameter: 'symbol_name'")
            val maxResults = (params["max_results"] as? Number)?.toInt() ?: 100

            val usages = refactorer.findUsages(symbolName)
            val duration = (System.currentTimeMillis() - startTime).toInt()
            val shown = usages.take(maxResults)

            val output = if (usages.isEmpty()) {
                "No usages found for symbol: $symbolName"
            } else {
                buildString {
                    appendLine("${usages.size} usage(s) of '$symbolName'${if (usages.size > shown.size) " (showing first ${shown.size})" else ""}:")
                    shown.forEach { appendLine("  ${it.file}:${it.line}: ${it.snippet}") }
                }.trimEnd()
            }

            logger.info { "find_usages('$symbolName'): ${usages.size} locations, ${duration}ms" }

            return ToolResult(
                success = true,
                output = output,
                durationMs = duration,
                nextActionHints = if (usages.isEmpty()) {
                    listOf(
                        "Check the spelling of symbol_name",
                        "Use grep_search for partial or non-identifier patterns"
                    )
                } else null,
                metadata = mapOf(
                    "symbol_name" to symbolName,
                    "usage_count" to usages.size
                )
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(
                message = "Invalid parameters: ${e.message}",
                recovery = "symbol_name must be a plain identifier (letters, digits, underscore)."
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to find usages" }
            return ToolResult.error("Failed to find usages: ${e.message}")
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "symbol_name" to mapOf(
                    "type" to "string",
                    "description" to "Symbol name to look up (plain identifier)"
                ),
                "max_results" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum number of locations returned",
                    "default" to 100
                )
            ),
            "required" to listOf("symbol_name")
        )
    }
}

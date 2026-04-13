package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.delay
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

class SleepTool : Tool {
    override val name = "sleep"
    override val description = "Pause execution for a specified number of milliseconds. " +
        "Use for rate limiting between API calls, or waiting for an external process. " +
        "Maximum sleep is 30 seconds (30000 ms)."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM

    override fun validateParams(params: Map<String, Any>) {
        val ms = toLong(params["duration_ms"])
            ?: throw IllegalArgumentException("Parameter 'duration_ms' is required")
        if (ms < 0) throw IllegalArgumentException("'duration_ms' must be >= 0")
        if (ms > MAX_SLEEP_MS) throw IllegalArgumentException("'duration_ms' must be <= $MAX_SLEEP_MS")
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val ms = toLong(params["duration_ms"])
            ?: return ToolResult.error("Missing required parameter: 'duration_ms'")
        val clamped = ms.coerceIn(0, MAX_SLEEP_MS)
        val start = System.currentTimeMillis()
        delay(clamped)
        val actual = System.currentTimeMillis() - start
        return ToolResult(
            success = true,
            output = "Slept for ${actual}ms",
            durationMs = actual.toInt()
        )
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "duration_ms" to mapOf(
                "type" to "integer",
                "description" to "Duration in milliseconds (max 30000)"
            )
        ),
        "required" to listOf("duration_ms")
    )

    private fun toLong(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    companion object {
        const val MAX_SLEEP_MS = 30_000L
    }
}

package pl.jclab.refio.core.security

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.NoEgressViolationException
import pl.jclab.refio.core.services.ConfigService

/**
 * Central gate for outbound network access from tools.
 *
 * Why: `general.no_egress_enabled` historically only blocked cloud LLM providers, while
 * `WebSearchTool`, `FetchWebpageTool` and `HttpRequestTool` happily reached the public internet.
 * This broke the local-first promise. NetworkPolicy unifies the check so any tool that opens
 * an outbound connection consults the same flag.
 */
class NetworkPolicy(
    private val configService: ConfigService,
    private val taskIdProvider: () -> String? = { null }
) {
    fun isNoEgressEnabled(taskId: String? = taskIdProvider()): Boolean {
        return try {
            configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, taskId)
        } catch (_: Exception) {
            false
        }
    }

    fun assertEgressAllowed(toolName: String, target: String, taskId: String? = taskIdProvider()) {
        if (isNoEgressEnabled(taskId)) {
            throw NoEgressViolationException(
                "no-egress mode blocks outbound network from '$toolName' (target: $target). " +
                    "Disable no-egress in Settings → General to allow this call."
            )
        }
    }
}

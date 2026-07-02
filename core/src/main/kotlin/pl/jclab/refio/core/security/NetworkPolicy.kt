package pl.jclab.refio.core.security

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.NoEgressViolationException
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService

private val logger = dualLogger("NetworkPolicy")

/**
 * Central gate for outbound network access from tools.
 *
 * Why: `general.no_egress_enabled` historically only blocked cloud LLM providers, while
 * `WebSearchTool`, `FetchWebpageTool` and `HttpRequestTool` happily reached the public internet.
 * This broke the local-first promise. NetworkPolicy unifies the check so any tool that opens
 * an outbound connection consults the same flag.
 *
 * Failure semantics: the gate must never silently fail OPEN. The config default stays
 * "no-egress disabled" (egress allowed), but a config read error is an abnormal state, not the
 * default state - it falls back to the last successfully read value, and with no history it
 * fails closed (blocked). Otherwise a transient DB/config hiccup would void an explicitly
 * enabled no-egress guarantee without a trace.
 */
class NetworkPolicy(
    private val configService: ConfigService,
    private val taskIdProvider: () -> String? = { null }
) {
    @Volatile
    private var lastKnownNoEgress: Boolean? = null

    fun isNoEgressEnabled(taskId: String? = taskIdProvider()): Boolean {
        return try {
            configService.getTyped<Boolean>(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, taskId)
                .also { lastKnownNoEgress = it }
        } catch (e: Exception) {
            val fallback = lastKnownNoEgress
            if (fallback != null) {
                logger.warn {
                    "Failed to read no-egress flag (${e.message}); using last known value: " +
                        if (fallback) "egress BLOCKED" else "egress allowed"
                }
                fallback
            } else {
                logger.warn {
                    "Failed to read no-egress flag with no prior successful read (${e.message}); " +
                        "failing closed: egress BLOCKED until the config becomes readable"
                }
                true
            }
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

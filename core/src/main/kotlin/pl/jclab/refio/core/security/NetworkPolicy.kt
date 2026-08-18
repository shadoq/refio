package pl.jclab.refio.core.security

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.NoEgressViolationException
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

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
    private val taskIdProvider: () -> String? = { null },
    private val resolveAll: (String) -> Array<InetAddress> = InetAddress::getAllByName
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

    /**
     * Like [assertEgressAllowed] but allows a target on the local machine or LAN.
     *
     * For callers whose endpoint is user-configured and usually local - LLM providers, embedding
     * endpoints - no-egress means "do not leave my network", not "do no I/O". Tools that always
     * reach the public internet should keep using [assertEgressAllowed].
     */
    fun assertRemoteEgressAllowed(source: String, target: String, taskId: String? = taskIdProvider()) {
        // Flag first: classifying the target resolves its host, so an open gate must not pay for
        // a name lookup on every call.
        if (!isNoEgressEnabled(taskId)) {
            return
        }
        if (isLocalTarget(target, resolveAll)) {
            return
        }
        logger.warn { "No-egress violation blocked: '$source' targets a non-local endpoint '$target'" }
        throw NoEgressViolationException(
            "No-egress mode is enabled and $source endpoint is not local: $target"
        )
    }

    companion object {
        /**
         * Whether [url] points at this machine or a private network.
         *
         * Shared so the LLM path and the embedding path cannot drift apart on what counts as
         * local. The verdict is taken from the RESOLVED addresses, never from the host text: a
         * host name is attacker-supplied data and `10.attacker.example.com` or
         * `192.168.evil.net` is a public target that merely reads like a private one. The endpoint
         * comes from `<project>/.refio/config.yaml`, so a cloned repository can supply it, and
         * everything the exemption lets through carries the user's code context.
         *
         * Decisions this encodes:
         * - Unparsable URL, missing host, failed lookup or an empty answer is NOT local. We cannot
         *   prove locality, so the gate closes; a target that does not resolve could not have been
         *   reached anyway, only the error message differs.
         * - EVERY resolved record must be local. One public record among the answers disqualifies
         *   the target, because the HTTP client that follows may connect to any of them
         *   (DNS rebinding).
         * - No result cache here. Literals and `localhost` resolve without network I/O, and real
         *   host names are already cached by the JVM resolver (`networkaddress.cache.ttl`), so a
         *   second cache would only add a staleness window that widens the rebinding gap this
         *   check exists to close.
         *
         * [resolveAll] is injectable so tests stay offline.
         */
        fun isLocalTarget(
            url: String,
            resolveAll: (String) -> Array<InetAddress> = InetAddress::getAllByName
        ): Boolean {
            val host = try {
                URI(url).host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
            } catch (_: Exception) {
                return false
            }
            val addresses = try {
                resolveAll(host)
            } catch (e: Exception) {
                logger.warn { "Cannot resolve '$host' (${e.message}); treating the target as non-local" }
                return false
            }
            return addresses.isNotEmpty() && addresses.all { isLocalAddress(it) }
        }

        /**
         * Loopback, unspecified and RFC1918 unicast count as local. Multicast never does - it is
         * not a legitimate provider endpoint, and a group address is not proof of anything about
         * who receives the payload.
         *
         * Note this is the mirror image of [UrlPolicy], which BLOCKS these same families as an
         * SSRF defense. The two gates answer opposite questions and must keep opposite verdicts.
         */
        private fun isLocalAddress(address: InetAddress): Boolean {
            if (address.isMulticastAddress) {
                return false
            }
            if (address.isLoopbackAddress || address.isAnyLocalAddress) {
                return true
            }
            return when (address) {
                is Inet4Address -> {
                    val bytes = address.address
                    val first = bytes[0].toInt() and 0xFF
                    val second = bytes[1].toInt() and 0xFF
                    // 172.16/12 is deliberately absent: it was never accepted before either, and
                    // widening what the no-egress exemption covers is a separate decision.
                    first == 10 || (first == 192 && second == 168)
                }
                else -> false
            }
        }
    }
}

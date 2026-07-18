package pl.jclab.refio.core.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * SSRF guard for outbound tool URLs. By default rejects loopback, private, link-local and
 * any-local addresses so a tool cannot be steered at internal services.
 *
 * [allowLoopback] is an opt-in escape hatch (resolved per call so a run-scope config override is
 * honoured): when it returns true, the loopback/private/local families are permitted - used by the
 * e2e harness to reach a deterministic fixture server on 127.0.0.1. Multicast is never permitted,
 * even with the opt-in. Shared by every outbound tool (http_request, fetch_webpage) so they enforce
 * one identical policy.
 *
 * DNS-rebinding defense: EVERY resolved record is validated, not just the first - a hostile DNS
 * server answering [public, 127.0.0.1] must not pass because the first record is clean, since the
 * HTTP client may connect to any of them. The connect that follows validation happens immediately
 * and reuses the JVM's positive DNS cache, which closes the fast record-flip window of
 * resolve-once-connect-later. [resolveAll] is injectable for offline tests.
 */
class UrlPolicy(
    private val allowLoopback: () -> Boolean = { false },
    private val resolveAll: (String) -> Array<InetAddress> = InetAddress::getAllByName
) {
    fun validate(url: String) {
        val parsed = try {
            URI(url)
        } catch (e: Exception) {
            throw SecurityException("Invalid URL: ${e.message}")
        }

        val scheme = parsed.scheme?.lowercase()
            ?: throw SecurityException("URL must include a scheme")
        require(scheme == "http" || scheme == "https") {
            "Only http/https URLs are allowed"
        }

        val host = parsed.host ?: throw SecurityException("URL must include a host")
        val addresses = try {
            resolveAll(host)
        } catch (e: Exception) {
            throw SecurityException("Failed to resolve host '$host': ${e.message}")
        }
        if (addresses.isEmpty()) {
            throw SecurityException("Failed to resolve host '$host': no address records")
        }

        if (addresses.any { isBlocked(it) }) {
            throw SecurityException("Blocked private, loopback, or local address: $host")
        }
    }

    private fun isBlocked(address: InetAddress): Boolean {
        // Multicast is never a legitimate tool target and stays blocked even under the opt-in.
        if (address.isMulticastAddress) return true
        // Opt-in: trust loopback/private/local addresses (e.g. a local fixture server).
        if (allowLoopback()) return false

        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress
        ) {
            return true
        }

        return when (address) {
            is Inet4Address -> {
                val bytes = address.address
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                first == 0 ||
                    first == 10 ||
                    first == 127 ||
                    (first == 169 && second == 254) ||
                    (first == 172 && second in 16..31) ||
                    (first == 192 && second == 168)
            }
            is Inet6Address -> {
                address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isSiteLocalAddress ||
                    address.hostAddress.startsWith("fc", ignoreCase = true) ||
                    address.hostAddress.startsWith("fd", ignoreCase = true)
            }
            else -> false
        }
    }
}

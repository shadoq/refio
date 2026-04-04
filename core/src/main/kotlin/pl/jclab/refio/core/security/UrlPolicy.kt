package pl.jclab.refio.core.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

class UrlPolicy {
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
        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            throw SecurityException("Failed to resolve host '$host': ${e.message}")
        }

        if (isBlocked(address)) {
            throw SecurityException("Blocked private, loopback, or local address: $host")
        }
    }

    private fun isBlocked(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
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

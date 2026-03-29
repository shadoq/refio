package pl.jclab.refio.core.security

/**
 * Redacts API keys, tokens, and other sensitive values from logs.
 */
object SecureLogger {
    private const val REDACTED = "[REDACTED]"

    private val keyValuePattern = Regex(
        """(?i)(["']?)(api[_-]?key|apikey|authorization|bearer|token|secret|password|credential|x-api-key|x-goog-api-key)\1(\s*[:=]\s*)(["']?)([^"'\r\n,}]+)"""
    )

    private val sensitivePatterns = listOf(
        Regex("""sk-proj-[a-zA-Z0-9_-]{6,}"""),      // OpenAI project keys (short forms in tests)
        Regex("""sk-[a-zA-Z0-9_-]{20,}"""),          // OpenAI keys
        Regex("""sk-ant-[a-zA-Z0-9_-]{20,}"""),      // Anthropic keys
        Regex("""ant-api-[a-zA-Z0-9_-]{20,}"""),     // Anthropic keys
        Regex("""sk-or-[a-zA-Z0-9_-]{20,}"""),       // OpenRouter keys
        Regex("""anthropic-[a-zA-Z0-9_-]{20,}"""),   // Anthropic alternative format
        Regex("""AIza[a-zA-Z0-9_-]{35}"""),          // Google API keys
        Regex("""(?i)Bearer\s+[a-zA-Z0-9._-]+"""),
        Regex("""(?i)Basic\s+[a-zA-Z0-9+/=]+""")
    )

    fun redact(input: String): String {
        var result = keyValuePattern.replace(input) { match ->
            val keyQuote = match.groups[1]?.value.orEmpty()
            val key = match.groups[2]?.value.orEmpty()
            val separator = match.groups[3]?.value.orEmpty()
            val valueQuote = match.groups[4]?.value.orEmpty()
            "$keyQuote$key$keyQuote$separator$valueQuote$REDACTED"
        }

        sensitivePatterns.forEach { pattern ->
            result = pattern.replace(result) { match ->
                if (match.value.startsWith("Bearer", ignoreCase = true)) {
                    "Bearer $REDACTED"
                } else {
                    REDACTED
                }
            }
        }

        return result
    }

    fun redactAndTruncate(input: String, head: Int = 30, tail: Int = 30): String {
        val redacted = redact(input)
        if (redacted.length <= head + tail + 3) {
            return redacted
        }
        val start = redacted.take(head)
        val end = redacted.takeLast(tail)
        return "$start...$end"
    }

    fun redactMap(map: Map<String, Any?>): Map<String, Any?> {
        return map.mapValues { (key, value) ->
            if (isSensitiveKey(key)) {
                REDACTED
            } else {
                redactValue(value)
            }
        }
    }

    fun redactValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> redact(value)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                redactMap(value as Map<String, Any?>)
            }
            is Iterable<*> -> value.map { redactValue(it) }
            else -> value
        }
    }

    /** Exact sensitive key names — avoids false positives like "totalTokens" or "contextTokenCount" */
    private val sensitiveKeyNames = setOf(
        "apikey", "api_key", "api-key",
        "authorization", "bearer",
        "token", "access_token", "refresh_token", "auth_token", "api_token",
        "secret", "client_secret",
        "password", "passwd",
        "credential", "credentials",
        "x-api-key", "x-goog-api-key",
        "private_key", "secret_key"
    )

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        // Check exact match first (fast path)
        if (normalized in sensitiveKeyNames) return true
        // Check if key ends with a sensitive suffix (e.g. "myApiKey", "oauth_token")
        // but NOT generic words like "tokenCount", "totalTokens"
        return normalized.endsWith("_key") ||
            normalized.endsWith("apikey") ||
            normalized.endsWith("_token") ||
            normalized.endsWith("_secret") ||
            normalized.endsWith("_password") ||
            normalized.endsWith("_credential")
    }
}

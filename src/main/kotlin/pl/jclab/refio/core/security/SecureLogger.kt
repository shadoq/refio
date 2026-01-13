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

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized.contains("apikey") ||
            normalized.contains("api_key") ||
            normalized.contains("api-key") ||
            normalized.contains("authorization") ||
            normalized.contains("bearer") ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("password") ||
            normalized.contains("credential") ||
            normalized.contains("x-api-key") ||
            normalized.contains("x-goog-api-key")
    }
}

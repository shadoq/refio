package pl.jclab.refio.core.security

/**
 * Lightweight validator for user/model-provided regex patterns.
 *
 * It blocks a small set of constructs strongly associated with catastrophic
 * backtracking while keeping regular project searches usable.
 */
object RegexSafetyValidator {
    private const val MAX_PATTERN_LENGTH = 500

    private val dangerousPatterns = listOf(
        Regex("""\((?:\.\*|\.\+)\)\+"""),
        Regex("""\((?:[^()]|\\.)*[+*](?:[^()]|\\.)*\)\+"""),
        Regex("""\((?:[^()]|\\.)*[+*](?:[^()]|\\.)*\)\*"""),
    )

    fun validate(pattern: String) {
        require(pattern.length <= MAX_PATTERN_LENGTH) {
            "Regex pattern too long (max $MAX_PATTERN_LENGTH characters)"
        }

        for (dangerous in dangerousPatterns) {
            require(!dangerous.containsMatchIn(pattern)) {
                "Regex pattern contains potentially dangerous nested quantifiers"
            }
        }
    }
}

package pl.jclab.refio.core.tools.security

/**
 * Execution limits for terminal/background commands.
 */
data class CommandLimits(
    /**
     * Default execution timeout in seconds.
     */
    val timeoutSeconds: Long = 120,

    /**
     * Maximum captured output size in characters (stdout+stderr combined).
     */
    val maxOutputSize: Int = 200_000
) {
    companion object {
        val DEFAULT = CommandLimits()

        val STRICT = CommandLimits(
            timeoutSeconds = 30,
            maxOutputSize = 50_000
        )
    }
}

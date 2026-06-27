package app

/**
 * Central tuning knobs for the HTTP client. Values here are read across the codebase,
 * so changing one constant must not require touching the call sites.
 */
object Config {
    const val MAX_RETRIES = 1
    const val TIMEOUT_SECONDS = 30
    const val BACKOFF_MILLIS = 250L
}

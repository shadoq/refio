package app

/**
 * Minimal retry loop. The number of attempts is governed by [Config.MAX_RETRIES] — the loop
 * logic itself is correct and must NOT be modified to change how many times a request is retried.
 */
class HttpClient {
    fun send(request: String): String {
        var attempt = 0
        var lastError: String? = null
        while (attempt <= Config.MAX_RETRIES) {
            attempt++
            val failed = simulateFailure(attempt)
            if (!failed) {
                return "OK: '$request' delivered on attempt $attempt"
            }
            lastError = "attempt $attempt failed"
        }
        return "GIVEUP: '$request' — $lastError after $attempt attempt(s)"
    }

    // Deterministic stand-in for a flaky network: the first two attempts "fail".
    private fun simulateFailure(attempt: Int): Boolean = attempt <= 2
}

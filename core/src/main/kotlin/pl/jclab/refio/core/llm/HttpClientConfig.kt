package pl.jclab.refio.core.llm

/**
 * HTTP client configuration for LLM adapters.
 * Loaded from database (ConfigService) and passed to adapters.
 *
 * Bug #18: HTTP limits from Settings → Limits panel
 */
data class HttpClientConfig(
    /**
     * Request timeout in milliseconds (default: 60000ms = 60s)
     * Used for API calls to LLM providers
     */
    val requestTimeoutMs: Long = 240000,

    /**
     * Connection timeout in milliseconds (default: 30000ms = 30s)
     * Time to wait for initial connection
     */
    val connectTimeoutMs: Long = 60000,

    /**
     * Tool execution timeout in seconds (default: 60s)
     * Used for tool execution in agent mode
     */
    val toolExecutionTimeoutSec: Int = 240,

    /**
     * Maximum retries for failed requests (default: 3)
     */
    val maxRetries: Int = 3,

    /**
     * Retry delay in milliseconds (default: 1000ms = 1s)
     */
    val retryDelayMs: Long = 1000,

    /**
     * Rate limit in requests per minute (default: 60)
     * Used to prevent API throttling
     */
    val rateLimitRequestsPerMinute: Int = 60
) {
    companion object {
        /**
         * Default configuration
         */
        val DEFAULT = HttpClientConfig()

        /**
         * Load configuration from ConfigService.
         * Reads limits.* keys from database.
         */
        fun fromConfigService(configService: pl.jclab.refio.core.services.ConfigService?): HttpClientConfig {
            if (configService == null) {
                return DEFAULT
            }

            // Load timeout values (stored in seconds, convert to ms)
            val apiCallTimeoutSec = configService.get(pl.jclab.refio.core.services.ConfigService.KEY_API_CALL_TIMEOUT)?.toIntOrNull() ?: 60
            val toolTimeoutSec = configService.get(pl.jclab.refio.core.services.ConfigService.KEY_TOOL_EXECUTION_TIMEOUT)?.toIntOrNull() ?: 240

            // Load other limits
            val maxRetries = configService.get(pl.jclab.refio.core.services.ConfigService.KEY_MAX_RETRIES)?.toIntOrNull() ?: 3
            val retryDelay = configService.get(pl.jclab.refio.core.services.ConfigService.KEY_RETRY_DELAY_MS)?.toLongOrNull() ?: 1000
            val rateLimit = configService.get(pl.jclab.refio.core.services.ConfigService.KEY_RATE_LIMIT_RPM)?.toIntOrNull() ?: 60

            return HttpClientConfig(
                requestTimeoutMs = apiCallTimeoutSec.toLong() * 1000,  // Convert sec to ms
                connectTimeoutMs = 30000,  // Fixed 30s
                toolExecutionTimeoutSec = toolTimeoutSec,
                maxRetries = maxRetries,
                retryDelayMs = retryDelay,
                rateLimitRequestsPerMinute = rateLimit
            )
        }
    }
}

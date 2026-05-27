package pl.jclab.refio.core.services

import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = dualLogger("EmbeddingCircuitBreaker")

/**
 * Circuit breaker for embedding providers to avoid hammering unavailable services.
 *
 * When a provider fails, it enters OPEN state and subsequent calls fail fast
 * without attempting connection. After cooldown period, it transitions to
 * HALF_OPEN state to test if service is back.
 *
 * This prevents UI freezing from repeated failed connection attempts.
 */
object EmbeddingCircuitBreaker {
    private const val FAILURE_THRESHOLD = 5  // Number of failures before opening circuit
    private const val COOLDOWN_MS = 60_000L  // 1 minute cooldown before retry
    private const val HALF_OPEN_COOLDOWN_MS = 300_000L  // 5 minutes after half-open success

    private enum class CircuitState {
        CLOSED,     // Normal operation
        OPEN,       // Service unavailable, fail fast
        HALF_OPEN   // Testing if service is back
    }

    /**
     * Mutable status holder — NOT a data class to avoid equals/hashCode based on mutable fields,
     * which would be incorrect for identity-based synchronization.
     */
    private class CircuitStatus(
        var state: CircuitState = CircuitState.CLOSED,
        var failureCount: Int = 0,
        var lastFailureTime: Long = 0,
        var lastNotificationTime: Long = 0
    )

    private val circuits = ConcurrentHashMap<String, CircuitStatus>()

    /**
     * Check if call should be allowed (fail fast if circuit is OPEN)
     *
     * @param providerKey Unique identifier for provider (e.g., "ollama:http://localhost:11434")
     * @return true if call should proceed, false if circuit is OPEN
     */
    fun allowCall(providerKey: String): Boolean {
        val status = circuits.computeIfAbsent(providerKey) { CircuitStatus() }

        synchronized(status) {
            val now = System.currentTimeMillis()

            when (status.state) {
                CircuitState.CLOSED -> {
                    // Normal operation
                    return true
                }

                CircuitState.OPEN -> {
                    // Check if cooldown period has passed
                    if (now - status.lastFailureTime > COOLDOWN_MS) {
                        logger.info { "Circuit breaker $providerKey: OPEN -> HALF_OPEN (testing if service is back)" }
                        status.state = CircuitState.HALF_OPEN
                        return true  // Allow one test call
                    }

                    // Still in cooldown, fail fast
                    logger.debug { "Circuit breaker $providerKey: OPEN, failing fast (${(now - status.lastFailureTime) / 1000}s since last failure)" }
                    return false
                }

                CircuitState.HALF_OPEN -> {
                    // Already testing, allow the call
                    return true
                }
            }
        }
    }

    /**
     * Record successful call (resets circuit to CLOSED)
     *
     * @param providerKey Unique identifier for provider
     */
    fun recordSuccess(providerKey: String) {
        val status = circuits.computeIfAbsent(providerKey) { CircuitStatus() }

        synchronized(status) {
            if (status.state != CircuitState.CLOSED) {
                logger.info { "Circuit breaker $providerKey: ${status.state} -> CLOSED (service recovered)" }
            }
            status.state = CircuitState.CLOSED
            status.failureCount = 0
        }
    }

    /**
     * Record failed call (may open circuit if threshold exceeded)
     *
     * @param providerKey Unique identifier for provider
     * @return true if this failure triggered circuit opening (should notify user)
     */
    fun recordFailure(providerKey: String): Boolean {
        val status = circuits.computeIfAbsent(providerKey) { CircuitStatus() }

        synchronized(status) {
            val now = System.currentTimeMillis()
            status.failureCount++
            status.lastFailureTime = now

            when (status.state) {
                CircuitState.CLOSED -> {
                    if (status.failureCount >= FAILURE_THRESHOLD) {
                        logger.warn { "Circuit breaker $providerKey: CLOSED -> OPEN (${status.failureCount} failures)" }
                        status.state = CircuitState.OPEN

                        // Only notify if we haven't notified recently
                        val shouldNotify = (now - status.lastNotificationTime) > HALF_OPEN_COOLDOWN_MS
                        if (shouldNotify) {
                            status.lastNotificationTime = now
                        }
                        return shouldNotify
                    }
                }

                CircuitState.HALF_OPEN -> {
                    logger.warn { "Circuit breaker $providerKey: HALF_OPEN -> OPEN (test call failed)" }
                    status.state = CircuitState.OPEN
                    // Don't notify again, we already notified when circuit first opened
                    return false
                }

                CircuitState.OPEN -> {
                    // Already open, no need to notify again
                    return false
                }
            }

            return false
        }
    }

    /**
     * Get current state for debugging
     */
    fun getState(providerKey: String): String {
        val status = circuits[providerKey] ?: return "CLOSED"
        synchronized(status) {
            return status.state.name
        }
    }

    /**
     * Reset circuit (for testing or manual override)
     */
    fun reset(providerKey: String) {
        circuits.remove(providerKey)
        logger.info { "Circuit breaker $providerKey: RESET" }
    }

    /**
     * Remaining cooldown (ms) before allowing another attempt while OPEN.
     */
    fun getCooldownRemaining(providerKey: String): Long {
        val status = circuits[providerKey] ?: return 0
        synchronized(status) {
            if (status.state != CircuitState.OPEN) {
                return 0
            }
            val elapsed = System.currentTimeMillis() - status.lastFailureTime
            return (COOLDOWN_MS - elapsed).coerceAtLeast(0)
        }
    }

    /**
     * Snapshot of providers that are currently NOT in CLOSED state. Used by the
     * RAG panel to surface circuit-breaker activity (OPEN/HALF_OPEN) to the user —
     * without this the breaker silently disables RAG and the user only sees
     * "RAG search disabled" with no explanation.
     */
    data class CircuitSnapshot(
        val providerKey: String,
        val state: String,
        val failureCount: Int,
        val cooldownRemainingMs: Long
    )

    fun getNonClosedCircuits(): List<CircuitSnapshot> {
        val now = System.currentTimeMillis()
        return circuits.entries.mapNotNull { (key, status) ->
            synchronized(status) {
                if (status.state == CircuitState.CLOSED) null
                else CircuitSnapshot(
                    providerKey = key,
                    state = status.state.name,
                    failureCount = status.failureCount,
                    cooldownRemainingMs = if (status.state == CircuitState.OPEN) {
                        (COOLDOWN_MS - (now - status.lastFailureTime)).coerceAtLeast(0)
                    } else 0
                )
            }
        }
    }
}

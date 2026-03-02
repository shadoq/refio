package pl.jclab.refio.core.api

/**
 * Base interface for domain-specific routers.
 * Each router handles a specific domain of functionality within CoreApiRouter.
 *
 * Routers are initialized when CoreApiRouter is created and shut down when disposed.
 * All routers should be thread-safe and use appropriate synchronization mechanisms.
 */
interface Router {
    /**
     * Initialize the router with required dependencies.
     * Called when CoreApiRouter is created.
     *
     * This method should:
     * - Initialize any internal state
     * - Validate dependencies
     * - Log initialization status
     *
     * @throws IllegalStateException if router cannot be initialized
     */
    suspend fun initialize()

    /**
     * Cleanup resources when router is no longer needed.
     * Called when CoreApiRouter is disposed.
     *
     * This method should:
     * - Release any held resources
     * - Cancel any ongoing operations
     * - Log shutdown status
     */
    suspend fun shutdown()
}

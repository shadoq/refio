package pl.jclab.refio.core.context

import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("ContextProviderRegistry")

/**
 * Registry of all available context providers.
 *
 * Providers are registered at startup and can be queried by title.
 * Singleton pattern ensures consistent provider instances across the plugin.
 *
 * Provider creation is delegated to [providerFactory] to keep this class
 * free of IntelliJ Platform dependencies. The IntelliJ plugin layer sets
 * the factory to create IDE-specific providers; standalone/CLI uses a
 * minimal factory with only platform-independent providers.
 */
object ContextProviderRegistry {

    private val providers = mutableMapOf<String, BaseContextProvider>()
    private var initialized = false
    private var ideEnvironment = true

    /**
     * Factory that creates the list of built-in providers.
     * Set by the platform layer before [initialize] is called.
     *
     * Default factory returns an empty list — override in plugin or CLI bootstrap.
     */
    var providerFactory: (isIdeEnvironment: Boolean) -> List<BaseContextProvider> = { emptyList() }

    /**
     * Register all built-in providers.
     * Called automatically on first access or manually during plugin startup.
     *
     * @param isIdeEnvironment When true (default), all providers are registered.
     *   When false, providers marked as [ContextProviderEnvironment.IDE_ONLY] are skipped.
     */
    @Synchronized
    fun initialize(isIdeEnvironment: Boolean = true) {
        if (initialized) {
            logger.debug { "Context providers already initialized" }
            return
        }

        this.ideEnvironment = isIdeEnvironment
        logger.info { "Initializing context providers (IDE environment: $isIdeEnvironment)..." }

        // Create and register providers from the platform-specific factory
        val builtinProviders = providerFactory(isIdeEnvironment)
        for (provider in builtinProviders) {
            register(provider)
        }

        initialized = true
        logger.info { "Registered ${providers.size} context providers" }
    }

    /**
     * Register a provider.
     * Public for extensions and MCP providers.
     *
     * Providers marked as [ContextProviderEnvironment.IDE_ONLY] are skipped
     * when running in a non-IDE environment.
     */
    fun register(provider: BaseContextProvider) {
        val title = provider.description.title
        if (!ideEnvironment && provider.environment == ContextProviderEnvironment.IDE_ONLY) {
            logger.debug { "Skipping IDE-only provider: $title (non-IDE environment)" }
            return
        }
        if (providers.containsKey(title)) {
            logger.warn { "Provider '$title' already registered, replacing..." }
        }
        providers[title] = provider
        logger.debug { "Registered provider: $title (${provider.description.displayTitle})" }
    }

    /**
     * Unregister a provider by title.
     * Useful for dynamic MCP providers.
     */
    fun unregister(title: String) {
        providers.remove(title)?.let {
            logger.debug { "Unregistered provider: $title" }
        }
    }

    /**
     * Get provider by title.
     */
    fun getProvider(title: String): BaseContextProvider? {
        if (!initialized) initialize()
        return providers[title]
    }

    /**
     * Get all providers.
     */
    fun getAllProviders(): List<BaseContextProvider> {
        if (!initialized) initialize()
        return providers.values.toList()
    }

    /**
     * Get providers of specific type.
     */
    fun getProvidersByType(type: ProviderType): List<BaseContextProvider> {
        if (!initialized) initialize()
        return providers.values.filter { it.description.type == type }
    }

    /**
     * Get provider titles for autocomplete.
     */
    fun getProviderTitles(): List<String> {
        if (!initialized) initialize()
        return providers.keys.sorted()
    }

    /**
     * Check if provider exists.
     */
    fun hasProvider(title: String): Boolean {
        if (!initialized) initialize()
        return providers.containsKey(title)
    }

    /**
     * Clear all providers (for testing).
     */
    @Synchronized
    fun clear() {
        providers.clear()
        initialized = false
        ideEnvironment = true
        logger.info { "Cleared all context providers" }
    }
}

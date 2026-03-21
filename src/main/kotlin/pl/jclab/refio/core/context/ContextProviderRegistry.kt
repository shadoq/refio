package pl.jclab.refio.core.context

import pl.jclab.refio.core.context.providers.*
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("ContextProviderRegistry")

/**
 * Registry of all available context providers.
 *
 * Providers are registered at startup and can be queried by title.
 * Singleton pattern ensures consistent provider instances across the plugin.
 */
object ContextProviderRegistry {

    private val providers = mutableMapOf<String, BaseContextProvider>()
    private var initialized = false
    private var ideEnvironment = true

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

        // Phase 1: MVP - Basic file providers
        register(OpenFilesContextProvider())
        register(FileContextProvider())
        register(RecentFilesContextProvider())

        // Phase 2: Extended file system and clipboard
        register(CurrentFileContextProvider())
        register(FolderContextProvider())
        register(ClipboardContextProvider())

        // Phase 3: IDE integration
        if (isIdeEnvironment && isTerminalPluginAvailable()) {
            register(TerminalContextProvider())
        }
        register(ProblemsContextProvider())
        register(GitDiffContextProvider())

        // Phase 4: Advanced features
        register(CodebaseContextProvider())
        register(UrlContextProvider())
        register(GrepSearchContextProvider())
        register(GitCommitContextProvider())
        register(DocsContextProvider())

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

    private fun isTerminalPluginAvailable(): Boolean {
        val pluginId = PluginId.getId("com.intellij.terminal")
        val plugin = PluginManagerCore.getPlugin(pluginId)
        return plugin?.isEnabled == true
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

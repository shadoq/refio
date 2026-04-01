package pl.jclab.refio.core.context

/**
 * Base interface for all context providers.
 *
 * Context providers fetch contextual information (files, terminal output, errors, etc.)
 * that can be attached to user queries.
 *
 * Inspired by Continue's context provider system.
 */
abstract class BaseContextProvider {

    /**
     * Unique metadata about this provider.
     */
    abstract val description: ContextProviderDescription

    /**
     * Fetch context items based on query.
     *
     * @param query User input (e.g., file pattern, search term)
     * @param extras Additional context (project, IDE interface)
     * @return List of context items
     */
    abstract suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem>

    /**
     * Optional: Load submenu items for interactive selection.
     *
     * @param args Arguments for loading submenu items
     * @return List of selectable items (e.g., recent files, open tabs)
     */
    open suspend fun loadSubmenuItems(
        args: LoadSubmenuItemsArgs
    ): List<ContextSubmenuItem> = emptyList()

    /**
     * Optional: Deprecation message if provider is being phased out.
     */
    open val deprecationMessage: String? = null

    /**
     * Environment required by this provider.
     * Providers marked IDE_ONLY will be skipped in non-IDE environments (e.g., CLI).
     */
    open val environment: ContextProviderEnvironment = ContextProviderEnvironment.ANY
}

/**
 * Metadata describing a context provider.
 */
data class ContextProviderDescription(
    val title: String,              // "file", "open", "terminal", "problems"
    val displayTitle: String,       // "Files", "Open Files", "Terminal", "Problems"
    val description: String,        // Human-readable description
    val type: ProviderType,         // NORMAL, QUERY, SUBMENU
    val icon: String? = null,       // Icon name (optional)
    val enabled: Boolean = true     // Whether this provider is enabled by default
)

/**
 * Environment in which a context provider can operate.
 */
enum class ContextProviderEnvironment {
    ANY,        // Works in any environment (CLI, IDE, etc.)
    IDE_ONLY    // Requires IntelliJ IDE (e.g., terminal, problems panel)
}

/**
 * Provider type determines UI interaction pattern.
 */
enum class ProviderType {
    NORMAL,     // Simple provider (e.g., clipboard, current file) - no input needed
    QUERY,      // Requires user input (e.g., file search, codebase search)
    SUBMENU     // Shows interactive menu (e.g., recent files, open files)
}

/**
 * Context item returned by provider.
 */
data class ContextItem(
    val description: String,        // "UserService.kt  /src/services/"
    val content: String,            // Actual content (code, text, etc.)
    val name: String,               // Display name
    val uri: ContextUri             // URI identifying the source
)

/**
 * URI identifying context source.
 */
data class ContextUri(
    val type: String,               // "file", "folder", "url", "terminal", etc.
    val value: String               // Path, URL, or identifier
)

/**
 * Extras passed to provider.
 */
data class ContextProviderExtras(
    val project: Any? = null,       // IntelliJ Project or null (opaque — IDE providers cast to Project)
    val fullInput: String = "",     // Full user input for context
    val workspacePath: String = ""  // Workspace root path
)

/**
 * Arguments for loading submenu items.
 */
data class LoadSubmenuItemsArgs(
    val query: String = "",
    val project: Any? = null        // IntelliJ Project (opaque — nullable for CLI)
)

/**
 * Submenu item for interactive selection.
 */
data class ContextSubmenuItem(
    val id: String,                 // Unique identifier (e.g., file path)
    val title: String,              // Display title
    val description: String = "",   // Additional info (e.g., file path)
    val icon: String? = null,       // Optional icon
    val metadata: Map<String, Any> = emptyMap()  // Additional metadata
)

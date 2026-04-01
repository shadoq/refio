package pl.jclab.refio.core.registry

import pl.jclab.refio.core.logging.dualLogger
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Abstract base class for registries that load definitions from MD files.
 *
 * Provides a 3-layer hierarchy with per-layer storage:
 * 1. BUILTIN - from classpath resources/{resourceDir}/
 * 2. USER - from ~/.refio/{resourceDir}/
 * 3. PROJECT - from {projectRoot}/.refio/{resourceDir}/
 *
 * Higher layers override lower layers by name (case-insensitive).
 * Read-only - subclasses handle CRUD if needed.
 *
 * @param T Type of definition (e.g. SubagentDefinition, PromptDefinition)
 * @param resourceDir Directory name in resources and ~/.refio/ (e.g. "subagents", "prompts")
 * @param projectRoot Path to project root directory (null = no project scope)
 */
abstract class FileBasedRegistry<T>(
    protected val resourceDir: String,
    protected val projectRoot: Path?
) {
    protected val logger = dualLogger("FileBasedRegistry[" + resourceDir + "]")

    protected val builtinItems = ConcurrentHashMap<String, T>()
    protected val userFileItems = ConcurrentHashMap<String, T>()
    protected val projectFileItems = ConcurrentHashMap<String, T>()

    private var lastLoadedAt: Long = 0
    private val cacheTtlMs = 60_000L // 1 minute

    // ==========================================
    // ABSTRACT - implemented by subclasses
    // ==========================================

    /** Parse file content (YAML frontmatter + body) into a definition. */
    abstract fun parseFile(content: String, sourcePath: Path?, scope: DefinitionScope): T

    /** Return the name/identifier from a definition (used as cache key, lowercased). */
    abstract fun getName(item: T): String

    /** Whether this definition is enabled. Default: true. */
    open fun isEnabled(item: T): Boolean = true

    // ==========================================
    // PUBLIC API
    // ==========================================

    /**
     * Load all definitions from the 3 file-based layers.
     * Clears existing cache before loading.
     * Subclasses may override to use different directory paths.
     */
    open fun loadAll() {
        builtinItems.clear()
        userFileItems.clear()
        projectFileItems.clear()

        // Layer 1: Builtin (lowest priority)
        loadBuiltinResources()

        // Layer 2: User files
        val userDir = Path.of(System.getProperty("user.home"), ".refio", resourceDir)
        loadFromDirectory(userDir, DefinitionScope.USER, userFileItems)

        // Layer 3: Project files (highest priority)
        projectRoot?.let { root ->
            loadFromDirectory(root.resolve(".refio/$resourceDir"), DefinitionScope.PROJECT, projectFileItems)
        }

        lastLoadedAt = System.currentTimeMillis()
    }

    /**
     * Get a definition by name with priority resolution: project > user > builtin.
     */
    fun get(name: String): T? {
        refreshIfNeeded()
        val key = name.lowercase()
        return projectFileItems[key] ?: userFileItems[key] ?: builtinItems[key]
    }

    /** Get only from builtin layer (for "show default" in UI). */
    fun getBuiltin(name: String): T? {
        refreshIfNeeded()
        return builtinItems[name.lowercase()]
    }

    /** Get only from user files layer. */
    fun getUserFile(name: String): T? {
        refreshIfNeeded()
        return userFileItems[name.lowercase()]
    }

    /** Get only from project files layer. */
    fun getProjectFile(name: String): T? {
        refreshIfNeeded()
        return projectFileItems[name.lowercase()]
    }

    /**
     * Get all definitions (merged view: project overrides user overrides builtin).
     */
    fun getAll(includeDisabled: Boolean = false): List<T> {
        refreshIfNeeded()
        val merged = LinkedHashMap<String, T>()
        builtinItems.forEach { (k, v) -> merged[k] = v }
        userFileItems.forEach { (k, v) -> merged[k] = v }
        projectFileItems.forEach { (k, v) -> merged[k] = v }
        return merged.values
            .let { if (includeDisabled) it.toList() else it.filter { item -> isEnabled(item) } }
    }

    /** Force reload from all sources. */
    fun refresh() {
        logger.info { "Refreshing cache..." }
        loadAll()
        logger.info { "Loaded ${builtinItems.size} builtin, ${userFileItems.size} user, ${projectFileItems.size} project items" }
    }

    /** Reload if TTL has expired. */
    fun refreshIfNeeded() {
        if (System.currentTimeMillis() - lastLoadedAt > cacheTtlMs) {
            refresh()
        }
    }

    /** Clear all caches (for testing). */
    fun clear() {
        builtinItems.clear()
        userFileItems.clear()
        projectFileItems.clear()
        lastLoadedAt = 0
    }

    /** Total number of unique items across all layers (merged). */
    fun size(): Int {
        refreshIfNeeded()
        val allKeys = mutableSetOf<String>()
        allKeys.addAll(builtinItems.keys)
        allKeys.addAll(userFileItems.keys)
        allKeys.addAll(projectFileItems.keys)
        return allKeys.size
    }

    // ==========================================
    // LOADING - classpath resources
    // ==========================================

    private fun loadBuiltinResources() {
        val resourcePaths = discoverBuiltinResources()

        logger.info { "Loading builtin resources (${resourcePaths.size}): ${resourcePaths.joinToString()}" }

        for (resourcePath in resourcePaths) {
            try {
                val content = readResourceContent(resourcePath)
                if (content != null) {
                    val definition = parseFile(content, null, DefinitionScope.BUILTIN)
                    builtinItems[getName(definition).lowercase()] = definition
                } else {
                    logger.warn { "Resource not found: $resourcePath" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load builtin resource: $resourcePath" }
            }
        }
    }

    /**
     * Discover .md files in classpath under [resourceDir].
     * Handles file:// (development) and jar:// (packaged) protocols.
     * Subclasses can override to add fallback logic.
     */
    protected open fun discoverBuiltinResources(): List<String> {
        val discovered = linkedSetOf<String>()
        val classLoaders = getClassLoaders()

        for (classLoader in classLoaders) {
            try {
                val urls = classLoader.getResources(resourceDir)
                while (urls.hasMoreElements()) {
                    val url = urls.nextElement()
                    when (url.protocol) {
                        "file" -> discovered.addAll(discoverFromFilesystem(url.toURI()))
                        "jar" -> discovered.addAll(discoverFromJar(url.openConnection() as JarURLConnection))
                        else -> logger.debug { "Unsupported resource protocol: ${url.protocol} ($url)" }
                    }
                }
            } catch (e: Exception) {
                logger.debug { "Failed to scan classpath: ${e.message}" }
            }
        }

        if (discovered.isEmpty()) {
            discovered.addAll(discoverFromFilesystem(Path.of("src", "main", "resources", resourceDir).toUri()))
        }

        return discovered.sorted()
    }

    private fun discoverFromFilesystem(directoryUri: java.net.URI): List<String> {
        val directory = try {
            Path.of(directoryUri)
        } catch (_: Exception) {
            return emptyList()
        }

        if (!directory.exists()) {
            return emptyList()
        }

        val result = mutableListOf<String>()
        Files.list(directory).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "md" }
                .forEach { result.add("$resourceDir/${it.fileName}") }
        }

        return result.sorted()
    }

    private fun discoverFromJar(connection: JarURLConnection): List<String> {
        val entryPrefix = connection.entryName?.let { "$it/" } ?: "$resourceDir/"
        val result = mutableListOf<String>()
        val entries = connection.jarFile.entries()

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.endsWith(".md")) {
                continue
            }

            if (entry.name.startsWith(entryPrefix)) {
                val relativePath = entry.name.removePrefix(entryPrefix)
                if (!relativePath.contains('/')) {
                    result.add(entry.name)
                }
            }
        }

        return result.sorted()
    }

    protected fun readResourceContent(resourcePath: String): String? {
        val classLoaders = getClassLoaders()

        for (classLoader in classLoaders) {
            classLoader.getResourceAsStream(resourcePath)?.use { input ->
                return input.bufferedReader().readText()
            }
        }

        return javaClass.getResourceAsStream("/$resourcePath")?.use { input ->
            input.bufferedReader().readText()
        }
    }

    // ==========================================
    // LOADING - filesystem directories
    // ==========================================

    protected fun loadFromDirectory(dir: Path, scope: DefinitionScope, target: ConcurrentHashMap<String, T>) {
        if (!dir.exists()) {
            logger.debug { "Directory does not exist: $dir" }
            return
        }

        try {
            Files.list(dir).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.extension == "md" }
                    .forEach { file ->
                        try {
                            val content = file.readText()
                            val definition = parseFile(content, file, scope)
                            val key = getName(definition).lowercase()
                            target[key] = definition
                            logger.debug { "Loaded $scope: ${getName(definition)} from $file" }
                        } catch (e: Exception) {
                            logger.warn { "Failed to load from $file: ${e.message}" }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to list directory $dir: ${e.message}" }
        }
    }

    private fun getClassLoaders(): List<ClassLoader> {
        return listOfNotNull(
            this::class.java.classLoader,
            javaClass.classLoader,
            Thread.currentThread().contextClassLoader
        ).distinct()
    }
}

package pl.jclab.refio.core.subagents

import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.subagents.models.SubagentScope
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

private val logger = dualLogger("SubagentRegistry")

/**
 * Rejestr subagentów z lazy loading i cache.
 *
 * Hierarchia rozwiązywania (od najwyższego priorytetu):
 * 1. Projekt: .refio/agents/<name>.md
 * 2. User: ~/.refio/agents/<name>.md
 * 3. Built-in: zasoby wbudowane (resources/subagents/)
 *
 * Przy konflikcie nazw, subagent z wyższego poziomu nadpisuje niższy.
 */
class SubagentRegistry(
    private val projectRoot: Path,
    private val parser: SubagentParser = SubagentParser()
) {
    private val cache = ConcurrentHashMap<String, SubagentDefinition>()
    private var lastScanTime: Long = 0
    private val cacheTtlMs = 60_000L // 1 minuta

    /**
     * Katalog subagentów w projekcie.
     */
    val projectAgentsDir: Path
        get() = projectRoot.resolve(".refio/agents")

    /**
     * Katalog subagentów użytkownika.
     */
    val userAgentsDir: Path
        get() = Path.of(System.getProperty("user.home"), ".refio", "agents")

    /**
     * Pobiera wszystkie dostępne subagenty.
     * Cache jest odświeżany automatycznie po upływie TTL.
     *
     * @param includeDisabled Czy uwzględnić wyłączone subagenty (dla panelu administracyjnego)
     * @return Lista subagentów posortowana wg priorytetu (malejąco)
     */
    fun getAllSubagents(includeDisabled: Boolean = false): List<SubagentDefinition> {
        refreshCacheIfNeeded()
        return cache.values
            .let { if (includeDisabled) it else it.filter { agent -> agent.enabled } }
            .sortedByDescending { it.priority }
    }

    /**
     * Pobiera subagenta po nazwie.
     *
     * @param name Nazwa subagenta (case-insensitive)
     * @return Definicja subagenta lub null jeśli nie znaleziono
     */
    fun getSubagent(name: String): SubagentDefinition? {
        refreshCacheIfNeeded()
        return cache[name.lowercase()]
    }

    /**
     * Sprawdza czy subagent istnieje.
     */
    fun exists(name: String): Boolean {
        return getSubagent(name) != null
    }

    /**
     * Wymusza odświeżenie cache.
     */
    fun refresh() {
        logger.info { "[SubagentRegistry] Refreshing cache..." }
        cache.clear()
        loadSubagents()
        lastScanTime = System.currentTimeMillis()
        logger.info { "[SubagentRegistry] Loaded ${cache.size} subagents" }
    }

    /**
     * Czyści cache (dla testów).
     */
    fun clear() {
        cache.clear()
        lastScanTime = 0
    }

    /**
     * Liczba subagentów w cache.
     */
    fun size(): Int {
        refreshCacheIfNeeded()
        return cache.size
    }

    /**
     * Wyszukuje subagentów pasujących do opisu/słów kluczowych.
     * Używane do auto-delegacji.
     *
     * @param keywords Słowa kluczowe do wyszukania
     * @return Lista pasujących subagentów posortowana wg priorytetu
     */
    fun findByKeywords(keywords: List<String>): List<SubagentDefinition> {
        if (keywords.isEmpty()) return emptyList()

        val keywordsLower = keywords.map { it.lowercase() }

        return getAllSubagents().filter { agent ->
            val agentText = "${agent.name} ${agent.description}".lowercase()
            keywordsLower.any { keyword -> agentText.contains(keyword) }
        }
    }

    /**
     * Odświeża cache jeśli minął TTL.
     */
    private fun refreshCacheIfNeeded() {
        if (System.currentTimeMillis() - lastScanTime > cacheTtlMs) {
            refresh()
        }
    }

    /**
     * Ładuje subagentów z wszystkich źródeł.
     * Kolejność jest ważna - późniejsze źródła nadpisują wcześniejsze.
     */
    private fun loadSubagents() {
        // 1. Ładuj wbudowane (najniższy priorytet)
        loadBuiltinSubagents()

        // 2. Ładuj user-level
        loadFromDirectory(userAgentsDir, SubagentScope.USER)

        // 3. Ładuj project-level (najwyższy priorytet - nadpisuje poprzednie)
        loadFromDirectory(projectAgentsDir, SubagentScope.PROJECT)
    }

    /**
     * Ładuje wbudowane subagenty z resources.
     */
    private fun loadBuiltinSubagents() {
        val builtinAgents = listOf(
            "security-reviewer",
            "code-reviewer"
        )

        logger.info { "[SubagentRegistry] Loading builtin subagents: ${builtinAgents.joinToString()}" }

        for (agentName in builtinAgents) {
            try {
                val resourcePath = "subagents/$agentName.md"
                logger.info { "[SubagentRegistry] Attempting to load: $resourcePath" }

                // Try multiple classloaders for better compatibility with IntelliJ plugins
                // Use SubagentRegistry class loader first (more reliable in plugin context)
                val classLoader = SubagentRegistry::class.java.classLoader
                logger.debug { "[SubagentRegistry] Trying SubagentRegistry classLoader" }
                val content = classLoader.getResourceAsStream(resourcePath)?.use { it.bufferedReader().readText() }
                    ?: run {
                        logger.debug { "[SubagentRegistry] SubagentRegistry classLoader failed, trying javaClass.getResourceAsStream" }
                        javaClass.getResourceAsStream("/$resourcePath")?.use { it.bufferedReader().readText() }
                    }
                    ?: run {
                        logger.debug { "[SubagentRegistry] javaClass failed, trying Thread.currentThread().contextClassLoader" }
                        Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)?.use { it.bufferedReader().readText() }
                    }
                    ?: run {
                        logger.debug { "[SubagentRegistry] Thread.currentThread() failed, trying javaClass.classLoader" }
                        javaClass.classLoader.getResourceAsStream(resourcePath)?.use { it.bufferedReader().readText() }
                    }

                if (content != null) {
                    logger.info { "[SubagentRegistry] ✓ Successfully loaded content for: $agentName (${content.length} chars)" }
                    val definition = parser.parse(content, null, SubagentScope.BUILTIN)
                    cache[definition.name.lowercase()] = definition
                    logger.info { "[SubagentRegistry] ✓ Loaded builtin subagent: ${definition.name} (scope=${definition.scope}, enabled=${definition.enabled}, priority=${definition.priority})" }
                } else {
                    logger.warn { "[SubagentRegistry] ✗ Resource not found: $resourcePath (tried 4 different classloaders)" }
                }
            } catch (e: Exception) {
                logger.error(e) { "[SubagentRegistry] Failed to load builtin subagent '$agentName'" }
            }
        }

        logger.info { "[SubagentRegistry] Finished loading builtin subagents: ${cache.size} total in cache" }
    }

    /**
     * Ładuje subagentów z katalogu.
     */
    private fun loadFromDirectory(dir: Path, scope: SubagentScope) {
        if (!dir.exists()) {
            logger.debug { "[SubagentRegistry] Directory does not exist: $dir" }
            return
        }

        try {
            Files.list(dir)
                .filter { it.isRegularFile() && it.extension == "md" }
                .forEach { file ->
                    loadFromFile(file, scope)
                }
        } catch (e: Exception) {
            logger.warn { "[SubagentRegistry] Failed to list directory $dir: ${e.message}" }
        }
    }

    /**
     * Ładuje pojedynczego subagenta z pliku.
     */
    private fun loadFromFile(file: Path, scope: SubagentScope) {
        try {
            val content = file.readText()
            val definition = parser.parse(content, file, scope)
            val key = definition.name.lowercase()

            // Sprawdź czy nadpisujemy
            val existing = cache[key]
            if (existing != null && existing.scope != scope) {
                logger.info {
                    "[SubagentRegistry] Overriding ${existing.scope} subagent '${existing.name}' with $scope version"
                }
            }

            cache[key] = definition
            logger.debug { "[SubagentRegistry] Loaded $scope: ${definition.name} from $file" }

        } catch (e: SubagentParseException) {
            logger.warn { "[SubagentRegistry] Failed to parse $file: ${e.message}" }
        } catch (e: Exception) {
            logger.error(e) { "[SubagentRegistry] Unexpected error loading $file" }
        }
    }

    /**
     * Tworzy nowego subagenta (zapisuje do pliku).
     *
     * @param definition Definicja do zapisania
     * @param targetScope Gdzie zapisać (PROJECT lub USER)
     * @return Zapisana definicja z zaktualizowaną ścieżką
     */
    fun createSubagent(definition: SubagentDefinition, targetScope: SubagentScope): SubagentDefinition {
        require(targetScope != SubagentScope.BUILTIN) { "Cannot create builtin subagent" }

        val targetDir = when (targetScope) {
            SubagentScope.PROJECT -> projectAgentsDir
            SubagentScope.USER -> userAgentsDir
            else -> throw IllegalArgumentException("Invalid scope")
        }

        Files.createDirectories(targetDir)
        val targetFile = targetDir.resolve("${definition.name}.md")

        if (targetFile.exists()) {
            throw SubagentAlreadyExistsException("Subagent already exists: ${definition.name}")
        }

        val content = buildSubagentFileContent(definition)
        Files.writeString(targetFile, content)

        val saved = definition.copy(sourcePath = targetFile, scope = targetScope)
        cache[saved.name.lowercase()] = saved

        logger.info { "[SubagentRegistry] Created subagent: ${saved.name} at $targetFile" }
        return saved
    }

    /**
     * Aktualizuje istniejącego subagenta.
     */
    fun updateSubagent(definition: SubagentDefinition): SubagentDefinition {
        val sourcePath = definition.sourcePath
            ?: throw IllegalArgumentException("Cannot update subagent without source path")

        if (!sourcePath.exists()) {
            throw SubagentNotFoundException("Subagent file not found: $sourcePath")
        }

        val content = buildSubagentFileContent(definition)
        Files.writeString(sourcePath, content)

        cache[definition.name.lowercase()] = definition

        logger.info { "[SubagentRegistry] Updated subagent: ${definition.name}" }
        return definition
    }

    /**
     * Usuwa subagenta.
     */
    fun deleteSubagent(name: String): Boolean {
        val definition = getSubagent(name)
            ?: throw SubagentNotFoundException("Subagent not found: $name")

        if (definition.scope == SubagentScope.BUILTIN) {
            throw IllegalArgumentException("Cannot delete builtin subagent")
        }

        val sourcePath = definition.sourcePath
            ?: throw IllegalArgumentException("Cannot delete subagent without source path")

        val deleted = Files.deleteIfExists(sourcePath)
        if (deleted) {
            cache.remove(name.lowercase())
            logger.info { "[SubagentRegistry] Deleted subagent: $name" }
        }

        return deleted
    }

    /**
     * Buduje zawartość pliku .md dla subagenta.
     */
    private fun buildSubagentFileContent(definition: SubagentDefinition): String {
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("name: ${definition.name}")
        sb.appendLine("description: ${definition.description}")

        definition.allowedTools?.let { tools ->
            if (tools.isNotEmpty()) {
                sb.appendLine("tools: ${tools.joinToString(", ") { SubagentParser.refioToClaudeCodeToolName(it) }}")
            }
        }

        definition.disallowedTools?.let { tools ->
            if (tools.isNotEmpty()) {
                sb.appendLine("disallowedTools: ${tools.joinToString(", ") { SubagentParser.refioToClaudeCodeToolName(it) }}")
            }
        }

        if (definition.model != "default") {
            sb.appendLine("model: ${definition.model}")
        }

        if (definition.skills.isNotEmpty()) {
            sb.appendLine("skills: [${definition.skills.joinToString(", ")}]")
        }

        if (!definition.enabled) {
            sb.appendLine("enabled: false")
        }

        if (definition.priority != 0) {
            sb.appendLine("priority: ${definition.priority}")
        }

        if (definition.executionMode != pl.jclab.refio.core.subagents.models.SubagentExecutionMode.SINGLE_SHOT) {
            sb.appendLine("executionMode: multi_step")
            sb.appendLine("maxSteps: ${definition.maxSteps}")
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine(definition.systemPrompt)

        return sb.toString()
    }
}

/**
 * Wyjątek: subagent już istnieje.
 */
class SubagentAlreadyExistsException(message: String) : Exception(message)

/**
 * Wyjątek: subagent nie znaleziony.
 */
class SubagentNotFoundException(message: String) : Exception(message)

/**
 * Wyjątek: subagent jest wyłączony.
 */
class SubagentDisabledException(message: String) : Exception(message)

package pl.jclab.refio.core.subagents

import pl.jclab.refio.core.registry.DefinitionScope
import pl.jclab.refio.core.registry.FileBasedRegistry
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.subagents.models.SubagentExecutionMode
import pl.jclab.refio.core.subagents.models.SubagentScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

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
    private val projectRootPath: Path,
    private val parser: SubagentParser = SubagentParser()
) : FileBasedRegistry<SubagentDefinition>("subagents", projectRootPath) {

    /**
     * Katalog subagentów w projekcie.
     */
    val projectAgentsDir: Path
        get() = projectRootPath.resolve(".refio/agents")

    /**
     * Katalog subagentów użytkownika.
     */
    val userAgentsDir: Path
        get() = Path.of(System.getProperty("user.home"), ".refio", "agents")

    // ==========================================
    // FileBasedRegistry overrides
    // ==========================================

    override fun parseFile(content: String, sourcePath: Path?, scope: DefinitionScope): SubagentDefinition {
        val subagentScope = when (scope) {
            DefinitionScope.BUILTIN -> SubagentScope.BUILTIN
            DefinitionScope.USER -> SubagentScope.USER
            DefinitionScope.PROJECT -> SubagentScope.PROJECT
        }
        return parser.parse(content, sourcePath, subagentScope)
    }

    override fun getName(item: SubagentDefinition): String = item.name

    override fun isEnabled(item: SubagentDefinition): Boolean = item.enabled

    /**
     * Override to add legacy fallback when classpath discovery finds nothing.
     */
    override fun discoverBuiltinResources(): List<String> {
        val discovered = super.discoverBuiltinResources()
        if (discovered.isNotEmpty()) return discovered

        // Legacy fallback to keep backward compatibility if discovery fails in uncommon environments.
        logger.warn { "Builtin discovery failed, using legacy fallback list" }
        return listOf("subagents/security-engineer.md", "subagents/code-reviewer.md")
    }

    /**
     * Override loadAll to use subagent-specific directories.
     * Subagents use "subagents/" for builtin resources but "agents/" for user/project directories.
     */
    override fun loadAll() {
        builtinItems.clear()
        userFileItems.clear()
        projectFileItems.clear()

        // Layer 1: Builtin (from resources/subagents/)
        // Handled by base class discoverBuiltinResources() which scans "subagents/" resourceDir
        loadBuiltinResourcesInternal()

        // Layer 2: User files (~/.refio/agents/)
        loadFromDirectory(userAgentsDir, DefinitionScope.USER, userFileItems)

        // Layer 3: Project files (.refio/agents/)
        loadFromDirectory(projectAgentsDir, DefinitionScope.PROJECT, projectFileItems)
    }

    private fun loadBuiltinResourcesInternal() {
        val resourcePaths = discoverBuiltinResources()

        logger.info { "Loading builtin subagents (${resourcePaths.size}): ${resourcePaths.joinToString()}" }

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
                logger.error(e) { "Failed to load builtin subagent: $resourcePath" }
            }
        }

        logger.info { "Finished loading builtin subagents: ${builtinItems.size} total" }
    }

    // ==========================================
    // PUBLIC API — subagent-specific
    // ==========================================

    /**
     * Pobiera wszystkie dostępne subagenty.
     * Cache jest odświeżany automatycznie po upływie TTL.
     *
     * @param includeDisabled Czy uwzględnić wyłączone subagenty (dla panelu administracyjnego)
     * @return Lista subagentów posortowana wg priorytetu (malejąco)
     */
    fun getAllSubagents(includeDisabled: Boolean = false): List<SubagentDefinition> {
        return getAll(includeDisabled).sortedByDescending { it.priority }
    }

    /**
     * Pobiera subagenta po nazwie.
     *
     * @param name Nazwa subagenta (case-insensitive)
     * @return Definicja subagenta lub null jeśli nie znaleziono
     */
    fun getSubagent(name: String): SubagentDefinition? {
        return get(name)
    }

    /**
     * Sprawdza czy subagent istnieje.
     */
    fun exists(name: String): Boolean {
        return get(name) != null
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

    // ==========================================
    // CRUD — subagent-specific
    // ==========================================

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
        val targetMap = when (targetScope) {
            SubagentScope.PROJECT -> projectFileItems
            SubagentScope.USER -> userFileItems
            else -> builtinItems
        }
        targetMap[saved.name.lowercase()] = saved

        logger.info { "Created subagent: ${saved.name} at $targetFile" }
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

        val targetMap = when (definition.scope) {
            SubagentScope.PROJECT -> projectFileItems
            SubagentScope.USER -> userFileItems
            SubagentScope.BUILTIN -> builtinItems
        }
        targetMap[definition.name.lowercase()] = definition

        logger.info { "Updated subagent: ${definition.name}" }
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
            val targetMap = when (definition.scope) {
                SubagentScope.PROJECT -> projectFileItems
                SubagentScope.USER -> userFileItems
                SubagentScope.BUILTIN -> builtinItems
            }
            targetMap.remove(name.lowercase())
            logger.info { "Deleted subagent: $name" }
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

        if (definition.executionMode != SubagentExecutionMode.SINGLE_SHOT) {
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

package pl.jclab.refio.core.services.context

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

// Loads project instruction files (AGENTS.md, .refio/agent.md) and conditional rules
// (.refio/rules/ with YAML frontmatter) into context for LLM consumption.
//
// Supported files:
// - .refio/agent.md — Refio-specific project instructions (highest priority)
// - AGENTS.md — universal standard (cascading: root + subdirectories)
// - .refio/rules/<name>.md — conditional rules with frontmatter (description, globs, alwaysApply)
class ProjectInstructionsLoader {

    data class InstructionFile(
        val source: String,
        val content: String,
        val priority: Int
    )

    data class ConditionalRule(
        val name: String,
        val description: String,
        val globs: List<String>,
        val alwaysApply: Boolean,
        val content: String
    )

    data class LoadedInstructions(
        val instructions: List<InstructionFile>,
        val rules: List<ConditionalRule>,
        val totalChars: Int
    ) {
        val isEmpty get() = instructions.isEmpty() && rules.isEmpty()
    }

    private data class CacheEntry(
        val result: LoadedInstructions,
        val loadedAt: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    companion object {
        private const val CACHE_TTL_MS = 30_000L
        private const val MAX_INSTRUCTION_CHARS = 8000
        private const val MAX_SINGLE_FILE_CHARS = 4000
    }

    /**
     * Load all project instructions for given project root and optional working subdirectory.
     * @param projectRoot the project root path
     * @param workingDir optional subdirectory the agent is working in (for cascading AGENTS.md)
     * @param activeFiles optional list of currently active/referenced file paths (for conditional rules)
     */
    fun load(
        projectRoot: Path,
        workingDir: Path? = null,
        activeFiles: List<String> = emptyList()
    ): LoadedInstructions {
        val cacheKey = "${projectRoot}|${workingDir ?: ""}|${activeFiles.hashCode()}"
        val cached = cache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < CACHE_TTL_MS) {
            return cached.result
        }

        val instructions = mutableListOf<InstructionFile>()

        // 1. .refio/agent.md (highest priority)
        loadFile(projectRoot.resolve(".refio/agent.md"), "refio", 1)?.let {
            instructions.add(it)
        }

        // 2. AGENTS.md at project root
        loadFile(projectRoot.resolve("AGENTS.md"), "AGENTS.md", 2)?.let {
            instructions.add(it)
        }

        // 3. Cascading AGENTS.md from subdirectories (if workingDir specified)
        if (workingDir != null && workingDir != projectRoot) {
            var dir: Path? = workingDir
            while (dir != null && dir != projectRoot && dir.startsWith(projectRoot)) {
                val agentsMd = dir.resolve("AGENTS.md")
                if (Files.exists(agentsMd) && agentsMd != projectRoot.resolve("AGENTS.md")) {
                    loadFile(agentsMd, "AGENTS.md (${projectRoot.relativize(dir)})", 3)?.let {
                        instructions.add(it)
                    }
                }
                dir = dir.parent
            }
        }

        // 4. Load conditional rules from .refio/rules/
        val rules = loadConditionalRules(projectRoot, activeFiles)

        val totalChars = instructions.sumOf { it.content.length } + rules.sumOf { it.content.length }

        val result = LoadedInstructions(
            instructions = instructions,
            rules = rules,
            totalChars = totalChars
        )

        cache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
        return result
    }

    fun invalidateCache() {
        cache.clear()
    }

    private fun loadFile(path: Path, source: String, priority: Int): InstructionFile? {
        if (!Files.exists(path) || !Files.isRegularFile(path)) return null
        return try {
            val content = Files.readString(path).trim()
            if (content.isBlank()) return null
            val truncated = if (content.length > MAX_SINGLE_FILE_CHARS) {
                content.take(MAX_SINGLE_FILE_CHARS) + "\n... (truncated)"
            } else content
            logger.debug { "Loaded project instructions from $source (${truncated.length} chars)" }
            InstructionFile(source, truncated, priority)
        } catch (e: Exception) {
            logger.warn { "Failed to load project instructions from $path: ${e.message}" }
            null
        }
    }

    private fun loadConditionalRules(
        projectRoot: Path,
        activeFiles: List<String>
    ): List<ConditionalRule> {
        val rulesDir = projectRoot.resolve(".refio/rules")
        if (!Files.exists(rulesDir) || !Files.isDirectory(rulesDir)) return emptyList()

        val rules = mutableListOf<ConditionalRule>()

        try {
            Files.list(rulesDir)
                .filter { it.toString().endsWith(".md") }
                .sorted()
                .forEach { rulePath ->
                    try {
                        val content = Files.readString(rulePath).trim()
                        if (content.isBlank()) return@forEach

                        val rule = parseRule(rulePath.fileName.toString().removeSuffix(".md"), content)
                        if (rule != null && shouldIncludeRule(rule, activeFiles)) {
                            rules.add(rule)
                        }
                    } catch (e: Exception) {
                        logger.warn { "Failed to parse rule file $rulePath: ${e.message}" }
                    }
                }
        } catch (e: Exception) {
            logger.warn { "Failed to list rules directory: ${e.message}" }
        }

        return rules
    }

    internal fun parseRule(name: String, content: String): ConditionalRule? {
        // Parse YAML frontmatter
        if (!content.startsWith("---")) {
            // No frontmatter — treat as always-apply rule
            return ConditionalRule(
                name = name,
                description = "",
                globs = emptyList(),
                alwaysApply = true,
                content = content.take(MAX_SINGLE_FILE_CHARS)
            )
        }

        val endIndex = content.indexOf("---", 3)
        if (endIndex < 0) return null

        val frontmatter = content.substring(3, endIndex).trim()
        val body = content.substring(endIndex + 3).trim()
        if (body.isBlank()) return null

        var description = ""
        var globs = emptyList<String>()
        var alwaysApply = false

        frontmatter.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("description:") -> {
                    description = trimmed.substringAfter("description:").trim().removeSurrounding("\"")
                }
                trimmed.startsWith("globs:") -> {
                    val globValue = trimmed.substringAfter("globs:").trim().removeSurrounding("\"")
                    globs = globValue.split(",").map { it.trim() }.filter { it.isNotBlank() }
                }
                trimmed.startsWith("alwaysApply:") -> {
                    alwaysApply = trimmed.substringAfter("alwaysApply:").trim().toBoolean()
                }
            }
        }

        return ConditionalRule(
            name = name,
            description = description,
            globs = globs,
            alwaysApply = alwaysApply,
            content = body.take(MAX_SINGLE_FILE_CHARS)
        )
    }

    internal fun shouldIncludeRule(rule: ConditionalRule, activeFiles: List<String>): Boolean {
        // Always-apply rules are always included
        if (rule.alwaysApply) return true

        // Glob-based rules: include if any active file matches
        if (rule.globs.isNotEmpty() && activeFiles.isNotEmpty()) {
            return activeFiles.any { file -> matchesAnyGlob(file, rule.globs) }
        }

        // No globs + not alwaysApply + no description = manual only (skip)
        // Has description but no globs = agent-requested (include for now, let LLM decide)
        return rule.description.isNotBlank()
    }

    internal fun matchesAnyGlob(filePath: String, globs: List<String>): Boolean {
        val normalizedPath = filePath.replace('\\', '/')
        return globs.any { glob -> matchGlob(normalizedPath, glob.trim()) }
    }

    private fun matchGlob(path: String, glob: String): Boolean {
        // Convert glob to regex
        val regex = buildString {
            append("^")
            var i = 0
            while (i < glob.length) {
                when (glob[i]) {
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            // ** matches any path
                            if (i + 2 < glob.length && glob[i + 2] == '/') {
                                append("(.*/)?")
                                i += 3
                            } else {
                                append(".*")
                                i += 2
                            }
                        } else {
                            append("[^/]*")
                            i++
                        }
                    }
                    '?' -> { append("[^/]"); i++ }
                    '.' -> { append("\\."); i++ }
                    else -> { append(glob[i]); i++ }
                }
            }
            append("$")
        }

        return try {
            Regex(regex).matches(path) || Regex(regex).matches(path.substringAfterLast('/'))
        } catch (e: Exception) {
            false
        }
    }
}

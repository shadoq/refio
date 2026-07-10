package pl.jclab.refio.core.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.analysis.project.FrameworkAnalysis
import pl.jclab.refio.core.services.analysis.project.FrameworkAnalyzer
import pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport
import pl.jclab.refio.core.services.analysis.project.RichProjectAnalysisEngine
import pl.jclab.refio.core.utils.AiIgnoreMatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

private val logger = dualLogger("ProjectAnalyzerService")

/**
 * Service for analyzing project structure, technologies, and code patterns.
 * Results are cached in memory for performance.
 *
 * Based on ADR 0018: Context Building & Visualization System
 */
class ProjectAnalyzerService(
    private val configService: ConfigService,
    private val richAnalysisEngine: RichProjectAnalysisEngine? = null
) {

    private val frameworkAnalyzer = FrameworkAnalyzer()
    private val cache = ConcurrentHashMap<String, Triple<ProjectAnalysis, Long, Long>>() // analysis, cacheTime, lastModified
    private val analysisMutexes = ConcurrentHashMap<String, Mutex>()
    private val cacheTTL = 600_000L // 10 minutes (reduced from 1 hour for fresher data)

    // Cached ignore matcher per project root, invalidated when .aiignore changes.
    // Avoids re-reading .aiignore from disk on every analyzeProject call.
    private val ignoreMatcherCache = ConcurrentHashMap<String, Pair<Long, AiIgnoreMatcher>>()

    /**
     * Analyze project and return comprehensive analysis
     */
    suspend fun analyzeProject(
        projectRoot: Path,
        includeContent: Boolean = false
    ): ProjectAnalysis {
        val cacheKey = buildCacheKey(projectRoot, includeContent)
        val now = System.currentTimeMillis()
        val ignoreMatcher = resolveIgnoreMatcher(projectRoot)
        val lastModified = getProjectLastModified(projectRoot, ignoreMatcher)

        // Check cache - invalidate if TTL expired OR files have been modified
        getCachedAnalysis(cacheKey, now, lastModified)?.let { return it }

        val mutex = analysisMutexes.computeIfAbsent(cacheKey) { Mutex() }
        return mutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedLastModified = getProjectLastModified(projectRoot, ignoreMatcher)
            getCachedAnalysis(cacheKey, lockedNow, lockedLastModified)?.let { return@withLock it }

            logger.info { "Starting project analysis: $projectRoot (single-flight)" }

            val fileTree = buildFileTree(projectRoot, ignoreMatcher)
            val structure = analyzeStructure(fileTree)
            val (technologies, infrastructure) = detectTechnologies(fileTree)
            val technologyVersions = detectTechnologyVersions(projectRoot, fileTree)
            val dependencies = analyzeDependencies(projectRoot, fileTree)
            val codeAnalysis = analyzeCodeStructure(projectRoot, fileTree, includeContent)
            val keyComponents = identifyKeyComponents(fileTree)
            val domainAnalysis = ProjectDomainScorer.analyzeProjectDomain(fileTree, structure)

            // ADR 0017: Detect architectural patterns
            val architectureInfo = ProjectArchitectureDetector.detectArchitecturalPatterns(fileTree)

            // Framework-aware analysis
            val allFilePaths = flattenFiles(fileTree).map { it.relativePath }
            val frameworkAnalysis = frameworkAnalyzer.analyze(allFilePaths, projectRoot)

            // Detect primary programming language
            val (primaryLanguage, _) = detectPrimaryLanguage(structure.fileTypes)

            val analysis = ProjectAnalysis(
                projectPath = projectRoot.toString(),
                structure = structure,
                technologies = technologies,
                infrastructure = infrastructure,
                technologyVersions = technologyVersions,
                dependencies = dependencies,
                codeAnalysis = codeAnalysis,
                keyComponents = keyComponents,
                projectType = domainAnalysis.primaryDomain,
                primaryLanguage = primaryLanguage,
                summary = generateSummary(structure, technologies, codeAnalysis, primaryLanguage),
                domainAnalysis = domainAnalysis,
                analyzedAt = System.currentTimeMillis(),
                architectureInfo = architectureInfo,
                frameworkAnalysis = frameworkAnalysis
            )

            val enrichedAnalysis = enrichWithRichReport(analysis, projectRoot)
            cache[cacheKey] = Triple(enrichedAnalysis, lockedNow, lockedLastModified)

            logger.info {
                "Project analysis completed: ${enrichedAnalysis.structure.totalFiles} files, ${technologies.size} technologies"
            }
            enrichedAnalysis
        }
    }

    private fun buildCacheKey(projectRoot: Path, includeContent: Boolean): String {
        return "${projectRoot.normalize()}|includeContent=$includeContent"
    }

    private fun getCachedAnalysis(
        cacheKey: String,
        now: Long,
        lastModified: Long
    ): ProjectAnalysis? {
        cache[cacheKey]?.let { (analysis, cacheTime, cachedLastModified) ->
            val isCacheValid = (now - cacheTime < cacheTTL) && (lastModified <= cachedLastModified)
            if (isCacheValid) {
                logger.debug { "Using cached project analysis for $cacheKey (age=${(now - cacheTime) / 1000}s)" }
                return analysis
            }

            val reason = if (now - cacheTime >= cacheTTL) "TTL expired" else "files modified"
            logger.debug { "Cache invalid for $cacheKey: $reason" }
        }
        return null
    }

    /**
     * Get last modification time of project (newest file)
     * Used for cache invalidation
     */
    private fun getProjectLastModified(projectRoot: Path, ignoreMatcher: AiIgnoreMatcher): Long {
        return try {
            var newest = 0L
            var checkedFiles = 0
            val maxFilesToCheck = 100 // Limit for performance

            Files.walk(projectRoot, 5) // Max depth 5 to cover deeper project structures
                .filter { !it.isDirectory() }
                .filter { path -> !ignoreMatcher.isIgnored(relativePath(projectRoot, path), isDirectory = false) }
                .limit(maxFilesToCheck.toLong())
                .forEach { path ->
                    try {
                        val modified = Files.getLastModifiedTime(path).toMillis()
                        if (modified > newest) newest = modified
                        checkedFiles++
                    } catch (e: Exception) {
                        // Skip files we can't read
                    }
                }

            logger.debug { "Checked $checkedFiles files, newest modification: $newest" }
            newest
        } catch (e: Exception) {
            logger.warn { "Failed to get project last modified time: ${e.message}" }
            System.currentTimeMillis() // Fallback to current time (forces re-analysis)
        }
    }

    /**
     * Invalidate cache for project
     */
    fun invalidateCache(projectRoot: Path) {
        val normalizedRoot = projectRoot.normalize().toString()
        cache.keys.removeIf { it.startsWith("$normalizedRoot|") }
        ignoreMatcherCache.remove(normalizedRoot)
        logger.info { "Invalidated cache for $normalizedRoot" }
    }

    /**
     * Build file tree from project root
     * Ignores: .git, node_modules, build, target, .idea, .vscode
     */
    private fun buildFileTree(projectRoot: Path, ignoreMatcher: AiIgnoreMatcher): FileNode {
        if (!Files.exists(projectRoot)) {
            throw IllegalArgumentException("Project root does not exist: $projectRoot")
        }

        fun walk(path: Path): FileNode? {
            val name = path.fileName.toString()
            val relative = relativePath(projectRoot, path)
            val ignored = ignoreMatcher.isIgnored(relative, isDirectory = path.isDirectory())

            if (path.isDirectory()) {
                if (ignored) {
                    return FileNode(name, true, emptyList(), relative)
                }

                val children = try {
                    path.listDirectoryEntries()
                        .filter { !it.fileName.toString().startsWith(".") || it.fileName.toString() in setOf(".env", ".gitignore") }
                        .mapNotNull { walk(it) }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                } catch (e: Exception) {
                    logger.warn { "Failed to read directory ${path}: ${e.message}" }
                    emptyList()
                }

                return FileNode(name, true, children, relative)
            } else {
                if (ignored) return null
                return FileNode(name, false, emptyList(), relative)
            }
        }

        return walk(projectRoot) ?: FileNode(projectRoot.fileName.toString(), true, emptyList(), "")
    }

    /**
     * Analyze project structure (file counts, depth, types)
     */
    private fun analyzeStructure(fileTree: FileNode): StructureInfo {
        var totalFiles = 0
        var maxDepth = 0
        val fileTypes = mutableMapOf<String, Int>()
        val topLevelItems = fileTree.children.map { it.name }

        fun walk(node: FileNode, depth: Int) {
            if (depth > maxDepth) maxDepth = depth

            if (!node.isDirectory) {
                totalFiles++
                val extension = node.name.substringAfterLast('.', "")
                if (extension.isNotEmpty() && extension != node.name) {
                    fileTypes[".$extension"] = fileTypes.getOrDefault(".$extension", 0) + 1
                }
            } else {
                node.children.forEach { walk(it, depth + 1) }
            }
        }

        walk(fileTree, 0)

        return StructureInfo(
            totalFiles = totalFiles,
            maxDepth = maxDepth,
            fileTypes = fileTypes,
            topLevelItems = topLevelItems,
            directoryCount = countDirectories(fileTree)
        )
    }

    private fun countDirectories(node: FileNode): Int {
        if (!node.isDirectory) return 0
        return 1 + node.children.sumOf { countDirectories(it) }
    }

    /**
     * Detect technologies from file extensions and config files.
     * Returns pair of (technologies, infrastructure).
     * Infrastructure tools (Docker, K8s, CI/CD) are separated from main technologies.
     */
    private fun detectTechnologies(fileTree: FileNode): Pair<List<String>, List<String>> {
        val technologies = mutableSetOf<String>()
        val infrastructure = mutableSetOf<String>()
        val files = flattenFiles(fileTree)

        // Language detection from extensions
        val extensionToTech = mapOf(
            ".kt" to "Kotlin",
            ".java" to "Java",
            ".py" to "Python",
            ".js" to "JavaScript",
            ".ts" to "TypeScript",
            ".jsx" to "React",
            ".tsx" to "React",
            ".vue" to "Vue.js",
            ".go" to "Go",
            ".rs" to "Rust",
            ".cpp" to "C++",
            ".c" to "C",
            ".cs" to "C#",
            ".rb" to "Ruby",
            ".php" to "PHP",
            ".swift" to "Swift",
            ".html" to "HTML",
            ".css" to "CSS",
            ".scss" to "SASS",
            ".sql" to "SQL"
        )

        files.forEach { file ->
            val ext = file.name.substringAfterLast('.', "").let { ".$it" }
            extensionToTech[ext]?.let { technologies.add(it) }
        }

        // Framework detection from config files (main technologies)
        val configToTech = mapOf(
            "package.json" to "Node.js",
            "requirements.txt" to "Python",
            "Pipfile" to "Python",
            "pom.xml" to "Maven",
            "build.gradle" to "Gradle",
            "build.gradle.kts" to "Gradle",
            "Cargo.toml" to "Rust",
            "go.mod" to "Go",
            "composer.json" to "PHP",
            "Gemfile" to "Ruby",
            "tsconfig.json" to "TypeScript",
            "angular.json" to "Angular",
            "vue.config.js" to "Vue.js",
            "next.config.js" to "Next.js",
            "nuxt.config.js" to "Nuxt.js",
            "webpack.config.js" to "Webpack",
            "vite.config.js" to "Vite",
            "vite.config.ts" to "Vite"
        )

        // Infrastructure tools (separate from main tech stack)
        val configToInfra = mapOf(
            "Dockerfile" to "Docker",
            "docker-compose.yml" to "Docker",
            "docker-compose.yaml" to "Docker",
            "kubernetes.yaml" to "Kubernetes",
            "k8s.yaml" to "Kubernetes",
            ".github/workflows" to "GitHub Actions",
            "Jenkinsfile" to "Jenkins",
            ".gitlab-ci.yml" to "GitLab CI",
            "azure-pipelines.yml" to "Azure DevOps",
            "terraform.tf" to "Terraform",
            "ansible.yml" to "Ansible"
        )

        files.forEach { file ->
            configToTech[file.name]?.let { technologies.add(it) }
            configToInfra[file.name]?.let { infrastructure.add(it) }

            // Check for CI/CD directories
            if (file.relativePath.contains(".github/workflows")) {
                infrastructure.add("GitHub Actions")
            }
        }

        return Pair(technologies.sorted(), infrastructure.sorted())
    }

    private fun detectTechnologyVersions(projectRoot: Path, fileTree: FileNode): Map<String, String?> {
        val files = flattenFiles(fileTree)
        val versions = mutableMapOf<String, String?>()

        files.forEach { file ->
            when (file.name) {
                "package.json" -> {
                    val packageJson = ProjectDependencyParsers.parsePackageJsonWithVersions(projectRoot.resolve(file.relativePath))
                    packageJson["react"]?.let { versions["React"] = it }
                    packageJson["next"]?.let { versions["Next.js"] = it }
                    packageJson["express"]?.let { versions["Express"] = it }
                    packageJson["vue"]?.let { versions["Vue.js"] = it }
                    packageJson["angular"]?.let { versions["Angular"] = it }
                }
                "requirements.txt" -> {
                    val reqs = ProjectDependencyParsers.parseRequirementsWithVersions(projectRoot.resolve(file.relativePath))
                    reqs["fastapi"]?.let { versions["FastAPI"] = it }
                    reqs["django"]?.let { versions["Django"] = it }
                    reqs["flask"]?.let { versions["Flask"] = it }
                    reqs["sqlalchemy"]?.let { versions["SQLAlchemy"] = it }
                    reqs["pydantic"]?.let { versions["Pydantic"] = it }
                }
                "build.gradle", "build.gradle.kts" -> {
                    val gradle = ProjectDependencyParsers.parseGradlePluginVersions(projectRoot.resolve(file.relativePath))
                    gradle["org.springframework.boot"]?.let { versions["Spring Boot"] = it }
                    gradle["org.jetbrains.kotlin.jvm"]?.let { versions["Kotlin"] = it }
                }
            }
        }

        return versions
    }

    /**
     * Analyze dependencies from package managers
     */
    private fun analyzeDependencies(projectRoot: Path, fileTree: FileNode): DependenciesInfo {
        val python = mutableListOf<String>()
        val javascript = mutableListOf<String>()
        val typescript = mutableListOf<String>()
        val kotlin = mutableListOf<String>()
        val java = mutableListOf<String>()
        val cpp = mutableListOf<String>()
        val packageManagers = mutableListOf<String>()
        val configFiles = mutableListOf<String>()

        val files = flattenFiles(fileTree)

        // Check for package manager files
        files.forEach { file ->
            when (file.name) {
                "requirements.txt" -> {
                    packageManagers.add("pip")
                    configFiles.add(file.relativePath)
                    python.addAll(ProjectDependencyParsers.parseRequirementsTxt(projectRoot.resolve(file.relativePath)))
                }
                "Pipfile" -> {
                    packageManagers.add("pipenv")
                    configFiles.add(file.relativePath)
                }
                "package.json" -> {
                    val path = projectRoot.resolve(file.relativePath)
                    configFiles.add(file.relativePath)
                    val deps = ProjectDependencyParsers.parsePackageJson(path)
                    javascript.addAll(deps)
                    // Check if TypeScript project
                    if (deps.any { it == "typescript" || it.startsWith("@types/") }) {
                        packageManagers.add("npm/yarn (TypeScript)")
                        typescript.addAll(deps)
                    } else {
                        packageManagers.add("npm/yarn")
                    }
                }
                "pom.xml" -> {
                    packageManagers.add("maven")
                    configFiles.add(file.relativePath)
                    java.addAll(ProjectDependencyParsers.parseMavenDependencies(projectRoot.resolve(file.relativePath)))
                }
                "build.gradle", "build.gradle.kts" -> {
                    packageManagers.add("gradle")
                    configFiles.add(file.relativePath)
                    val deps = ProjectDependencyParsers.parseGradleDependencies(projectRoot.resolve(file.relativePath))
                    kotlin.addAll(deps)
                    java.addAll(deps)
                }
                "Cargo.toml" -> {
                    packageManagers.add("cargo")
                    configFiles.add(file.relativePath)
                }
                "CMakeLists.txt" -> {
                    packageManagers.add("cmake")
                    configFiles.add(file.relativePath)
                    cpp.addAll(ProjectDependencyParsers.parseCMakeDependencies(projectRoot.resolve(file.relativePath)))
                }
                "conanfile.txt", "conanfile.py" -> {
                    packageManagers.add("conan")
                    configFiles.add(file.relativePath)
                }
                "vcpkg.json" -> {
                    packageManagers.add("vcpkg")
                    configFiles.add(file.relativePath)
                    cpp.addAll(ProjectDependencyParsers.parseVcpkgDependencies(projectRoot.resolve(file.relativePath)))
                }
            }
        }

        return DependenciesInfo(
            python = python.distinct().sorted(),
            javascript = javascript.distinct().sorted(),
            typescript = typescript.distinct().sorted(),
            kotlin = kotlin.distinct().sorted(),
            java = java.distinct().sorted(),
            cpp = cpp.distinct().sorted(),
            packageManagers = packageManagers.distinct().sorted(),
            configFiles = configFiles.sorted()
        )
    }

    /**
     * Analyze code structure (classes, functions, patterns)
     */
    private fun analyzeCodeStructure(
        projectRoot: Path,
        fileTree: FileNode,
        includeContent: Boolean
    ): CodeAnalysisInfo {
        val kotlin = ProjectLanguageAnalyzers.analyzeKotlinCode(projectRoot, fileTree, includeContent)
        val java = ProjectLanguageAnalyzers.analyzeJavaCode(projectRoot, fileTree, includeContent)
        val python = ProjectLanguageAnalyzers.analyzePythonCode(projectRoot, fileTree, includeContent)
        val javascript = ProjectLanguageAnalyzers.analyzeJavaScriptCode(projectRoot, fileTree, includeContent)
        val typescript = ProjectLanguageAnalyzers.analyzeTypeScriptCode(projectRoot, fileTree, includeContent)
        val html = ProjectLanguageAnalyzers.analyzeHtmlCode(projectRoot, fileTree, includeContent)
        val css = ProjectLanguageAnalyzers.analyzeCssCode(projectRoot, fileTree, includeContent)

        return CodeAnalysisInfo(
            kotlin = kotlin,
            java = java,
            python = python,
            javascript = javascript,
            typescript = typescript,
            html = html,
            css = css
        )
    }

    /**
     * Identify key components (entry points, configs, important files)
     */
    private fun identifyKeyComponents(fileTree: FileNode): List<String> {
        val keyFiles = listOf(
            "README.md", "CHANGELOG.md", "LICENSE",
            "main.kt", "main.kt", "Main.java", "main.py", "__main__.py",
            "index.js", "index.ts", "App.tsx", "App.jsx",
            "package.json", "requirements.txt", "pom.xml", "build.gradle.kts",
            "Dockerfile", "docker-compose.yml", ".env.example"
        )

        val files = flattenFiles(fileTree)
        return files
            .filter { keyFiles.contains(it.name) }
            .map { it.relativePath }
            .sorted()
    }

    /**
     * Generate summary from analysis
     */
    private fun generateSummary(
        structure: StructureInfo,
        technologies: List<String>,
        codeAnalysis: CodeAnalysisInfo,
        primaryLanguage: String = "Unknown"
    ): SummaryInfo {
        // Determine project type based on primary language and frameworks
        val projectType = when {
            primaryLanguage == "Kotlin" && technologies.contains("Gradle") -> "Kotlin Application"
            primaryLanguage == "Kotlin" -> "Kotlin Project"
            primaryLanguage == "Java" && technologies.contains("Maven") -> "Java Maven Application"
            primaryLanguage == "Java" && technologies.contains("Gradle") -> "Java Gradle Application"
            primaryLanguage == "Java" -> "Java Application"
            primaryLanguage == "Python" && technologies.contains("FastAPI") -> "Python FastAPI Application"
            primaryLanguage == "Python" && technologies.contains("Django") -> "Python Django Application"
            primaryLanguage == "Python" -> "Python Application"
            primaryLanguage == "TypeScript" && technologies.contains("React") -> "React TypeScript Application"
            primaryLanguage == "TypeScript" && technologies.contains("Angular") -> "Angular Application"
            primaryLanguage == "TypeScript" && technologies.contains("Next.js") -> "Next.js Application"
            primaryLanguage == "TypeScript" -> "TypeScript Application"
            primaryLanguage == "JavaScript" && technologies.contains("React") -> "React Application"
            primaryLanguage == "JavaScript" && technologies.contains("Vue.js") -> "Vue.js Application"
            primaryLanguage == "JavaScript" -> "JavaScript Application"
            primaryLanguage == "Go" -> "Go Application"
            primaryLanguage == "Rust" -> "Rust Application"
            primaryLanguage == "C#" -> "C# Application"
            primaryLanguage == "C++" -> "C++ Application"
            primaryLanguage != "Unknown" -> "$primaryLanguage Project"
            technologies.contains("React") || technologies.contains("Vue.js") -> "Frontend Application"
            else -> "Software Project"
        }

        val complexity = when {
            structure.totalFiles < 10 -> "Low"
            structure.totalFiles < 50 -> "Medium"
            structure.totalFiles < 200 -> "High"
            else -> "Very High"
        }

        // Use detected primary language, not first from technologies list
        val mainLanguage = if (primaryLanguage != "Unknown") {
            primaryLanguage
        } else {
            // Fallback to first programming language in technologies (not frameworks)
            val programmingLangs = listOf("Kotlin", "Java", "Python", "TypeScript", "JavaScript", "Go", "Rust", "C#", "C++", "C", "Ruby", "PHP", "Swift")
            technologies.firstOrNull { it in programmingLangs } ?: "Unknown"
        }

        val semanticDescription = buildSemanticDescription(structure, technologies, codeAnalysis, primaryLanguage)

        return SummaryInfo(
            projectType = projectType,
            complexity = complexity,
            mainLanguage = mainLanguage,
            fileCount = structure.totalFiles,
            architectureNotes = "Standard project structure",
            semanticDescription = semanticDescription
        )
    }

    private fun buildSemanticDescription(
        structure: StructureInfo,
        technologies: List<String>,
        codeAnalysis: CodeAnalysisInfo,
        primaryLanguage: String
    ): String {
        val parts = mutableListOf<String>()

        when {
            technologies.contains("FastAPI") -> parts.add("FastAPI REST API")
            technologies.contains("Django") -> parts.add("Django web application")
            technologies.contains("React") && technologies.contains("Next.js") -> parts.add("Next.js React application")
            technologies.contains("React") -> parts.add("React frontend application")
            technologies.contains("Spring") -> parts.add("Spring Boot application")
            primaryLanguage != "Unknown" -> parts.add("$primaryLanguage application")
            else -> parts.add("Software project")
        }

        when {
            technologies.contains("SQLAlchemy") -> parts.add("with SQLAlchemy ORM")
            technologies.contains("JPA") -> parts.add("with JPA/Hibernate")
            technologies.contains("Prisma") -> parts.add("with Prisma ORM")
            technologies.contains("MongoDB") -> parts.add("with MongoDB")
        }

        val pythonAsync = (codeAnalysis.python["async_functions"] as? Int ?: 0) > 5
        if (pythonAsync) {
            parts.add("using async patterns")
        }

        if (structure.totalFiles < 10) {
            parts.add("small codebase")
        }

        return parts.joinToString(" ")
    }

    private suspend fun enrichWithRichReport(
        analysis: ProjectAnalysis,
        projectRoot: Path
    ): ProjectAnalysis {
        val engine = richAnalysisEngine ?: return analysis
        return try {
            val report = engine.analyzeProject(projectRoot)
        val updatedSummary = analysis.summary.copy(
            architectureNotes = report.architecture.style ?: analysis.summary.architectureNotes
        )
            analysis.copy(summary = updatedSummary, richReport = report)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate rich project analysis for ${projectRoot.fileName}" }
            analysis
        }
    }

    private fun resolveIgnoreMatcher(projectRoot: Path): AiIgnoreMatcher {
        val cacheKey = projectRoot.normalize().toString()
        val aiIgnoreLastModified = aiIgnoreLastModified(projectRoot)

        ignoreMatcherCache[cacheKey]?.let { (cachedLastModified, matcher) ->
            if (cachedLastModified == aiIgnoreLastModified) {
                return matcher
            }
        }

        val patterns = configService.getTyped<List<String>>(ConfigKeys.RAG_IGNORED_DIRECTORIES).toSet()
        val matcher = try {
            AiIgnoreMatcher.load(projectRoot) ?: AiIgnoreMatcher.fromPatterns(patterns)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${AiIgnoreMatcher.FILE_NAME}; using default ignore patterns" }
            AiIgnoreMatcher.fromPatterns(patterns)
        }
        ignoreMatcherCache[cacheKey] = aiIgnoreLastModified to matcher
        return matcher
    }

    /** Last-modified stamp of .aiignore, or -1 when absent/unreadable (still cacheable). */
    private fun aiIgnoreLastModified(projectRoot: Path): Long {
        return try {
            val file = projectRoot.resolve(AiIgnoreMatcher.FILE_NAME)
            if (Files.exists(file)) Files.getLastModifiedTime(file).toMillis() else -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun relativePath(projectRoot: Path, path: Path): String {
        return try {
            projectRoot.relativize(path).toString()
        } catch (_: Exception) {
            path.fileName.toString()
        }
    }

}

// Helper functions shared with the extracted analyzer objects in this package

/**
 * Flattens the file tree into a list of file (non-directory) nodes.
 */
internal fun flattenFiles(node: FileNode): List<FileNode> {
    val result = mutableListOf<FileNode>()
    fun walk(n: FileNode) {
        if (!n.isDirectory) {
            result.add(n)
        } else {
            n.children.forEach { walk(it) }
        }
    }
    walk(node)
    return result
}

/**
 * Detect primary programming language based on file counts.
 * Returns pair of (language name, file count).
 */
internal fun detectPrimaryLanguage(fileTypes: Map<String, Int>): Pair<String, Int> {
    val codeExtensions = mapOf(
        ".kt" to "Kotlin",
        ".java" to "Java",
        ".py" to "Python",
        ".ts" to "TypeScript",
        ".tsx" to "TypeScript",
        ".js" to "JavaScript",
        ".jsx" to "JavaScript",
        ".go" to "Go",
        ".rs" to "Rust",
        ".cs" to "C#",
        ".cpp" to "C++",
        ".c" to "C",
        ".rb" to "Ruby",
        ".php" to "PHP",
        ".swift" to "Swift"
    )

    // Count files per language (merge tsx->ts, jsx->js)
    val languageCounts = mutableMapOf<String, Int>()
    codeExtensions.forEach { (ext, lang) ->
        val count = fileTypes[ext] ?: 0
        if (count > 0) {
            languageCounts[lang] = (languageCounts[lang] ?: 0) + count
        }
    }

    return languageCounts.maxByOrNull { it.value }?.toPair() ?: ("Unknown" to 0)
}

// Data classes
data class FileNode(
    val name: String,
    val isDirectory: Boolean,
    val children: List<FileNode>,
    val relativePath: String
)

data class ProjectAnalysis(
    val projectPath: String,
    val structure: StructureInfo,
    val technologies: List<String>,
    val infrastructure: List<String> = emptyList(),  // Docker, K8s, CI/CD - separate from main tech
    val technologyVersions: Map<String, String?> = emptyMap(),
    val dependencies: DependenciesInfo,
    val codeAnalysis: CodeAnalysisInfo,
    val keyComponents: List<String>,
    val projectType: String,
    val primaryLanguage: String = "Unknown",  // Main programming language
    val summary: SummaryInfo,
    val domainAnalysis: DomainAnalysis,
    val analyzedAt: Long,
    val richReport: ProjectAnalysisReport? = null,
    val architectureInfo: ArchitectureInfo? = null,  // ADR 0017: Enhanced architecture analysis
    val frameworkAnalysis: FrameworkAnalysis? = null
)

data class StructureInfo(
    val totalFiles: Int,
    val maxDepth: Int,
    val fileTypes: Map<String, Int>,
    val topLevelItems: List<String>,
    val directoryCount: Int
)

data class DependenciesInfo(
    val python: List<String>,
    val javascript: List<String>,
    val typescript: List<String> = emptyList(),
    val kotlin: List<String> = emptyList(),
    val java: List<String> = emptyList(),
    val cpp: List<String> = emptyList(),
    val packageManagers: List<String>,
    val configFiles: List<String>
)

data class CodeAnalysisInfo(
    val kotlin: Map<String, Any>,
    val java: Map<String, Any>,
    val python: Map<String, Any>,
    val javascript: Map<String, Any>,
    val typescript: Map<String, Any>,
    val html: Map<String, Any>,
    val css: Map<String, Any>
)

data class SummaryInfo(
    val projectType: String,
    val complexity: String,
    val mainLanguage: String,
    val fileCount: Int,
    val architectureNotes: String,
    val semanticDescription: String? = null,
    val keyCapabilities: List<String> = emptyList(),
    val entryPoints: List<String> = emptyList()
)

data class DomainAnalysis(
    val primaryDomain: String,
    val confidenceScore: Double,
    val domainScores: Map<String, Double>
)

// ADR 0017: Enhanced architecture analysis
data class ArchitectureInfo(
    val patterns: List<String>,
    val entryPoints: List<EntryPoint>,
    val modules: List<ModuleInfo>,
    val style: String
)

data class EntryPoint(
    val path: String,
    val description: String,
    val language: String
)

data class ModuleInfo(
    val name: String,
    val path: String,
    val fileCount: Int,
    val primaryType: String,
    val description: String
)

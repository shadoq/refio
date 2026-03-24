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
            val domainAnalysis = analyzeProjectDomain(fileTree, structure)

            // ADR 0017: Detect architectural patterns
            val architectureInfo = detectArchitecturalPatterns(fileTree)

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
                    val packageJson = parsePackageJsonWithVersions(projectRoot.resolve(file.relativePath))
                    packageJson["react"]?.let { versions["React"] = it }
                    packageJson["next"]?.let { versions["Next.js"] = it }
                    packageJson["express"]?.let { versions["Express"] = it }
                    packageJson["vue"]?.let { versions["Vue.js"] = it }
                    packageJson["angular"]?.let { versions["Angular"] = it }
                }
                "requirements.txt" -> {
                    val reqs = parseRequirementsWithVersions(projectRoot.resolve(file.relativePath))
                    reqs["fastapi"]?.let { versions["FastAPI"] = it }
                    reqs["django"]?.let { versions["Django"] = it }
                    reqs["flask"]?.let { versions["Flask"] = it }
                    reqs["sqlalchemy"]?.let { versions["SQLAlchemy"] = it }
                    reqs["pydantic"]?.let { versions["Pydantic"] = it }
                }
                "build.gradle", "build.gradle.kts" -> {
                    val gradle = parseGradlePluginVersions(projectRoot.resolve(file.relativePath))
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
                    python.addAll(parseRequirementsTxt(projectRoot.resolve(file.relativePath)))
                }
                "Pipfile" -> {
                    packageManagers.add("pipenv")
                    configFiles.add(file.relativePath)
                }
                "package.json" -> {
                    val path = projectRoot.resolve(file.relativePath)
                    configFiles.add(file.relativePath)
                    val deps = parsePackageJson(path)
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
                    java.addAll(parseMavenDependencies(projectRoot.resolve(file.relativePath)))
                }
                "build.gradle", "build.gradle.kts" -> {
                    packageManagers.add("gradle")
                    configFiles.add(file.relativePath)
                    val deps = parseGradleDependencies(projectRoot.resolve(file.relativePath))
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
                    cpp.addAll(parseCMakeDependencies(projectRoot.resolve(file.relativePath)))
                }
                "conanfile.txt", "conanfile.py" -> {
                    packageManagers.add("conan")
                    configFiles.add(file.relativePath)
                }
                "vcpkg.json" -> {
                    packageManagers.add("vcpkg")
                    configFiles.add(file.relativePath)
                    cpp.addAll(parseVcpkgDependencies(projectRoot.resolve(file.relativePath)))
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

    private fun parseRequirementsTxt(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            Files.readAllLines(path)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split("==", ">=", "<=", "~=", "!=")[0].trim() }
        } catch (e: Exception) {
            logger.warn { "Failed to parse requirements.txt: ${e.message}" }
            emptyList()
        }
    }

    private fun parseRequirementsWithVersions(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        return try {
            Files.readAllLines(path)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val match = Regex("""^([A-Za-z0-9_.-]+)\s*([=<>!~]+)\s*([A-Za-z0-9_.-]+)""").find(line)
                    if (match != null) {
                        val name = match.groupValues[1]
                        val version = match.groupValues[3]
                        name to version
                    } else {
                        null
                    }
                }
                .toMap()
        } catch (e: Exception) {
            logger.warn { "Failed to parse requirements versions: ${e.message}" }
            emptyMap()
        }
    }

    private fun parsePackageJson(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            val deps = mutableListOf<String>()

            json.getAsJsonObject("dependencies")?.keySet()?.let { deps.addAll(it) }
            json.getAsJsonObject("devDependencies")?.keySet()?.let { deps.addAll(it) }

            deps.sorted()
        } catch (e: Exception) {
            logger.warn { "Failed to parse package.json: ${e.message}" }
            emptyList()
        }
    }

    private fun parsePackageJsonWithVersions(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        return try {
            val content = Files.readString(path)
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            val deps = mutableMapOf<String, String>()

            json.getAsJsonObject("dependencies")?.entrySet()?.forEach {
                deps[it.key] = it.value.asString
            }
            json.getAsJsonObject("devDependencies")?.entrySet()?.forEach {
                deps.putIfAbsent(it.key, it.value.asString)
            }

            deps
        } catch (e: Exception) {
            logger.warn { "Failed to parse package.json versions: ${e.message}" }
            emptyMap()
        }
    }

    private fun parseGradlePluginVersions(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        return try {
            val content = Files.readString(path)
            val versions = mutableMapOf<String, String>()
        val idRegex = Regex("id\\(\"([^\"]+)\"\\)\\s+version\\s+\"([^\"]+)\"")
            idRegex.findAll(content).forEach { match ->
                versions[match.groupValues[1]] = match.groupValues[2]
            }
        val kotlinRegex = Regex("kotlin\\(\"([^\"]+)\"\\)\\s+version\\s+\"([^\"]+)\"")
            kotlinRegex.findAll(content).forEach { match ->
                val pluginId = "org.jetbrains.kotlin.${match.groupValues[1]}"
                versions[pluginId] = match.groupValues[2]
            }
            versions
        } catch (e: Exception) {
            logger.warn { "Failed to parse Gradle plugin versions: ${e.message}" }
            emptyMap()
        }
    }

    private fun parseGradleDependencies(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val deps = mutableListOf<String>()
            // Match: implementation("group:artifact:version"), api("group:artifact"), etc.
            val depRegex = Regex("""(?:implementation|api|compileOnly|runtimeOnly|testImplementation|kapt|ksp)\s*\(\s*["']([^"']+)["']\s*\)""")
            depRegex.findAll(content).forEach { match ->
                val dep = match.groupValues[1]
                // Extract group:artifact (without version)
                val parts = dep.split(":")
                if (parts.size >= 2) {
                    deps.add("${parts[0]}:${parts[1]}")
                } else {
                    deps.add(dep)
                }
            }
            deps.distinct()
        } catch (e: Exception) {
            logger.warn { "Failed to parse Gradle dependencies: ${e.message}" }
            emptyList()
        }
    }

    private fun parseMavenDependencies(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val deps = mutableListOf<String>()
            // Simple regex for <groupId>...</groupId> <artifactId>...</artifactId> pairs
            val depRegex = Regex("""<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>""")
            depRegex.findAll(content).forEach { match ->
                deps.add("${match.groupValues[1]}:${match.groupValues[2]}")
            }
            deps.distinct()
        } catch (e: Exception) {
            logger.warn { "Failed to parse Maven dependencies: ${e.message}" }
            emptyList()
        }
    }

    private fun parseCMakeDependencies(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val deps = mutableListOf<String>()
            // find_package(Boost REQUIRED), find_package(OpenSSL), target_link_libraries(... lib)
            val findPkgRegex = Regex("""find_package\s*\(\s*(\w+)""")
            findPkgRegex.findAll(content).forEach { match ->
                deps.add(match.groupValues[1])
            }
            deps.distinct()
        } catch (e: Exception) {
            logger.warn { "Failed to parse CMake dependencies: ${e.message}" }
            emptyList()
        }
    }

    private fun parseVcpkgDependencies(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            val deps = mutableListOf<String>()
            json.getAsJsonArray("dependencies")?.forEach { dep ->
                if (dep.isJsonPrimitive) {
                    deps.add(dep.asString)
                } else if (dep.isJsonObject) {
                    dep.asJsonObject.get("name")?.asString?.let { deps.add(it) }
                }
            }
            deps
        } catch (e: Exception) {
            logger.warn { "Failed to parse vcpkg dependencies: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Analyze code structure (classes, functions, patterns)
     */
    private fun analyzeCodeStructure(
        projectRoot: Path,
        fileTree: FileNode,
        includeContent: Boolean
    ): CodeAnalysisInfo {
        val kotlin = analyzeKotlinCode(projectRoot, fileTree, includeContent)
        val java = analyzeJavaCode(projectRoot, fileTree, includeContent)
        val python = analyzePythonCode(projectRoot, fileTree, includeContent)
        val javascript = analyzeJavaScriptCode(projectRoot, fileTree, includeContent)
        val typescript = analyzeTypeScriptCode(projectRoot, fileTree, includeContent)
        val html = analyzeHtmlCode(projectRoot, fileTree, includeContent)
        val css = analyzeCssCode(projectRoot, fileTree, includeContent)

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

    @Suppress("UNUSED_PARAMETER")
    private fun analyzeKotlinCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
        val files = flattenFiles(fileTree).filter { it.name.endsWith(".kt") }
        val classes = mutableListOf<String>()
        val dataClasses = mutableListOf<String>()
        val sealedClasses = mutableListOf<String>()
        val objects = mutableListOf<String>()
        val functions = mutableListOf<String>()
        val suspendFunctions = mutableListOf<String>()
        val extensionFunctions = mutableListOf<String>()
        val classSignatures = mutableListOf<String>()
        val functionSignatures = mutableListOf<String>()
        val publicApiFunctions = mutableListOf<String>()
        val classPurposes = mutableMapOf<String, String>()
        var documentedFunctions = 0
        val annotations = mutableSetOf<String>()
        val coroutinePatterns = mutableListOf<String>()

        files.forEach { file ->
            val path = projectRoot.resolve(file.relativePath)
            if (!path.exists() || path.fileSize() > 2_000_000) return@forEach

            try {
                val content = Files.readString(path)
                val lines = content.lines()

                // Extract data classes
                Regex("""data\s+class\s+(\w+)""").findAll(content).forEach {
                    dataClasses.add(it.groupValues[1])
                    classes.add(it.groupValues[1])
                }

                // Extract sealed classes
                Regex("""sealed\s+(?:class|interface)\s+(\w+)""").findAll(content).forEach {
                    sealedClasses.add(it.groupValues[1])
                    classes.add(it.groupValues[1])
                }

                // Extract regular classes (non-data, non-sealed)
                Regex("""(?<!data\s)(?<!sealed\s)class\s+(\w+)""").findAll(content).forEach {
                    if (!dataClasses.contains(it.groupValues[1]) && !sealedClasses.contains(it.groupValues[1])) {
                        classes.add(it.groupValues[1])
                    }
                }

                // Extract object declarations
                Regex("""object\s+(\w+)""").findAll(content).forEach {
                    objects.add(it.groupValues[1])
                }

                Regex("""(data\s+|sealed\s+|abstract\s+|open\s+|internal\s+|private\s+|public\s+)?class\s+(\w+)[^{]*""")
                    .findAll(content)
                    .forEach { match ->
                        val signature = match.value.trim()
                        classSignatures.add(signature)
                        val name = match.groupValues[2]
                        inferClassPurpose(name, annotations = emptyList())?.let { purpose ->
                            classPurposes[name] = purpose
                        }
                    }

                // Extract suspend functions (coroutines)
                Regex("""suspend\s+fun\s+(\w+)\s*\(""").findAll(content).forEach {
                    suspendFunctions.add(it.groupValues[1])
                    functions.add(it.groupValues[1])
                }

                // Extract extension functions
                Regex("""fun\s+(\w+)\.(\w+)\s*\(""").findAll(content).forEach {
                    extensionFunctions.add("${it.groupValues[1]}.${it.groupValues[2]}")
                }

                // Extract regular functions
                Regex("""(?<!suspend\s)fun\s+(\w+)\s*\(""").findAll(content).forEach {
                    if (!suspendFunctions.contains(it.groupValues[1])) {
                        functions.add(it.groupValues[1])
                    }
                }

                val functionWithTypesRegex = Regex(
                    """(public|private|internal|protected)?\s*(suspend\s+)?fun\s+(\w+)\s*\(([^)]*)\)\s*(?::\s*([^\{=]+))?"""
                )
                functionWithTypesRegex.findAll(content).forEach { match ->
                    val visibility = match.groupValues[1].trim()
                    val suspendModifier = match.groupValues[2].trim()
                    val name = match.groupValues[3]
                    val params = match.groupValues[4].trim()
                    val returnType = match.groupValues[5].trim().ifBlank { null }
                    val signature = buildKotlinFunctionSignature(suspendModifier, name, params, returnType)
                    functionSignatures.add(signature)
                    if (visibility.isBlank() || visibility == "public") {
                        publicApiFunctions.add(signature)
                    }
                    val startLine = lineNumberAt(content, match.range.first)
                    if (hasDocCommentBefore(lines, startLine)) {
                        documentedFunctions++
                    }
                }

                // Extract annotations
                Regex("""@(\w+)""").findAll(content).forEach {
                    annotations.add(it.groupValues[1])
                }

                // Detect coroutine patterns
                if (content.contains("launch")) coroutinePatterns.add("launch")
                if (content.contains("async")) coroutinePatterns.add("async")
                if (content.contains("Flow<")) coroutinePatterns.add("Flow")
                if (content.contains("StateFlow<")) coroutinePatterns.add("StateFlow")
                if (content.contains("withContext")) coroutinePatterns.add("withContext")

            } catch (e: Exception) {
                logger.warn { "Failed to analyze Kotlin file ${file.relativePath}: ${e.message}" }
            }
        }

        return mapOf(
            "files" to files.size,
            "classes" to classes.size,
            "data_classes" to dataClasses.size,
            "sealed_classes" to sealedClasses.size,
            "objects" to objects.size,
            "functions" to functions.size,
            "suspend_functions" to suspendFunctions.size,
            "extension_functions" to extensionFunctions.size,
            "class_signatures" to classSignatures.take(20),
            "function_signatures" to functionSignatures.take(30),
            "public_api" to publicApiFunctions.take(30),
            "class_purposes" to classPurposes,
            "kdoc_coverage" to coveragePercent(documentedFunctions, functionSignatures.size),
            "annotations" to annotations.sorted().take(20),
            "coroutine_patterns" to coroutinePatterns.distinct().sorted(),
            "class_names" to classes.take(20),
            "data_class_names" to dataClasses.take(10)
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun analyzeJavaCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
        val files = flattenFiles(fileTree).filter { it.name.endsWith(".java") }
        val classes = mutableListOf<String>()
        val interfaces = mutableListOf<String>()
        val abstractClasses = mutableListOf<String>()
        val enums = mutableListOf<String>()
        val packages = mutableSetOf<String>()
        val annotations = mutableSetOf<String>()
        val springPatterns = mutableListOf<String>()
        val methodSignatures = mutableListOf<String>()
        val classPurposes = mutableMapOf<String, String>()
        val javadocSummaries = mutableListOf<String>()

        files.forEach { file ->
            val path = projectRoot.resolve(file.relativePath)
            if (!path.exists() || path.fileSize() > 2_000_000) return@forEach

            try {
                val content = Files.readString(path)
                val lines = content.lines()

                // Extract package
                Regex("""package\s+([\w.]+)""").find(content)?.let {
                    packages.add(it.groupValues[1])
                }

                // Extract abstract classes
                Regex("""abstract\s+class\s+(\w+)""").findAll(content).forEach {
                    abstractClasses.add(it.groupValues[1])
                    classes.add(it.groupValues[1])
                    inferClassPurpose(it.groupValues[1])?.let { purpose ->
                        classPurposes[it.groupValues[1]] = purpose
                    }
                }

                // Extract regular classes
                Regex("""(?<!abstract\s)class\s+(\w+)""").findAll(content).forEach {
                    if (!abstractClasses.contains(it.groupValues[1])) {
                        classes.add(it.groupValues[1])
                        inferClassPurpose(it.groupValues[1])?.let { purpose ->
                            classPurposes[it.groupValues[1]] = purpose
                        }
                    }
                }

                // Extract interfaces
                Regex("""interface\s+(\w+)""").findAll(content).forEach {
                    interfaces.add(it.groupValues[1])
                    inferClassPurpose(it.groupValues[1])?.let { purpose ->
                        classPurposes[it.groupValues[1]] = purpose
                    }
                }

                // Extract enums
                Regex("""enum\s+(\w+)""").findAll(content).forEach {
                    enums.add(it.groupValues[1])
                    inferClassPurpose(it.groupValues[1])?.let { purpose ->
                        classPurposes[it.groupValues[1]] = purpose
                    }
                }

                val methodSignatureRegex = Regex(
                    """((?:public|private|protected|static|final|abstract|synchronized)\s+)*([A-Za-z0-9_<>\[\].?]+)\s+(\w+)\s*\(([^)]*)\)"""
                )
                methodSignatureRegex.findAll(content).forEach { match ->
                    val modifiers = match.groupValues[1].trim()
                    val returnType = match.groupValues[2].trim()
                    val name = match.groupValues[3].trim()
                    val params = match.groupValues[4].trim()
                    val signature = listOf(modifiers, "$returnType $name($params)").filter { it.isNotBlank() }.joinToString(" ")
                    methodSignatures.add(signature)
                    val startLine = lineNumberAt(content, match.range.first)
                    extractDocCommentBefore(lines, startLine)?.let { summary ->
                        javadocSummaries.add(summary)
                    }
                }

                // Extract annotations
                Regex("""@(\w+)""").findAll(content).forEach {
                    annotations.add(it.groupValues[1])
                }

                // Detect Spring/Jakarta patterns
                if (content.contains("@Controller") || content.contains("@RestController")) {
                    springPatterns.add("Spring MVC Controller")
                }
                if (content.contains("@Service")) {
                    springPatterns.add("Spring Service")
                }
                if (content.contains("@Repository")) {
                    springPatterns.add("Spring Repository")
                }
                if (content.contains("@Component")) {
                    springPatterns.add("Spring Component")
                }
                if (content.contains("@Configuration")) {
                    springPatterns.add("Spring Configuration")
                }
                if (content.contains("@Entity") || content.contains("@Table")) {
                    springPatterns.add("JPA Entity")
                }
                if (content.contains("@Autowired") || content.contains("@Inject")) {
                    springPatterns.add("Dependency Injection")
                }

            } catch (e: Exception) {
                logger.warn { "Failed to analyze Java file ${file.relativePath}: ${e.message}" }
            }
        }

        return mapOf(
            "files" to files.size,
            "classes" to classes.size,
            "interfaces" to interfaces.size,
            "abstract_classes" to abstractClasses.size,
            "enums" to enums.size,
            "packages" to packages.sorted().take(10),
            "annotations" to annotations.sorted().take(20),
            "spring_patterns" to springPatterns.distinct().sorted(),
            "class_names" to classes.take(20),
            "method_signatures" to methodSignatures.take(30),
            "class_purposes" to classPurposes,
            "javadoc_summaries" to javadocSummaries.take(20)
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun analyzePythonCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
        val files = flattenFiles(fileTree).filter { it.name.endsWith(".py") }
        val classes = mutableListOf<String>()
        val functions = mutableListOf<String>()
        val asyncFunctions = mutableListOf<String>()
        val decorators = mutableSetOf<String>()
        val typeHints = mutableListOf<String>()
        val frameworkPatterns = mutableListOf<String>()
        val functionSignatures = mutableListOf<String>()
        val dataclasses = mutableListOf<String>()
        val pydanticModels = mutableListOf<String>()
        val docstrings = mutableMapOf<String, String>()
        var typedFunctions = 0

        files.forEach { file ->
            val path = projectRoot.resolve(file.relativePath)
            if (!path.exists() || path.fileSize() > 2_000_000) return@forEach

            try {
                val content = Files.readString(path)

                // Extract classes
                Regex("""class\s+(\w+)""").findAll(content).forEach {
                    classes.add(it.groupValues[1])
                }

                Regex("""@dataclass[^\n]*\nclass\s+(\w+)""").findAll(content).forEach {
                    dataclasses.add(it.groupValues[1])
                }

                Regex("""class\s+(\w+)\s*\(\s*BaseModel\s*\)""").findAll(content).forEach {
                    pydanticModels.add(it.groupValues[1])
                }

                // Extract async functions
                Regex("""async\s+def\s+(\w+)\s*\(""").findAll(content).forEach {
                    asyncFunctions.add(it.groupValues[1])
                    functions.add(it.groupValues[1])
                }

                // Extract regular functions
                Regex("""(?<!async\s)def\s+(\w+)\s*\(""").findAll(content).forEach {
                    if (!asyncFunctions.contains(it.groupValues[1])) {
                        functions.add(it.groupValues[1])
                    }
                }

                // Extract decorators
                Regex("""@(\w+)""").findAll(content).forEach {
                    decorators.add(it.groupValues[1])
                }

                // Extract type hints
                Regex("""def\s+\w+\([^)]*\)\s*->\s*(\w+)""").findAll(content).forEach {
                    typeHints.add(it.groupValues[1])
                }

                val functionWithTypesRegex = Regex(
                    """(async\s+)?def\s+(\w+)\s*\(([^)]*)\)\s*(?:->\s*([^:]+))?:"""
                )
                val lines = content.lines()
                functionWithTypesRegex.findAll(content).forEach { match ->
                    val asyncFlag = match.groupValues[1].trim()
                    val name = match.groupValues[2]
                    val params = match.groupValues[3]
                    val returnType = match.groupValues[4].trim().ifBlank { null }
                    val signature = buildPythonFunctionSignature(asyncFlag, name, params, returnType)
                    functionSignatures.add(signature)
                    if (params.contains(":") || returnType != null) {
                        typedFunctions++
                    }
                    val startLine = lineNumberAt(content, match.range.first)
                    extractPythonDocstring(lines, startLine)?.let { doc ->
                        docstrings[name] = doc
                    }
                }

                // Detect framework patterns
                if (content.contains("from django") || content.contains("import django")) {
                    frameworkPatterns.add("Django")
                }
                if (content.contains("from fastapi") || content.contains("import fastapi") ||
                    content.contains("FastAPI(")) {
                    frameworkPatterns.add("FastAPI")
                }
                if (content.contains("from flask") || content.contains("import flask") ||
                    content.contains("Flask(__name__)")) {
                    frameworkPatterns.add("Flask")
                }
                if (content.contains("@app.route") || content.contains("@api.get") ||
                    content.contains("@api.post")) {
                    frameworkPatterns.add("REST API")
                }
                if (content.contains("from sqlalchemy") || content.contains("Base = declarative_base()")) {
                    frameworkPatterns.add("SQLAlchemy")
                }
                if (content.contains("from pydantic") || content.contains("BaseModel")) {
                    frameworkPatterns.add("Pydantic")
                }
                if (content.contains("async def") || content.contains("await ")) {
                    frameworkPatterns.add("Async/Await")
                }

            } catch (e: Exception) {
                logger.warn { "Failed to analyze Python file ${file.relativePath}: ${e.message}" }
            }
        }

        return mapOf(
            "files" to files.size,
            "classes" to classes.size,
            "functions" to functions.size,
            "async_functions" to asyncFunctions.size,
            "decorators" to decorators.sorted().take(20),
            "type_hints" to typeHints.distinct().sorted().take(10),
            "framework_patterns" to frameworkPatterns.distinct().sorted(),
            "class_names" to classes.take(20),
            "function_signatures" to functionSignatures.take(30),
            "dataclasses" to dataclasses.distinct().take(20),
            "pydantic_models" to pydanticModels.distinct().take(20),
            "docstrings" to docstrings,
            "type_hint_coverage" to coveragePercent(typedFunctions, functionSignatures.size)
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun analyzeJavaScriptCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
        val files = flattenFiles(fileTree).filter { it.name.endsWith(".js") || it.name.endsWith(".jsx") }
        val classes = mutableListOf<String>()
        val functions = mutableListOf<String>()
        val components = mutableListOf<String>()

        files.forEach { file ->
            val path = projectRoot.resolve(file.relativePath)
            if (!path.exists() || path.fileSize() > 2_000_000) return@forEach

            try {
                val content = Files.readString(path)

                Regex("""class\s+(\w+)""").findAll(content).forEach {
                    classes.add(it.groupValues[1])
                }

                Regex("""function\s+(\w+)\s*\(""").findAll(content).forEach {
                    functions.add(it.groupValues[1])
                }

                // React components
                Regex("""const\s+(\w+)\s*=\s*\([^)]*\)\s*=>""").findAll(content).forEach {
                    components.add(it.groupValues[1])
                }
            } catch (e: Exception) {
                logger.warn { "Failed to analyze JavaScript file ${file.relativePath}: ${e.message}" }
            }
        }

        return mapOf(
            "files" to files.size,
            "classes" to classes.size,
            "functions" to functions.size,
            "components" to components.size
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun analyzeTypeScriptCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
        val files = flattenFiles(fileTree).filter { it.name.endsWith(".ts") || it.name.endsWith(".tsx") }
        val classes = mutableListOf<String>()
        val interfaces = mutableListOf<String>()
        val types = mutableListOf<String>()
        val functions = mutableListOf<String>()
        val decorators = mutableSetOf<String>()
        val interfaceContracts = mutableMapOf<String, List<String>>()
        val reactComponents = mutableListOf<String>()
        val reactHooks = mutableListOf<String>()
        val propsTypes = mutableListOf<String>()
        val exportedApi = mutableListOf<String>()

        files.forEach { file ->
            val path = projectRoot.resolve(file.relativePath)
            if (!path.exists() || path.fileSize() > 2_000_000) return@forEach

            try {
                val content = Files.readString(path)

                // Extract interfaces
                Regex("""(?:export\s+)?interface\s+(\w+)""").findAll(content).forEach {
                    interfaces.add(it.groupValues[1])
                }
                Regex(
                    """interface\s+(\w+)(?:<[^>]+>)?\s*(?:extends\s+[\w,\s<>]+)?\s*\{([^}]+)\}""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(content).forEach { match ->
                    val name = match.groupValues[1]
                    val body = match.groupValues[2]
                    val methods = body.lines().mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.contains("(") && trimmed.contains("):")) {
                            trimmed.substringBefore("{").trim().trimEnd(';')
                        } else null
                    }
                    if (methods.isNotEmpty()) {
                        interfaceContracts[name] = methods.take(10)
                    }
                }

                // Extract type definitions
                Regex("""(?:export\s+)?type\s+(\w+)\s*=""").findAll(content).forEach {
                    types.add(it.groupValues[1])
                }

                // Extract classes
                Regex("""(?:export\s+)?class\s+(\w+)""").findAll(content).forEach {
                    classes.add(it.groupValues[1])
                }

                // Extract functions
                Regex("""(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\(""").findAll(content).forEach {
                    functions.add(it.groupValues[1])
                    if (it.value.contains("export")) exportedApi.add(it.groupValues[1])
                }
                Regex("""(?:export\s+)?const\s+(\w+)\s*=\s*\([^)]*\)\s*=>""").findAll(content).forEach {
                    functions.add(it.groupValues[1])
                    if (it.value.contains("export")) exportedApi.add(it.groupValues[1])
                }

                // Extract decorators
                Regex("""@(\w+)""").findAll(content).forEach {
                    decorators.add(it.groupValues[1])
                }

                Regex("""(?:export\s+)?(?:const|function)\s+(use\w+)""").findAll(content).forEach {
                    reactHooks.add(it.groupValues[1])
                }
                Regex(
                    """(?:export\s+)?(?:const|function)\s+([A-Z]\w+)\s*[=:]\s*(?:\([^)]*\)|React\.FC)"""
                ).findAll(content).forEach {
                    reactComponents.add(it.groupValues[1])
                }
                Regex("""(?:type|interface)\s+(\w+Props)\s*[=:{]""").findAll(content).forEach {
                    propsTypes.add(it.groupValues[1])
                }

            } catch (e: Exception) {
                logger.warn { "Failed to analyze TypeScript file ${file.relativePath}: ${e.message}" }
            }
        }

        return mapOf(
            "files" to files.size,
            "classes" to classes.size,
            "interfaces" to interfaces.size,
            "types" to types.size,
            "functions" to functions.size,
            "decorators" to decorators.sorted().take(10),
            "interface_names" to interfaces.take(15),
            "type_names" to types.take(15),
            "interface_contracts" to interfaceContracts,
            "react_components" to reactComponents.distinct().take(20),
            "react_hooks" to reactHooks.distinct().take(20),
            "props_types" to propsTypes.distinct().take(20),
            "exported_api" to exportedApi.distinct().take(30)
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun analyzeHtmlCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
        val files = flattenFiles(fileTree).filter { it.name.endsWith(".html") }
        val pages = mutableListOf<Map<String, Any>>()
        val canvasGames = mutableListOf<String>()

        files.forEach { file ->
            val path = projectRoot.resolve(file.relativePath)
            if (!path.exists() || path.fileSize() > 2_000_000) return@forEach

            try {
                val content = Files.readString(path)

                val pageInfo = mutableMapOf<String, Any>(
                    "file" to file.relativePath
                )

                // Extract title
                Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(content)?.let {
                    pageInfo["title"] = it.groupValues[1].trim()
                }

                // Check for canvas
                val hasCanvas = content.contains("<canvas")
                if (hasCanvas) {
                    pageInfo["has_canvas"] = true
                    val canvasIds = Regex("""<canvas[^>]*id=['"]([^'"]+)['"]""").findAll(content)
                        .map { it.groupValues[1] }.toList()
                    if (canvasIds.isNotEmpty()) {
                        pageInfo["canvas_ids"] = canvasIds
                    }
                }

                // Check for WebGL
                if (Regex("""getContext\s*\(\s*['"]webgl['"]""").containsMatchIn(content)) {
                    pageInfo["has_webgl"] = true
                }

                // Extract forms
                val forms = Regex("""<form[^>]*>(.*?)</form>""", RegexOption.DOT_MATCHES_ALL).findAll(content)
                if (forms.count() > 0) {
                    pageInfo["forms_count"] = forms.count()
                }

                // Detect game indicators
                val gameIndicators = mutableListOf<String>()
                val gameKeywords = listOf(
                    "requestAnimationFrame", "game", "player", "score", "collision",
                    "Math.random", "Math.sin", "Math.cos", "canvas.width", "canvas.height"
                )
                gameKeywords.forEach { keyword ->
                    if (content.contains(keyword)) {
                        gameIndicators.add(keyword)
                    }
                }
                if (gameIndicators.isNotEmpty()) {
                    pageInfo["game_indicators"] = gameIndicators.take(5)
                    if (hasCanvas) {
                        canvasGames.add(file.relativePath)
                    }
                }

                // Extract external scripts
                val scripts = Regex("""<script[^>]+src\s*=\s*['"]([^'"]+)['"]""").findAll(content)
                    .map { it.groupValues[1] }.toList()
                if (scripts.isNotEmpty()) {
                    pageInfo["external_scripts"] = scripts.take(5)
                }

                pages.add(pageInfo)

            } catch (e: Exception) {
                logger.warn { "Failed to analyze HTML file ${file.relativePath}: ${e.message}" }
            }
        }

        return mapOf(
            "files" to files.size,
            "pages" to pages,
            "canvas_games" to canvasGames
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun analyzeCssCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
        val files = flattenFiles(fileTree).filter { it.name.endsWith(".css") || it.name.endsWith(".scss") || it.name.endsWith(".sass") }
        val cssClasses = mutableSetOf<String>()
        val cssIds = mutableSetOf<String>()
        val cssVariables = mutableListOf<String>()
        val animations = mutableListOf<String>()
        val mediaQueries = mutableListOf<String>()

        files.forEach { file ->
            val path = projectRoot.resolve(file.relativePath)
            if (!path.exists() || path.fileSize() > 2_000_000) return@forEach

            try {
                val content = Files.readString(path)

                // Extract class selectors
                Regex("""\\.([a-zA-Z0-9_-]+)\s*\{""").findAll(content).forEach {
                    cssClasses.add(it.groupValues[1])
                }

                // Extract ID selectors
                Regex("""#([a-zA-Z0-9_-]+)\s*\{""").findAll(content).forEach {
                    cssIds.add(it.groupValues[1])
                }

                // Extract CSS variables
                Regex("""--([a-zA-Z0-9_-]+)\s*:""").findAll(content).forEach {
                    cssVariables.add("--${it.groupValues[1]}")
                }

                // Extract animations
                Regex("""@keyframes\s+([a-zA-Z0-9_-]+)""").findAll(content).forEach {
                    animations.add(it.groupValues[1])
                }

                // Extract media queries
                Regex("""@media\s*\(([^)]+)\)""").findAll(content).forEach {
                    mediaQueries.add(it.groupValues[1])
                }

            } catch (e: Exception) {
                logger.warn { "Failed to analyze CSS file ${file.relativePath}: ${e.message}" }
            }
        }

        return mapOf(
            "files" to files.size,
            "classes" to cssClasses.sorted().take(50),
            "classes_count" to cssClasses.size,
            "ids" to cssIds.sorted().take(20),
            "ids_count" to cssIds.size,
            "variables" to cssVariables.distinct().take(20),
            "animations" to animations.distinct().take(10),
            "media_queries" to mediaQueries.distinct().take(5)
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
     * Detect primary programming language based on file counts.
     * Returns pair of (language name, file count).
     */
    private fun detectPrimaryLanguage(fileTypes: Map<String, Int>): Pair<String, Int> {
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

    /**
     * Analyze project domain (programming, documentation, creative, etc.)
     */
    private fun analyzeProjectDomain(fileTree: FileNode, structure: StructureInfo): DomainAnalysis {
        val fileTypes = structure.fileTypes
        val folderStructure = collectFolderNames(fileTree)

        val domainScores = mapOf(
            "Programming" to scoreProgrammingProject(fileTypes, folderStructure),
            "Documentation" to scoreDocumentationProject(fileTypes, folderStructure),
            "Creative" to scoreCreativeProject(fileTypes, folderStructure),
            "Research" to scoreResearchProject(fileTypes, folderStructure),
            "Business" to scoreBusinessProject(fileTypes, folderStructure),
            "Educational" to scoreEducationalProject(fileTypes, folderStructure)
        )

        val primaryDomain = domainScores.maxByOrNull { it.value }?.key ?: "unknown"
        val confidence = domainScores[primaryDomain] ?: 0.0

        return DomainAnalysis(
            primaryDomain = primaryDomain,
            confidenceScore = confidence,
            domainScores = domainScores
        )
    }

    private fun scoreProgrammingProject(fileTypes: Map<String, Int>, folderStructure: List<String>): Double {
        var score = 0.0

        val codeExtensions = listOf(".kt", ".java", ".py", ".js", ".ts", ".tsx", ".jsx", ".cpp", ".c", ".cs", ".go", ".rs", ".rb", ".php", ".swift")
        val totalCodeFiles = codeExtensions.sumOf { fileTypes[it] ?: 0 }

        // Higher weight for code files (was 0.1, max 5.0)
        score += minOf(totalCodeFiles * 0.4, 8.0)

        // Bonus for having a dominant language (consistency)
        val (_, primaryCount) = detectPrimaryLanguage(fileTypes)
        if (primaryCount > 0 && totalCodeFiles > 0) {
            val dominanceRatio = primaryCount.toDouble() / totalCodeFiles
            if (dominanceRatio > 0.5) {
                score += 1.5  // Bonus for technological consistency
            }
        }

        // Programming folder structure
        val progFolders = listOf("src", "lib", "app", "components", "modules", "tests", "test", "spec", "main", "kotlin", "java", "python")
        val folderMatches = folderStructure.count { folder ->
            progFolders.any { it.equals(folder, ignoreCase = true) }
        }
        score += folderMatches * 0.4

        return minOf(score, 10.0)
    }

    private fun scoreDocumentationProject(fileTypes: Map<String, Int>, folderStructure: List<String>): Double {
        var score = 0.0

        val docExtensions = listOf(".md", ".rst", ".txt", ".pdf")
        val docFiles = docExtensions.sumOf { fileTypes[it] ?: 0 }
        score += minOf(docFiles * 0.3, 6.0)

        val docFolders = listOf("docs", "documentation", "wiki", "manual")
        val folderMatches = folderStructure.count { folder ->
            docFolders.any { it in folder.lowercase() }
        }
        score += folderMatches * 1.5

        return minOf(score, 10.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scoreCreativeProject(fileTypes: Map<String, Int>, _folderStructure: List<String>): Double {
        var score = 0.0

        val creativeExtensions = listOf(".jpg", ".png", ".gif", ".svg", ".mp3", ".mp4", ".blend", ".psd")
        val creativeFiles = creativeExtensions.sumOf { fileTypes[it] ?: 0 }
        score += minOf(creativeFiles * 0.4, 5.0)

        return minOf(score, 10.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scoreResearchProject(fileTypes: Map<String, Int>, _folderStructure: List<String>): Double {
        var score = 0.0

        val researchExtensions = listOf(".csv", ".xlsx", ".json", ".ipynb", ".r")
        val researchFiles = researchExtensions.sumOf { fileTypes[it] ?: 0 }
        score += minOf(researchFiles * 0.5, 5.0)

        return minOf(score, 10.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scoreBusinessProject(fileTypes: Map<String, Int>, _folderStructure: List<String>): Double {
        var score = 0.0

        val businessExtensions = listOf(".xlsx", ".docx", ".pptx", ".pdf")
        val businessFiles = businessExtensions.sumOf { fileTypes[it] ?: 0 }
        score += minOf(businessFiles * 0.3, 4.0)

        return minOf(score, 10.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scoreEducationalProject(_fileTypes: Map<String, Int>, folderStructure: List<String>): Double {
        var score = 0.0

        val eduFolders = listOf("lessons", "courses", "modules", "exercises")
        val folderMatches = folderStructure.count { folder ->
            eduFolders.any { it in folder.lowercase() }
        }
        score += folderMatches * 1.5

        return minOf(score, 10.0)
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

    // Helper functions
    private fun flattenFiles(node: FileNode): List<FileNode> {
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

    private fun collectFolderNames(node: FileNode): List<String> {
        val result = mutableListOf<String>()
        fun walk(n: FileNode) {
            if (n.isDirectory) {
                result.add(n.name)
                n.children.forEach { walk(it) }
            }
        }
        walk(node)
        return result
    }

    private fun lineNumberAt(content: String, index: Int): Int {
        if (index <= 0) return 1
        var count = 1
        for (i in 0 until index) {
            if (content[i] == '\n') count++
        }
        return count
    }

    private fun hasDocCommentBefore(lines: List<String>, startLine: Int): Boolean {
        return extractDocCommentBefore(lines, startLine) != null
    }

    private fun extractDocCommentBefore(lines: List<String>, startLine: Int): String? {
        var i = startLine - 2
        while (i >= 0) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i--
                continue
            }
            if (line.startsWith("@")) {
                i--
                continue
            }
            if (line.endsWith("*/")) {
                val buffer = StringBuilder()
                var j = i
                while (j >= 0) {
                    val current = lines[j].trim()
                    buffer.insert(0, current + "\n")
                    if (current.startsWith("/**")) {
                        return buffer.toString()
                            .replace("/**", "")
                            .replace("*/", "")
                            .lines()
                            .map { it.trimStart('*', ' ').trim() }
                            .joinToString(" ")
                            .trim()
                            .ifBlank { null }
                    }
                    j--
                }
                return null
            }
            break
        }
        return null
    }

    private fun extractPythonDocstring(lines: List<String>, startLine: Int): String? {
        val defIndent = lines.getOrNull(startLine - 1)?.takeWhile { it == ' ' || it == '\t' } ?: ""
        var i = startLine
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            if (indent.length <= defIndent.length) return null
            val trimmed = line.trimStart()
            val quote = when {
                trimmed.startsWith("\"\"\"") -> "\"\"\""
                trimmed.startsWith("'''") -> "'''"
                else -> null
            } ?: return null
            val startIdx = line.indexOf(quote) + quote.length
            val remainder = line.substring(startIdx)
            if (remainder.contains(quote)) {
                return remainder.substringBefore(quote).trim().take(200)
            }
            val buffer = StringBuilder(remainder)
            var j = i + 1
            while (j < lines.size) {
                val next = lines[j]
                val endIdx = next.indexOf(quote)
                if (endIdx >= 0) {
                    if (buffer.isNotEmpty()) buffer.appendLine()
                    buffer.append(next.substring(0, endIdx))
                    return buffer.toString().trim().take(200)
                }
                if (buffer.isNotEmpty()) buffer.appendLine()
                buffer.append(next)
                j++
            }
            return buffer.toString().trim().take(200)
        }
        return null
    }

    private fun buildPythonFunctionSignature(
        asyncFlag: String,
        name: String,
        params: String,
        returnType: String?
    ): String {
        val asyncPrefix = if (asyncFlag.isNotBlank()) "async " else ""
        val returnStr = if (returnType != null) " -> $returnType" else ""
        return "${asyncPrefix}def $name(${params.trim()})$returnStr"
    }

    private fun buildKotlinFunctionSignature(
        suspendModifier: String,
        name: String,
        params: String,
        returnType: String?
    ): String {
        val suspendPrefix = if (suspendModifier.isNotBlank()) "suspend " else ""
        val returnStr = if (returnType != null) ": $returnType" else ""
        return "${suspendPrefix}fun $name(${params.trim()})$returnStr"
    }

    private fun coveragePercent(documented: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((documented.toDouble() / total) * 100).toInt()
    }

    private fun inferClassPurpose(name: String, annotations: List<String> = emptyList()): String? {
        return when {
            annotations.any { it.contains("Controller") } -> "REST API Controller"
            annotations.any { it.contains("Service") } -> "Business Logic Service"
            annotations.any { it.contains("Repository") } -> "Data Access Repository"
            annotations.any { it.contains("Entity") } -> "JPA Entity / Domain Model"
            annotations.any { it.contains("Configuration") } -> "Configuration"
            name.endsWith("Controller") -> "Controller"
            name.endsWith("Service") -> "Service"
            name.endsWith("Repository") -> "Repository"
            name.endsWith("DTO") || name.endsWith("Request") || name.endsWith("Response") -> "Data Transfer Object"
            name.endsWith("Factory") -> "Factory"
            name.endsWith("Builder") -> "Builder"
            else -> null
        }
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
        val patterns = configService.getTyped<List<String>>(ConfigKeys.RAG_IGNORED_DIRECTORIES).toSet()
        return try {
            AiIgnoreMatcher.load(projectRoot) ?: AiIgnoreMatcher.fromPatterns(patterns)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${AiIgnoreMatcher.FILE_NAME}; using default ignore patterns" }
            AiIgnoreMatcher.fromPatterns(patterns)
        }
    }

    /**
     * Detect architectural patterns in the project.
     * Based on ADR 0017.
     */
    private fun detectArchitecturalPatterns(fileTree: FileNode): ArchitectureInfo {
        val patterns = mutableListOf<String>()
        val folders = collectFolderNames(fileTree)
        val files = flattenFiles(fileTree)

        // Wykrywanie wzorców
        if (folders.any { it in listOf("src", "lib", "dist") }) {
            patterns.add("Standard JS/TS project structure")
        }
        if (folders.any { it in listOf("components", "views", "pages") }) {
            patterns.add("Component-based architecture")
        }
        if (folders.any { it in listOf("models", "controllers", "views") }) {
            patterns.add("MVC pattern")
        }
        if (folders.any { it in listOf("domain", "application", "infrastructure") }) {
            patterns.add("Clean Architecture / DDD")
        }
        if (files.any { it.name == "index.html" } && files.count { it.name.endsWith(".html") } > 10) {
            patterns.add("Multi-page static site")
        }
        if (files.any { it.name.contains("game", ignoreCase = true) || it.relativePath.contains("arcade") }) {
            patterns.add("Game collection / Arcade")
        }

        // Wykrywanie entry points
        val entryPoints = detectEntryPoints(files)

        // Wykrywanie głównych modułów
        val modules = detectModules(fileTree)

        return ArchitectureInfo(
            patterns = patterns,
            entryPoints = entryPoints,
            modules = modules,
            style = inferArchitectureStyle(patterns)
        )
    }

    /**
     * Detect entry points in the project.
     * Based on ADR 0017.
     */
    private fun detectEntryPoints(files: List<FileNode>): List<EntryPoint> {
        val entryPoints = mutableListOf<EntryPoint>()

        files.forEach { file ->
            when {
                file.name == "index.html" -> entryPoints.add(
                    EntryPoint(file.relativePath, "Web entry", "HTML")
                )
                file.name == "main.kt" || file.name == "main.kt" -> entryPoints.add(
                    EntryPoint(file.relativePath, "Application entry", "Kotlin")
                )
                file.name == "index.js" || file.name == "index.ts" -> entryPoints.add(
                    EntryPoint(file.relativePath, "Module entry", "JavaScript/TypeScript")
                )
                file.name == "__main__.py" || file.name == "main.py" -> entryPoints.add(
                    EntryPoint(file.relativePath, "Python entry", "Python")
                )
                file.name == "App.tsx" || file.name == "App.jsx" -> entryPoints.add(
                    EntryPoint(file.relativePath, "React app entry", "React")
                )
            }
        }

        return entryPoints.take(10)
    }

    /**
     * Detect modules in the project.
     * Based on ADR 0017.
     */
    private fun detectModules(fileTree: FileNode): List<ModuleInfo> {
        val modules = mutableListOf<ModuleInfo>()

        fileTree.children.filter { it.isDirectory }.forEach { dir ->
            val fileCount = countFilesRecursive(dir)
            val primaryType = detectPrimaryFileType(dir)

            if (fileCount > 0) {
                modules.add(ModuleInfo(
                    name = dir.name,
                    path = dir.relativePath,
                    fileCount = fileCount,
                    primaryType = primaryType,
                    description = inferModuleDescription(dir.name)
                ))
            }
        }

        return modules.sortedByDescending { it.fileCount }.take(10)
    }

    /**
     * Count files recursively in directory node.
     */
    private fun countFilesRecursive(node: FileNode): Int {
        if (!node.isDirectory) return 1
        return node.children.sumOf { countFilesRecursive(it) }
    }

    /**
     * Detect primary file type in directory.
     */
    private fun detectPrimaryFileType(dir: FileNode): String {
        val extensions = mutableMapOf<String, Int>()

        fun collectExtensions(node: FileNode) {
            if (!node.isDirectory) {
                val ext = node.name.substringAfterLast('.', "")
                if (ext.isNotEmpty()) {
                    extensions[ext] = extensions.getOrDefault(ext, 0) + 1
                }
            } else {
                node.children.forEach { collectExtensions(it) }
            }
        }

        collectExtensions(dir)
        return extensions.maxByOrNull { it.value }?.key ?: "unknown"
    }

    /**
     * Infer module description from name.
     * Based on ADR 0017.
     */
    private fun inferModuleDescription(name: String): String {
        return when {
            name.equals("arcade", ignoreCase = true) -> "Arcade games collection"
            name.equals("src", ignoreCase = true) -> "Source code"
            name.equals("lib", ignoreCase = true) -> "Libraries and utilities"
            name.equals("test", ignoreCase = true) || name.equals("tests", ignoreCase = true) -> "Test files"
            name.equals("docs", ignoreCase = true) -> "Documentation"
            name.equals("assets", ignoreCase = true) -> "Static assets"
            name.equals("components", ignoreCase = true) -> "UI components"
            name.equals("services", ignoreCase = true) -> "Services layer"
            name.equals("models", ignoreCase = true) -> "Data models"
            name.equals("controllers", ignoreCase = true) -> "Controllers"
            name.equals("views", ignoreCase = true) -> "Views"
            name.equals("utils", ignoreCase = true) || name.equals("utilities", ignoreCase = true) -> "Utility functions"
            else -> "Module"
        }
    }

    /**
     * Infer architecture style from detected patterns.
     */
    private fun inferArchitectureStyle(patterns: List<String>): String {
        return when {
            patterns.any { it.contains("Clean Architecture") } -> "Clean Architecture"
            patterns.any { it.contains("MVC") } -> "MVC"
            patterns.any { it.contains("Component-based") } -> "Component-based"
            patterns.any { it.contains("Multi-page") } -> "Multi-page application"
            patterns.any { it.contains("Game") } -> "Game collection"
            else -> "Standard"
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

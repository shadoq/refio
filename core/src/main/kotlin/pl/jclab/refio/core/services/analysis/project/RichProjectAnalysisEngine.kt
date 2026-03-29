package pl.jclab.refio.core.services.analysis.project

import pl.jclab.refio.core.db.repositories.ProjectAnalysisReportRecord
import pl.jclab.refio.core.db.repositories.ProjectAnalysisReportRepository
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.analysis.CodeElements
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.services.analysis.FileAnalysis
import pl.jclab.refio.core.services.analysis.LanguageAnalyzer
import pl.jclab.refio.core.services.analysis.ExtensionLanguageAnalyzer
import pl.jclab.refio.core.services.analysis.FunctionElement
import pl.jclab.refio.core.services.analysis.ParameterElement
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.utils.AiIgnoreMatcher
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

private val logger = dualLogger("RichProjectAnalysisEngine")

class RichProjectAnalysisEngine(
    private val fileAnalyzerService: FileAnalyzerService,
    private val configService: ConfigService,
    private val repository: ProjectAnalysisReportRepository,
    private val languageAnalyzers: List<LanguageAnalyzer>
) {

    private val cache = ConcurrentHashMap<String, CachedReport>()
    private val projectMutexes = ConcurrentHashMap<String, Mutex>()
    private val digest = MessageDigest.getInstance("SHA-256")
    private val supportedExtensions = languageAnalyzers
        .filterIsInstance<ExtensionLanguageAnalyzer>()
        .flatMap { it.supportedExtensions() }
        .map { it.lowercase() }
        .ifEmpty { DEFAULT_SOURCE_EXTENSIONS }
        .toSet()

    suspend fun analyzeProject(projectRoot: Path): ProjectAnalysisReport = withContext(Dispatchers.IO) {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val projectKey = normalizedRoot.toString()

        // Get or create mutex for this project to prevent concurrent analysis
        val mutex = projectMutexes.getOrPut(projectKey) { Mutex() }

        // Acquire mutex lock to ensure only one coroutine analyzes this project at a time
        mutex.withLock {
            logger.debug { "Acquired analysis lock for project: $projectKey" }

            val ignoreMatcher = resolveIgnoreMatcher(normalizedRoot)
            val fingerprint = fingerprintProject(normalizedRoot, ignoreMatcher)

            cache[projectKey]?.let { cached ->
                if ((System.currentTimeMillis() - cached.cachedAt) < configService.getTyped<Long>(ConfigKeys.PROJECT_ANALYSIS_CACHE_TTL_MS)
                    && cached.matches(fingerprint)
                ) {
                    logger.debug { "Rich analysis cache hit for $projectKey" }
                    return@withContext cached.report
                }
            }

            repository.getByProjectRoot(projectKey)?.let { record ->
                if (record.checksum == fingerprint.checksum) {
                    val parsed = gson.fromJson(record.reportJson, ProjectAnalysisReport::class.java)
                    cache[projectKey] = CachedReport(parsed, fingerprint)
                    logger.info { "Loaded project analysis report for $projectKey from database cache" }
                    return@withContext parsed
                }
            }

            val files = discoverSourceFiles(normalizedRoot, ignoreMatcher)
            logger.info { "Building AST-backed project analysis for $projectKey (${files.size} files)" }

            val analyses = mutableListOf<FileAnalysis>()
            for (path in files) {
                try {
                    val analysis = fileAnalyzerService.analyzeOnly(normalizedRoot, path)
                    analyses.add(analysis)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    // Job was cancelled - this is normal, re-throw to propagate cancellation
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to analyze $path" }
                }
            }

            val report = buildReport(normalizedRoot, fingerprint.checksum, analyses)
            repository.upsert(
                ProjectAnalysisReportRecord(
                    projectRoot = projectKey,
                    analyzedAt = report.analyzedAt,
                    checksum = fingerprint.checksum,
                    reportJson = gson.toJson(report)
                )
            )
            cache[projectKey] = CachedReport(report, fingerprint)
            logger.debug { "Released analysis lock for project: $projectKey" }
            report
        }
    }

    private fun buildReport(
        projectRoot: Path,
        checksum: String,
        analyses: List<FileAnalysis>
    ): ProjectAnalysisReport {
        val stats = buildStatistics(analyses)
        val codeStructure = buildCodeStructure(projectRoot, analyses)
        val dependencies = buildDependencyAnalysis(projectRoot, analyses, codeStructure)
        val patterns = detectPatterns(codeStructure)
        val quality = buildQualityMetrics(codeStructure, analyses)
        val technologies = buildTechnologyStack(projectRoot, analyses, stats, patterns)
        val architecture = inferArchitecture(codeStructure, dependencies, technologies)

        return ProjectAnalysisReport(
            projectPath = projectRoot.toString(),
            analyzedAt = System.currentTimeMillis(),
            checksum = checksum,
            statistics = stats,
            codeStructure = codeStructure,
            dependencies = dependencies,
            patterns = patterns,
            quality = quality,
            technologies = technologies,
            architecture = architecture
        )
    }

    private fun buildStatistics(analyses: List<FileAnalysis>): ProjectStatistics {
        val filesByLanguage = analyses
            .groupingBy { it.language ?: "unknown" }
            .eachCount()
        val linesByLanguage = analyses
            .groupBy { it.language ?: "unknown" }
            .mapValues { (_, entries) -> entries.sumOf { it.lineCount ?: 0 } }
        val totalLines = linesByLanguage.values.sum()

        return ProjectStatistics(
            totalFiles = analyses.size,
            totalLines = totalLines,
            codeLines = totalLines, // heuristics until comment/blank detection arrives
            commentLines = 0,
            blankLines = 0,
            filesByLanguage = filesByLanguage,
            linesByLanguage = linesByLanguage
        )
    }

    private fun buildCodeStructure(projectRoot: Path, analyses: List<FileAnalysis>): CodeStructure {
        val packages = mutableMapOf<String, MutableList<String>>()
        val classInfos = mutableListOf<ClassInfo>()
        val functions = mutableListOf<FunctionInfo>()

        analyses.forEach { analysis ->
            val pkg = packageNameFromPath(projectRoot, analysis.filePath)
            val pkgClasses = packages.getOrPut(pkg) { mutableListOf() }

            analysis.codeElements.classes.forEach { cls ->
                val qualified = if (pkg.isNotBlank()) "$pkg.${cls.name}" else cls.name
                pkgClasses += cls.name
                classInfos += ClassInfo(
                    name = cls.name,
                    qualifiedName = qualified,
                    filePath = analysis.filePath,
                    startLine = cls.startLine,
                    endLine = cls.endLine,
                    modifiers = cls.modifiers,
                    superclass = cls.superclass,
                    interfaces = cls.interfaces,
                    annotations = cls.annotations,
                    documentation = cls.documentation,
                    methods = cls.methods.map { it.toFunctionInfo(analysis.filePath) },
                    fields = cls.fields.map {
                        FieldInfo(
                            name = it.name,
                            type = it.type,
                            modifiers = it.modifiers,
                            annotations = it.annotations
                        )
                    },
                    metrics = ClassMetrics(
                        linesOfCode = cls.endLine - cls.startLine,
                        methodCount = cls.methods.size,
                        fieldCount = cls.fields.size
                    )
                )
            }

            analysis.codeElements.functions.forEach { function ->
                functions += function.toFunctionInfo(analysis.filePath)
            }
        }

        val packageInfos = packages.map { (pkg, classes) ->
            PackageInfo(
                name = pkg,
                files = analyses.filter { packageNameFromPath(projectRoot, it.filePath) == pkg }.map { it.filePath },
                classes = classes,
                publicApi = classes.filter { it[0].isUpperCase() },
                dependencies = emptyList()
            )
        }

        val classHierarchy = buildClassHierarchy(classInfos)

        return CodeStructure(
            packages = packageInfos,
            classes = classInfos,
            interfaces = classInfos.filter { it.modifiers.any { mod -> mod.equals("interface", ignoreCase = true) } },
            enums = classInfos.filter { it.modifiers.any { mod -> mod.equals("enum", ignoreCase = true) } },
            topLevelFunctions = functions,
            classHierarchy = classHierarchy
        )
    }

    private fun FunctionElement.toFunctionInfo(filePath: String): FunctionInfo {
        return FunctionInfo(
            name = name,
            signature = signature,
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            returnType = returnType,
            parameters = parameters.map { ParameterInfo(it.name, it.type) },
            modifiers = modifiers,
            annotations = annotations,
            documentation = documentation
        )
    }

    private fun buildClassHierarchy(classes: List<ClassInfo>): ClassHierarchy {
        val tree = mutableMapOf<String, MutableList<String>>()
        val interfaceImpl = mutableMapOf<String, MutableList<String>>()
        val roots = mutableListOf<String>()

        classes.forEach { cls ->
            val qualified = cls.qualifiedName ?: cls.name
            val parent = cls.superclass
            if (parent.isNullOrBlank()) {
                roots += qualified
            } else {
                tree.getOrPut(parent) { mutableListOf() }.add(qualified)
            }

            cls.interfaces.forEach { iface ->
                interfaceImpl.getOrPut(iface) { mutableListOf() }.add(qualified)
            }
        }

        return ClassHierarchy(
            rootClasses = roots.distinct(),
            inheritanceTree = tree.mapValues { it.value.distinct() },
            interfaceImplementations = interfaceImpl.mapValues { it.value.distinct() }
        )
    }

    private fun buildDependencyAnalysis(
        projectRoot: Path,
        analyses: List<FileAnalysis>,
        codeStructure: CodeStructure
    ): DependencyAnalysis {
        val projectPackages = codeStructure.packages.map { it.name }.filter { it.isNotBlank() }
        val imports = mutableListOf<ImportInfo>()
        val edges = mutableListOf<DependencyEdge>()

        analyses.forEach { analysis ->
            val language = analysis.language
            analysis.codeElements.imports.forEach { imp ->
                val module = imp.module
                val isExternal = projectPackages.none { module.startsWith(it) }
                imports += ImportInfo(
                    module = module,
                    member = null,
                    isExternal = isExternal,
                    language = language,
                    filePath = analysis.filePath
                )

                if (!isExternal) {
                    val pkg = packageNameFromPath(projectRoot, analysis.filePath)
                    edges += DependencyEdge(
                        from = pkg,
                        to = module.substringBeforeLast('.', missingDelimiterValue = module),
                        type = "imports"
                    )
                }
            }
        }

        val externalDeps = imports.filter { it.isExternal }
            .groupingBy { it.module.substringBefore('.') }
            .eachCount()
            .map { ExternalDependency(name = it.key, usageCount = it.value) }
            .sortedByDescending { it.usageCount }
            .take(20)

        val internal = imports.filter { !it.isExternal }
            .groupingBy { it.module }
            .eachCount()
            .map {
                InternalDependency(
                    from = it.key.substringBefore('.'),
                    to = it.key.substringAfter('.', missingDelimiterValue = it.key),
                    usageCount = it.value
                )
            }

        val mostUsedClasses = imports
            .groupingBy { it.module }
            .eachCount()
            .map { ClassUsage(className = it.key, usageCount = it.value) }
            .sortedByDescending { it.usageCount }
            .take(20)

        val mostUsedPackages = imports
            .map { it.module.substringBefore('.', missingDelimiterValue = it.module) }
            .groupingBy { it }
            .eachCount()
            .map { PackageUsage(packageName = it.key, usageCount = it.value) }
            .sortedByDescending { it.usageCount }
            .take(20)

        return DependencyAnalysis(
            imports = imports,
            dependencyGraph = DependencyGraph(
                nodes = codeStructure.packages.map { it.name },
                edges = edges
            ),
            externalDependencies = externalDeps,
            internalDependencies = internal,
            circularDependencies = emptyList(),
            mostUsedClasses = mostUsedClasses,
            mostUsedPackages = mostUsedPackages
        )
    }

    private fun detectPatterns(codeStructure: CodeStructure): PatternAnalysis {
        val patterns = mutableListOf<DetectedPattern>()
        codeStructure.classes.forEach { cls ->
            if (cls.annotations.any { it.endsWith("RestController") }) {
                patterns += DetectedPattern(
                    type = "Controller",
                    confidence = 0.9,
                    location = cls.qualifiedName ?: cls.name,
                    evidence = listOf("@RestController annotation")
                )
            }
            if (cls.name.endsWith("Factory")) {
                patterns += DetectedPattern(
                    type = "Factory",
                    confidence = 0.7,
                    location = cls.qualifiedName ?: cls.name,
                    evidence = listOf("Class name ends with Factory")
                )
            }
            if (cls.modifiers.any { it.equals("object", ignoreCase = true) }) {
                patterns += DetectedPattern(
                    type = "Singleton",
                    confidence = 0.8,
                    location = cls.qualifiedName ?: cls.name,
                    evidence = listOf("Kotlin object declaration")
                )
            }
        }

        // Detect framework from annotations — verify Spring presence before attributing
        val hasSpringAnnotations = codeStructure.classes.any { cls ->
            cls.annotations.any { it.startsWith("org.springframework") }
        }

        val frameworkPatterns = codeStructure.classes.flatMap { cls ->
            cls.annotations.mapNotNull { ann ->
                when {
                    // Full qualified Spring annotations → definitely Spring
                    ann.startsWith("org.springframework") && ann.contains("Controller") -> FrameworkPattern("Spring", "Controller", listOf(cls.qualifiedName ?: cls.name))
                    ann.startsWith("org.springframework") && ann.contains("Service") -> FrameworkPattern("Spring", "Service", listOf(cls.qualifiedName ?: cls.name))
                    ann.startsWith("org.springframework") && ann.contains("Repository") -> FrameworkPattern("Spring", "Repository", listOf(cls.qualifiedName ?: cls.name))
                    // Short annotations → only Spring if Spring imports are present elsewhere
                    ann.contains("Controller") && hasSpringAnnotations -> FrameworkPattern("Spring", "Controller", listOf(cls.qualifiedName ?: cls.name))
                    ann.contains("Service") && hasSpringAnnotations -> FrameworkPattern("Spring", "Service", listOf(cls.qualifiedName ?: cls.name))
                    ann.contains("Repository") && hasSpringAnnotations -> FrameworkPattern("Spring", "Repository", listOf(cls.qualifiedName ?: cls.name))
                    // Non-Spring: generic DI/Service pattern
                    ann.contains("Service") -> FrameworkPattern("DI", "Service", listOf(cls.qualifiedName ?: cls.name))
                    else -> null
                }
            }
        }

        val classNames = codeStructure.classes.map { it.name }
        val methodNames = codeStructure.classes.flatMap { it.methods }.map { it.name }

        val naming = NamingConventions(
            classNaming = detectCase(classNames),
            methodNaming = detectCase(methodNames),
            constantNaming = "UPPER_SNAKE_CASE",
            packageNaming = "lowercase.dotted"
        )

        val codingStyle = CodingStyle(
            indentation = "4 spaces",
            braceStyle = "K&R",
            maxLineLength = 120
        )

        return PatternAnalysis(
            designPatterns = patterns.distinctBy { it.location + it.type },
            frameworkPatterns = frameworkPatterns,
            namingConventions = naming,
            codingStyle = codingStyle
        )
    }

    private fun detectCase(names: List<String>): String {
        val sample = names.take(50)
        val camel = sample.count { it.matches(Regex("[a-z]+([A-Z][a-z0-9]+)+")) }
        val pascal = sample.count { it.matches(Regex("[A-Z][a-z0-9]+([A-Z][a-z0-9]+)*")) }
        val snake = sample.count { it.contains('_') }
        return when {
            camel >= pascal && camel >= snake -> "camelCase"
            pascal >= camel && pascal >= snake -> "PascalCase"
            else -> "snake_case"
        }
    }

    private fun buildQualityMetrics(
        codeStructure: CodeStructure,
        analyses: List<FileAnalysis>
    ): QualityMetrics {
        val methods = codeStructure.classes.flatMap { cls ->
            cls.methods.map { method -> cls to method }
        }
        val complexityScores = methods.map { (_, method) -> method.endLine - method.startLine }.filter { it > 0 }
        val averageComplexity = if (complexityScores.isNotEmpty()) {
            complexityScores.average()
        } else 0.0
        val maxComplexity = complexityScores.maxOrNull() ?: 0

        val complexMethods = methods
            .filter { (_, method) -> method.endLine - method.startLine > 50 }
            .map { (cls, method) ->
                ComplexMethod(
                    name = method.name,
                    qualifiedName = "${cls.qualifiedName ?: cls.name}.${method.name}",
                    complexity = method.endLine - method.startLine,
                    linesOfCode = method.endLine - method.startLine,
                    filePath = cls.filePath,
                    startLine = method.startLine
                )
            }

        val codeSmells = codeStructure.classes
            .filter { it.metrics.linesOfCode > 400 }
            .map {
                CodeSmell(
                    type = "GodClass",
                    severity = "HIGH",
                    location = it.qualifiedName ?: it.name,
                    description = "Class exceeds 400 lines (${it.metrics.linesOfCode})."
                )
            }

        val documented = analyses.sumOf { analysis ->
            analysis.codeElements.classes.count { !it.documentation.isNullOrBlank() }
        }
        val undocumented = analyses.sumOf { it.codeElements.classes.size } - documented

        val documentationCoverage = DocumentationCoverage(
            documentedSymbols = documented,
            undocumentedSymbols = undocumented,
            coveragePercent = if (documented + undocumented == 0) 0.0 else documented * 100.0 / (documented + undocumented)
        )

        return QualityMetrics(
            averageComplexity = averageComplexity,
            maxComplexity = maxComplexity,
            complexMethods = complexMethods,
            codeSmells = codeSmells,
            documentationCoverage = documentationCoverage,
            testCoverage = null
        )
    }

    private fun buildTechnologyStack(
        projectRoot: Path,
        analyses: List<FileAnalysis>,
        stats: ProjectStatistics,
        patterns: PatternAnalysis
    ): TechnologyStack {
        val languages = stats.filesByLanguage.map { (language, count) ->
            language to LanguageInfo(
                name = language,
                version = null,
                fileCount = count,
                lineCount = stats.linesByLanguage[language] ?: 0,
                percentage = if (stats.totalFiles == 0) 0.0 else count * 100.0 / stats.totalFiles
            )
        }.toMap()

        val frameworks = patterns.frameworkPatterns.groupBy { "${it.framework}:${it.pattern}" }.map { (name, entries) ->
            val (framework, _) = name.split(':', limit = 2)
            FrameworkInfo(
                name = framework,
                version = null,
                confidence = 0.75,
                detectedFrom = entries.flatMap { it.classes }.distinct()
            )
        }

        val libraries = analyses.flatMap { analysis ->
            analysis.codeElements.imports.map { it.module.substringBefore('.') }
        }.groupingBy { it }
            .eachCount()
            .map { LibraryInfo(it.key, null, it.value) }
            .sortedByDescending { it.usageCount }
            .take(15)

        val buildTools = detectBuildTools(projectRoot) // heuristics

        return TechnologyStack(
            languages = languages,
            frameworks = frameworks,
            libraries = libraries,
            buildTools = buildTools,
            infrastructure = emptyList()
        )
    }

    private fun inferArchitecture(
        structure: CodeStructure,
        dependencies: DependencyAnalysis,
        technologyStack: TechnologyStack
    ): ArchitecturalInsights {
        val layers = mutableListOf<Layer>()
        val apiPackages = structure.packages.filter { it.name.contains(".api") }
        if (apiPackages.isNotEmpty()) {
            layers += Layer(
                name = "API Layer",
                packages = apiPackages.map { it.name },
                classes = apiPackages.flatMap { it.classes },
                dependencies = emptyList()
            )
        }

        val servicePackages = structure.packages.filter { it.name.contains(".service") || it.name.contains(".services") }
        if (servicePackages.isNotEmpty()) {
            layers += Layer(
                name = "Service Layer",
                packages = servicePackages.map { it.name },
                classes = servicePackages.flatMap { it.classes },
                dependencies = emptyList()
            )
        }

        val dataPackages = structure.packages.filter { it.name.contains(".db") || it.name.contains(".repository") }
        if (dataPackages.isNotEmpty()) {
            layers += Layer(
                name = "Data Layer",
                packages = dataPackages.map { it.name },
                classes = dataPackages.flatMap { it.classes },
                dependencies = emptyList()
            )
        }

        val apiSurface = ApiSurface(
            publicClasses = structure.classes.filter { it.modifiers.contains("public") }.mapNotNull { it.qualifiedName },
            publicMethods = structure.classes.flatMap { cls ->
                cls.methods.filter { it.modifiers.contains("public") }.map { method ->
                    "${cls.qualifiedName ?: cls.name}.${method.name}"
                }
            },
            entryPoints = structure.classes
                .filter { cls -> cls.annotations.any { it.endsWith("Controller") || it.endsWith("Component") } }
                .mapNotNull { it.qualifiedName }
        )

        val style = when {
            layers.size >= 3 -> "Layered"
            technologyStack.frameworks.any { it.name.contains("Spring", ignoreCase = true) } -> "Hexagonal"
            else -> "Modular"
        }

        return ArchitecturalInsights(
            style = style,
            layers = layers,
            modules = structure.packages.map {
                Module(
                    name = it.name.substringAfterLast('.'),
                    packages = listOf(it.name),
                    publicApi = it.publicApi,
                    dependencies = dependencies.internalDependencies
                        .filter { dep -> dep.from == it.name || dep.to == it.name }
                        .map { dep -> if (dep.from == it.name) dep.to else dep.from }
                )
            },
            apiSurface = apiSurface,
            dataFlow = DataFlow(
                sources = listOf("user_input"),
                sinks = listOf("database"),
                transformations = listOf("service -> repository")
            )
        )
    }

    private fun detectBuildTools(projectRoot: Path): List<String> {
        val tools = mutableListOf<String>()
        if (Files.exists(projectRoot.resolve("build.gradle")) || Files.exists(projectRoot.resolve("build.gradle.kts"))) {
            tools += "Gradle"
        }
        if (Files.exists(projectRoot.resolve("pom.xml"))) tools += "Maven"
        if (Files.exists(projectRoot.resolve("package.json"))) tools += "npm"
        if (Files.exists(projectRoot.resolve("pnpm-lock.yaml"))) tools += "pnpm"
        return tools
    }

    private fun discoverSourceFiles(projectRoot: Path, ignoreMatcher: AiIgnoreMatcher): List<Path> {
        val maxFiles = configService.getTyped<Int>(ConfigKeys.PROJECT_ANALYSIS_MAX_FILES)
        val result = mutableListOf<Path>()
        Files.walk(projectRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { shouldAnalyze(projectRoot, it, ignoreMatcher) }
                .forEach { path ->
                    if (result.size < maxFiles) {
                        result.add(path)
                    }
                }
        }
        return result
    }

    private fun shouldAnalyze(projectRoot: Path, path: Path, ignoreMatcher: AiIgnoreMatcher): Boolean {
        val relative = safeRelative(projectRoot, path)
        if (ignoreMatcher.isIgnored(relative, isDirectory = false)) return false
        val ext = ".${path.extension.lowercase()}"
        return supportedExtensions.contains(ext)
    }

    private fun fingerprintProject(projectRoot: Path, ignoreMatcher: AiIgnoreMatcher): ProjectFingerprint {
        digest.reset()
        var newest = 0L
        var count = 0
        Files.walk(projectRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { !isIgnored(projectRoot, it, ignoreMatcher) }
                .limit(configService.getTyped<Int>(ConfigKeys.PROJECT_ANALYSIS_FINGERPRINT_LIMIT).toLong())
                .forEach { path ->
                    val modified = Files.getLastModifiedTime(path).toMillis()
                    newest = maxOf(newest, modified)
                    val size = Files.size(path)
                    val relative = safeRelative(projectRoot, path)
                    val entry = "$relative:$size:$modified"
                    digest.update(entry.toByteArray())
                    count++
                }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        return ProjectFingerprint(checksum = checksum, newestModified = newest, fileCount = count)
    }

    private fun safeRelative(root: Path, path: Path): String {
        return try {
            path.relativeTo(root).toString()
        } catch (_: Exception) {
            path.fileName.toString()
        }
    }

    private fun isIgnored(projectRoot: Path, path: Path, ignoreMatcher: AiIgnoreMatcher): Boolean {
        val relative = safeRelative(projectRoot, path)
        return ignoreMatcher.isIgnored(relative, isDirectory = false)
    }

    private fun packageNameFromPath(projectRoot: Path, filePath: String): String {
        val normalized = filePath.replace('\\', '/')
        return when {
            normalized.contains("src/main/kotlin/") -> normalized.substringAfter("src/main/kotlin/").substringBeforeLast('/', "").replace('/', '.')
            normalized.contains("src/main/java/") -> normalized.substringAfter("src/main/java/").substringBeforeLast('/', "").replace('/', '.')
            normalized.contains("src/") -> normalized.substringAfter("src/").substringBeforeLast('/', "").replace('/', '.')
            else -> projectRoot.fileName.toString()
        }
    }

    private data class ProjectFingerprint(
        val checksum: String,
        val newestModified: Long,
        val fileCount: Int
    )

    private data class CachedReport(
        val report: ProjectAnalysisReport,
        val fingerprint: ProjectFingerprint,
        val cachedAt: Long = System.currentTimeMillis()
    ) {
        fun matches(other: ProjectFingerprint): Boolean =
            fingerprint.checksum == other.checksum && fingerprint.newestModified == other.newestModified
    }

    companion object {
        private val DEFAULT_SOURCE_EXTENSIONS = setOf(".kt", ".kts", ".java", ".py", ".ts", ".tsx", ".js", ".jsx")
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
}

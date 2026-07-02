package pl.jclab.refio.core.services

import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize

private val logger = dualLogger("ProjectAnalyzerService")

/**
 * Per-language source code analyzers (Kotlin, Java, Python, JavaScript, TypeScript,
 * HTML, CSS) plus doc-comment and signature extraction helpers used by project analysis.
 */
internal object ProjectLanguageAnalyzers {

    @Suppress("UNUSED_PARAMETER")
    fun analyzeKotlinCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
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
    fun analyzeJavaCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
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
    fun analyzePythonCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
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
    fun analyzeJavaScriptCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
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
    fun analyzeTypeScriptCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
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
    fun analyzeHtmlCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
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
    fun analyzeCssCode(projectRoot: Path, fileTree: FileNode, _includeContent: Boolean): Map<String, Any> {
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
}

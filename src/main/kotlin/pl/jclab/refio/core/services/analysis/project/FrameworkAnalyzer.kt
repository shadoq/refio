package pl.jclab.refio.core.services.analysis.project

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

data class FrameworkAnalysis(
    val frameworks: List<DetectedFramework> = emptyList(),
    val layers: List<ArchitecturalLayer> = emptyList(),
    val endpoints: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val configFiles: List<String> = emptyList(),
    val conventions: List<String> = emptyList()
)

data class DetectedFramework(
    val name: String,
    val version: String? = null,
    val confidence: Float = 1.0f
)

data class ArchitecturalLayer(
    val name: String,
    val description: String,
    val filePatterns: List<String> = emptyList(),
    val exampleFiles: List<String> = emptyList()
)

/**
 * Detects frameworks and architectural layers by analyzing file paths and dependency files.
 */
class FrameworkAnalyzer {

    fun analyze(files: List<String>, projectRoot: Path? = null): FrameworkAnalysis {
        val frameworks = mutableListOf<DetectedFramework>()
        val layers = mutableListOf<ArchitecturalLayer>()
        val endpoints = mutableListOf<String>()
        val models = mutableListOf<String>()
        val configFiles = mutableListOf<String>()
        val conventions = mutableListOf<String>()

        // Read build files for dependency-based detection
        val buildFileContents = readBuildFiles(files, projectRoot)

        // JVM frameworks
        detectSpringBoot(files, buildFileContents, frameworks, layers, endpoints, models, configFiles, conventions)
        detectKtor(files, buildFileContents, frameworks, layers, configFiles, conventions)
        detectMicronaut(files, buildFileContents, frameworks, layers, configFiles, conventions)

        // Frontend frameworks
        detectReact(files, buildFileContents, frameworks, layers, conventions)
        detectNextJs(files, buildFileContents, frameworks, layers, conventions)
        detectVue(files, buildFileContents, frameworks, layers, conventions)
        detectStateManagement(files, conventions)

        // Python frameworks
        detectDjango(files, frameworks, layers, endpoints, models, configFiles, conventions)
        detectFastAPI(files, frameworks, layers, endpoints, conventions)
        detectFlask(files, frameworks, layers, conventions)

        // Node.js frameworks
        detectExpress(files, buildFileContents, frameworks, layers, conventions)
        detectNestJS(files, frameworks, layers, conventions)

        return FrameworkAnalysis(
            frameworks = frameworks,
            layers = layers,
            endpoints = endpoints,
            models = models,
            configFiles = configFiles,
            conventions = conventions
        )
    }

    private fun readBuildFiles(files: List<String>, projectRoot: Path?): Map<String, String> {
        if (projectRoot == null) return emptyMap()

        val buildFiles = listOf(
            "build.gradle", "build.gradle.kts",
            "package.json",
            "requirements.txt", "pyproject.toml",
            "pom.xml"
        )

        val contents = mutableMapOf<String, String>()
        for (buildFile in buildFiles) {
            if (files.any { it == buildFile || it.endsWith("/$buildFile") }) {
                val path = projectRoot.resolve(buildFile)
                if (path.exists()) {
                    try {
                        contents[buildFile] = path.readText()
                    } catch (_: Exception) {
                        // Ignore read errors
                    }
                }
            }
        }
        return contents
    }

    // --- JVM Frameworks ---

    @Suppress("UNUSED_PARAMETER")
    private fun detectSpringBoot(
        files: List<String>,
        buildContents: Map<String, String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        _endpoints: MutableList<String>,
        models: MutableList<String>,
        configFiles: MutableList<String>,
        conventions: MutableList<String>
    ) {
        val hasSpringDep = buildContents.any { (name, content) ->
            (name.startsWith("build.gradle") && content.contains("spring-boot")) ||
                (name == "pom.xml" && content.contains("spring-boot"))
        }
        val hasAppProperties = files.any {
            it.endsWith("application.properties") || it.endsWith("application.yml") || it.endsWith("application.yaml")
        }

        val controllerFiles = files.filter { it.contains("Controller") && (it.endsWith(".kt") || it.endsWith(".java")) }
        val serviceFiles = files.filter {
            it.contains("Service") && !it.contains("Test") && (it.endsWith(".kt") || it.endsWith(".java"))
        }
        val repositoryFiles = files.filter { it.contains("Repository") && (it.endsWith(".kt") || it.endsWith(".java")) }

        val hasSpringPatterns = controllerFiles.isNotEmpty() && serviceFiles.isNotEmpty()

        if (hasSpringDep || (hasAppProperties && hasSpringPatterns)) {
            val confidence = when {
                hasSpringDep && hasAppProperties -> 1.0f
                hasSpringDep -> 0.9f
                hasAppProperties && hasSpringPatterns -> 0.8f
                else -> 0.6f
            }

            val version = extractSpringVersion(buildContents)
            frameworks.add(DetectedFramework("Spring Boot", version, confidence))

            if (controllerFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Controllers",
                        description = "REST API controllers handling HTTP requests",
                        filePatterns = listOf("*Controller.kt", "*Controller.java"),
                        exampleFiles = controllerFiles.take(5)
                    )
                )
            }
            if (serviceFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Services",
                        description = "Business logic layer",
                        filePatterns = listOf("*Service.kt", "*Service.java"),
                        exampleFiles = serviceFiles.take(5)
                    )
                )
            }
            if (repositoryFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Repositories",
                        description = "Data access layer",
                        filePatterns = listOf("*Repository.kt", "*Repository.java"),
                        exampleFiles = repositoryFiles.take(5)
                    )
                )
            }

            // Detect model/entity files
            val entityFiles = files.filter {
                (it.contains("Entity") || it.contains("Model") || it.contains("/model/") || it.contains("/entity/")) &&
                    (it.endsWith(".kt") || it.endsWith(".java"))
            }
            models.addAll(entityFiles.take(10).map { it.substringAfterLast("/").removeSuffix(".kt").removeSuffix(".java") })

            // Config files
            files.filter {
                it.endsWith("application.properties") || it.endsWith("application.yml") || it.endsWith("application.yaml")
            }.forEach { configFiles.add(it) }

            conventions.add("Spring Boot: Controller -> Service -> Repository")
        }
    }

    private fun extractSpringVersion(buildContents: Map<String, String>): String? {
        for ((name, content) in buildContents) {
            if (name.startsWith("build.gradle")) {
                val match = Regex("""spring-boot.*?(\d+\.\d+\.\d+)""").find(content)
                if (match != null) return match.groupValues[1]
            }
            if (name == "pom.xml") {
                val match = Regex("""<spring-boot.version>(\d+\.\d+\.\d+)</spring-boot.version>""").find(content)
                if (match != null) return match.groupValues[1]
            }
        }
        return null
    }

    private fun detectKtor(
        files: List<String>,
        buildContents: Map<String, String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        configFiles: MutableList<String>,
        conventions: MutableList<String>
    ) {
        val hasKtorDep = buildContents.any { (name, content) ->
            name.startsWith("build.gradle") && content.contains("ktor")
        }
        val hasApplicationKt = files.any { it.endsWith("Application.kt") }
        val routingFiles = files.filter {
            (it.contains("Routing") || it.contains("Route") || it.contains("/routing/") || it.contains("/routes/")) &&
                it.endsWith(".kt")
        }

        if (hasKtorDep || (hasApplicationKt && routingFiles.isNotEmpty())) {
            val confidence = if (hasKtorDep) 1.0f else 0.7f
            val version = buildContents.values.firstNotNullOfOrNull { content ->
                Regex("""ktor.*?(\d+\.\d+\.\d+)""").find(content)?.groupValues?.get(1)
            }
            frameworks.add(DetectedFramework("Ktor", version, confidence))

            if (routingFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Routes",
                        description = "Ktor routing definitions",
                        filePatterns = listOf("*Route*.kt", "*Routing*.kt"),
                        exampleFiles = routingFiles.take(5)
                    )
                )
            }

            files.filter { it.endsWith("application.conf") || it.endsWith("application.yaml") }
                .forEach { configFiles.add(it) }

            conventions.add("Ktor: Route-based architecture")
        }
    }

    private fun detectMicronaut(
        files: List<String>,
        buildContents: Map<String, String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        configFiles: MutableList<String>,
        conventions: MutableList<String>
    ) {
        val hasMicronautDep = buildContents.any { (name, content) ->
            name.startsWith("build.gradle") && content.contains("micronaut")
        }
        val controllerFiles = files.filter { it.contains("Controller") && (it.endsWith(".kt") || it.endsWith(".java")) }

        if (hasMicronautDep) {
            val version = buildContents.values.firstNotNullOfOrNull { content ->
                Regex("""micronaut.*?(\d+\.\d+\.\d+)""").find(content)?.groupValues?.get(1)
            }
            frameworks.add(DetectedFramework("Micronaut", version, 1.0f))

            if (controllerFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Controllers",
                        description = "Micronaut HTTP controllers",
                        filePatterns = listOf("*Controller.kt", "*Controller.java"),
                        exampleFiles = controllerFiles.take(5)
                    )
                )
            }

            files.filter { it.endsWith("application.yml") || it.endsWith("application.yaml") }
                .forEach { configFiles.add(it) }

            conventions.add("Micronaut: Controller-based architecture")
        }
    }

    // --- Frontend Frameworks ---

    private fun detectReact(
        files: List<String>,
        buildContents: Map<String, String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        conventions: MutableList<String>
    ) {
        val hasReactDep = buildContents["package.json"]?.contains("\"react\"") == true
        val jsxFiles = files.filter { it.endsWith(".jsx") || it.endsWith(".tsx") }
        val componentFiles = files.filter {
            (it.contains("components/") || it.contains("Components/")) &&
                (it.endsWith(".jsx") || it.endsWith(".tsx") || it.endsWith(".js") || it.endsWith(".ts"))
        }

        if (hasReactDep || jsxFiles.isNotEmpty()) {
            val confidence = when {
                hasReactDep -> 1.0f
                jsxFiles.size > 3 -> 0.9f
                else -> 0.7f
            }
            val version = extractPackageVersion(buildContents, "react")
            frameworks.add(DetectedFramework("React", version, confidence))

            if (componentFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Components",
                        description = "React UI components",
                        filePatterns = listOf("components/**/*.tsx", "components/**/*.jsx"),
                        exampleFiles = componentFiles.take(5)
                    )
                )
            }

            val hookFiles = files.filter { it.contains("hooks/") || it.contains("use") && it.endsWith(".ts") }
            if (hookFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Hooks",
                        description = "Custom React hooks",
                        filePatterns = listOf("hooks/**/*.ts", "use*.ts"),
                        exampleFiles = hookFiles.take(5)
                    )
                )
            }

            conventions.add("React: Component-based architecture")
        }
    }

    private fun detectNextJs(
        files: List<String>,
        buildContents: Map<String, String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        conventions: MutableList<String>
    ) {
        val hasNextConfig = files.any {
            it.endsWith("next.config.js") || it.endsWith("next.config.mjs") || it.endsWith("next.config.ts")
        }
        val hasNextDep = buildContents["package.json"]?.contains("\"next\"") == true
        val hasPagesDir = files.any { it.startsWith("pages/") || it.contains("/pages/") }
        val hasAppDir = files.any { it.startsWith("app/") || it.contains("/app/") }

        if (hasNextConfig || hasNextDep) {
            val version = extractPackageVersion(buildContents, "next")
            frameworks.add(DetectedFramework("Next.js", version, 1.0f))

            if (hasPagesDir) {
                val pageFiles = files.filter { it.contains("/pages/") || it.startsWith("pages/") }
                layers.add(
                    ArchitecturalLayer(
                        name = "Pages",
                        description = "Next.js pages (file-based routing)",
                        filePatterns = listOf("pages/**/*.tsx", "pages/**/*.ts"),
                        exampleFiles = pageFiles.take(5)
                    )
                )
                conventions.add("Next.js: Pages Router (file-based routing)")
            }

            if (hasAppDir) {
                val appFiles = files.filter { it.contains("/app/") || it.startsWith("app/") }
                layers.add(
                    ArchitecturalLayer(
                        name = "App Routes",
                        description = "Next.js App Router (file-based routing)",
                        filePatterns = listOf("app/**/page.tsx", "app/**/layout.tsx"),
                        exampleFiles = appFiles.take(5)
                    )
                )
                conventions.add("Next.js: App Router (file-based routing)")
            }
        }
    }

    private fun detectVue(
        files: List<String>,
        buildContents: Map<String, String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        conventions: MutableList<String>
    ) {
        val vueFiles = files.filter { it.endsWith(".vue") }
        val hasVueDep = buildContents["package.json"]?.contains("\"vue\"") == true
        @Suppress("UNUSED_VARIABLE")
        val _hasVueConfig = files.any { it.endsWith("vue.config.js") || it.endsWith("vite.config.ts") }

        if (vueFiles.isNotEmpty() || hasVueDep) {
            val confidence = if (hasVueDep) 1.0f else 0.8f
            val version = extractPackageVersion(buildContents, "vue")
            frameworks.add(DetectedFramework("Vue", version, confidence))

            if (vueFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Components",
                        description = "Vue single-file components",
                        filePatterns = listOf("**/*.vue"),
                        exampleFiles = vueFiles.take(5)
                    )
                )
            }

            conventions.add("Vue: Single-file component architecture")
        }
    }

    private fun detectStateManagement(
        files: List<String>,
        conventions: MutableList<String>
    ) {
        val hasRedux = files.any { it.contains("redux/") || it.contains("/reducers/") || it.contains("/actions/") }
        val hasStore = files.any { it.contains("/store/") || it.contains("Store.") }
        val hasZustand = files.any { it.contains("zustand") || it.contains("/store") && it.endsWith(".ts") }

        if (hasRedux) conventions.add("State management: Redux")
        if (hasZustand) conventions.add("State management: Zustand")
        if (hasStore && !hasRedux && !hasZustand) conventions.add("State management: Store pattern")
    }

    // --- Python Frameworks ---

    private fun detectDjango(
        files: List<String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        endpoints: MutableList<String>,
        models: MutableList<String>,
        configFiles: MutableList<String>,
        conventions: MutableList<String>
    ) {
        val hasManagePy = files.any { it == "manage.py" || it.endsWith("/manage.py") }
        val hasSettingsPy = files.any { it.endsWith("settings.py") }
        val hasUrlsPy = files.any { it.endsWith("urls.py") }
        val viewFiles = files.filter { it.endsWith("views.py") }
        val modelFiles = files.filter { it.endsWith("models.py") }

        if (hasManagePy || (hasSettingsPy && hasUrlsPy)) {
            val confidence = if (hasManagePy && hasSettingsPy) 1.0f else 0.8f
            frameworks.add(DetectedFramework("Django", null, confidence))

            if (viewFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Views",
                        description = "Django views handling requests",
                        filePatterns = listOf("**/views.py"),
                        exampleFiles = viewFiles.take(5)
                    )
                )
            }
            if (modelFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Models",
                        description = "Django ORM models",
                        filePatterns = listOf("**/models.py"),
                        exampleFiles = modelFiles.take(5)
                    )
                )
                models.addAll(modelFiles.take(10).map { it.substringBeforeLast("/") + " models" })
            }

            files.filter { it.endsWith("urls.py") }.forEach { endpoints.add(it) }
            files.filter { it.endsWith("settings.py") }.forEach { configFiles.add(it) }

            conventions.add("Django: Model -> View -> Template (MVT)")
        }
    }

    private fun detectFastAPI(
        files: List<String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        endpoints: MutableList<String>,
        conventions: MutableList<String>
    ) {
        val hasMainPy = files.any { it == "main.py" || it.endsWith("/main.py") }
        val routerFiles = files.filter {
            (it.contains("routers/") || it.contains("routes/") || it.contains("api/")) && it.endsWith(".py")
        }

        // Check if main.py could be FastAPI (heuristic: has main.py + routers directory)
        val hasFastAPIStructure = hasMainPy && routerFiles.isNotEmpty()

        if (hasFastAPIStructure) {
            frameworks.add(DetectedFramework("FastAPI", null, 0.7f))

            if (routerFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Routers",
                        description = "FastAPI route handlers",
                        filePatterns = listOf("routers/**/*.py", "routes/**/*.py"),
                        exampleFiles = routerFiles.take(5)
                    )
                )
                endpoints.addAll(routerFiles.take(10))
            }

            conventions.add("FastAPI: Router-based architecture")
        }
    }

    private fun detectFlask(
        files: List<String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        conventions: MutableList<String>
    ) {
        val hasAppPy = files.any { it == "app.py" || it.endsWith("/app.py") }
        val hasBlueprintDir = files.any { it.contains("blueprints/") }

        if (hasAppPy) {
            frameworks.add(DetectedFramework("Flask", null, 0.6f))

            if (hasBlueprintDir) {
                val blueprintFiles = files.filter { it.contains("blueprints/") && it.endsWith(".py") }
                layers.add(
                    ArchitecturalLayer(
                        name = "Blueprints",
                        description = "Flask blueprints for modular routing",
                        filePatterns = listOf("blueprints/**/*.py"),
                        exampleFiles = blueprintFiles.take(5)
                    )
                )
            }

            conventions.add("Flask: Blueprint-based architecture")
        }
    }

    // --- Node.js Frameworks ---

    private fun detectExpress(
        files: List<String>,
        buildContents: Map<String, String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        conventions: MutableList<String>
    ) {
        val hasExpressDep = buildContents["package.json"]?.contains("\"express\"") == true
        val routeFiles = files.filter {
            (it.contains("routes/") || it.contains("routing/")) &&
                (it.endsWith(".js") || it.endsWith(".ts"))
        }
        val middlewareFiles = files.filter {
            it.contains("middleware/") && (it.endsWith(".js") || it.endsWith(".ts"))
        }

        if (hasExpressDep) {
            val version = extractPackageVersion(buildContents, "express")
            frameworks.add(DetectedFramework("Express", version, 1.0f))

            if (routeFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Routes",
                        description = "Express route handlers",
                        filePatterns = listOf("routes/**/*.ts", "routes/**/*.js"),
                        exampleFiles = routeFiles.take(5)
                    )
                )
            }
            if (middlewareFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Middleware",
                        description = "Express middleware",
                        filePatterns = listOf("middleware/**/*.ts", "middleware/**/*.js"),
                        exampleFiles = middlewareFiles.take(5)
                    )
                )
            }

            conventions.add("Express: Route + Middleware architecture")
        }
    }

    private fun detectNestJS(
        files: List<String>,
        frameworks: MutableList<DetectedFramework>,
        layers: MutableList<ArchitecturalLayer>,
        conventions: MutableList<String>
    ) {
        val moduleFiles = files.filter { it.endsWith(".module.ts") }
        val controllerFiles = files.filter { it.endsWith(".controller.ts") }
        val serviceFiles = files.filter { it.endsWith(".service.ts") }

        if (moduleFiles.isNotEmpty() && controllerFiles.isNotEmpty()) {
            frameworks.add(DetectedFramework("NestJS", null, 0.9f))

            layers.add(
                ArchitecturalLayer(
                    name = "Modules",
                    description = "NestJS modules for dependency injection",
                    filePatterns = listOf("**/*.module.ts"),
                    exampleFiles = moduleFiles.take(5)
                )
            )
            if (controllerFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Controllers",
                        description = "NestJS HTTP controllers",
                        filePatterns = listOf("**/*.controller.ts"),
                        exampleFiles = controllerFiles.take(5)
                    )
                )
            }
            if (serviceFiles.isNotEmpty()) {
                layers.add(
                    ArchitecturalLayer(
                        name = "Services",
                        description = "NestJS service providers",
                        filePatterns = listOf("**/*.service.ts"),
                        exampleFiles = serviceFiles.take(5)
                    )
                )
            }

            conventions.add("NestJS: Module -> Controller -> Service")
        }
    }

    // --- Utilities ---

    private fun extractPackageVersion(buildContents: Map<String, String>, packageName: String): String? {
        val packageJson = buildContents["package.json"] ?: return null
        val pattern = Regex(""""$packageName"\s*:\s*"[^"]*?(\d+\.\d+[\.\d]*)""")
        return pattern.find(packageJson)?.groupValues?.get(1)
    }
}

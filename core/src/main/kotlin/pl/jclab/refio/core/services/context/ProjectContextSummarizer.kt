package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ProjectAnalysis
import pl.jclab.refio.core.services.analysis.project.ProjectAnalysisReport

private val logger = dualLogger("ProjectContextSummarizer")

class ProjectContextSummarizer {

    /**
     * Generate compact project summary optimized for small LLMs.
     */
    fun buildCompactProjectSummary(
        projectAnalysis: ProjectAnalysis,
        richReport: ProjectAnalysisReport?,
        maxTokens: Int = 4000
    ): String {
        val parts = mutableListOf<String>()

        parts.add(buildArchitectureSummary(projectAnalysis, richReport))
        buildKeyComponentsSummary(richReport)?.let { parts.add(it) }
        buildPatternsAndConventions(richReport)?.let { parts.add(it) }
        buildNavigationMap(richReport, projectAnalysis)?.let { parts.add(it) }

        return parts.joinToString("\n\n").take(maxTokens * 4)
    }

    private fun buildArchitectureSummary(
        analysis: ProjectAnalysis,
        rich: ProjectAnalysisReport?
    ): String {
        val arch = rich?.architecture
        val style = arch?.style ?: "Unknown"
        val layers = arch?.layers?.map { it.name } ?: emptyList()
        val entryPoints = arch?.apiSurface?.entryPoints?.take(5).orEmpty()
        val layersLine = if (layers.isNotEmpty()) "Layers: ${layers.joinToString(" -> ")}" else ""

        val entryPointsLine = if (entryPoints.isNotEmpty()) "\nKey Entry Points: ${entryPoints.joinToString(", ")}" else ""

        return """
            <PROJECT_ARCHITECTURE>
            Style: $style
            Primary Language: ${analysis.primaryLanguage}
            $layersLine$entryPointsLine
            </PROJECT_ARCHITECTURE>
        """.trimIndent()
    }

    private fun buildKeyComponentsSummary(
        rich: ProjectAnalysisReport?
    ): String? {
        if (rich == null) return null

        val classes = rich.codeStructure.classes
        val controllers = classes.filter { cls -> cls.annotations.any { it.contains("Controller") } }
        val services = classes.filter { cls -> cls.annotations.any { it.contains("Service") } }
        val repositories = classes.filter { cls -> cls.annotations.any { it.contains("Repository") } }
        val models = classes.filter { cls ->
            cls.name.endsWith("DTO") || cls.name.endsWith("Entity") || cls.modifiers.contains("data")
        }

        // Also detect non-JVM key classes: decorators (@app.route), base classes, main modules
        val keyClasses = classes.filter { cls ->
            cls.annotations.any { a ->
                a.contains("route") || a.contains("endpoint") || a.contains("view") ||
                a.contains("Component") || a.contains("Injectable") || a.contains("Module")
            } || cls.methods.size >= 5
        }.filterNot { it in controllers || it in services || it in repositories || it in models }

        val hasContent = controllers.isNotEmpty() || services.isNotEmpty() ||
            repositories.isNotEmpty() || models.isNotEmpty() || keyClasses.isNotEmpty()
        if (!hasContent) return null

        val parts = mutableListOf<String>()
        parts.add("<KEY_COMPONENTS>")

        if (controllers.isNotEmpty()) {
            parts.add("## API Controllers")
            controllers.take(5).forEach { ctrl ->
                val methods = ctrl.methods.filter { it.modifiers.contains("public") }
                    .take(3)
                    .map { "${it.name}(${it.parameters.size} params) -> ${it.returnType ?: "void"}" }
                parts.add("- ${ctrl.name}: ${methods.joinToString(", ")}")
            }
        }

        if (services.isNotEmpty()) {
            parts.add("## Services")
            services.take(5).forEach { svc ->
                val doc = svc.documentation?.take(100) ?: "Business logic"
                parts.add("- ${svc.name}: $doc")
            }
        }

        if (repositories.isNotEmpty()) {
            parts.add("## Repositories")
            repositories.take(5).forEach { repo ->
                parts.add("- ${repo.name}: data access")
            }
        }

        if (models.isNotEmpty()) {
            parts.add("## Data Models")
            models.take(10).forEach { model ->
                val fields = model.fields.take(5).map { "${it.name}: ${it.type ?: "?"}" }
                val fieldText = if (fields.isNotEmpty()) "(${fields.joinToString(", ")})" else ""
                parts.add("- ${model.name}$fieldText")
            }
        }

        if (keyClasses.isNotEmpty()) {
            parts.add("## Key Classes")
            keyClasses.take(10).forEach { cls ->
                val methodNames = cls.methods.take(5).joinToString(", ") { it.name }
                val suffix = if (methodNames.isNotBlank()) ": $methodNames" else ""
                parts.add("- ${cls.name}$suffix")
            }
        }

        parts.add("</KEY_COMPONENTS>")
        return parts.joinToString("\n")
    }

    private fun buildPatternsAndConventions(
        rich: ProjectAnalysisReport?
    ): String? {
        if (rich == null) return null

        val patterns = rich.patterns
        // Deduplicate framework patterns by unique framework:pattern pairs
        val frameworkPatterns = patterns.frameworkPatterns
            .map { "${it.framework}:${it.pattern}" }
            .distinct()
            .take(5)
            .joinToString(", ")
        val naming = patterns.namingConventions

        val parts = mutableListOf<String>()
        parts.add("<PATTERNS>")
        if (frameworkPatterns.isNotBlank()) {
            parts.add("Framework patterns: $frameworkPatterns")
        }
        parts.add("Naming: class=${naming.classNaming}, method=${naming.methodNaming}")
        parts.add("</PATTERNS>")

        return parts.joinToString("\n")
    }

    // Removed buildExternalDependenciesList() — it listed import package prefixes (java, com, kotlinx)
    // which are not actual dependencies. Real dependencies are in buildDependenciesSection().

    private fun buildNavigationMap(
        rich: ProjectAnalysisReport?,
        projectAnalysis: ProjectAnalysis
    ): String? {
        val structure = rich?.codeStructure ?: return null

        // Categorize packages/directories by purpose
        val packageMap = structure.packages.groupBy { pkg ->
            val name = pkg.name.lowercase()
            when {
                name.contains(".api") || name.contains(".controller") || name.contains("/api") ||
                    name.contains("/controllers") || name.contains("/routes") || name.contains("/views") ||
                    name.contains("/endpoints") -> "API / Routes"
                name.contains(".service") || name.contains("/services") || name.contains("/logic") ||
                    name.contains("/core") -> "Business Logic"
                name.contains(".repository") || name.contains(".db") || name.contains("/db") ||
                    name.contains("/database") || name.contains("/repositories") ||
                    name.contains("/models") || name.contains("/dao") -> "Data Access"
                name.contains(".model") || name.contains(".dto") || name.contains("/schemas") ||
                    name.contains("/entities") || name.contains("/types") -> "Models"
                name.contains(".config") || name.contains("/config") || name.contains("/settings") -> "Configuration"
                name.contains(".util") || name.contains("/utils") || name.contains("/helpers") ||
                    name.contains("/lib") || name.contains("/common") -> "Utilities"
                name.contains("/test") || name.contains("/tests") || name.contains("/spec") -> "Tests"
                name.contains("/ui") || name.contains("/components") || name.contains("/pages") ||
                    name.contains("/templates") || name.contains("/static") -> "UI / Frontend"
                name.contains("/scripts") || name.contains("/tools") || name.contains("/bin") -> "Scripts / Tools"
                name.contains("/docs") || name.contains("/documentation") -> "Documentation"
                else -> null
            }
        }

        // Filter out uncategorized and empty categories
        val categorized = packageMap.filterKeys { it != null }
        if (categorized.isEmpty()) {
            // Fall back to top-level directory structure from project analysis
            val topLevelDirs = projectAnalysis.structure.topLevelItems
                .filter { !it.startsWith(".") }
            if (topLevelDirs.isEmpty()) return null

            val parts = mutableListOf<String>()
            parts.add("<NAVIGATION_MAP>")
            parts.add("Top-level structure:")
            compactDirectoryList(topLevelDirs).forEach { parts.add("- $it") }
            parts.add("</NAVIGATION_MAP>")
            return parts.joinToString("\n")
        }

        val parts = mutableListOf<String>()
        parts.add("<NAVIGATION_MAP>")
        parts.add("Where to find what:")

        categorized.entries.sortedBy { it.key }.forEach { (category, packages) ->
            val names = packages.map { it.name.substringAfterLast('.').substringAfterLast('/') }
                .distinct().take(5)
            parts.add("- $category: ${names.joinToString(", ")}")
        }

        parts.add("</NAVIGATION_MAP>")
        return parts.joinToString("\n")
    }

    /**
     * Groups similar directory/file names by common prefix to reduce noise.
     * E.g., ["mimir_benchmark_20260130_143610", "mimir_benchmark_20260130_144914", "mimir_benchmark_20260310_103925"]
     * becomes ["mimir_benchmark_* (3 dirs)"]
     */
    private fun compactDirectoryList(items: List<String>): List<String> {
        if (items.size <= 10) {
            // Try grouping by common prefix (at least 4 chars) with numeric/date suffix
            val groups = mutableMapOf<String, MutableList<String>>()
            val standalone = mutableListOf<String>()

            for (item in items) {
                // Find prefix before numeric/date suffix pattern
                val prefixMatch = Regex("""^(.{4,}?)[\d_.-]+\d{4,}.*$""").find(item)
                if (prefixMatch != null) {
                    groups.getOrPut(prefixMatch.groupValues[1]) { mutableListOf() }.add(item)
                } else {
                    standalone.add(item)
                }
            }

            val result = mutableListOf<String>()
            for ((prefix, members) in groups) {
                if (members.size >= 2) {
                    result.add("${prefix.trimEnd('_', '-', '.')}* (${members.size} items)")
                } else {
                    result.addAll(members)
                }
            }
            result.addAll(standalone)
            return result.take(12)
        }

        // Many items — group aggressively
        val byExtension = items.groupBy { item ->
            val dot = item.lastIndexOf('.')
            if (dot > 0) item.substring(dot) else ""
        }

        val result = mutableListOf<String>()
        for ((ext, members) in byExtension.entries.sortedByDescending { it.value.size }) {
            if (ext.isNotEmpty() && members.size > 3) {
                result.add("*$ext (${members.size} files)")
            } else {
                result.addAll(members.take(3))
                if (members.size > 3) result.add("... and ${members.size - 3} more")
            }
        }
        return result.take(12)
    }
}

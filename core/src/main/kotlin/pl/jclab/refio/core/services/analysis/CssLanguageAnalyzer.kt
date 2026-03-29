package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class CssLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "css",
    extensions = setOf(".css", ".scss", ".sass", ".less")
) {

    private val classSelectorRegex = Regex("""(?:^|[},;\s])\s*(\.[a-zA-Z_][a-zA-Z0-9_-]*)\s*[{,:]""")
    private val idSelectorRegex = Regex("""(?:^|[},;\s])\s*(#[a-zA-Z_][a-zA-Z0-9_-]*)\s*[{,:]""")
    private val keyframesRegex = Regex("""@keyframes\s+([a-zA-Z_][a-zA-Z0-9_-]*)""")
    private val mediaQueryRegex = Regex("""@media\s+([^{]+)""")
    private val importRegex = Regex("""@import\s+(?:url\()?['"]?([^'");\s]+)['"]?\)?[^;]*;""")
    private val cssVariableRegex = Regex("""(--[a-zA-Z][a-zA-Z0-9_-]*)\s*:""")

    // Framework detection patterns
    private val tailwindDirectiveRegex = Regex("""@tailwind\b""")
    private val cssModulesRegex = Regex(""":(?:global|local)\b""")
    private val gridTemplateRegex = Regex("""grid-template""")
    private val flexboxRegex = Regex("""display\s*:\s*flex\b""")

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val classes = mutableListOf<ClassElement>()
        val frameworks = mutableListOf<String>()

        // Extract class selectors
        classSelectorRegex.findAll(content).map { it.groupValues[1] }.distinct().forEach { selector ->
            val firstOccurrence = content.indexOf(selector)
            val startLine = if (firstOccurrence >= 0) lineNumberAt(content, firstOccurrence) else 1
            val endLine = findBlockEndLine(lines, startLine)
            classes.add(
                ClassElement(
                    name = selector,
                    type = "stylesheet",
                    startLine = startLine,
                    endLine = endLine
                )
            )
        }

        // Extract ID selectors
        idSelectorRegex.findAll(content).map { it.groupValues[1] }.distinct().forEach { selector ->
            val firstOccurrence = content.indexOf(selector)
            val startLine = if (firstOccurrence >= 0) lineNumberAt(content, firstOccurrence) else 1
            val endLine = findBlockEndLine(lines, startLine)
            classes.add(
                ClassElement(
                    name = selector,
                    type = "stylesheet",
                    startLine = startLine,
                    endLine = endLine
                )
            )
        }

        // Extract @keyframes animations
        keyframesRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            classes.add(
                ClassElement(
                    name = name,
                    type = "keyframes",
                    startLine = startLine,
                    endLine = endLine
                )
            )
        }

        // Extract @media queries
        mediaQueryRegex.findAll(content).forEach { match ->
            val query = match.groupValues[1].trim()
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            classes.add(
                ClassElement(
                    name = "@media $query",
                    type = "media_query",
                    startLine = startLine,
                    endLine = endLine
                )
            )
        }

        // Extract @import statements
        val imports = importRegex.findAll(content).map { match ->
            ImportElement(
                module = match.groupValues[1],
                alias = null,
                isWildcard = false
            )
        }.toList()

        // Extract CSS variables as fields on a synthetic element
        val variables = mutableListOf<FieldElement>()
        cssVariableRegex.findAll(content).map { it.groupValues[1] }.distinct().forEach { varName ->
            variables.add(
                FieldElement(
                    name = varName,
                    type = "css_variable",
                    modifiers = listOf("custom-property")
                )
            )
        }
        if (variables.isNotEmpty()) {
            classes.add(
                ClassElement(
                    name = "__css_variables__",
                    type = "stylesheet",
                    startLine = 0,
                    endLine = 0,
                    fields = variables
                )
            )
        }

        // Detect frameworks
        if (tailwindDirectiveRegex.containsMatchIn(content)) frameworks.add("Tailwind")
        if (cssModulesRegex.containsMatchIn(content)) frameworks.add("CSS-Modules")
        if (gridTemplateRegex.containsMatchIn(content)) frameworks.add("CSS-Grid")
        if (flexboxRegex.containsMatchIn(content)) frameworks.add("Flexbox")

        // Estimate complexity based on nesting depth and selector count
        val selectorCount = classes.count { it.type == "stylesheet" || it.type == "keyframes" || it.type == "media_query" }
        val maxNesting = estimateMaxNestingDepth(content)
        val complexity = selectorCount + maxNesting

        return CodeElements(
            classes = classes,
            imports = imports,
            frameworks = frameworks.distinct(),
            documentation = if (complexity > 0) "Complexity estimate: $complexity (selectors: $selectorCount, max nesting: $maxNesting)" else null
        )
    }

    private fun estimateMaxNestingDepth(content: String): Int {
        var maxDepth = 0
        var currentDepth = 0
        for (ch in content) {
            when (ch) {
                '{' -> {
                    currentDepth++
                    if (currentDepth > maxDepth) maxDepth = currentDepth
                }
                '}' -> {
                    currentDepth = (currentDepth - 1).coerceAtLeast(0)
                }
            }
        }
        return maxDepth
    }
}

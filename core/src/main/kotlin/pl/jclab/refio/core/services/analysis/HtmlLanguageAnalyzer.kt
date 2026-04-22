package pl.jclab.refio.core.services.analysis

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class HtmlLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "html",
    extensions = setOf(".html", ".htm")
) {

    private val titleRegex = Regex("(?is)<title>(.*?)</title>")
    private val canvasRegex = Regex("(?is)<canvas[^>]*id\\s*=\\s*\"([^\"]+)\"[^>]*>")
    private val buttonRegex = Regex("(?is)<button[^>]*>(.*?)</button>")
    private val scriptSrcRegex = Regex("(?is)<script[^>]*src\\s*=\\s*\"([^\"]+)\"[^>]*>")
    private val inlineScriptRegex = Regex("(?is)<script[^>]*>(.*?)</script>")
    private val jsAnalyzer = TypeScriptLanguageAnalyzer()

    override fun analyze(filePath: Path, content: String): CodeElements {
        val title = titleRegex.find(content)?.groupValues?.getOrNull(1)?.trim()
        val canvases = canvasRegex.findAll(content).mapNotNull { it.groupValues.getOrNull(1)?.trim() }.toList()
        val buttons = buttonRegex.findAll(content).mapNotNull { it.groupValues.getOrNull(1)?.trim() }.toList()
        val scriptSources = scriptSrcRegex.findAll(content).mapNotNull { it.groupValues.getOrNull(1)?.trim() }.toList()
        val inlineScripts = inlineScriptRegex.findAll(content).mapNotNull { it.groupValues.getOrNull(1)?.trim() }.toList()

        val summary = buildString {
            if (!title.isNullOrBlank()) {
                appendLine("Title: $title")
            }
            if (canvases.isNotEmpty()) {
                appendLine("Canvas elements: ${canvases.joinToString()}")
            }
            if (buttons.isNotEmpty()) {
                appendLine("Buttons: ${buttons.joinToString { normalizeWhitespace(it).take(30) }}")
            }
            if (scriptSources.isNotEmpty()) {
                appendLine("Script sources: ${scriptSources.joinToString()}")
            }
            if (inlineScripts.isNotEmpty()) {
                appendLine("Inline scripts: ${inlineScripts.size}")
            }
        }.trim()

        val jsElements = mutableListOf<CodeElements>()
        inlineScripts.filter { it.isNotBlank() }.forEach { script ->
            jsElements += jsAnalyzer.analyze(filePath, script)
        }

        scriptSources
            .mapNotNull { it.takeIf { src -> !src.startsWith("http", ignoreCase = true) && !src.startsWith("//") } }
            .mapNotNull { src ->
                // Strip query string (?v=10) and fragment (#anchor), reject scheme-qualified
                // URLs (javascript:, data:). Wrap in runCatching because Windows rejects
                // additional chars (* < > | ") that can appear in generated src attributes.
                val clean = src.substringBefore('?').substringBefore('#').trim()
                if (clean.isBlank() || clean.contains(':')) return@mapNotNull null
                runCatching { filePath.parent?.resolve(clean)?.normalize() }.getOrNull()
            }
            .filter { Files.exists(it) && Files.isRegularFile(it) }
            .forEach { resolved ->
                runCatching {
                    jsElements += jsAnalyzer.analyze(resolved, resolved.readText())
                }
            }

        val aggregatedClasses = jsElements.flatMap { it.classes }
        val aggregatedFunctions = jsElements.flatMap { it.functions }
        val aggregatedImports = jsElements.flatMap { it.imports }
        val aggregatedExports = jsElements.flatMap { it.exports }
        val aggregatedFrameworks = jsElements.flatMap { it.frameworks }.distinct()

        val enrichedSummary = buildString {
            append(summary)
            if (summary.isNotEmpty() && aggregatedFunctions.isNotEmpty()) appendLine()
            if (aggregatedFunctions.isNotEmpty()) {
                appendLine("JS functions: ${aggregatedFunctions.map { it.name }.distinct().take(5).joinToString()}")
            }
            if (aggregatedClasses.isNotEmpty()) {
                appendLine("JS classes: ${aggregatedClasses.map { it.name }.distinct().take(5).joinToString()}")
            }
        }.trim()

        return CodeElements(
            classes = aggregatedClasses,
            functions = aggregatedFunctions,
            imports = aggregatedImports,
            exports = aggregatedExports,
            frameworks = aggregatedFrameworks,
            documentation = enrichedSummary.ifBlank { null }
        )
    }

    private fun normalizeWhitespace(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()
}

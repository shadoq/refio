package pl.jclab.refio.core.services.analysis

import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Contract for lightweight, per-file analyzers that emit structured metadata.
 */
interface LanguageAnalyzer {
    val languageId: String

    /**
     * @return true if analyzer should handle the given file.
     */
    fun matches(filePath: Path): Boolean

    /**
     * Analyze file contents and return structured code elements.
     */
    fun analyze(filePath: Path, content: String): CodeElements
}

abstract class ExtensionLanguageAnalyzer(
    override val languageId: String,
    private val extensions: Set<String>
) : LanguageAnalyzer {

    override fun matches(filePath: Path): Boolean {
        val ext = ".${filePath.extension.lowercase()}"
        return extensions.any { it.equals(ext, ignoreCase = true) }
    }

    protected fun lineNumberAt(content: String, index: Int): Int {
        if (index <= 0) return 1
        var count = 1
        for (i in 0 until index.coerceAtMost(content.length)) {
            if (content[i] == '\n') count++
        }
        return count
    }

    /**
     * Find the closing brace line for a block starting at [startLine].
     * Uses comment/string-aware brace counting to avoid false matches inside literals.
     */
    protected fun findBlockEndLine(lines: List<String>, startLine: Int): Int {
        var braceBalance = 0
        var foundOpening = false
        for (i in (startLine - 1) until lines.size) {
            val stripped = stripLineStringsAndComments(lines[i])
            if (stripped.contains('{')) {
                foundOpening = true
                braceBalance += stripped.count { it == '{' }
            }
            if (stripped.contains('}')) {
                braceBalance -= stripped.count { it == '}' }
            }
            if (foundOpening && braceBalance <= 0) {
                return i + 1
            }
        }
        return lines.size
    }

    protected fun annotationsAbove(lines: List<String>, startLine: Int): List<String> {
        val result = mutableListOf<String>()
        for (i in startLine - 2 downTo 0) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("@")) {
                result.add(trimmed.removePrefix("@").takeWhile { it != '(' && !it.isWhitespace() })
            } else if (trimmed.isNotEmpty()) {
                break
            }
        }
        return result.reversed()
    }

    /**
     * Extracts full annotation text (including parameters) from lines above [startLine].
     * E.g., `@RequestMapping("/api")` returns `RequestMapping("/api")`.
     */
    protected fun annotationsWithParamsAbove(lines: List<String>, startLine: Int): List<String> {
        val result = mutableListOf<String>()
        for (i in startLine - 2 downTo 0) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("@")) {
                result.add(trimmed.removePrefix("@").trim())
            } else if (trimmed.isNotEmpty()) {
                break
            }
        }
        return result.reversed()
    }

    /**
     * Strips string literals and single-line comments from a single line.
     * Replaces content inside quotes with spaces to preserve character positions.
     */
    private fun stripLineStringsAndComments(line: String): String {
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                // Line comment
                ch == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                    // Rest of line is comment
                    repeat(line.length - i) { sb.append(' ') }
                    return sb.toString()
                }
                // String literal (double quote)
                ch == '"' -> {
                    sb.append(' ')
                    i++
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\') { sb.append(' '); i++ }
                        sb.append(' ')
                        i++
                    }
                    if (i < line.length) { sb.append(' '); i++ }
                }
                // Char literal
                ch == '\'' -> {
                    sb.append(' ')
                    i++
                    while (i < line.length && line[i] != '\'') {
                        if (line[i] == '\\') { sb.append(' '); i++ }
                        sb.append(' ')
                        i++
                    }
                    if (i < line.length) { sb.append(' '); i++ }
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * Joins multiline declarations into single logical lines for regex matching.
     * Collapses lines where parentheses are unclosed across line boundaries.
     * Returns the joined content (NOT suitable for line number calculation — use original content for that).
     */
    protected fun joinMultilineDeclarations(content: String): String {
        val lines = content.lines()
        val result = StringBuilder()
        var parenDepth = 0
        var accumulator = StringBuilder()

        for (line in lines) {
            val stripped = stripLineStringsAndComments(line)
            if (parenDepth > 0) {
                accumulator.append(' ').append(line.trim())
                parenDepth += stripped.count { it == '(' } - stripped.count { it == ')' }
                if (parenDepth <= 0) {
                    result.appendLine(accumulator.toString())
                    accumulator = StringBuilder()
                    parenDepth = 0
                }
            } else {
                val opens = stripped.count { it == '(' }
                val closes = stripped.count { it == ')' }
                if (opens > closes) {
                    parenDepth = opens - closes
                    accumulator.append(line.trimEnd())
                } else {
                    result.appendLine(line)
                }
            }
        }
        if (accumulator.isNotEmpty()) result.appendLine(accumulator.toString())
        return result.toString()
    }

    fun supportedExtensions(): Set<String> = extensions
}

/**
 * Fallback analyzer that always returns empty metadata but still marks file-level info.
 */
class GenericLanguageAnalyzer : LanguageAnalyzer {
    override val languageId: String = "generic"

    override fun matches(filePath: Path): Boolean = true

    override fun analyze(filePath: Path, content: String): CodeElements = CodeElements()
}

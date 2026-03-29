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
        for (i in 0 until index) {
            if (content[i] == '\n') count++
        }
        return count
    }

    protected fun findBlockEndLine(lines: List<String>, startLine: Int): Int {
        var braceBalance = 0
        var foundOpening = false
        for (i in (startLine - 1) until lines.size) {
            val line = lines[i]
            if (line.contains('{')) {
                foundOpening = true
                braceBalance += line.count { it == '{' }
            }
            if (line.contains('}')) {
                braceBalance -= line.count { it == '}' }
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
                result.add(trimmed.removePrefix("@").takeWhile { !it.isWhitespace() })
            } else if (trimmed.isNotEmpty()) {
                break
            }
        }
        return result.reversed()
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

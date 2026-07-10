package pl.jclab.refio.core.tools.refactor

/**
 * Result of a rename operation.
 */
data class RenameResult(
    /** Relative paths of files that were modified. */
    val filesChanged: List<String>,
    /** Total number of occurrences replaced across all files. */
    val replacements: Int
)

/**
 * A single location where a symbol is used.
 */
data class UsageLocation(
    /** Relative file path. */
    val file: String,
    /** 1-based line number. */
    val line: Int,
    /** The trimmed source line containing the usage. */
    val snippet: String
)

/**
 * Structural refactoring contract with two narrow operations: rename a symbol and find its usages.
 *
 * Two implementations exist:
 * - a text-based fallback in :core (word-boundary-aware search/replace across project files),
 * - a semantic implementation in the IntelliJ plugin backed by the IDE refactoring engine (PSI).
 *
 * The plugin injects its implementation when building the project ToolFactory; without an IDE
 * the text fallback is used.
 */
interface StructuralRefactorer {

    /**
     * Short honest description of what this engine guarantees. Embedded in the tool
     * descriptions shown to the LLM, so it must reflect the actual implementation.
     */
    val engineDescription: String

    /**
     * Rename a symbol across the project.
     *
     * @param file relative path of a file containing the symbol (anchor for semantic engines)
     * @param line 1-based line number of the symbol in [file] (anchor for semantic engines)
     * @param oldName current symbol name
     * @param newName new symbol name
     */
    suspend fun renameSymbol(file: String, line: Int, oldName: String, newName: String): RenameResult

    /**
     * Find all usages of a symbol across the project.
     *
     * @param symbolName the symbol name to look up
     */
    suspend fun findUsages(symbolName: String): List<UsageLocation>
}

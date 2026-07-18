package pl.jclab.refio.core.tools

/**
 * Line-ending reconciliation for exact-match file edits.
 *
 * read_file always hands the model LF-normalized content (it reads via readAllLines and rejoins
 * with "\n"), so a model's old_string/new_string carry LF even when the file on disk uses CRLF.
 * The exact-match editors (code_editing, multi_edit) compare the model string against the file's
 * raw bytes, so on a CRLF file every match misses - the edit fails regardless of model quality.
 * This is the dominant cause of "String not found" edit failures on Windows checkouts
 * (core.autocrlf materializes tracked files as CRLF in the working tree).
 *
 * The fix: before matching, re-express the model string in the file's own line-ending convention.
 * Matching and replacement then happen in the file's bytes, so the file keeps its original EOL
 * (no whole-file CRLF->LF churn in the resulting diff).
 */
object LineEndings {

    /** A file is treated as CRLF when it contains at least one CRLF pair. */
    fun usesCrlf(fileContent: String): Boolean = fileContent.contains("\r\n")

    /**
     * Re-express [modelString] (assumed LF, as fed to the model) in the line-ending convention of
     * [fileContent]. Any CRLF/lone-CR in the input is first folded to LF (idempotent), then expanded
     * back to CRLF only when the file itself is CRLF. A file without CRLF is left untouched.
     */
    fun toFileEol(modelString: String, fileContent: String): String {
        val lf = modelString.replace("\r\n", "\n").replace("\r", "\n")
        return if (usesCrlf(fileContent)) lf.replace("\n", "\r\n") else lf
    }
}

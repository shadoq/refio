package pl.jclab.refio.core.services.context

/**
 * Deterministic compression for directory listings (`read_directory`, `file_search`).
 *
 * WHY not the LLM summarizer: a 29 KB recursive listing came back as 562 chars of prose
 * ("the directory listing reveals a project structure containing...") with not a single
 * directory name in it. The model could no longer pick a file to read, and the WEAK call
 * cost ~5 s on top. A listing is structured data - the useful compression is structural
 * (per-directory counts + a sample of names), not linguistic.
 *
 * WHY it also runs in RECENT_WORK: the raw listing is stored in the subtask row, so the same
 * 400 paths were shipped again on every following iteration (~15,5K tokens = 40 % of the
 * context budget in the observed session) while the conversation held only the prose.
 *
 * Pure and format-tolerant: it keys off the last path-like token of a line, so both the
 * `FILE  45KB  dir\file.html` shape of `read_directory` and the bare paths of `file_search`
 * compress the same way. Lines with no path token are kept verbatim (headers, totals).
 */
object FileListingCompression {

    /** Entries listed per directory before the rest collapses into a "+N more" line. */
    const val DEFAULT_SAMPLE_PER_DIR = 5

    /** Below this many lines a listing is already readable - do not touch it. */
    const val MIN_LINES_TO_COMPRESS = 20

    private val SIZE_REGEX = Regex("^([0-9]+(?:[.,][0-9]+)?)(B|KB|MB|GB)$", RegexOption.IGNORE_CASE)

    /**
     * Group [rawOutput] by directory, keeping [samplePerDir] entry names per directory plus the
     * per-directory count and total size. Returns the input unchanged when it is short enough
     * that compressing would only lose information.
     */
    fun compress(rawOutput: String, samplePerDir: Int = DEFAULT_SAMPLE_PER_DIR): String {
        val lines = rawOutput.lines().filter { it.isNotBlank() }
        if (lines.size < MIN_LINES_TO_COMPRESS) return rawOutput

        val preamble = mutableListOf<String>()
        val groups = LinkedHashMap<String, MutableList<Entry>>()

        for (line in lines) {
            val entry = parseEntry(line)
            if (entry == null) {
                // Header / totals / free text: only meaningful before the first entry; once the
                // listing has started, stray lines are noise the grouped view replaces.
                if (groups.isEmpty()) preamble += line.trim()
                continue
            }
            groups.getOrPut(entry.directory) { mutableListOf() } += entry
        }

        if (groups.isEmpty()) return rawOutput

        val totalEntries = groups.values.sumOf { it.size }
        val compressed = buildString {
            preamble.forEach { appendLine(it) }
            appendLine("$totalEntries entries in ${groups.size} director${if (groups.size == 1) "y" else "ies"}:")
            for ((dir, entries) in groups) {
                val bytes = entries.sumOf { it.bytes }
                val sizePart = if (bytes > 0) ", ${formatBytes(bytes)}" else ""
                appendLine("$dir/  (${entries.size} entries$sizePart)")
                entries.take(samplePerDir).forEach { appendLine("    ${it.name}") }
                val rest = entries.size - samplePerDir
                if (rest > 0) appendLine("    ... +$rest more")
            }
        }.trimEnd()

        // A listing that is mostly one-file directories compresses to more than it started as;
        // in that case the original IS the better representation.
        return if (compressed.length < rawOutput.length) compressed else rawOutput
    }

    private data class Entry(val directory: String, val name: String, val bytes: Long)

    /**
     * Pull the path-like token out of a listing line. Accepts both separators and tolerates the
     * leading `FILE`/`DIR` + size columns of `read_directory`.
     */
    private fun parseEntry(line: String): Entry? {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        val pathToken = tokens.lastOrNull { it.contains('/') || it.contains('\\') }
            ?: tokens.last().takeIf { looksLikeBareName(it, tokens) }
            ?: return null

        val normalized = pathToken.replace('\\', '/').trimEnd('/')
        if (normalized.isBlank()) return null

        val separator = normalized.lastIndexOf('/')
        val directory = if (separator > 0) normalized.substring(0, separator) else "."
        val name = if (separator >= 0) normalized.substring(separator + 1) else normalized
        if (name.isBlank()) return null

        val bytes = tokens.firstNotNullOfOrNull { parseSize(it) } ?: 0L
        return Entry(directory, name, bytes)
    }

    /**
     * A single-token line is an entry only when it carries a file-ish name (has an extension or a
     * size column next to it). Prose lines like "Total: 95 results" must not become entries.
     */
    private fun looksLikeBareName(token: String, tokens: List<String>): Boolean =
        (tokens.size == 1 && token.contains('.') && !token.endsWith(".")) ||
            tokens.any { parseSize(it) != null }

    private fun parseSize(token: String): Long? {
        val match = SIZE_REGEX.matchEntire(token) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "B" -> 1L
            "KB" -> 1024L
            "MB" -> 1024L * 1024
            else -> 1024L * 1024 * 1024
        }
        return (value * multiplier).toLong()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)}GB"
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)}MB"
        bytes >= 1024 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }
}

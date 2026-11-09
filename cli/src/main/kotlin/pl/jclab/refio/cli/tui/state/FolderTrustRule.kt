package pl.jclab.refio.cli.tui.state

/**
 * Session rule "allow this tool in this folder", as a pattern for the core's approval service.
 *
 * The core matches a rule against the call's arguments written as `key=value` pairs joined by spaces, in
 * the order the model wrote them, anywhere in that text. The pattern is therefore anchored to the start
 * (`path=` must be the first argument, as the file tools' schemas list it) so a folder named inside other
 * values (`new_string=... path=src/...`) cannot satisfy it.
 *
 * Where the path value ends cannot be told from that text, because a path may contain spaces. So the rule
 * does not match at all when a `..` path segment appears anywhere in the arguments: `path=src/main/a ../../x`
 * would otherwise pass as "inside src/main/". Content that merely contains such a segment (a relative
 * import, for example) is asked about again; a call that does not fit the rule is never refused, only asked.
 */
object FolderTrustRule {

    private const val SPECIAL = "\\^$.|?*+()[]{}"
    /** Start of the arguments, with no `..` segment (after `/` or `\`, before one, a space or the end) anywhere in them. */
    private const val PREFIX = "^(?![\\s\\S]*[/\\\\]\\.\\.(?:[/\\\\ ]|$))path="
    /** Rest of the first path segment run: no `..`, up to the next space or the end. */
    private const val REST = "(?:(?!\\.\\.)[^ ])*(?: |$)"

    /**
     * Folder of [path] with a trailing slash, or null when it cannot become a rule: a path that climbs out with
     * `..`, has a space, or names a file directly in the project root (a root rule could not be kept to the root,
     * since the end of the path value is unknown).
     */
    fun folderOf(path: String): String? {
        var normalized = path.replace('\\', '/')
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        if (normalized.isBlank() || normalized.split('/').any { it == ".." } || normalized.contains(' ')) return null
        val slash = normalized.lastIndexOf('/')
        return if (slash <= 0) null else normalized.substring(0, slash + 1)
    }

    fun pattern(folder: String): String {
        require(folder.isNotEmpty()) { "A folder rule needs a folder" }
        return PREFIX + escape(folder) + REST
    }

    /** The folder a pattern made by [pattern] covers, for showing the rule; null for any other pattern. */
    fun describe(pattern: String): String? = when {
        pattern.startsWith(PREFIX) && pattern.endsWith(REST) -> unescape(pattern.removePrefix(PREFIX).removeSuffix(REST))
        else -> null
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            if (c in SPECIAL) append('\\')
            append(c)
        }
    }

    private fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) i++
            append(text[i])
            i++
        }
    }
}

package pl.jclab.refio.core.config

/**
 * Fallback YAML sanitization used when Kaml's strict parse fails.
 *
 * Handles two historical problems in user-authored `~/.refio/config.yaml` files:
 *  - invalid double-quoted escape sequences (e.g. `"C:\Users"` with an
 *    unescaped backslash) — we double the backslash so Kaml accepts it.
 *  - broken `''` list items that some legacy emitters produced without a
 *    preceding `-` marker — we re-indent them as proper list entries.
 *
 * Kept out of [ConfigYaml] so the data class doesn't drown in string-bashing.
 */
internal object YamlSanitizer {

    /**
     * Escape any raw `\` inside a double-quoted scalar whose following char
     * is not a valid YAML escape indicator. Leaves everything else untouched.
     */
    fun sanitizeInvalidDoubleQuotedEscapes(input: String): String {
        val out = StringBuilder(input.length + 32)
        var inDoubleQuoted = false
        var inSingleQuoted = false
        var inComment = false
        var i = 0

        while (i < input.length) {
            val ch = input[i]

            if (inComment) {
                out.append(ch)
                if (ch == '\n') {
                    inComment = false
                }
                i++
                continue
            }

            if (inSingleQuoted) {
                out.append(ch)
                if (ch == '\'') {
                    if (i + 1 < input.length && input[i + 1] == '\'') {
                        out.append('\'')
                        i += 2
                        continue
                    }
                    inSingleQuoted = false
                }
                i++
                continue
            }

            if (inDoubleQuoted) {
                if (ch == '"') {
                    inDoubleQuoted = false
                    out.append(ch)
                    i++
                    continue
                }

                if (ch == '\\') {
                    val next = input.getOrNull(i + 1)
                    if (next == null || !isValidYamlEscape(input, i + 1)) {
                        out.append("\\\\")
                        i++
                        continue
                    }
                }

                out.append(ch)
                i++
                continue
            }

            when (ch) {
                '#' -> inComment = true
                '"' -> inDoubleQuoted = true
                '\'' -> inSingleQuoted = true
            }
            out.append(ch)
            i++
        }

        return out.toString()
    }

    /**
     * Repair lonely `''` lines (broken "empty scalar in a list" output) by
     * making them explicit `- ''` entries at the correct indent. When the
     * input has no such lines, returns [input] unchanged to preserve identity.
     */
    fun sanitizeBrokenStandaloneEmptyQuotedLines(input: String): String {
        val lines = input.split('\n')
        val out = ArrayList<String>(lines.size)

        var previousSignificant: String? = null
        var lastListIndent: Int? = null
        var changed = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed == "''") {
                val replacement = when {
                    lastListIndent != null -> "${" ".repeat(lastListIndent)}- ''"
                    previousSignificant?.trimEnd()?.endsWith(":") == true -> {
                        val baseIndent = previousSignificant.takeWhile { it == ' ' }.length
                        "${" ".repeat(baseIndent + 2)}- ''"
                    }
                    else -> "- ''"
                }
                out.add(replacement)
                previousSignificant = replacement
                lastListIndent = replacement.takeWhile { it == ' ' }.length
                changed = true
                continue
            }

            out.add(line)
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                previousSignificant = line
                if (trimmed.startsWith("- ")) {
                    lastListIndent = line.takeWhile { it == ' ' }.length
                } else if (trimmed.endsWith(":")) {
                    lastListIndent = null
                }
            }
        }

        if (!changed) return input
        return out.joinToString("\n")
    }

    private fun isValidYamlEscape(input: String, escapeCharIndex: Int): Boolean {
        val escapeChar = input.getOrNull(escapeCharIndex) ?: return false
        return when (escapeChar) {
            '0', 'a', 'b', 't', 'n', 'v', 'f', 'r', 'e', ' ', '"', '/', '\\', 'N', '_', 'L', 'P' -> true
            'x' -> hasHexDigits(input, escapeCharIndex + 1, 2)
            'u' -> hasHexDigits(input, escapeCharIndex + 1, 4)
            'U' -> hasHexDigits(input, escapeCharIndex + 1, 8)
            else -> false
        }
    }

    private fun hasHexDigits(input: String, start: Int, length: Int): Boolean {
        if (start + length > input.length) return false
        for (idx in start until start + length) {
            if (!input[idx].isDigit() && input[idx].lowercaseChar() !in 'a'..'f') return false
        }
        return true
    }
}

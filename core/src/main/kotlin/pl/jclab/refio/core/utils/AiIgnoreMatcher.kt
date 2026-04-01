package pl.jclab.refio.core.utils

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class AiIgnoreMatcher private constructor(
    private val rules: List<Rule>
) {

    fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean {
        if (rules.isEmpty()) return false
        val normalized = normalizePath(relativePath)
        var ignored = false
        rules.forEach { rule ->
            if (rule.matches(normalized, isDirectory)) {
                ignored = !rule.negated
            }
        }
        return ignored
    }

    companion object {
        const val FILE_NAME = ".aiignore"

        fun load(projectRoot: Path): AiIgnoreMatcher? {
            val file = projectRoot.resolve(FILE_NAME)
            if (!file.exists()) return null
            return fromLines(Files.readAllLines(file))
        }

        fun fromPatterns(patterns: Iterable<String>): AiIgnoreMatcher {
            val rules = patterns.mapNotNull { parseLine(it) }
            return AiIgnoreMatcher(rules)
        }

        fun fromLines(lines: List<String>): AiIgnoreMatcher {
            val rules = lines.mapNotNull { parseLine(it) }
            return AiIgnoreMatcher(rules)
        }

        private fun parseLine(rawLine: String): Rule? {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

            var line = trimmed
            var negated = false
            if (line.startsWith("!")) {
                negated = true
                line = line.drop(1)
            }

            if (line.isBlank()) return null

            val dirOnly = line.endsWith("/")
            if (dirOnly) {
                line = line.dropLast(1)
            }

            val anchored = line.startsWith("/")
            if (anchored) {
                line = line.drop(1)
            }

            if (line.isBlank()) return null

            val hasSlash = line.contains("/")
            val matchAnyLevel = !anchored && !hasSlash
            val regex = compileRegex(line, matchAnyLevel, dirOnly)

            return Rule(
                pattern = line,
                negated = negated,
                dirOnly = dirOnly,
                regex = regex
            )
        }

        private fun compileRegex(
            pattern: String,
            matchAnyLevel: Boolean,
            dirOnly: Boolean
        ): Regex {
            val normalized = normalizePath(pattern)
            val sb = StringBuilder()

            if (matchAnyLevel) {
                sb.append("(^|.*/)")
            } else {
                sb.append("^")
            }

            sb.append(globToRegex(normalized))

            if (dirOnly || matchAnyLevel) {
                sb.append("(/.*)?")
            }

            sb.append("$")

            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }

        private fun globToRegex(pattern: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when (c) {
                    '*' -> {
                        val isDouble = i + 1 < pattern.length && pattern[i + 1] == '*'
                        if (isDouble) {
                            sb.append(".*")
                            i++
                        } else {
                            sb.append("[^/]*")
                        }
                    }
                    '?' -> sb.append("[^/]")
                    '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                        sb.append("\\").append(c)
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }

        private fun normalizePath(path: String): String = path.replace('\\', '/')
    }

    private data class Rule(
        val pattern: String,
        val negated: Boolean,
        val dirOnly: Boolean,
        val regex: Regex
    ) {
        fun matches(path: String, isDirectory: Boolean): Boolean {
            if (!regex.matches(path)) return false
            if (dirOnly && !isDirectory && !path.contains("/")) return false
            return true
        }
    }
}

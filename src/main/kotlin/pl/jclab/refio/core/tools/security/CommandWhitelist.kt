package pl.jclab.refio.core.tools.security

import pl.jclab.refio.core.logging.dualLogger
import kotlin.text.RegexOption.IGNORE_CASE

private val logger = dualLogger("CommandWhitelist")

data class ParsedCommand(
    val program: String,
    val subcommand: String?,
    val args: List<String>,
    val rawCommand: String
)

class CommandWhitelist(
    private val config: CommandWhitelistConfig,
    private val denylist: CommandDenylist
) {
    data class ValidationResult(
        val allowed: Boolean,
        val reason: String? = null,
        val requiresConfirmation: Boolean = false
    )

    private val globalBlockedRegex: List<Regex> = config.globalBlockedPatterns.mapNotNull { pattern ->
        runCatching { Regex(pattern, IGNORE_CASE) }
            .onFailure { logger.warn { "Invalid terminal whitelist regex pattern: '$pattern'" } }
            .getOrNull()
    }

    fun validate(rawCommand: String): ValidationResult {
        if (rawCommand.isBlank()) {
            return ValidationResult(allowed = false, reason = "Empty command")
        }

        if (!config.enabled) {
            return validateWithDenylistFallback(rawCommand, "Whitelist disabled")
        }

        val blockedByGlobalPattern = globalBlockedRegex.firstOrNull { it.containsMatchIn(rawCommand) }
        if (blockedByGlobalPattern != null) {
            return ValidationResult(
                allowed = false,
                reason = "Matches blocked pattern: ${blockedByGlobalPattern.pattern}"
            )
        }

        val chain = runCatching { parseCommandChain(rawCommand) }.getOrElse { error ->
            return ValidationResult(allowed = false, reason = "Cannot parse command: ${error.message}")
        }

        if (chain.isEmpty()) {
            return ValidationResult(allowed = false, reason = "No executable command found")
        }

        var requiresConfirmation = false
        for (parsed in chain) {
            val result = validateSingleCommand(parsed)
            if (!result.allowed) {
                return result
            }
            requiresConfirmation = requiresConfirmation || result.requiresConfirmation
        }

        return ValidationResult(allowed = true, requiresConfirmation = requiresConfirmation)
    }

    fun parseCommand(rawCommand: String): ParsedCommand {
        val tokens = tokenize(rawCommand)
        if (tokens.isEmpty()) {
            throw IllegalArgumentException("Command is empty")
        }

        val normalizedProgram = normalizeProgram(tokens.first())
        if (normalizedProgram.isBlank()) {
            throw IllegalArgumentException("Program name is empty")
        }

        val args = tokens.drop(1)
        return ParsedCommand(
            program = normalizedProgram,
            subcommand = args.firstOrNull(),
            args = args,
            rawCommand = rawCommand.trim()
        )
    }

    fun parseCommandChain(raw: String): List<ParsedCommand> {
        val segments = splitByControlOperators(raw)
        return segments.map { parseCommand(it) }
    }

    private fun validateSingleCommand(parsed: ParsedCommand): ValidationResult {
        val allowedCommand = findAllowedCommand(parsed.program)
        if (allowedCommand == null) {
            return when (config.mode) {
                WhitelistMode.WHITELIST_ONLY -> ValidationResult(
                    allowed = false,
                    reason = "Program '${parsed.program}' is not on whitelist"
                )

                WhitelistMode.WHITELIST_PLUS_DENY -> validateWithDenylistFallback(
                    parsed.rawCommand,
                    "Program '${parsed.program}' is not on whitelist"
                )
            }
        }

        if (parsed.args.size > allowedCommand.maxArgs) {
            return ValidationResult(
                allowed = false,
                reason = "Too many arguments for '${allowedCommand.program}' (${parsed.args.size} > ${allowedCommand.maxArgs})"
            )
        }

        val blockedFlag = allowedCommand.blockedFlags.firstOrNull { blocked ->
            parsed.args.any { it.equals(blocked, ignoreCase = true) }
        }
        if (blockedFlag != null) {
            return ValidationResult(
                allowed = false,
                reason = "Blocked flag '$blockedFlag' for '${allowedCommand.program}'"
            )
        }

        val blockedSubcommand = allowedCommand.blockedSubcommands.firstOrNull { blocked ->
            matchesSubcommandPhrase(parsed.args, blocked)
        }
        if (blockedSubcommand != null) {
            return ValidationResult(
                allowed = false,
                reason = "Blocked subcommand '$blockedSubcommand' for '${allowedCommand.program}'"
            )
        }

        val blockedArgPattern = allowedCommand.blockedArgPatterns.firstOrNull { pattern ->
            val regex = runCatching { Regex(pattern, IGNORE_CASE) }.getOrNull()
            regex?.let { parsed.args.any(it::matches) } == true
        }
        if (blockedArgPattern != null) {
            return ValidationResult(
                allowed = false,
                reason = "Blocked argument pattern '$blockedArgPattern' for '${allowedCommand.program}'"
            )
        }

        if (allowedCommand.allowedSubcommands.isNotEmpty()) {
            val matchesAllowed = allowedCommand.allowedSubcommands.any { allowed ->
                matchesSubcommandPhrase(parsed.args, allowed)
            }
            if (!matchesAllowed) {
                return ValidationResult(
                    allowed = false,
                    reason = "Subcommand not allowed for '${allowedCommand.program}'"
                )
            }
        }

        return ValidationResult(
            allowed = true,
            requiresConfirmation = allowedCommand.requireConfirmation
        )
    }

    private fun validateWithDenylistFallback(rawCommand: String, prefixReason: String): ValidationResult {
        return if (denylist.isBlocked(rawCommand)) {
            ValidationResult(
                allowed = false,
                reason = "$prefixReason and denylist blocked command"
            )
        } else {
            ValidationResult(allowed = true)
        }
    }

    private fun findAllowedCommand(program: String): AllowedCommand? {
        return config.allowedCommands.firstOrNull { allowed ->
            val names = listOf(allowed.program) + allowed.aliases
            names.any { normalizeProgram(it) == program }
        }
    }

    private fun matchesSubcommandPhrase(args: List<String>, phrase: String): Boolean {
        val phraseTokens = tokenize(phrase)
        if (args.isEmpty() || phraseTokens.isEmpty() || args.size < phraseTokens.size) {
            return false
        }

        for (start in 0..(args.size - phraseTokens.size)) {
            val matchesAtStart = phraseTokens.indices.all { offset ->
                args[start + offset].equals(phraseTokens[offset], ignoreCase = true)
            }
            if (matchesAtStart) {
                return true
            }
        }
        return false
    }

    private fun splitByControlOperators(raw: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()

        var inSingleQuote = false
        var inDoubleQuote = false
        var inBackticks = false

        var i = 0
        while (i < raw.length) {
            val char = raw[i]

            when (char) {
                '\'' -> if (!inDoubleQuote && !inBackticks) inSingleQuote = !inSingleQuote
                '"' -> if (!inSingleQuote && !inBackticks) inDoubleQuote = !inDoubleQuote
                '`' -> if (!inSingleQuote && !inDoubleQuote) inBackticks = !inBackticks
            }

            if (!inSingleQuote && !inDoubleQuote && !inBackticks) {
                val twoChars = raw.substring(i, minOf(i + 2, raw.length))
                if (twoChars == "&&" || twoChars == "||") {
                    addSegment(segments, current.toString())
                    current.clear()
                    i += 2
                    continue
                }
                if (char == '|' || char == ';') {
                    addSegment(segments, current.toString())
                    current.clear()
                    i++
                    continue
                }
            }

            current.append(char)
            i++
        }

        addSegment(segments, current.toString())
        return segments
    }

    private fun tokenize(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        var inSingleQuote = false
        var inDoubleQuote = false

        for (char in raw) {
            when (char) {
                '\'' -> if (!inDoubleQuote) {
                    inSingleQuote = !inSingleQuote
                    continue
                }

                '"' -> if (!inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote
                    continue
                }
            }

            if (!inSingleQuote && !inDoubleQuote && char.isWhitespace()) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
                continue
            }

            current.append(char)
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens
    }

    private fun addSegment(segments: MutableList<String>, segment: String) {
        val trimmed = segment.trim()
        if (trimmed.isNotEmpty()) {
            segments.add(trimmed)
        }
    }

    private fun normalizeProgram(raw: String): String {
        val unquoted = raw.trim().trim('"', '\'')
        val slashesNormalized = unquoted.replace('\\', '/')
        val fileName = slashesNormalized.substringAfterLast('/').lowercase()
        return when {
            fileName.endsWith(".exe") -> fileName.removeSuffix(".exe")
            fileName.endsWith(".bat") -> fileName.removeSuffix(".bat")
            fileName.endsWith(".cmd") -> fileName.removeSuffix(".cmd")
            else -> fileName
        }
    }
}

package pl.jclab.refio.core.tools.security

/**
 * Breaks a raw command line into the individual commands a shell would actually run.
 *
 * A security rule anchored with `^` (every built-in BLOCK rule is) can only ever describe the
 * *first* program on the line, but the shell runs every segment of it. [commandUnits] therefore
 * returns every place a command can hide, so an anchored rule gets tested against each of them:
 *
 *  - the raw line itself (so nothing that matched before stops matching),
 *  - each segment between chaining operators (`;` `&&` `||` `|` `&`, newline) and redirections,
 *  - the body of each command substitution (`$(...)`, backticks),
 *  - the payload of a wrapper program - `env`, `timeout`, `xargs`, `sh -c`, `find -exec`, ... -
 *    whose arguments are themselves a command to run.
 *
 * ## Parsing model and its limits
 *
 * This is a *segmenter*, not a shell parser. It tracks quoting and backslash escapes well enough
 * that quoted text stays one argument (`git commit -m "drop the rm -rf helper"` is one command,
 * not two), and otherwise errs toward producing more units than a shell would: an extra unit can
 * only ever cause an extra rule match, and on a deny list a false deny is the safe direction.
 * The two places it deliberately reads *less* are the two where the shell itself treats the text
 * as data: quoted arguments, and here-document bodies (`cat > deploy.sh <<'EOF'` writes its lines
 * to a file, it does not run them). Deliberately not modelled at all: parameter expansion,
 * arithmetic expansion, aliases, functions and PowerShell syntax beyond the operators it shares
 * with POSIX shells. When a quote is left open the line is scanned a second time with quoting
 * disabled, so an unbalanced quote cannot be used to hide an operator.
 *
 * Expansion is bounded by [MAX_DEPTH] and [MAX_UNITS] so a pathological line cannot blow up the
 * rule check.
 */
object ShellCommandAnalyzer {

    /**
     * True when the command contains a shell operator that chains, pipes, substitutes or
     * redirects - the vectors that let a vetted leading program smuggle in a second, unvetted
     * command, or turn a read-only program into a file write (`>` / `>>`). Detection is a plain
     * substring scan, so an operator inside a quoted string counts too; for a gate that only ever
     * withholds an automatic approval, an extra prompt is the right side to err on. Bare `$VAR`
     * expansion is intentionally not matched - only `$(` opens a subshell.
     */
    fun hasControlOperators(command: String): Boolean {
        return CONTROL_OPERATORS.any { command.contains(it) }
    }

    /**
     * True when the command contains an operator that appends a SECOND command to the line. This is
     * [hasControlOperators] without the redirection operators: `>` and `<` change where a program's
     * bytes go, they do not introduce a program that was never vetted. Callers that only need to
     * know "is more than one command running here" should use this narrower signal, since
     * redirection is ordinary in day-to-day commands (`python3 app.py > out.txt`).
     */
    fun hasCommandChainingOperators(command: String): Boolean {
        return CHAINING_OPERATORS.any { command.contains(it) }
    }

    /**
     * Every command the line could end up running, starting with the raw line. Order is stable:
     * outer commands before the commands nested inside them.
     */
    fun commandUnits(command: String): List<String> {
        val units = LinkedHashSet<String>()
        units.add(command)
        expand(command, depth = 0, units = units)
        return units.toList()
    }

    private fun expand(command: String, depth: Int, units: MutableSet<String>) {
        if (depth > MAX_DEPTH || units.size >= MAX_UNITS) {
            return
        }

        val scan = scan(command, quoteAware = true)
        // An unbalanced quote would otherwise swallow every operator after it, so re-scan the
        // line as if nothing were quoted and keep both readings.
        val segments = if (scan.unterminatedQuote) {
            scan.segments + scan(command, quoteAware = false).segments
        } else {
            scan.segments
        }

        for (segment in segments) {
            if (units.size >= MAX_UNITS) {
                return
            }
            val normalized = normalize(segment)
            if (normalized.isEmpty()) {
                continue
            }
            units.add(normalized)
            for (nested in nestedCommands(normalized)) {
                expand(nested, depth + 1, units)
            }
        }

        for (substitution in scan.substitutions) {
            expand(substitution, depth + 1, units)
        }
    }

    private data class Scan(
        val segments: List<String>,
        val substitutions: List<String>,
        val unterminatedQuote: Boolean
    )

    /**
     * Splits one line into segments and pulls out command-substitution bodies. Redirection
     * operators start a new segment but stay attached to it, so a rule describing the redirect
     * target (the block-device overwrite rule) still has the `>` to anchor on.
     */
    private fun scan(command: String, quoteAware: Boolean): Scan {
        val segments = mutableListOf<String>()
        val substitutions = mutableListOf<String>()
        val current = StringBuilder()
        // Delimiters of here-documents opened on the line currently being scanned. Their bodies
        // start after the next newline and are data, not commands.
        val pendingHeredocs = mutableListOf<String>()
        var quote: Char? = null
        var i = 0

        fun flush() {
            if (current.isNotBlank()) {
                segments.add(current.toString())
            }
            current.setLength(0)
        }

        while (i < command.length) {
            val c = command[i]

            // Single quotes are literal: nothing inside them is an operator or an escape.
            if (quote == SINGLE_QUOTE) {
                if (c == SINGLE_QUOTE) {
                    quote = null
                }
                current.append(c)
                i++
                continue
            }

            if (c == '\\' && i + 1 < command.length) {
                current.append(c).append(command[i + 1])
                i += 2
                continue
            }

            if (quoteAware && quote == null && (c == SINGLE_QUOTE || c == DOUBLE_QUOTE)) {
                quote = c
                current.append(c)
                i++
                continue
            }

            if (quote == DOUBLE_QUOTE && c == DOUBLE_QUOTE) {
                quote = null
                current.append(c)
                i++
                continue
            }

            // Command substitution stays active inside double quotes, so it is handled before the
            // "everything else inside quotes is literal" branch below.
            if (c == '$' && i + 1 < command.length && command[i + 1] == '(') {
                val end = findClosingParen(command, i + 2)
                if (end < 0) {
                    current.append(c)
                    i++
                } else {
                    substitutions.add(command.substring(i + 2, end))
                    i = end + 1
                }
                continue
            }
            if (c == BACKTICK) {
                val end = command.indexOf(BACKTICK, i + 1)
                if (end < 0) {
                    current.append(c)
                    i++
                } else {
                    substitutions.add(command.substring(i + 1, end))
                    i = end + 1
                }
                continue
            }

            if (quote == DOUBLE_QUOTE) {
                current.append(c)
                i++
                continue
            }

            // The newline that closes a line carrying `<<DELIM` opens the here-document body:
            // everything up to the delimiter line is text handed to the command, so it is skipped
            // rather than segmented. Writing a script with `cat > deploy.sh <<'EOF'` must not be
            // read as running the lines being written.
            if ((c == '\n' || c == '\r') && pendingHeredocs.isNotEmpty()) {
                flush()
                i = skipHeredocBodies(command, i, pendingHeredocs)
                pendingHeredocs.clear()
                continue
            }

            if (c in CHAINING_CHARS) {
                flush()
                while (i < command.length && command[i] in CHAINING_CHARS) {
                    i++
                }
                continue
            }

            // `<<DELIM` / `<<-DELIM` open a here-document; `<<<` is a here-string, whose payload is
            // an ordinary argument, so it falls through to plain redirection handling below.
            if (c == '<' && i + 1 < command.length && command[i + 1] == '<' && command.getOrNull(i + 2) != '<') {
                var afterOperator = i + 2
                if (command.getOrNull(afterOperator) == '-') {
                    afterOperator++
                }
                while (afterOperator < command.length && (command[afterOperator] == ' ' || command[afterOperator] == '\t')) {
                    afterOperator++
                }
                val delimiter = readHeredocDelimiter(command, afterOperator)
                if (delimiter != null) {
                    pendingHeredocs.add(delimiter.value)
                    flush()
                    current.append(command, i, delimiter.end)
                    i = delimiter.end
                    continue
                }
            }

            if (c == '>' || c == '<') {
                flush()
                while (i < command.length && (command[i] == '>' || command[i] == '<')) {
                    current.append(command[i])
                    i++
                }
                continue
            }

            current.append(c)
            i++
        }
        flush()

        return Scan(segments, substitutions, quote != null)
    }

    private data class HeredocDelimiter(val value: String, val end: Int)

    /**
     * Reads the delimiter word after `<<`. Quoting only decides whether the shell expands the body
     * (`<<'EOF'` vs `<<EOF`), and since the body is skipped either way, both forms are read the
     * same. Returns null when no delimiter word follows, which leaves the operator to be handled
     * as a plain redirection.
     */
    private fun readHeredocDelimiter(command: String, start: Int): HeredocDelimiter? {
        if (start >= command.length) {
            return null
        }
        val opening = command[start]
        if (opening == SINGLE_QUOTE || opening == DOUBLE_QUOTE) {
            val end = command.indexOf(opening, start + 1)
            return if (end < 0) null else HeredocDelimiter(command.substring(start + 1, end), end + 1)
        }

        val word = StringBuilder()
        var i = start
        while (i < command.length) {
            val c = command[i]
            if (c.isWhitespace() || c in HEREDOC_DELIMITER_STOP) {
                break
            }
            // `\EOF` is another way of quoting the delimiter.
            if (c != '\\') {
                word.append(c)
            }
            i++
        }
        return if (word.isEmpty()) null else HeredocDelimiter(word.toString(), i)
    }

    /**
     * Skips the bodies of the here-documents opened on the line ending at [newlineAt], one per
     * delimiter, and returns the offset where ordinary scanning resumes. A body that is never
     * terminated runs to the end of the input, exactly as the shell would read it.
     *
     * The terminator is matched on the trimmed line, which also accepts the indented terminator
     * `<<-` allows. Being lenient here resumes command scanning earlier rather than later, so it
     * cannot be used to hide a command inside a body.
     */
    private fun skipHeredocBodies(command: String, newlineAt: Int, delimiters: List<String>): Int {
        var i = newlineAt
        if (i < command.length && command[i] == '\r') {
            i++
        }
        if (i < command.length && command[i] == '\n') {
            i++
        }

        for (delimiter in delimiters) {
            var terminated = false
            while (i < command.length) {
                val lineEnd = command.indexOf('\n', i).takeIf { it >= 0 } ?: command.length
                val line = command.substring(i, lineEnd).trim()
                i = if (lineEnd < command.length) lineEnd + 1 else command.length
                if (line == delimiter) {
                    terminated = true
                    break
                }
            }
            if (!terminated) {
                return command.length
            }
        }
        return i
    }

    private fun findClosingParen(command: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < command.length) {
            when (command[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return i
                    }
                }
            }
            i++
        }
        return -1
    }

    /**
     * Strips what stands between the start of a segment and the program it runs: grouping
     * characters, leading `VAR=value` assignments and control-flow keywords (`do rm -rf $d` is a
     * `rm` invocation).
     */
    private fun normalize(segment: String): String {
        var text = segment.trim()

        while (text.isNotEmpty() && text[0] in GROUPING_CHARS) {
            text = text.substring(1).trimStart()
        }

        while (true) {
            val assignment = LEADING_ASSIGNMENT.find(text) ?: break
            text = text.substring(assignment.value.length).trimStart()
        }

        while (true) {
            val space = text.indexOf(' ')
            if (space <= 0 || text.substring(0, space).lowercase() !in LEADING_KEYWORDS) {
                break
            }
            text = text.substring(space + 1).trimStart()
        }

        return text
    }

    /**
     * Commands carried as arguments of the segment's leading program. Empty for an ordinary
     * program - only the listed wrappers are unpacked, otherwise `echo rm -rf /` would look like
     * a delete.
     */
    private fun nestedCommands(segment: String): List<String> {
        val tokens = tokenize(segment)
        if (tokens.isEmpty()) {
            return emptyList()
        }

        val program = programName(tokens[0])

        if (program in SHELL_PROGRAMS) {
            val flag = tokens.indexOfFirst { it.lowercase() in SHELL_COMMAND_FLAGS }
            if (flag < 0 || flag + 1 >= tokens.size) {
                return emptyList()
            }
            return listOf(tokens.drop(flag + 1).joinToString(" ") { unquote(it) })
        }

        if (program == FIND_PROGRAM) {
            return findActionCommands(tokens)
        }

        if (program !in WRAPPER_PROGRAMS) {
            return emptyList()
        }

        // Where the payload starts depends on the wrapper's own options (`timeout -s KILL 5 <cmd>`,
        // `nice -n 10 <cmd>`), which are not worth modelling one by one: take every suffix and let
        // the rules decide. Tokens keep their quotes here, so a quoted argument (`xargs grep "rm
        // -rf"`) cannot masquerade as a command.
        val lastStart = minOf(tokens.size, 1 + MAX_WRAPPER_SUFFIXES)
        return (1 until lastStart).map { start -> tokens.drop(start).joinToString(" ") }
    }

    /** The command `find` runs for each hit: everything between `-exec`-style flags and `;` / `+`. */
    private fun findActionCommands(tokens: List<String>): List<String> {
        val commands = mutableListOf<String>()
        var i = 1
        while (i < tokens.size) {
            if (tokens[i].lowercase() !in FIND_ACTION_FLAGS) {
                i++
                continue
            }
            val body = mutableListOf<String>()
            var j = i + 1
            while (j < tokens.size && unquote(tokens[j]).trimStart('\\') !in FIND_ACTION_TERMINATORS) {
                body.add(unquote(tokens[j]))
                j++
            }
            if (body.isNotEmpty()) {
                commands.add(body.joinToString(" "))
            }
            i = j + 1
        }
        return commands
    }

    /** Whitespace split that keeps quoted runs together. */
    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0

        while (i < command.length) {
            val c = command[i]
            when {
                quote == null && c == '\\' && i + 1 < command.length -> {
                    current.append(c).append(command[i + 1])
                    i += 2
                }
                quote == null && (c == SINGLE_QUOTE || c == DOUBLE_QUOTE) -> {
                    quote = c
                    current.append(c)
                    i++
                }
                quote != null && c == quote -> {
                    quote = null
                    current.append(c)
                    i++
                }
                quote == null && c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                    }
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    private fun unquote(token: String): String {
        if (token.length >= 2 && token[0] == token[token.length - 1] && (token[0] == SINGLE_QUOTE || token[0] == DOUBLE_QUOTE)) {
            return token.substring(1, token.length - 1)
        }
        return token
    }

    /** `C:\tools\bash.exe` and `/bin/bash` are both `bash`. */
    private fun programName(token: String): String {
        val bare = unquote(token)
        val name = bare.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name.removeSuffix(".exe")
    }

    private const val SINGLE_QUOTE = '\''
    private const val DOUBLE_QUOTE = '"'
    private const val BACKTICK = '`'
    private const val FIND_PROGRAM = "find"

    private const val MAX_DEPTH = 4
    private const val MAX_UNITS = 64
    private const val MAX_WRAPPER_SUFFIXES = 6

    private val CHAINING_OPERATORS: List<String> = listOf(
        ";",    // command separator
        "&",    // background / && and-chain
        "|",    // pipe / || or-chain
        "`",    // backtick command substitution
        "$(",   // command substitution
        "\n",   // newline-injected second command
        "\r"
    )

    private val CONTROL_OPERATORS: List<String> = CHAINING_OPERATORS + listOf(
        ">",    // output redirection - lets an allowed read-only program overwrite files (also catches >>, 2>, &>)
        "<"     // input redirection (also catches <( process substitution and <<< here-string)
    )

    private val CHAINING_CHARS: Set<Char> = setOf(';', '&', '|', '\n', '\r')

    private val HEREDOC_DELIMITER_STOP: Set<Char> = setOf(';', '&', '|', '<', '>', '(', ')', '`')

    private val GROUPING_CHARS: Set<Char> = setOf('(', ')', '{', '}', '!')

    private val LEADING_ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+")

    private val LEADING_KEYWORDS: Set<String> = setOf(
        "if", "then", "elif", "else", "while", "until", "do", "done", "fi", "case", "esac"
    )

    /** Programs that run another program handed to them as arguments. */
    private val WRAPPER_PROGRAMS: Set<String> = setOf(
        "env", "eval", "nohup", "setsid", "stdbuf", "nice", "ionice", "time", "timeout",
        "xargs", "sudo", "doas", "runuser", "command", "builtin", "exec", "watch", "chroot"
    )

    private val SHELL_PROGRAMS: Set<String> = setOf(
        "sh", "bash", "zsh", "dash", "ksh", "ash", "busybox", "powershell", "pwsh", "cmd"
    )

    private val SHELL_COMMAND_FLAGS: Set<String> = setOf("-c", "/c", "-command", "-encodedcommand")

    private val FIND_ACTION_FLAGS: Set<String> = setOf("-exec", "-execdir", "-ok", "-okdir")

    private val FIND_ACTION_TERMINATORS: Set<String> = setOf(";", "+")
}

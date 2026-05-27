package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.FileLimits
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

private val logger = dualLogger("CodeIntelligenceTool")

/**
 * Code intelligence without requiring IDE or IntelliJ PSI.
 *
 * Actions:
 * - find_usages:    Find all uses of a symbol (method, class, variable) — internal scan
 * - find_definition: Find where a symbol is defined — uses ctags if available, else internal scan
 * - list_symbols:  List all symbols in a file or directory — requires ctags
 * - get_diagnostics: Run compiler/linter and return errors — uses language-specific CLI
 *
 * Symbol scanning is implemented in pure Kotlin (Files.walk + Regex) so it works on
 * Windows without external `grep` — previous versions shelled out to `grep` which
 * failed on Windows with `CreateProcess error=2`.
 */
class CodeIntelligenceTool(
    private val sandbox: PathSandbox,
    private val limits: FileLimits = FileLimits.DEFAULT
) : Tool {
    override val name = "code_intelligence"
    override val description = "Analyze code structure: find symbol usages, definitions, list symbols, get compiler diagnostics. " +
        "Works without IntelliJ PSI — pure-Kotlin symbol scan (no external grep), optional ctags for list_symbols. " +
        "Actions: find_usages, find_definition, list_symbols, get_diagnostics."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint =
        "Code navigation: find_usages, find_definition, list_symbols (ctags), get_diagnostics."

    override fun validateParams(params: Map<String, Any>) {
        val action = params["action"] as? String
        if (action !in VALID_ACTIONS)
            throw IllegalArgumentException("'action' must be one of: ${VALID_ACTIONS.joinToString()}")
        if (action != "get_diagnostics") {
            val symbol = params["symbol"] as? String
            if (symbol.isNullOrBlank() && action != "list_symbols")
                throw IllegalArgumentException("'symbol' is required for action: $action")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val action = params["action"] as? String ?: return@withContext ToolResult.error("Missing 'action'")
        val symbol = params["symbol"] as? String
        val path = params["path"] as? String ?: "."
        val language = params["language"] as? String

        when (action) {
            "find_usages" -> findUsages(symbol!!, path, startTime)
            "find_definition" -> findDefinition(symbol!!, path, language, startTime)
            "list_symbols" -> listSymbols(path, language, startTime)
            "get_diagnostics" -> getDiagnostics(path, language, startTime)
            else -> ToolResult.error("Unknown action: $action")
        }
    }

    private fun findUsages(symbol: String, path: String, startTime: Long): ToolResult {
        val root = sandbox.resolve(path.replace('\\', '/')).toAbsolutePath()
        if (!Files.exists(root)) return ToolResult.error("Path not found: $path")

        return try {
            val pattern = Regex("\\b${Regex.escape(symbol)}\\b")
            val matches = scanForRegex(root, pattern, maxResults = 100)

            if (matches.isEmpty()) {
                return ToolResult(
                    success = true,
                    output = "No usages of '$symbol' found in $path",
                    durationMs = elapsed(startTime)
                )
            }

            val sandboxBase = sandbox.resolve(".").toAbsolutePath()
            val formatted = buildString {
                appendLine("Usages of '$symbol' ($path):")
                appendLine("Found: ${matches.size} occurrences\n")
                matches.forEach { m ->
                    val rel = relativize(sandboxBase, m.file)
                    appendLine("  $rel:${m.lineNumber}  ${m.line}")
                }
            }
            ToolResult(success = true, output = formatted, durationMs = elapsed(startTime))
        } catch (e: Exception) {
            logger.error(e) { "find_usages failed for symbol='$symbol'" }
            ToolResult.error("find_usages failed: ${e.message}")
        }
    }

    private fun findDefinition(symbol: String, path: String, language: String?, startTime: Long): ToolResult {
        val root = sandbox.resolve(path.replace('\\', '/')).toAbsolutePath()
        if (!Files.exists(root)) return ToolResult.error("Path not found: $path")

        if (isCtagsAvailable()) {
            val ctagsResult = runCtagsForSymbol(symbol, root.toFile())
            if (ctagsResult.isNotEmpty()) {
                return ToolResult(
                    success = true,
                    output = "Definition of '$symbol':\n$ctagsResult",
                    durationMs = elapsed(startTime)
                )
            }
        }

        // Pure-Kotlin definition scan. Looks for common declaration shapes across
        // Kotlin/Java/Python/TypeScript/JavaScript. Single Files.walk pass tested
        // against multiple regexes in parallel — cheaper than walking N times.
        val escapedSymbol = Regex.escape(symbol)
        val defPatterns = listOf(
            Regex("\\bfun\\s+$escapedSymbol\\b"),
            Regex("\\bclass\\s+$escapedSymbol\\b"),
            Regex("\\binterface\\s+$escapedSymbol\\b"),
            Regex("\\bobject\\s+$escapedSymbol\\b"),
            Regex("\\bdef\\s+$escapedSymbol\\b"),
            Regex("\\bfunction\\s+$escapedSymbol\\b"),
            Regex("\\b(?:val|var)\\s+$escapedSymbol\\b"),
            Regex("\\b(?:public|private|protected|internal|static)\\b.*\\b$escapedSymbol\\s*\\(")
        )

        val matches = try {
            scanForAnyRegex(root, defPatterns, maxResults = 50)
        } catch (e: Exception) {
            logger.error(e) { "find_definition scan failed for symbol='$symbol'" }
            return ToolResult.error("find_definition failed: ${e.message}")
        }

        if (matches.isEmpty()) {
            return ToolResult(
                success = true,
                output = "Definition of '$symbol' not found in $path. " +
                    "Install universal-ctags for better results.",
                durationMs = elapsed(startTime)
            )
        }

        val sandboxBase = sandbox.resolve(".").toAbsolutePath()
        val output = buildString {
            appendLine("Definition of '$symbol':")
            matches.distinctBy { it.file to it.lineNumber }.take(20).forEach { m ->
                val rel = relativize(sandboxBase, m.file)
                appendLine("  $rel:${m.lineNumber}  ${m.line}")
            }
        }
        return ToolResult(success = true, output = output.trimEnd(), durationMs = elapsed(startTime))
    }

    private fun listSymbols(path: String, language: String?, startTime: Long): ToolResult {
        val root = sandbox.resolve(path.replace('\\', '/')).toAbsolutePath()

        if (!isCtagsAvailable()) {
            return ToolResult(
                success = false,
                output = "list_symbols requires universal-ctags. Install with:\n" +
                    "  macOS: brew install universal-ctags\n" +
                    "  Ubuntu: apt-get install universal-ctags\n" +
                    "  Windows: winget install universal-ctags\n\n" +
                    "Alternative: use grep_search to search for class/fun/def patterns manually.",
                durationMs = elapsed(startTime)
            )
        }

        val langFilter = language?.let {
            listOf("--languages=${it.lowercase().replaceFirstChar { c -> c.uppercase() }}")
        } ?: emptyList()

        val cmd = buildList {
            add("ctags")
            add("-R")
            add("--fields=+n")
            add("--output-format=json")
            addAll(langFilter)
            add("--output=-")
            add(root.toString())
        }

        return try {
            val output = runCommand(cmd, root.toFile())
            val lines = output.lines().filter { it.isNotBlank() }.take(500)
            val formatted = buildString {
                appendLine("Symbols in $path (${lines.size} found):\n")
                lines.forEach { line ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val tag = pl.jclab.refio.core.utils.GsonInstance.gson
                            .fromJson(line, Map::class.java) as Map<String, Any>
                        val name = tag["name"] ?: return@forEach
                        val kind = tag["kind"] ?: ""
                        val tagPath = (tag["path"] as? String)
                            ?.removePrefix(root.toString())?.trimStart('/', '\\') ?: ""
                        val lineNo = tag["line"] ?: ""
                        appendLine("  [$kind] $name  ($tagPath:$lineNo)")
                    } catch (_: Exception) {
                        appendLine("  $line")
                    }
                }
            }
            ToolResult(success = true, output = formatted, durationMs = elapsed(startTime))
        } catch (e: Exception) {
            ToolResult.error("list_symbols failed: ${e.message}")
        }
    }

    private fun getDiagnostics(path: String, language: String?, startTime: Long): ToolResult {
        val root = sandbox.resolve(path.replace('\\', '/')).toAbsolutePath()
        val detectedLang = language ?: detectProjectLanguage(root)

        val cmd: List<String> = when (detectedLang?.lowercase()) {
            "kotlin" -> listOf("./gradlew", "--no-daemon", "compileKotlin", "--info")
            "java" -> listOf("./gradlew", "--no-daemon", "compileJava")
            "typescript" -> listOf("npx", "tsc", "--noEmit")
            "javascript" -> listOf("npx", "eslint", ".")
            "python" -> listOf("python", "-m", "py_compile")
            else -> return ToolResult.error(
                "Cannot determine language for diagnostics. " +
                    "Specify 'language' parameter: kotlin, java, typescript, javascript, python"
            )
        }

        return try {
            val output = runCommandWithTimeout(cmd, root.toFile(), timeoutSeconds = 120)
            val lines = output.lines()

            val errors = lines.filter { line ->
                val l = line.lowercase()
                l.contains("error:") || l.contains("warning:") ||
                    l.contains("exception") || l.contains("failed")
            }

            val summary = if (errors.isEmpty()) {
                "No errors found ($detectedLang diagnostics passed)"
            } else {
                "Diagnostics ($detectedLang) — ${errors.size} issues:\n${errors.take(50).joinToString("\n")}"
            }

            ToolResult(success = true, output = summary, durationMs = elapsed(startTime))
        } catch (e: Exception) {
            ToolResult.error("get_diagnostics failed: ${e.message}")
        }
    }

    private data class ScanMatch(val file: Path, val lineNumber: Int, val line: String)

    /** Single-regex scan over project tree honoring sandbox + FileLimits. */
    private fun scanForRegex(root: Path, regex: Regex, maxResults: Int): List<ScanMatch> =
        scanForAnyRegex(root, listOf(regex), maxResults)

    /**
     * Walk [root], for each accepted file check every line against any of [regexes].
     * Excludes match GrepSearchTool: build/.git/node_modules dirs via FileLimits.shouldExcludeDirectory,
     * binary/large files via shouldExcludeFile + maxFileSize. Stops early at maxResults.
     */
    private fun scanForAnyRegex(root: Path, regexes: List<Regex>, maxResults: Int): List<ScanMatch> {
        if (regexes.isEmpty()) return emptyList()
        val results = mutableListOf<ScanMatch>()
        val extensions = setOf("kt", "kts", "java", "ts", "tsx", "js", "jsx", "py")

        if (root.isRegularFile()) {
            scanFile(root, regexes, results, maxResults)
            return results
        }
        if (!Files.isDirectory(root)) return results

        Files.walk(root, limits.maxSearchDepth).use { stream ->
            val iterator = stream
                .filter { p ->
                    val rel = try { root.relativize(p) } catch (_: Exception) { p }
                    rel.none { seg -> limits.shouldExcludeDirectory(seg.toString()) }
                }
                .filter { it.isRegularFile() }
                .filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in extensions }
                .iterator()

            while (iterator.hasNext()) {
                val file = iterator.next()
                if (limits.shouldExcludeFile(file.fileName.toString())) continue
                scanFile(file, regexes, results, maxResults)
                if (results.size >= maxResults) break
            }
        }
        return results
    }

    private fun scanFile(
        file: Path,
        regexes: List<Regex>,
        results: MutableList<ScanMatch>,
        maxResults: Int
    ) {
        try {
            val size = Files.size(file)
            if (size > limits.maxFileSize) return
            val lines = Files.readString(file).lines()
            for ((idx, line) in lines.withIndex()) {
                if (regexes.any { it.containsMatchIn(line) }) {
                    results += ScanMatch(file, idx + 1, line.trim())
                    if (results.size >= maxResults) return
                }
            }
        } catch (e: Exception) {
            logger.debug { "Failed to read ${file.fileName}: ${e.message}" }
        }
    }

    private fun relativize(base: Path, file: Path): String =
        try {
            base.relativize(file).toString().replace('\\', '/')
        } catch (_: Exception) {
            file.toString()
        }

    private fun isCtagsAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("ctags", "--version"))
            p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runCtagsForSymbol(symbol: String, dir: java.io.File): String {
        val cmd = listOf(
            "ctags", "-R", "--fields=+n", "--output-format=json",
            "--output=-", dir.absolutePath
        )
        return try {
            val output = runCommand(cmd, dir)
            output.lines().filter { line ->
                line.contains("\"name\":\"$symbol\"") || line.contains("\"name\": \"$symbol\"")
            }.joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectProjectLanguage(root: java.nio.file.Path): String? {
        return when {
            Files.exists(root.resolve("build.gradle.kts")) || Files.exists(root.resolve("build.gradle")) -> "kotlin"
            Files.exists(root.resolve("package.json")) && Files.exists(root.resolve("tsconfig.json")) -> "typescript"
            Files.exists(root.resolve("package.json")) -> "javascript"
            Files.exists(root.resolve("requirements.txt")) || Files.exists(root.resolve("pyproject.toml")) -> "python"
            else -> null
        }
    }

    private fun runCommand(cmd: List<String>, workDir: java.io.File): String {
        val process = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return output
    }

    private fun runCommandWithTimeout(cmd: List<String>, workDir: java.io.File, timeoutSeconds: Long): String {
        val process = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("Command timed out after ${timeoutSeconds}s")
        }
        return output
    }

    private fun elapsed(start: Long) = (System.currentTimeMillis() - start).toInt()

    companion object {
        val VALID_ACTIONS = setOf("find_usages", "find_definition", "list_symbols", "get_diagnostics")
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "enum" to VALID_ACTIONS.toList(),
                "description" to "find_usages: where is symbol used | find_definition: where is symbol defined | list_symbols: all symbols in path | get_diagnostics: compiler errors"
            ),
            "symbol" to mapOf(
                "type" to "string",
                "description" to "Symbol name to search for (required for find_usages, find_definition)"
            ),
            "path" to mapOf(
                "type" to "string",
                "description" to "File or directory path relative to project root (default: '.')"
            ),
            "language" to mapOf(
                "type" to "string",
                "description" to "Language hint: kotlin, java, typescript, javascript, python. Auto-detected if omitted."
            )
        ),
        "required" to listOf("action")
    )
}

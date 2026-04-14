package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private val logger = dualLogger("CodeIntelligenceTool")

/**
 * Code intelligence without requiring IDE or IntelliJ PSI.
 *
 * Actions:
 * - find_usages:    Find all uses of a symbol (method, class, variable) — uses grep
 * - find_definition: Find where a symbol is defined — uses ctags or grep
 * - list_symbols:  List all symbols in a file or directory — uses ctags
 * - get_diagnostics: Run compiler/linter and return errors — uses language-specific CLI
 */
class CodeIntelligenceTool(
    private val sandbox: PathSandbox
) : Tool {
    override val name = "code_intelligence"
    override val description = "Analyze code structure: find symbol usages, definitions, list symbols, get compiler diagnostics. " +
        "Works without IntelliJ PSI — uses ctags and grep. " +
        "Actions: find_usages, find_definition, list_symbols, get_diagnostics."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING
    override val selectionHint =
        "Code navigation: find_usages, find_definition, list_symbols, get_diagnostics (ctags-based)."

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
        val root = sandbox.resolve(path).toAbsolutePath()

        val grepCmd = buildList {
            add("grep")
            add("-rn")
            add("--include=*.kt")
            add("--include=*.java")
            add("--include=*.ts")
            add("--include=*.py")
            add("--include=*.js")
            add("--color=never")
            add("\\b${Regex.escape(symbol)}\\b")
            add(root.toString())
        }

        return try {
            val output = runCommand(grepCmd, root.toFile())
            val lines = output.lines().filter { it.isNotBlank() }.take(100)

            if (lines.isEmpty()) {
                return ToolResult(
                    success = true,
                    output = "No usages of '$symbol' found in $path",
                    durationMs = elapsed(startTime)
                )
            }

            val formatted = buildString {
                appendLine("Usages of '$symbol' ($path):")
                appendLine("Found: ${lines.size} occurrences\n")
                lines.forEach { line ->
                    val parts = line.split(":", limit = 3)
                    if (parts.size >= 3) {
                        val file = parts[0].removePrefix(root.toString()).trimStart('/', '\\')
                        appendLine("  $file:${parts[1]}  ${parts[2].trim()}")
                    } else {
                        appendLine("  $line")
                    }
                }
            }
            ToolResult(success = true, output = formatted, durationMs = elapsed(startTime))
        } catch (e: Exception) {
            ToolResult.error("find_usages failed: ${e.message}")
        }
    }

    private fun findDefinition(symbol: String, path: String, language: String?, startTime: Long): ToolResult {
        val root = sandbox.resolve(path).toAbsolutePath()

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

        val defPatterns = listOf(
            "fun $symbol",
            "class $symbol",
            "interface $symbol",
            "object $symbol",
            "def $symbol",
            "function $symbol",
            "public.*$symbol\\(",
            "val $symbol",
            "var $symbol"
        )

        val allLines = mutableListOf<String>()
        defPatterns.forEach { pattern ->
            try {
                val cmd = listOf(
                    "grep", "-rn", "--color=never", "-E", pattern,
                    "--include=*.kt", "--include=*.java", "--include=*.ts",
                    "--include=*.py", "--include=*.js", root.toString()
                )
                val out = runCommand(cmd, root.toFile())
                allLines.addAll(out.lines().filter { it.isNotBlank() })
            } catch (_: Exception) {
            }
        }

        if (allLines.isEmpty()) {
            return ToolResult(
                success = true,
                output = "Definition of '$symbol' not found in $path. " +
                    "Install universal-ctags for better results.",
                durationMs = elapsed(startTime)
            )
        }

        val output = "Definition of '$symbol':\n" +
            allLines.distinct().take(20).joinToString("\n") { "  $it" }
        return ToolResult(success = true, output = output, durationMs = elapsed(startTime))
    }

    private fun listSymbols(path: String, language: String?, startTime: Long): ToolResult {
        val root = sandbox.resolve(path).toAbsolutePath()

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
        val root = sandbox.resolve(path).toAbsolutePath()
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

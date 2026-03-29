package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private val logger = dualLogger("RunCodeTool")

/**
 * Run Code Tool - executes inline code snippets in supported languages.
 *
 * Parameters:
 * - language: Programming language (python, javascript, kotlin)
 * - code: Source code to execute
 *
 * Workflow:
 * 1. Writes code to a temporary file in project directory
 * 2. Executes using the language runtime
 * 3. Returns stdout/stderr
 * 4. Cleans up temporary file
 *
 * Security:
 * - Runs within project sandbox
 * - Timeout enforced (120s default)
 * - Output size limited (200KB)
 */
class RunCodeTool(
    private val sandbox: PathSandbox,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val maxOutputSize: Int = DEFAULT_MAX_OUTPUT_SIZE
) : Tool {

    override val name = "run_code"
    override val description = "Execute inline code snippets in Python, JavaScript, or Kotlin script. " +
        "Ideal for data processing, CSV parsing, calculations, and transformations. " +
        "Code runs in the project directory with access to project files. " +
        "Use together with http_request's save_to_file parameter: first download large data to a file, " +
        "then use run_code to read and process it (e.g., filter CSV rows, parse JSON, aggregate data)."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.DATA_PRODUCING

    override fun validateParams(params: Map<String, Any>) {
        val language = params["language"] as? String
        if (language.isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'language' is required")
        }
        if (language.lowercase() !in SUPPORTED_LANGUAGES) {
            throw IllegalArgumentException(
                "Unsupported language: $language. Supported: ${SUPPORTED_LANGUAGES.keys.joinToString()}"
            )
        }
        val code = params["code"] as? String
        if (code.isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'code' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val language = (params["language"] as? String)?.lowercase()
            ?: return@withContext ToolResult.error("Missing required parameter: 'language'")
        val code = params["code"] as? String
            ?: return@withContext ToolResult.error("Missing required parameter: 'code'")

        val langConfig = SUPPORTED_LANGUAGES[language]
            ?: return@withContext ToolResult.error("Unsupported language: $language")

        var tempFile: java.nio.file.Path? = null

        try {
            val workingDir = sandbox.resolve(".").toAbsolutePath()
            val tempFileName = ".refio_run_${System.currentTimeMillis()}${langConfig.extension}"
            tempFile = workingDir.resolve(tempFileName)

            Files.writeString(tempFile, code)

            logger.info {
                "Executing $language code (${code.length} chars) in $workingDir"
            }

            val command = langConfig.buildCommand(tempFileName)
            val shellCommand = getShellCommand(command)

            val process = ProcessBuilder()
                .command(shellCommand)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start()

            val outputDeferred = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                outputDeferred.cancel()
                logger.warn { "Code execution timed out after ${timeoutSeconds}s" }
                return@withContext ToolResult.error(
                    "Code execution timed out after $timeoutSeconds seconds"
                )
            }

            val output = runCatching { outputDeferred.await() }.getOrDefault("")
            val exitCode = process.exitValue()
            val duration = (System.currentTimeMillis() - startTime).toInt()

            val truncatedOutput = if (output.length > maxOutputSize) {
                output.take(maxOutputSize) +
                    "\n\n... (output truncated to $maxOutputSize characters). " +
                    "TIP: For large data, save results to a file (e.g., open('output.json', 'w')) " +
                    "and print only a summary."
            } else if (output.length > LARGE_OUTPUT_WARNING_THRESHOLD) {
                output + "\n\n[NOTE: Output is ${output.length} chars. For multi-step processing, " +
                    "consider saving results to a file to prevent data loss from context compaction.]"
            } else {
                output
            }

            logger.info {
                "Code execution completed: language=$language, exitCode=$exitCode, " +
                    "output=${output.length} chars, ${duration}ms"
            }

            ToolResult(
                success = exitCode == 0,
                output = truncatedOutput,
                exitCode = exitCode,
                durationMs = duration,
                metadata = mapOf(
                    "language" to language,
                    "exit_code" to exitCode,
                    "code_length" to code.length,
                    "output_length" to output.length,
                    "truncated" to (output.length > maxOutputSize)
                )
            )

        } catch (e: Exception) {
            logger.error(e) { "Code execution failed" }
            ToolResult.error("Code execution failed: ${e.message}")
        } finally {
            tempFile?.let { file ->
                runCatching {
                    Files.deleteIfExists(file)
                    logger.debug { "Cleaned up temp file: ${file.fileName}" }
                }
            }
        }
    }

    private fun getShellCommand(command: String): List<String> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("windows") -> listOf(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", command
            )
            else -> listOf("/bin/sh", "-c", command)
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "language" to mapOf(
                    "type" to "string",
                    "description" to "Programming language: python, javascript, or kotlin",
                    "enum" to SUPPORTED_LANGUAGES.keys.toList()
                ),
                "code" to mapOf(
                    "type" to "string",
                    "description" to "Source code to execute. Has access to files in the project directory."
                )
            ),
            "required" to listOf("language", "code")
        )
    }

    data class LanguageConfig(
        val extension: String,
        val runtimeCommands: List<String>,
        val buildCommand: (fileName: String) -> String
    )

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val DEFAULT_MAX_OUTPUT_SIZE = 200 * 1024 // 200KB
        const val LARGE_OUTPUT_WARNING_THRESHOLD = 16_000 // 16KB - warn about saving to file

        val SUPPORTED_LANGUAGES = mapOf(
            "python" to LanguageConfig(
                extension = ".py",
                runtimeCommands = listOf("python3", "python"),
                buildCommand = { fileName ->
                    val os = System.getProperty("os.name").lowercase()
                    if (os.contains("windows")) "python $fileName" else "python3 $fileName"
                }
            ),
            "javascript" to LanguageConfig(
                extension = ".mjs",
                runtimeCommands = listOf("node"),
                buildCommand = { fileName -> "node $fileName" }
            ),
            "kotlin" to LanguageConfig(
                extension = ".kts",
                runtimeCommands = listOf("kotlin"),
                buildCommand = { fileName -> "kotlin $fileName" }
            )
        )
    }
}

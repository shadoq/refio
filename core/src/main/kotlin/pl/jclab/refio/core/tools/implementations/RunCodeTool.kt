package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    override val description = "Execute Python, JavaScript, or Kotlin script inline."
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

        // Optional timeout override from LLM (clamped to safe bounds)
        val effectiveTimeout = (params["timeout_seconds"] as? Number)?.toLong()
            ?.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
            ?: timeoutSeconds

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

            val completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                // Capture partial output instead of discarding it
                val partialOutput = withTimeoutOrNull(3000L) {
                    runCatching { outputDeferred.await() }.getOrDefault("")
                } ?: ""
                val duration = (System.currentTimeMillis() - startTime).toInt()

                logger.warn { "Code execution timed out after ${effectiveTimeout}s, partial output=${partialOutput.length} chars" }

                val truncatedPartial = if (partialOutput.length > maxOutputSize) {
                    partialOutput.take(maxOutputSize) + "\n\n... (output truncated)"
                } else {
                    partialOutput
                }

                val message = buildString {
                    append("Code execution timed out after $effectiveTimeout seconds.")
                    if (truncatedPartial.isNotBlank()) {
                        append("\n\nPartial output before timeout:\n")
                        append(truncatedPartial)
                    }
                }

                return@withContext ToolResult(
                    success = false,
                    output = message,
                    exitCode = -1,
                    durationMs = duration,
                    metadata = mapOf(
                        "language" to language,
                        "timed_out" to true,
                        "timeout_seconds" to effectiveTimeout,
                        "partial_output_length" to partialOutput.length
                    )
                )
            }

            val output = runCatching { outputDeferred.await() }.getOrDefault("")
            val exitCode = process.exitValue()
            val duration = (System.currentTimeMillis() - startTime).toInt()

            // For large outputs: persist the FULL stdout to a file inside the project
            // sandbox and feed only a head/tail snippet back to the LLM. This stops the
            // summarizer from collapsing structured outputs to useless prose and gives
            // the model an explicit way to retrieve more via read_file.
            var savedPath: String? = null
            var savedFullSize: Int? = null
            val displayOutput = if (output.length > maxOutputSize) {
                // Hard cap (200 KB) — save full output, return head only.
                val snapshot = saveOutputSnapshot(workingDir, output)
                savedPath = snapshot
                savedFullSize = output.length
                buildLargeOutputMessage(
                    head = output.take(LARGE_OUTPUT_HEAD_CHARS),
                    tail = output.takeLast(LARGE_OUTPUT_TAIL_CHARS),
                    fullSize = output.length,
                    path = snapshot,
                    capExceeded = true
                )
            } else if (output.length > LARGE_OUTPUT_AUTOSAVE_THRESHOLD) {
                // Soft threshold (8 KB) — save full output but ALSO return everything,
                // so the model can use it directly OR re-read later. The summarizer is
                // told not to crush this since the file path is already in the message.
                val snapshot = saveOutputSnapshot(workingDir, output)
                savedPath = snapshot
                savedFullSize = output.length
                output + buildString {
                    append("\n\n[NOTE: full output (${output.length} chars) also saved to ")
                    append("`$snapshot`. If this gets summarised later, re-read with ")
                    append("read_file(path=\"$snapshot\") to recover the raw data.]")
                }
            } else {
                output
            }

            logger.info {
                "Code execution completed: language=$language, exitCode=$exitCode, " +
                    "output=${output.length} chars, ${duration}ms" +
                    if (savedPath != null) ", saved=$savedPath" else ""
            }

            val metadata = mutableMapOf<String, Any>(
                "language" to language,
                "exit_code" to exitCode,
                "code_length" to code.length,
                "output_length" to output.length,
                "truncated" to (output.length > maxOutputSize)
            )
            if (savedPath != null) {
                metadata["output_saved_path"] = savedPath
                metadata["output_full_size"] = savedFullSize ?: output.length
            }

            ToolResult(
                success = exitCode == 0,
                output = displayOutput,
                exitCode = exitCode,
                durationMs = duration,
                metadata = metadata
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

    /**
     * Save full stdout to a workspace file so the model can re-read it via `read_file`.
     * Returns the relative path (forward-slash) for use in messages.
     */
    private fun saveOutputSnapshot(workingDir: java.nio.file.Path, content: String): String {
        return try {
            val fileName = ".refio_output_${System.currentTimeMillis()}.txt"
            val target = workingDir.resolve(fileName)
            Files.writeString(target, content)
            // Relative form so the LLM can pass it straight to read_file.
            fileName
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist run_code output snapshot: ${e.message}" }
            "(failed to save: ${e.message})"
        }
    }

    /**
     * Compose a head+tail message that tells the model exactly how to recover the
     * full output via read_file. Used when the hard cap (maxOutputSize) is exceeded.
     */
    private fun buildLargeOutputMessage(
        head: String,
        tail: String,
        fullSize: Int,
        path: String,
        capExceeded: Boolean
    ): String = buildString {
        if (capExceeded) {
            append("[!! LARGE OUTPUT — full ${fullSize} chars exceeds inline cap. ")
            append("Saved to `$path`. Read it with read_file(path=\"$path\") ")
            append("(use offset/limit for paging). Below: head ${head.length} + tail ${tail.length} chars only. !!]\n\n")
        } else {
            append("[!! LARGE OUTPUT — ${fullSize} chars saved to `$path`. ")
            append("Re-read with read_file(path=\"$path\") if needed. !!]\n\n")
        }
        append("--- HEAD ---\n")
        append(head)
        append("\n\n--- TAIL ---\n")
        append(tail)
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
                    "description" to "Source code to execute."
                ),
                "timeout_seconds" to mapOf(
                    "type" to "integer",
                    "description" to "Timeout in seconds (default: $DEFAULT_TIMEOUT_SECONDS, max: $MAX_TIMEOUT_SECONDS)."
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
        const val MIN_TIMEOUT_SECONDS = 30L
        const val MAX_TIMEOUT_SECONDS = 600L
        const val DEFAULT_MAX_OUTPUT_SIZE = 200 * 1024 // 200KB
        const val LARGE_OUTPUT_WARNING_THRESHOLD = 16_000 // 16KB - warn about saving to file

        /** Outputs above this size are auto-persisted to a workspace file
         *  so the model can recover them via read_file even after summarization. */
        const val LARGE_OUTPUT_AUTOSAVE_THRESHOLD = 8_000  // 8 KB

        /** Head/tail snippet sizes returned to the model when the hard cap is exceeded. */
        const val LARGE_OUTPUT_HEAD_CHARS = 4_000
        const val LARGE_OUTPUT_TAIL_CHARS = 2_000

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

package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.tools.DiffUtils
import pl.jclab.refio.core.tools.LineEndings
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.FileTool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private val logger = dualLogger("AdvanceCodeEditingTool")

/**
 * Advanced Code Editing Tool - LLM-assisted file editing with intelligent intent processing
 *
 * This tool support:
 * 1. LLM-ASSISTED MODE (new): Uses edit_description parameter
 *    - Agent provides intent: "Add error handling for null values"
 *    - LLM reads file, generates complete modified version
 *    - Tool creates diff, snapshot, and writes file
 *
 * Parameters (LLM-assisted mode):
 * - path: Relative file path
 * - edit_description: Natural language description of what to change
 *   Example: "Add error handling for null values in parseUser function"
 *
 * Security:
 * - Path sandbox prevents directory traversal
 * - Snapshot created before modification
 * - Unified diff generation for review
 * - File size limits enforced
 */
class AdvanceCodeEditingTool(
    sandbox: PathSandbox,
    private val limits: FileLimits,
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val promptsService: PromptsService,
    private val taskRepository: TaskRepository
) : FileTool(sandbox) {

    override val name = "advance_code_editing"
    override val description =
        "LLM-assisted FULL FILE generation/regeneration. " +
        "PREFERRED for: (a) creating new files >50 lines from scratch (HTML pages, classes, scripts), " +
        "(b) rewrites covering >50% of an existing file, (c) structurally broken files. " +
        "Generates content via a dedicated LLM call so the agent's own response stays small — " +
        "avoid stuffing large `content` payloads into `create_new_file`, which inflates the agent " +
        "response and risks streaming timeouts. Returns a diff/change summary so you usually do not need " +
        "to re-read the file immediately after writing; if the tool result gets summarized or truncated, " +
        "recover the full raw output with memory(action=\"get_subtask_output\", subtask_id=...). " +
        "For small targeted edits prefer code_editing or multi_line_editor."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_PRODUCING
    override val selectionHint =
        "Full-file generation or >50% rewrites (new HTML/classes/scripts, structurally broken files). " +
        "LLM generates the content so your agent response stays small — use instead of create_new_file for large files."

    override fun validateParams(params: Map<String, Any>) {
        validatePathParam(params)
        val editDescription = params["edit_description"] as? String
        if (editDescription.isNullOrBlank()) {
            throw IllegalArgumentException("Either 'edit_description' must be provided")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return execute(params, stream = false, onChunk = null)
    }

    /**
     * Execute with optional streaming support.
     *
     * @param params Tool parameters
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return Tool result
     */
    suspend fun execute(
        params: Map<String, Any>,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ToolResult {
        return executeWithListener(params, stream, onChunk, null, null)
    }

    /**
     * Execute with ExecutionEventListener for direct UI streaming integration.
     *
     * This method is called by ToolExecutor when listener is available,
     * providing direct streaming of generated code to ChatView.
     *
     * @param params Tool parameters
     * @param subtask Current subtask (for listener context)
     * @param listener ExecutionEventListener for streaming to UI
     * @return Tool result
     */
    suspend fun executeWithListener(
        params: Map<String, Any>,
        subtask: Subtask?,
        listener: ExecutionEventListener?
    ): ToolResult {
        val pathStr = params["path"] as? String ?: "unknown"
        logger.info { "[ACE_STREAM] executeWithListener called: path=$pathStr, hasListener=${listener != null}, hasSubtask=${subtask != null}" }

        // Create callback that forwards to listener
        val onChunk: StreamCallback? = if (listener != null && subtask != null) { chunk ->
            logger.trace { "[ACE_STREAM] onChunk invoked: accumulated=${chunk.accumulated.length} chars, isComplete=${chunk.isComplete}" }
            listener.onToolCodeGenerationStream(
                step = subtask,
                toolName = name,
                filePath = pathStr,
                streamContent = chunk.accumulated,
                isComplete = chunk.isComplete
            )
        } else {
            logger.warn { "[ACE_STREAM] No callback created - listener=${listener != null}, subtask=${subtask != null}" }
            null
        }

        logger.info { "[ACE_STREAM] Calling internal executeWithListener with stream=${listener != null}" }
        return executeWithListener(params, listener != null, onChunk, subtask, listener)
    }

    /**
     * Internal execute with all options.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun executeWithListener(
        params: Map<String, Any>,
        stream: Boolean,
        onChunk: StreamCallback?,
        _subtask: Subtask?,
        _listener: ExecutionEventListener?
    ): ToolResult {
        val startTime = System.currentTimeMillis()
        val pathStr = params["path"] as? String
            ?: return ToolResult.error("Missing required parameter: 'path'")
        val editDescription = params["edit_description"] as? String
        val taskId = (params[ToolInternalParams.TASK_ID] as? String) ?: (params["taskId"] as? String)
        val subtaskId = params[ToolInternalParams.SUBTASK_ID] as? String
        val conversationContext = params["conversation_context"] as? String

        return try {
            if (editDescription != null) {
                executeLLMAssistedEdit(pathStr, editDescription, taskId, subtaskId, startTime, stream, onChunk, conversationContext)
            } else {
                ToolResult.error("Either 'edit_description' or 'old_string' must be provided")
            }
        } catch (e: SecurityException) {
            logger.warn { "Security violation in advance_code_editing: ${e.message}" }
            ToolResult.error("Security error: ${e.message}")
        } catch (e: Exception) {
            logger.error(e) { "Failed to edit file: $pathStr" }
            ToolResult.error("Failed to edit file: ${e.message}")
        }
    }

    /**
     * LLM-assisted edit: Intent → Full file regeneration → Diff → Snapshot → Write
     * (unified streaming support)
     */
    private suspend fun executeLLMAssistedEdit(
        pathStr: String,
        editDescription: String,
        taskId: String?,
        subtaskId: String?,
        startTime: Long,
        stream: Boolean = false,
        onChunk: StreamCallback? = null,
        conversationContext: String? = null
    ): ToolResult {
        // 1. Read file or prepare for creation
        val path = resolveSandboxPath(pathStr)
        val fileExists = path.exists()

        if (limits.shouldExcludeFile(path.fileName.toString())) {
            return ToolResult.error("File extension not allowed: ${path.fileName}")
        }

        return withLockedFile(path) {

            val originalContent: String
            val fileSize: Long

            if (!fileExists) {
                // File doesn't exist - will create it with LLM-generated content
                logger.info { "LLM-assisted create: path=$pathStr, description='$editDescription' (file will be created)" }

                // Create parent directories if needed
                path.parent?.let { parent ->
                    if (!Files.exists(parent)) {
                        Files.createDirectories(parent)
                        logger.info { "Created parent directories: ${path.parent}" }
                    }
                }

                originalContent = ""
                fileSize = 0
            } else {
                // File exists - will edit it
                if (!path.isRegularFile()) {
                    return@withLockedFile ToolResult.error("Not a regular file: $pathStr")
                }

                // Check file size
                fileSize = path.fileSize()
                if (fileSize > limits.maxFileSize) {
                    return@withLockedFile ToolResult.error(
                        "File too large for LLM-assisted editing: $fileSize bytes (max ${limits.maxFileSize} bytes). " +
                                "Use simple search-replace mode or split into smaller edits."
                    )
                }

                originalContent = Files.readString(path)
                logger.info { "LLM-assisted edit: path=$pathStr, description='$editDescription', size=$fileSize bytes" }
            }

            // 2. Get file extension for language detection
            val extension = path.fileName.toString().substringAfterLast('.', "")
            val language = detectLanguage(extension)

            // 3. Build LLM prompts using PromptsService with automatic variable substitution
            // CODE_EDITING_SYSTEM: Instructions for LLM behavior → sent as LLM role "system"
            // CODE_EDITING_USER: Data template with variables → sent as LLM role "user" after substitution
            val systemPrompt = promptsService.getSystemPrompt(
                type = pl.jclab.refio.core.db.PromptType.CODE_EDITING_SYSTEM
            )

            val userPrompt = promptsService.getSystemPrompt(
                type = pl.jclab.refio.core.db.PromptType.CODE_EDITING_USER,
                variables = mapOf(
                    "FILE_PATH" to pathStr,
                    "LANGUAGE" to language,
                    "ORIGINAL_CONTENT" to originalContent,
                    "EDIT_DESCRIPTION" to editDescription
                )
            )

            // 4. Call LLM with paired prompts
            // - systemPrompt → sent via systemPrompt parameter (instructions: rules, format, constraints)
            // - userPrompt → sent as user message (data: file content, edit request)
            // - optional conversation_context from agent turn (recent tool results, user data)
            val messages = buildList {
                if (!conversationContext.isNullOrBlank()) {
                    add(LLMMessage(
                        role = "user",
                        content = "<conversation_context>\n$conversationContext\n</conversation_context>"
                    ))
                    add(LLMMessage(
                        role = "assistant",
                        content = "I understand the context. I'll use this information when editing the file."
                    ))
                }
                add(LLMMessage(role = "user", content = userPrompt))
            }

            val (model, provider) = configService.getModel(
                operation = ModelOperation.CODING,
                taskId = taskId
            )
            logger.info { "Using coding model for edit (${originalContent.lines().size} lines): $model ($provider), stream=$stream, hasOnChunk=${onChunk != null}" }

            // Use unified complete() with stream flag
            var didStream = false
            // Tracks whether any streamed chunk already reached the UI. A transient error is only
            // safe to retry when nothing has streamed yet: the live consumer appends deltas, so a
            // second stream after a partial first one would duplicate output. Transient HTTP 5xx
            // errors are thrown at the status check BEFORE any SSE body is read, so this stays false
            // and the retry fires — exactly the reported "editor got a 500" case.
            var streamedAnyChunk = false
            val streamingCallback: StreamCallback? = if (stream && onChunk != null) { chunk ->
                didStream = true
                if (chunk.delta.isNotEmpty()) streamedAnyChunk = true
                onChunk(chunk)
            } else {
                null
            }

            // Extraction-repair loop: a weak editor model sometimes replies
            // with prose or an unterminated/unfenced block, so extractCodeBlock yields null. Rather
            // than fail the whole turn on the first such miss, re-prompt the editor with a corrective
            // hint and retry, bounded to MAX_EXTRACTION_ATTEMPTS, then fail loud (Rule 12). There is
            // no diff to "apply" here — the model returns the FULL file and the write happens only
            // after a clean extraction — so this guards extraction, not diff application, and there
            // is never a partial write to roll back.
            val attemptMessages = messages.toMutableList()
            var response: pl.jclab.refio.core.llm.LLMResponse? = null
            var newContent: String? = null
            var attempt = 0
            while (attempt < MAX_EXTRACTION_ATTEMPTS) {
                attempt++
                val attemptResponse = try {
                    // The editor call bypasses LLMRetryHandler, so a transient upstream hiccup
                    // (Anthropic HTTP 500, Cloudflare 520, 529 overloaded) would otherwise fail the
                    // whole edit on a single blip. Retry those here with a bounded backoff, but only
                    // while nothing has streamed to the UI yet (see streamedAnyChunk).
                    var transientAttempt = 0
                    var completed: pl.jclab.refio.core.llm.LLMResponse? = null
                    while (completed == null) {
                        try {
                            completed = llmClient.complete(
                                provider = provider,
                                model = model,
                                messages = attemptMessages,
                                systemPrompt = systemPrompt,
                                temperature = 0.2, // Low temperature for deterministic output
                                maxTokens = EDITOR_MAX_OUTPUT_REQUEST, // full model output budget; adapters clamp to per-model limit
                                stream = stream,
                                onChunk = streamingCallback,
                                taskId = taskId,
                                subtaskId = subtaskId,
                                source = "AdvCodeEditor"  // Request source for tracking
                            )
                        } catch (e: Exception) {
                            val canRetry = transientAttempt < MAX_TRANSIENT_RETRIES &&
                                !streamedAnyChunk &&
                                pl.jclab.refio.core.errors.TransientErrorClassifier.isTransient(e)
                            if (!canRetry) throw e
                            transientAttempt++
                            val backoffMs = 1000L * (1L shl (transientAttempt - 1)) // 1s, 2s
                            logger.warn {
                                "[ACE_RETRY] transient editor error (attempt $transientAttempt/$MAX_TRANSIENT_RETRIES) " +
                                    "for $pathStr: ${e.message}. Retrying in ${backoffMs}ms"
                            }
                            kotlinx.coroutines.delay(backoffMs)
                        }
                    }
                    completed
                } catch (e: Exception) {
                    logger.error(e) { "LLM request failed" }
                    return@withLockedFile ToolResult.error("LLM request failed: ${e.message}. Try again or use simple search-replace mode.")
                }
                response = attemptResponse

                val extracted = extractCodeBlock(attemptResponse.content, language)
                if (extracted != null) {
                    newContent = extracted
                    break
                }

                logger.warn {
                    "[EDITOR] code-block extraction failed (attempt=$attempt/$MAX_EXTRACTION_ATTEMPTS) for $pathStr — " +
                            "editor model returned no usable fenced code block"
                }

                // A large unterminated block means the generation was cut off (hit the output cap or
                // an upstream truncation), NOT that the model refused. Re-prompting would just produce
                // the same truncation and burn another full multi-minute generation. Break out and let
                // the salvage path below recover the near-complete content instead of regenerating.
                if (salvageTruncatedCodeBlock(attemptResponse.content, language) != null) {
                    logger.warn {
                        "[EDITOR] attempt=$attempt for $pathStr returned a truncated/unterminated block — " +
                                "skipping repair re-generation (would truncate again) and salvaging instead"
                    }
                    break
                }

                if (attempt < MAX_EXTRACTION_ATTEMPTS) {
                    attemptMessages.add(LLMMessage(role = "assistant", content = attemptResponse.content))
                    attemptMessages.add(LLMMessage(role = "user", content = extractionRepairHint(language)))
                }
            }

            // Last-resort salvage: strict extraction requires a CLOSED fence, so a large generation
            // whose stream ended without the trailing ``` (truncation, upstream cut-off) is dropped
            // even though most of the file arrived intact. Rather than lose a near-complete 65KB
            // deliverable and burn the whole turn, recover the content after the opening fence and
            // write it, marked loudly as possibly-truncated so the agent verifies/continues.
            var salvaged = false
            if (newContent == null && response != null) {
                val rescued = salvageTruncatedCodeBlock(response.content, language)
                if (rescued != null) {
                    newContent = rescued
                    salvaged = true
                    logger.warn {
                        "[EDITOR] salvaged ${rescued.length} chars from a truncated/unterminated code block " +
                            "for $pathStr (strict extraction found no closing fence)"
                    }
                }
            }

            if (response == null || newContent == null) {
                // 5. Exhausted the repair budget — fail loud with a diagnostic (Rule 12). Nothing
                // has been written to disk, so there is no partial state to clean up.
                return@withLockedFile ToolResult.error(
                    "LLM did not return a usable code block after $MAX_EXTRACTION_ATTEMPTS attempt(s). " +
                            "The coding model kept replying with prose or an unterminated block. " +
                            "Try rephrasing the edit description, set a stronger coding model via default_model.agent, " +
                            "or use code_editing / multi_line_editor with an exact string to match."
                )
            }

            val responseContent = response.content
            val usage = response.usage
            val cost = response.cost

            if (stream && onChunk != null && !didStream) {
                onChunk(
                    StreamChunk(
                        delta = responseContent,
                        accumulated = responseContent,
                        isComplete = true,
                        source = "AdvCodeEditor",
                        usage = usage,
                        cost = cost
                    )
                )
            }

            // 6. Re-express the generated file in the line-ending convention it had on disk. The
            // model is fed LF and answers in LF, so writing its reply verbatim rewrites every line
            // ending of a CRLF checkout and turns a small edit into a whole-file diff. A file being
            // created has no previous convention to preserve, so it stays as generated.
            if (fileExists) {
                newContent = LineEndings.toFileEol(newContent, originalContent)
            }

            // 7. Generate diff and change summary BEFORE writing: the diff is the expensive step,
            // and computing it first means a failure there leaves the file untouched instead of
            // losing the result of a write that already happened.
            val changeSummary = DiffUtils.buildChangeSummary(
                originalContent = originalContent,
                newContent = newContent,
                filePath = pathStr,
                created = !fileExists
            )
            val diff = changeSummary.unifiedDiff ?: ""
            val addedLines = changeSummary.addedLines
            val removedLines = changeSummary.removedLines

            // 8. Optional: Syntax validation
            val validationError = validateSyntax(newContent, language)
            if (validationError != null) {
                logger.warn { "Syntax validation warning: $validationError" }
                // Continue anyway - user can rollback
            }

            // 9. Snapshot creation
            // Note: Snapshots are created by AgentExecutor before tool execution
            // Tool itself doesn't create snapshots - this is handled at the workflow level

            // 10. Write file
            Files.writeString(path, newContent)
            val duration = (System.currentTimeMillis() - startTime).toInt()
            val newFileSize = path.fileSize()

            logger.info {
                "LLM-assisted edit completed: path=$pathStr, model=$model, " +
                        "tokens_in=${usage.inputTokens}, tokens_out=${usage.outputTokens}, " +
                        "cost_usd=$cost, diff_lines=${diff.lines().size}, duration=${duration}ms"
            }

            // Task / subtask metrics auto-incremented inside LLMClient.complete()
            // via taskId / subtaskId passed in the call above. No manual increment here.

            // 11. Return result (changes displayed as badge in UI)
            ToolResult(
                success = true,
                output = buildString {
                    if (salvaged) {
                        // Loud, non-negotiable notice: the file was written from an UNTERMINATED
                        // model reply, so the tail may be missing. Steer the agent to verify the end
                        // and append the remainder with a targeted edit instead of regenerating.
                        appendLine("⚠ SALVAGED (possibly truncated): the editor model's reply had no closing code fence, so the")
                        appendLine("stream likely got cut off. The recovered content was written to $pathStr, but the END of the")
                        appendLine("file may be missing. VERIFY the last lines are complete; if truncated, append the remainder with")
                        appendLine("code_editing / multi_line_editor — do NOT regenerate the whole file from scratch.")
                        appendLine()
                    }
                    if (changeSummary.noop) {
                        // LLM returned content identical to the existing file — no change applied.
                        // Surface this explicitly so the agent notices (instead of seeing a bland
                        // "File edited successfully" with an empty diff and concluding all is fine).
                        appendLine("⚠ No changes applied: generated content is identical to the existing file ($pathStr).")
                        appendLine("Likely causes: the edit_description was too vague for the model to act on, the change is already present, or the model refused and returned the file unchanged.")
                        appendLine("Next step: refine edit_description with concrete before/after snippets, or switch to code_editing / multi_line_editor with an exact string to match.")
                        appendLine("If you need to inspect the current file state, use read_file(path=\"$pathStr\"). If this tool result was summarized, recover the full raw diff/output with memory(action=\"get_subtask_output\", subtask_id=\"<this tool result id>\"). Do NOT retry advance_code_editing with the same description.")
                        appendLine("Model: $model, Tokens: ${usage.inputTokens}/${usage.outputTokens}, Cost: $${"%.4f".format(cost)}")
                    } else {
                        if (fileExists) {
                            appendLine("File edited successfully: $pathStr")
                        } else {
                            appendLine("File created successfully: $pathStr")
                        }
                        if (fileExists) {
                            appendLine("Size: $fileSize bytes → $newFileSize bytes")
                        } else {
                            appendLine("Size: $newFileSize bytes")
                        }
                        appendLine("Model: $model, Tokens: ${usage.inputTokens}/${usage.outputTokens}, Cost: $${"%.4f".format(cost)}")
                        // Wrap diff in markdown code block for proper UI rendering
                        appendLine("Diff:")
                        appendLine("```diff")
                        diff.lines().forEach { line -> appendLine(line) }
                        append("```")
                    }
                },
                bytesRead = originalContent.toByteArray().size,
                bytesWritten = newContent.toByteArray().size,
                durationMs = duration,
                filesChanged = listOf(pathStr),
                changeSummary = changeSummary,
                metadata = mapOf(
                    "path" to pathStr,  // Relative path to project root
                    "mode" to "llm_assisted",
                    "diff_lines" to diff.lines().size,
                    "added_lines" to addedLines,
                    "removed_lines" to removedLines,
                    "noop" to changeSummary.noop,
                    "edit_description" to editDescription,
                    "model" to model,
                    "provider" to provider,
                    "tokens_in" to usage.inputTokens,
                    "tokens_out" to usage.outputTokens,
                    "cost_usd" to cost
                )
            )
        }
    }

    /**
     * Extract the generated file content from an LLM reply.
     *
     * Only a fenced code block is accepted (with or without a language tag). An unfenced
     * reply is deliberately rejected: a model that did not fence its output is as likely to
     * be returning prose, an apology, or a refusal as raw code, and writing that verbatim
     * would corrupt the file. Returning null routes the caller into its extraction-repair
     * loop, which re-prompts for a proper fenced block and fails loud if none ever arrives —
     * never a silent write of non-code.
     */
    private fun extractCodeBlock(response: String, expectedLanguage: String): String? {
        // Pattern 1: ```language\ncode\n```
        extractFencedBody(response, Regex("```$expectedLanguage\\s*\\n"))?.let { return it }

        // Pattern 2: ```\ncode\n``` (no language specified)
        return extractFencedBody(response, Regex("```\\s*\\n"))
    }

    /**
     * Body of the block opened by [openFence], ending at the fence that balances it.
     *
     * Fence lines starting at column 0 are depth-counted instead of matched lazily: the generated file
     * legitimately contains fenced blocks of its own (a ```bash sample inside a README, prompt or doc),
     * and closing on the first inner fence would write only the part of the file before it while the
     * tool still reported success. A bare ``` line closes a block, a ```something line opens a nested
     * one. Returns null when the opening fence is missing or is never balanced by a closing one, so the
     * caller takes its extraction-repair/salvage path instead of writing a partial file.
     */
    private fun extractFencedBody(response: String, openFence: Regex): String? {
        val open = openFence.find(response) ?: return null
        val body = response.substring(open.range.last + 1)
        var depth = 1
        for (fence in FENCE_LINE.findAll(body)) {
            if (fence.groupValues[1].isBlank()) {
                depth--
            } else {
                depth++
            }
            if (depth == 0) {
                // The newline before the closing fence terminates the last content line, so it is part
                // of the fence, not of the file. Everything else is kept verbatim: trimming here would
                // strip the file's trailing newline and any intentional leading blank line.
                val end = (fence.range.first - 1).coerceAtLeast(0)
                val content = body.substring(0, end)
                return if (content.isBlank()) null else content
            }
        }
        return null
    }

    /**
     * Last-resort recovery of an unterminated fenced block (opening ``` present, closing ``` never
     * arrived because the stream was truncated). Returns the content after the opening fence, minus
     * any dangling partial closing fence, but only when it is substantial ([SALVAGE_MIN_CHARS]) — a
     * short unterminated reply is more likely a prose apology than a real file, and must not be
     * written. Returns null when there is no opening fence or the body is too small to trust.
     */
    private fun salvageTruncatedCodeBlock(response: String, expectedLanguage: String): String? {
        val openFence = Regex("```(?:$expectedLanguage)?[^\\n]*\\n", RegexOption.IGNORE_CASE).find(response)
            ?: return null
        var body = response.substring(openFence.range.last + 1)
        // Drop a trailing closing fence if one happens to be present (defensive; strict extraction
        // would have handled a well-formed block before we reach salvage).
        val closeIdx = body.lastIndexOf("\n```")
        if (closeIdx >= 0) {
            body = body.substring(0, closeIdx)
        }
        body = body.trimEnd()
        return if (body.length >= SALVAGE_MIN_CHARS) body else null
    }

    /**
     * Corrective re-prompt for the extraction-repair loop: tells a model that
     * replied without a clean fenced code block to re-emit the whole file inside a single fence and
     * nothing else. Terse and verbatim — the model still has the file and edit description in context.
     */
    private fun extractionRepairHint(language: String): String =
        "Your previous reply did not contain a usable code block. " +
            "Reply again with the COMPLETE file content inside a single fenced code block " +
            "(```$language ... ```) and nothing else — no explanation, no prose before or after the fence."

    /**
     * Detect programming language from file extension
     */
    private fun detectLanguage(extension: String): String {
        return when (extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "cs" -> "csharp"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "md" -> "markdown"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "xml" -> "xml"
            "html" -> "html"
            "css" -> "css"
            "sql" -> "sql"
            "sh", "bash" -> "bash"
            else -> "plaintext"
        }
    }

    /**
     * Optional: Validate syntax (language-specific)
     * For MVP: skip or basic checks only
     */
    @Suppress("UNUSED_PARAMETER")
    private fun validateSyntax(content: String, _language: String): String? {
        // Future: language-specific parsers
        // For MVP: just check if file is not empty and has basic structure
        if (content.trim().isEmpty()) {
            return "Generated content is empty"
        }
        return null
    }

    private fun countOccurrences(text: String, substring: String): Int {
        if (substring.isEmpty()) return 0

        var count = 0
        var index = 0

        while (text.indexOf(substring, index).also { index = it } != -1) {
            count++
            index += substring.length
        }

        return count
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Relative path to the file to edit"
                ),
                "edit_description" to mapOf(
                    "type" to "string",
                    "description" to "Natural-language description of the change. " +
                        "Provide this OR the (old_string, new_string) pair — not both."
                ),
                "old_string" to mapOf(
                    "type" to "string",
                    "description" to "Exact substring to replace. Use together with new_string " +
                        "as an alternative to edit_description."
                ),
                "new_string" to mapOf(
                    "type" to "string",
                    "description" to "Replacement text for old_string."
                ),
            ),
            "required" to listOf("path")
        )
    }

    companion object {
        /**
         * Bound on the extraction-repair loop: the initial editor call plus
         * one corrective re-prompt. A weak model that still cannot emit a clean code block after a
         * reminder will not recover with more — fail loud instead of looping and burning tokens.
         */
        private const val MAX_EXTRACTION_ATTEMPTS = 2

        /**
         * Bounded transient-error retries for the editor LLM call (this tool bypasses
         * LLMRetryHandler). Two retries = up to three attempts with 1s/2s backoff — enough to ride
         * out a brief upstream 5xx/overload without stalling on a genuinely down provider.
         */
        private const val MAX_TRANSIENT_RETRIES = 2

        /**
         * Output-token budget requested for the editor call. Deliberately far above any model's real
         * output cap: the adapters clamp it down to the per-model limit (e.g. 64000 for Claude sonnet),
         * so the editor gets the model's FULL budget to generate a large file in one shot instead of
         * being throttled by the global `limits.max_output_size` default. For models with no known
         * limit the adapter falls back to that config value as a safety ceiling.
         */
        private const val EDITOR_MAX_OUTPUT_REQUEST = 1_000_000

        /**
         * Minimum salvage size. Below this, an unterminated reply is treated as prose/refusal, not a
         * truncated file, and is NOT written. ~2 KB is comfortably larger than any apology yet small
         * enough to rescue a partially-generated real file.
         */
        private const val SALVAGE_MIN_CHARS = 2000

        /**
         * A markdown fence line, i.e. a ``` starting at column 0. Group 1 is the info string: empty for
         * a closing fence, the language (or anything else) for an opening one. Indented fences are
         * deliberately ignored, since the editor's own wrapping fence is always at column 0.
         */
        private val FENCE_LINE = Regex("^```(.*)$", RegexOption.MULTILINE)
    }

}

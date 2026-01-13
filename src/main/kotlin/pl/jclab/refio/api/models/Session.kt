package pl.jclab.refio.api.models

import com.google.gson.Gson
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageMetrics
import pl.jclab.refio.core.db.getMetrics

/**
 * Task mode enum matching backend TaskMode
 */
enum class TaskMode {
    CHAT,
    PLAN,
    AGENT
}

/**
 * Task status enum matching backend TaskStatus
 */
enum class TaskStatus {
    NEW,
    PENDING,
    PLANNED,  // Subtask has been prepared but not yet executed (US-101)
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED
}

/**
 * Session (Task) model representing a conversation/agent session
 */
data class Session(
    val id: String,
    val name: String,
    val mode: TaskMode,
    val status: TaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val model: String? = null,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val costUsd: Double = 0.0,
    val contextWarningShown: Boolean = false,
    val executionMode: ExecutionMode = ExecutionMode.AUTO,
    val thinkingEnabled: Boolean = false,
    val noEgressEnabled: Boolean = false,
    val orchestrationEnabled: Boolean = true,
    val intentClassificationEnabled: Boolean = false,
    val pinned: Boolean = false,
    val rate: Int? = null  // User rating: 1 (positive) or -1 (negative), null if not rated
)

/**
 * Message model for chat history
 */
data class Message(
    val id: String,
    val taskId: String,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val thinking: String? = null,
    val codeBlocks: List<CodeBlock>? = null,
    val toolCalls: List<ToolCall>? = null,
    val diffSummary: DiffSummary? = null,
    val model: String? = null,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val duration: Double? = null,
    val costUsd: Double? = null,
    val createdAt: Long,
    val pendingApprovalSubtaskId: String? = null, // For INTERACTIVE mode approval buttons
    val metrics: MessageMetrics? = null, // Detailed metrics from MessageMetrics (US-027)
    val metadata: String? = null, // JSON metadata for special message types (e.g., orchestrator questions)

    // Streaming fields (US-027)
    val isStreaming: Boolean = false,      // Whether message is currently streaming
    val streamStartedAt: Long? = null,     // When streaming started (epoch ms)
    val lastChunkAt: Long? = null          // When last chunk was received (epoch ms)
)

/**
 * Code block extracted from assistant message
 */
data class CodeBlock(
    val language: String,
    val code: String
)

/**
 * Tool call data
 */
data class ToolCall(
    val id: String,
    val name: String,
    val params: Map<String, Any>,
    val output: String? = null,
    val status: String, // "pending", "success", "failed"
    val summary: String? = null,
    val cost: Double = 0.0,
    val duration: Double? = null
)

/**
 * Diff summary for file changes
 */
data class DiffSummary(
    val additions: Int,
    val deletions: Int,
    val filesCount: Int,
    val snapshotId: String? = null
)

/**
 * Subtask (step) model
 */
data class Subtask(
    val id: String,
    val taskId: String,
    val orderIndex: Int,
    val kind: String, // tool name
    val description: String? = null,
    val paramsJson: String? = null,
    val resultJson: String? = null,
    val status: TaskStatus,
    val requiresApproval: Boolean = false,
    val snapshotIdBeforeWrite: String? = null,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val duration: Double? = null,
    val costUsd: Double? = null,
    val logStdout: String? = null,
    val logStderr: String? = null
)

/**
 * Context reference for prompt
 *
 * Types:
 * - @file: single file reference
 * - @folder: folder with depth limit
 * - @selection: current editor selection
 * - @open: all open files
 * - @docs: external documentation
 * - @rules: rules file (Agents.md)
 */
data class ContextReference(
    val type: ContextType,
    val path: String,              // File/folder path or doc URL
    val displayName: String,       // Shown in UI and input
    val content: String? = null,   // Loaded content (null until resolved)
    val sizeBytes: Long = 0,       // Size for validation
    val estimatedTokens: Int = 0,  // Estimated token count
    val metadata: Map<String, Any> = emptyMap()  // Additional data
) {
    companion object {
        /**
         * Create file reference
         */
        fun file(path: String, displayName: String? = null): ContextReference {
            return ContextReference(
                type = ContextType.FILE,
                path = path,
                displayName = displayName ?: path.substringAfterLast('/')
            )
        }

        /**
         * Create folder reference
         */
        fun folder(path: String, depth: Int = 1): ContextReference {
            return ContextReference(
                type = ContextType.FOLDER,
                path = path,
                displayName = path.substringAfterLast('/'),
                metadata = mapOf("depth" to depth)
            )
        }

        /**
         * Create selection reference
         */
        fun selection(content: String, fileName: String): ContextReference {
            return ContextReference(
                type = ContextType.SELECTION,
                path = fileName,
                displayName = "Selection from $fileName",
                content = content
            )
        }

        /**
         * Create open files reference
         */
        fun openFiles(): ContextReference {
            return ContextReference(
                type = ContextType.OPEN,
                path = "",
                displayName = "Open files"
            )
        }

        /**
         * Create documentation reference
         */
        fun docs(url: String, title: String? = null): ContextReference {
            return ContextReference(
                type = ContextType.DOCS,
                path = url,
                displayName = title ?: url
            )
        }

        /**
         * Create rules reference
         */
        fun rules(path: String = "Agents.md"): ContextReference {
            return ContextReference(
                type = ContextType.RULES,
                path = path,
                displayName = "Rules ($path)"
            )
        }

        /**
         * Create provider-based context reference
         *
         * Used for dynamic context providers from ContextProviderRegistry:
         * - Built-in providers: @current, @clipboard, @diff, @problems, @terminal, @grep, @commit, @codebase
         * - MCP servers: @server-id:query
         *
         * @param providerId Provider ID from ContextProviderRegistry (e.g., "current", "clipboard", "diff")
         * @param query Optional query string for QUERY-type providers
         * @param displayName Display name for UI
         * @param additionalMetadata Additional provider-specific metadata
         */
        fun provider(
            providerId: String,
            query: String = "",
            displayName: String = providerId,
            additionalMetadata: Map<String, Any> = emptyMap()
        ): ContextReference {
            return ContextReference(
                type = ContextType.PROVIDER,
                path = query,
                displayName = displayName,
                metadata = additionalMetadata + mapOf("providerId" to providerId)
            )
        }
    }
}

/**
 * Types of context references
 */
enum class ContextType {
    FILE,       // @file:path/to/file.kt
    FOLDER,     // @folder:path/to/folder
    SELECTION,  // @selection (current editor selection)
    OPEN,       // @open (all open files)
    DOCS,       // @docs:url or indexed doc
    RULES,      // @rules or @rules:path/to/rules.md
    PROVIDER    // Dynamic context provider (e.g., @current, @clipboard, @diff, @problems, MCP servers)
                // Provider ID stored in metadata["providerId"], content resolved by ContextProviderRegistry
}

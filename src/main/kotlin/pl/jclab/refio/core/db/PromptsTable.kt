package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.Table
import java.util.UUID

/**
 * Prompt type enum
 */
enum class PromptType {
    // ========== Mode System Prompts (single prompt → LLM system role) ==========
    SYSTEM_CHAT,            // Chat mode - conversational assistance (LLM role: system)
    SYSTEM_PLAN,            // Plan mode - read-only analysis and planning (LLM role: system)
    SYSTEM_AGENT,           // Agent mode - autonomous execution with write access (LLM role: system)

    // ========== Workflow System Prompts (single prompt → LLM system role) ==========
    SYSTEM_STEP_PLANNER,    // Step planner - dynamic tool parameter generation (LLM role: system)
    SYSTEM_STEP_SUMMARIZER, // Step summarizer - execution result summarization (LLM role: system)
    SYSTEM_ORCHESTRATOR,    // Orchestrator - reflection and plan adaptation (LLM role: system)
    SYSTEM_EXECUTION_SUMMARY, // Execution summary - final task summary generation (LLM role: system)
    SYSTEM_CONVERSATION_SUMMARY, // Conversation summary - history compaction (LLM role: system)
    SYSTEM_INTENT_CLASSIFIER, // Intent classifier - decides action type for user input (LLM role: system)

    // ========== Tool Prompts (paired: system instructions + user data template) ==========
    // NOTE: These are BOTH system-managed prompts, but serve different LLM roles:
    // - CODE_EDITING_SYSTEM → LLM role: "system" (instructions: rules, format, behavior)
    // - CODE_EDITING_USER → LLM role: "user" (data: file content, edit description, context)
    CODE_EDITING_SYSTEM,    // AdvanceCodeEditingTool - LLM instructions (LLM role: system)
    CODE_EDITING_USER,      // AdvanceCodeEditingTool - data template with {{variables}} (LLM role: user)

    // MultiLineEditorTool - precise line-based editing with LLM-identified ranges
    MULTI_LINE_EDITING_SYSTEM,  // MultiLineEditorTool - LLM instructions (LLM role: system)
    MULTI_LINE_EDITING_USER,    // MultiLineEditorTool - data template with {{variables}} (LLM role: user)

    // ========== User-Defined Content ==========
    RULE,                   // User-defined rule (appended to system prompts)
    SLASH_COMMAND           // User-defined slash command (triggered by /command)
    ;

    companion object {
        val SYSTEM_PROMPT_TYPES = setOf(
            SYSTEM_CHAT,
            SYSTEM_PLAN,
            SYSTEM_AGENT,
            SYSTEM_STEP_PLANNER,
            SYSTEM_STEP_SUMMARIZER,
            SYSTEM_ORCHESTRATOR,
            SYSTEM_EXECUTION_SUMMARY,
            SYSTEM_CONVERSATION_SUMMARY,
            SYSTEM_INTENT_CLASSIFIER,
            CODE_EDITING_SYSTEM,
            CODE_EDITING_USER,
            MULTI_LINE_EDITING_SYSTEM,
            MULTI_LINE_EDITING_USER
        )
    }

    fun isSystemPrompt(): Boolean = SYSTEM_PROMPT_TYPES.contains(this)

    fun isSlashCommand(): Boolean = this == SLASH_COMMAND
}

/**
 * Prompts table definition using Exposed ORM DSL
 * Stores system prompts, rules, and slash commands
 */
object PromptsTable : Table("prompts") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val name = varchar("name", 128)  // Display name (e.g., "Chat Mode", "TypeScript Rule", "/refactor")
    val type = enumerationByName<PromptType>("type", 32)
    val content = text("content")  // Template content with {{variable}} placeholders
    val description = text("description").nullable()  // Human-readable description
    val isCustom = bool("is_custom").default(false)  // false = system default, true = user-defined
    val isEnabled = bool("is_enabled").default(true)  // Can be toggled on/off
    val orderIndex = integer("order_index").default(0)  // For ordering in UI
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for efficient retrieval by type
        index("idx_prompts_type", false, type, isEnabled)
        // Unique constraint on name + type (case-insensitive in SQLite)
        uniqueIndex("idx_prompts_name_type", name, type)
    }
}

/**
 * Prompt data class for results
 */
data class Prompt(
    val id: String,
    val name: String,
    val type: PromptType,
    val content: String,
    val description: String?,
    val isCustom: Boolean,
    val isEnabled: Boolean,
    val orderIndex: Int,
    val createdAt: Long,
    val updatedAt: Long
)

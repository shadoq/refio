package pl.jclab.refio.ui.theme

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Single source of truth for context section colors.
 * Keep this shared between ContextPanel and StatusBar.
 */
object ContextSectionColorPalette {
    private val sectionColors = mapOf(
        // Core context
        "project_overview" to Color(0x4A90D9),
        "project_instructions" to Color(0x3498DB),
        "dependencies" to Color(0x7B68EE),
        "code_analysis" to Color(0x9370DB),
        "current_task" to Color(0xDA70D6),
        "subtasks" to Color(0xFF69B4),
        "conversation" to Color(0xF08080),
        "rag_fragments" to Color(0xFFB347),
        "user_context" to Color(0x98FB98),
        "tool_outputs" to Color(0x87CEEB),
        "recent_work" to Color(0xDDA0DD),
        // Semantic summary subsections
        "architecture" to Color(0x5B9BD5),
        "key_components" to Color(0x6C8EBF),
        "framework_analysis" to Color(0x48C9B0),
        "patterns" to Color(0x7DCEA0),
        "navigation_map" to Color(0xA3D977),
        // Language analysis
        "typescript_analysis" to Color(0x3178C6),
        "html_analysis" to Color(0xE44D26),
        "css_analysis" to Color(0x264DE4),
        // System
        "system_prompt" to Color(0x5DADE2),
        "system_messages" to Color(0x2E86C1),
        "messages_user" to Color(0x45B39D),
        "messages_assistant" to Color(0xF5B041),
        "messages_system" to Color(0xAAB7B8),
        "messages_other" to Color(0xBFC9CA),
        "context_injection_overhead" to Color(0x85929E),
        "request_overhead" to Color(0x7D3C98),
        "working_memory" to Color(0xF39C12),
        "user_requirements" to Color(0x27AE60),
        "mcp_resources" to Color(0x16A085),
        "free_space" to Color(0xD3D3D3),
        // ContextPanel sections without explicit tag parsing
        "semantic_summary" to Color(0x5499C7),
        "project_structure" to Color(0x5DADE2),
        "task_requirements" to Color(0xE74C3C),
        "context_stability" to Color(0x95A5A6),
        "domain_analysis" to Color(0x2980B9)
    )

    fun colorFor(key: String): Color = sectionColors[key] ?: JBColor.GRAY
}

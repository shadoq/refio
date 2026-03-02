package pl.jclab.refio.ui.theme

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Single source of truth for context section colors.
 * Keep this shared between ContextPanel and StatusBar.
 */
object ContextSectionColorPalette {
    private val sectionColors = mapOf(
        "project_overview" to Color(0x4A90D9),
        "dependencies" to Color(0x7B68EE),
        "code_analysis" to Color(0x9370DB),
        "current_task" to Color(0xDA70D6),
        "subtasks" to Color(0xFF69B4),
        "conversation" to Color(0xF08080),
        "rag_fragments" to Color(0xFFB347),
        "user_context" to Color(0x98FB98),
        "tool_outputs" to Color(0x87CEEB),
        "recent_work" to Color(0xDDA0DD),
        "system_prompt" to Color(0x5DADE2),
        "system_messages" to Color(0x2E86C1),
        "messages_user" to Color(0x45B39D),
        "messages_assistant" to Color(0xF5B041),
        "messages_system" to Color(0xAAB7B8),
        "messages_other" to Color(0xBFC9CA),
        "context_injection_overhead" to Color(0x85929E),
        "request_overhead" to Color(0x7D3C98),
        "free_space" to Color(0xD3D3D3)
    )

    fun colorFor(key: String): Color = sectionColors[key] ?: JBColor.GRAY
}

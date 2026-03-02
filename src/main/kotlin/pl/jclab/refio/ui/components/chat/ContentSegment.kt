package pl.jclab.refio.ui.components.chat

/**
 * Typed segment of assistant message content.
 * Produced by ContentSegmentParser, consumed by ChatView rendering.
 */
sealed interface ContentSegment {

    /**
     * Thinking block extracted from <think>...</think> or <thinking>...</thinking> tags.
     */
    data class Thinking(val content: String) : ContentSegment

    /**
     * Fenced code block: ```lang:path\n...\n```
     */
    data class Code(val codeBlock: CodeBlock) : ContentSegment

    /**
     * Standalone JSON object (not inside a code fence).
     * Rendered as a JSON code block.
     */
    data class Json(val content: String) : ContentSegment

    /**
     * Plan JSON: {"plan": "...", "subtasks": [...]} or {"actions": [...]}.
     * Rendered as plan description + formatted step list.
     */
    data class Plan(
        val description: String?,
        val subtasks: List<Map<String, Any?>>
    ) : ContentSegment

    /**
     * Markdown text (everything that is not thinking, code, JSON, or plan).
     */
    data class Markdown(val content: String) : ContentSegment
}

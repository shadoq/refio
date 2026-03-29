package pl.jclab.refio.cli.tui.rendering

/**
 * Typed segment of assistant message content for TUI rendering.
 * Adapted from plugin's ContentSegment (ui/components/chat/ContentSegment.kt).
 *
 * Each segment type has its own ANSI rendering strategy in TuiMessageBubble.
 */
sealed interface TuiContentSegment {
    /** Thinking block from <think>/<thinking> tags — rendered dimmed/italic. */
    data class Thinking(val content: String) : TuiContentSegment

    /** Fenced code block with optional language and file path — rendered with frame. */
    data class Code(
        val language: String,
        val filePath: String?,
        val content: String
    ) : TuiContentSegment

    /** Standalone JSON object — rendered as formatted code block. */
    data class Json(val content: String) : TuiContentSegment

    /** Markdown text (everything else) — rendered via Mordant. */
    data class Markdown(val content: String) : TuiContentSegment
}

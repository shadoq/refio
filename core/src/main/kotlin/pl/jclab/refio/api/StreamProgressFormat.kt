package pl.jclab.refio.api

import java.util.Locale

/**
 * Formats the live progress of streaming tool output (e.g. code generation) for display
 * in tool-call bubbles. Shared by the IntelliJ plugin and the CLI TUI so the counter wording
 * stays identical across both surfaces.
 */
object StreamProgressFormat {

    /**
     * Human-readable character count, e.g. "1,234 chars". Uses a singular unit for exactly one
     * character, a fixed US-style grouping separator so output does not depend on the host locale,
     * and clamps negative inputs to zero.
     */
    fun charCount(count: Int): String {
        val safe = count.coerceAtLeast(0)
        val grouped = "%,d".format(Locale.US, safe)
        val unit = if (safe == 1) "char" else "chars"
        return "$grouped $unit"
    }

    /**
     * The counter portion on its own, e.g. "· 1,234 chars". Used when the counter lives in a
     * separate label that is patched in place during streaming, so the wording stays identical
     * to [withCharCount].
     */
    fun counterSuffix(count: Int): String {
        return "· ${charCount(count)}"
    }

    /**
     * Appends a live character counter to a status label when [count] is non-null
     * (e.g. "Generating... · 1,234 chars"). A null count returns the label unchanged, so
     * non-streaming states render without a counter.
     */
    fun withCharCount(label: String, count: Int?): String {
        if (count == null) {
            return label
        }
        return "$label ${counterSuffix(count)}"
    }
}

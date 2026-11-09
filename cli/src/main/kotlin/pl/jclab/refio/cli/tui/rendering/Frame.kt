package pl.jclab.refio.cli.tui.rendering

import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.cli.tui.theme.ThemeColor

/** Border colour of a frame says what the pane is doing. */
enum class FrameState(val border: ThemeColor) {
    /** Default. */
    INACTIVE(ThemeColor.FRAME),
    /** The pane that receives letter keys. */
    FOCUSED(ThemeColor.ACCENT),
    /** Approval request, output-limit notice, config problem. */
    WARNING(ThemeColor.WARN),
    /** Command popup. */
    POPUP(ThemeColor.TEAL),
    /** "Other / more" boxes; drawn with dashed lines. */
    PLACEHOLDER(ThemeColor.FRAME)
}

/**
 * The one frame renderer every pane uses: rounded border, upper-case title at the left of the top edge, optional
 * dim meta text at the right. Produces exactly `height` rows of exactly `width` columns, so frames can be placed
 * next to each other by plain string concatenation.
 */
class Frame(private val theme: Theme, private val glyphs: Glyphs) {

    /** Columns and rows available to the body for a frame of the given size. */
    fun innerSize(width: Int, height: Int, padding: Int = 1): Pair<Int, Int> {
        val pad = effectivePadding(width, padding)
        return (width - 2 - 2 * pad).coerceAtLeast(0) to (height - 2).coerceAtLeast(0)
    }

    fun render(
        width: Int,
        height: Int,
        title: String,
        meta: String?,
        state: FrameState,
        body: List<String>,
        padding: Int = 1
    ): List<String> {
        if (height <= 0) return emptyList()
        if (width < 2 || height < 2) return List(height) { " ".repeat(width.coerceAtLeast(0)) }

        val dashed = state == FrameState.PLACEHOLDER
        val horizontal = if (dashed) glyphs.dashedHorizontal else glyphs.horizontal
        val vertical = if (dashed) glyphs.dashedVertical else glyphs.vertical
        // Bold keeps focus and warnings visible when the terminal shows no colour at all.
        val bold = state == FrameState.FOCUSED || state == FrameState.WARNING || state == FrameState.POPUP
        val border = { s: String -> theme.style(s, fg = state.border, bold = bold) }

        val pad = effectivePadding(width, padding)
        val (innerWidth, _) = innerSize(width, height, padding)
        val side = border(vertical)
        val gap = " ".repeat(pad)

        val rows = ArrayList<String>(height)
        rows += topBorder(width, title, meta, horizontal, border)
        for (i in 0 until height - 2) {
            rows += side + gap + TuiRenderBuffer.fitToWidth(body.getOrElse(i) { "" }, innerWidth) + gap + side
        }
        rows += border(glyphs.bottomLeft + horizontal.repeat(width - 2) + glyphs.bottomRight)
        return rows
    }

    /**
     * `╭─ TITLE ───── meta ─╮`. The title keeps its length as long as possible; the meta text gives way first
     * and never takes more than ~45% of the width.
     */
    private fun topBorder(width: Int, title: String, meta: String?, horizontal: String, border: (String) -> String): String {
        val inner = width - 2
        val maxTitle = inner - 4
        if (maxTitle < 1 || title.isBlank()) {
            return border(glyphs.topLeft + horizontal.repeat(inner) + glyphs.topRight)
        }
        val titleText = TextFit.truncateEnd(title.uppercase(), maxTitle, glyphs.ellipsis)
        val titleWidth = TuiRenderBuffer.visibleLength(titleText)

        val metaCap = minOf(inner - titleWidth - 7, width * 45 / 100)
        val metaText = meta?.takeIf { it.isNotBlank() && metaCap >= 3 }?.let { TextFit.truncateMiddle(it, metaCap, glyphs.ellipsis) }
        val metaWidth = metaText?.let { TuiRenderBuffer.visibleLength(it) + 3 } ?: 0

        val fill = inner - titleWidth - 3 - metaWidth
        return buildString {
            append(border(glyphs.topLeft + horizontal))
            append(' ').append(theme.style(titleText, fg = ThemeColor.ACCENT, bold = true)).append(' ')
            append(border(horizontal.repeat(fill)))
            if (metaText != null) {
                append(' ').append(theme.style(metaText, fg = ThemeColor.DIM)).append(' ')
                append(border(horizontal))
            }
            append(border(glyphs.topRight))
        }
    }

    private fun effectivePadding(width: Int, padding: Int): Int = minOf(padding, (width - 2) / 2).coerceAtLeast(0)
}

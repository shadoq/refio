package pl.jclab.refio.ui.components.chat.toolcall

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A collapsed tool call must stay on one line and still say what happened. The rules under test:
 * a real diff is only offered when the "before" side can actually be reconstructed, an edit
 * advertises its size, and a long path is elided in the middle so the file name survives.
 */
class ToolCallRowViewTest {

    private fun view(
        snapshotId: String? = null,
        filePath: String? = null,
        added: Int? = null,
        removed: Int? = null,
        durationMs: Long? = null
    ) = ToolCallRowView(
        messageId = "m1",
        name = "advance_code_editing",
        subtitle = filePath,
        state = ToolCallRowView.State.OK,
        added = added,
        removed = removed,
        durationMs = durationMs,
        output = "done",
        snapshotId = snapshotId,
        filePath = filePath
    )

    @Test
    fun `diff is offered only when both snapshot and file are known`() {
        assertTrue(view(snapshotId = "snap-1", filePath = "src/discount.py").canDiff)
        assertFalse(view(snapshotId = "snap-1", filePath = null).canDiff)
        assertFalse(view(snapshotId = null, filePath = "src/discount.py").canDiff)
        assertFalse(view(snapshotId = "", filePath = "src/discount.py").canDiff)
    }

    @Test
    fun `an edit reports its size, a read reports none`() {
        assertEquals("+1 −1", view(added = 1, removed = 1).diffText)
        assertEquals("+12 −0", view(added = 12, removed = 0).diffText)
        assertNull(view().diffText)
    }

    @Test
    fun `duration is omitted when the engine reported none`() {
        assertNull(view().durationText)
        assertNull(view(durationMs = 0).durationText)
        assertEquals("22ms", view(durationMs = 22).durationText)
    }

    @Test
    fun `duration switches unit so a slow tool does not print five digits`() {
        assertEquals("950ms", ToolCallRowView.formatDuration(950))
        assertEquals("1,5s", ToolCallRowView.formatDuration(1_500).replace('.', ','))
        assertEquals("2m 5s", ToolCallRowView.formatDuration(125_000))
    }

    @Test
    fun `long paths keep the file name that identifies them`() {
        val shortened = ToolCallRowView.shortenPath("src/main/kotlin/pl/jclab/refio/core/tools/ReadFileTool.kt", 34)

        assertTrue(shortened.length <= 34, "got '$shortened'")
        assertTrue(shortened.endsWith("ReadFileTool.kt"))
        assertTrue(shortened.startsWith("src/"))
    }

    @Test
    fun `short paths are left alone and backslashes are normalised`() {
        assertEquals("src/app.py", ToolCallRowView.shortenPath("src/app.py"))
        assertEquals("src/app.py", ToolCallRowView.shortenPath("src\\app.py"))
    }
}

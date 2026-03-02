package pl.jclab.refio.ui.components.chat.bubble

import com.intellij.ui.components.JBPanel
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.Color
import javax.swing.BorderFactory

/**
 * Flat message block panel (no rounded corners, matching landing page design).
 * Used for .tool-card and .tool-message style blocks.
 */
internal class FlatMessageBlock(backgroundColor: Color) : JBPanel<FlatMessageBlock>() {

    init {
        background = backgroundColor
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, LCATheme.subtleSeparatorColor),
            LCATheme.paddedBorder(4, 8, 4, 8)
        )
    }
}

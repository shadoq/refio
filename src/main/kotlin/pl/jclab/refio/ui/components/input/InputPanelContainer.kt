package pl.jclab.refio.ui.components.input

import com.intellij.ui.components.JBPanel
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

class InputPanelContainer(
    snippetsContainer: JComponent,
    contextTagsPanel: JComponent,
    editorComponent: JComponent,
) : JBPanel<InputPanelContainer>(BorderLayout()) {

    init {
        isOpaque = false
        border = LCATheme.paddedBorder(6)

        val topPanel = JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout()
            isOpaque = false
            add(snippetsContainer, BorderLayout.NORTH)
            add(contextTagsPanel, BorderLayout.CENTER)
        }

        add(topPanel, BorderLayout.NORTH)
        add(editorComponent, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val arc = LCATheme.bubbleRadius
        g2.color = LCATheme.editorBackground
        g2.fillRoundRect(0, 0, width, height, arc, arc)

        g2.dispose()
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val arc = LCATheme.bubbleRadius
        val shape = RoundRectangle2D.Double(
            0.5,
            0.5,
            (width - 1).toDouble(),
            (height - 1).toDouble(),
            arc.toDouble(),
            arc.toDouble(),
        )

        g2.color = LCATheme.headerInactiveColor
        g2.draw(shape)
        g2.dispose()
    }
}


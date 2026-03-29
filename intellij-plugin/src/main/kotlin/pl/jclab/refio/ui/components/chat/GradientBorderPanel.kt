package pl.jclab.refio.ui.components.chat

import com.intellij.ui.components.JBPanel
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Timer

class GradientBorderPanel : JBPanel<GradientBorderPanel>() {

    private var animationPhase = 0f
    private var timer: Timer? = null

    var isLoading: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                startTimer()
            } else {
                stopTimer()
            }
            repaint()
        }

    private fun startTimer() {
        if (timer != null) return
        timer = Timer(50) {
            animationPhase = (animationPhase + 0.05f) % 1f
            repaint()
        }.apply { start() }
    }

    private fun stopTimer() {
        timer?.stop()
        timer = null
        animationPhase = 0f
    }

    override fun paintBorder(g: Graphics) {
        if (!isLoading) {
            super.paintBorder(g)
            return
        }

        val g2 = (g as Graphics2D).create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val base = LCATheme.borderColor
            val accent1 = LCATheme.codeBlockHighlight1
            val accent2 = LCATheme.codeBlockHighlight2

            val alpha1 = (80 + 120 * animationPhase).toInt().coerceIn(0, 255)
            val alpha2 = (80 + 120 * (1f - animationPhase)).toInt().coerceIn(0, 255)

            val gradient = java.awt.LinearGradientPaint(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                floatArrayOf(0f, 0.5f, 1f),
                arrayOf(
                    java.awt.Color(accent1.red, accent1.green, accent1.blue, alpha1),
                    java.awt.Color(accent2.red, accent2.green, accent2.blue, 200),
                    java.awt.Color(base.red, base.green, base.blue, alpha2)
                )
            )

            g2.paint = gradient
            val arc = LCATheme.bubbleRadius.toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }
}

package pl.jclab.refio.ui.rail

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.jclab.refio.ui.RefioScreen
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Vertical icon strip that replaces the horizontal tab row. One toggle button per
 * [RefioScreen]; the panel body is a CardLayout owned by the caller.
 *
 * Screens are supplied lazily so the rail always reflects the current "Advanced View" state
 * without the owner having to rebuild it.
 */
class RefioRail(
    private val screens: () -> List<RefioScreen>,
    private val onSelect: (RefioScreen) -> Unit
) : JPanel(BorderLayout()) {

    var selected: RefioScreen = RefioScreen.CHAT
        private set

    private val badges = mutableSetOf<RefioScreen>()

    private val actions: Map<RefioScreen, AnAction> = RefioScreen.entries.associateWith { screen ->
        object : ToggleAction(screen.title, screen.title, null), DumbAware {
            override fun isSelected(e: AnActionEvent): Boolean = selected == screen

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                select(screen)
            }

            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.icon = iconFor(screen)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
    }

    private val group = object : ActionGroup(), DumbAware {
        override fun getChildren(e: AnActionEvent?): Array<AnAction> =
            screens().mapNotNull { actions[it] }.toTypedArray()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private val toolbar = ActionManager.getInstance()
        .createActionToolbar("Refio.Rail", group, false).apply {
            targetComponent = this@RefioRail
            setMinimumButtonSize(JBUI.size(BUTTON_SIZE))
        }

    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.customLineRight(JBColor.border())
        add(toolbar.component, BorderLayout.NORTH)
        registerShortcuts()
    }

    /**
     * Alt+1..8 keeps the keyboard navigation the tab strip used to provide. Shortcuts are bound
     * to the rail itself, so they work while any screen has focus inside the tool window.
     */
    private fun registerShortcuts() {
        RefioScreen.entries.forEachIndexed { index, screen ->
            if (index >= 9) return@forEachIndexed
            val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_1 + index, InputEvent.ALT_DOWN_MASK)
            actions[screen]?.registerCustomShortcutSet(CustomShortcutSet(keyStroke), this)
        }
    }

    fun select(screen: RefioScreen) {
        if (selected == screen) return
        selected = screen
        badges.remove(screen)
        onSelect(screen)
        toolbar.updateActionsAsync()
    }

    /** Rebuilds the buttons, e.g. after the set of visible screens changed. */
    fun refresh() {
        toolbar.updateActionsAsync()
    }

    /** Marks a background screen as having new content; cleared when the user opens it. */
    fun setBadge(screen: RefioScreen, on: Boolean) {
        if (screen == selected) return
        val changed = if (on) badges.add(screen) else badges.remove(screen)
        if (changed) toolbar.updateActionsAsync()
    }

    fun setCompact(compact: Boolean) {
        toolbar.setMinimumButtonSize(JBUI.size(if (compact) COMPACT_BUTTON_SIZE else BUTTON_SIZE))
        toolbar.updateActionsAsync()
        revalidate()
        repaint()
    }

    private fun iconFor(screen: RefioScreen): Icon =
        if (screen in badges) BadgedIcon(screen.icon) else screen.icon

    /**
     * Base icon with a small accent dot in the bottom-right corner. Drawn here rather than
     * through a platform helper so the marker stays identical across icon sets.
     */
    private class BadgedIcon(private val base: Icon) : Icon {

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            base.paintIcon(c, g, x, y)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val d = JBUI.scale(5)
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                g2.fillOval(x + base.iconWidth - d, y + base.iconHeight - d, d, d)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = base.iconWidth

        override fun getIconHeight(): Int = base.iconHeight
    }

    private companion object {
        const val BUTTON_SIZE = 26
        const val COMPACT_BUTTON_SIZE = 22
    }
}

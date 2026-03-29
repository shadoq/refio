package pl.jclab.refio.ui.components.autocomplete

import com.intellij.openapi.editor.Editor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Autocomplete popup shown above prompt input.
 *
 * Handles:
 * - Filtering items by prefix
 * - Keyboard navigation (Up/Down/Enter/Esc)
 * - Mouse selection
 * - Positioning above input field
 */
class AutocompletePopup<T : AutocompleteItem>(
    private val onSelect: (T) -> Unit
) {
    private val popup = JWindow()
    private val listModel = DefaultListModel<T>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = AutocompleteRenderer()

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        selectItem(index)
                    }
                }
            }
        })
    }

    private var currentPrefix = ""
    private var allItems = listOf<T>()
    private var editor: Editor? = null

    init {
        popup.contentPane = JBScrollPane(list).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LCATheme.grayColor),
                LCATheme.paddedBorder(4)
            )
            preferredSize = Dimension(400, 200)
        }
        popup.focusableWindowState = false
    }

    fun attach(editor: Editor) {
        if (this.editor === editor) return
        this.editor = editor

        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!popup.isVisible) return

                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        e.consume()
                        if (listModel.size() <= 0) return
                        val newIndex = (list.selectedIndex + 1) % listModel.size()
                        list.selectedIndex = newIndex
                        list.ensureIndexIsVisible(newIndex)
                    }

                    KeyEvent.VK_UP -> {
                        e.consume()
                        if (listModel.size() <= 0) return
                        val newIndex = if (list.selectedIndex <= 0) {
                            listModel.size() - 1
                        } else {
                            list.selectedIndex - 1
                        }
                        list.selectedIndex = newIndex
                        list.ensureIndexIsVisible(newIndex)
                    }

                    KeyEvent.VK_ENTER -> {
                        if (list.selectedIndex >= 0) {
                            e.consume()
                            selectItem(list.selectedIndex)
                        }
                    }

                    KeyEvent.VK_ESCAPE -> {
                        e.consume()
                        hide()
                    }
                }
            }
        })
    }

    /**
     * Show popup with filtered items
     */
    fun show(items: List<T>, prefix: String) {
        allItems = items
        currentPrefix = prefix

        // Filter items by prefix
        val filtered = items.filter { item ->
            item.matchesPrefix(prefix)
        }.sortedBy { it.getSortKey() }

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

        if (listModel.isEmpty) {
            hide()
            return
        }

        // Select first item
        list.selectedIndex = 0

        // Pack first to get correct dimensions
        popup.pack()

        val editor = requireNotNull(editor) { "AutocompletePopup is not attached to an editor yet" }

        val inputLocation = editor.contentComponent.locationOnScreen
        val caretVisualPosition = editor.caretModel.visualPosition
        val caretPointInEditor = editor.visualPositionToXY(caretVisualPosition)

        val x = inputLocation.x + caretPointInEditor.x
        // Position ABOVE input field with gap, ensure not off-screen
        val proposedY = inputLocation.y - popup.height - 5
        val y = if (proposedY < 0) {
            // If would be off-screen top, show below input instead
            inputLocation.y + editor.contentComponent.height + 5
        } else {
            proposedY
        }

        popup.location = Point(x, y)
        popup.isVisible = true
    }

    /**
     * Update filter with new prefix
     */
    fun updateFilter(newPrefix: String) {
        if (newPrefix == currentPrefix) return
        show(allItems, newPrefix)
    }

    /**
     * Hide popup
     */
    fun hide() {
        popup.isVisible = false
        listModel.clear()
    }

    /**
     * Check if popup is visible
     */
    fun isVisible(): Boolean = popup.isVisible

    /**
     * Select item at index
     */
    private fun selectItem(index: Int) {
        val item = listModel.getElementAt(index)
        hide()
        onSelect(item)
    }

    /**
     * Custom renderer for autocomplete items
     */
    private class AutocompleteRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            if (value is AutocompleteItem) {
                val icon = value.getIcon()
                val iconPrefix = if (icon.isNotEmpty()) "$icon " else ""
                text = "<html>$iconPrefix<b>${value.getDisplayName()}</b> - ${value.getDescription()}</html>"
            }

            return component
        }
    }
}

/**
 * Interface for autocomplete items
 */
interface AutocompleteItem {
    fun getDisplayName(): String
    fun getDescription(): String
    fun matchesPrefix(prefix: String): Boolean
    fun getSortKey(): String = getDisplayName()
    fun getIcon(): String = ""  // Optional icon (emoji or symbol)
}

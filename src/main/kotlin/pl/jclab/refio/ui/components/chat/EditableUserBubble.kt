package pl.jclab.refio.ui.components.chat

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBPanel
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

class EditableUserBubble(
    private val project: Project,
    private val initialText: String,
    private val contentComponent: JPanel,
    private val onSubmit: (String) -> Unit
) : JBPanel<EditableUserBubble>(BorderLayout()) {

    private var isEditing = false

    private val editor = EditorTextField(project, PlainTextFileType.INSTANCE).apply {
        text = initialText
        setOneLineMode(false)
        border = LCATheme.compoundBorder(
            LCATheme.customLineBorder(LCATheme.borderColor, 1),
            LCATheme.paddedBorder(6)
        )
    }

    private val editorActions = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
    }

    private val applyButton = JButton("Apply").apply {
        toolTipText = "Apply edited prompt (rewinds conversation)"
        addActionListener { finishEditing(submit = true) }
    }

    private val cancelButton = JButton("Cancel").apply {
        toolTipText = "Cancel editing"
        addActionListener { finishEditing(submit = false) }
    }

    init {
        isOpaque = false

        add(contentComponent, BorderLayout.CENTER)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    startEditing()
                }
            }
        })
    }

    fun beginEditing() {
        startEditing()
    }

    private fun startEditing() {
        if (isEditing) return
        isEditing = true

        editor.text = initialText

        removeAll()

        val editPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
        }

        editorActions.removeAll()
        editorActions.add(cancelButton)
        editorActions.add(applyButton)

        editPanel.add(editor, BorderLayout.CENTER)
        editPanel.add(editorActions, BorderLayout.SOUTH)
        add(editPanel, BorderLayout.CENTER)

        revalidate()
        repaint()

        SwingUtilities.invokeLater { editor.requestFocusInWindow() }
    }

    private fun finishEditing(submit: Boolean) {
        val newText = editor.text.trim()
        isEditing = false

        removeAll()

        add(contentComponent, BorderLayout.CENTER)
        revalidate()
        repaint()

        if (submit && newText.isNotBlank() && newText != initialText.trim()) {
            onSubmit(newText)
        }
    }
}

package pl.jclab.refio.ui.components.common

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Reusable dialog for getting multiline prompt input from user.
 *
 * Used for:
 * - Add Steps: prompt for new steps to append
 * - Re-plan: prompt for new plan to replace pending steps
 */
class PromptDialog(
    private val title: String,
    private val label: String,
    private val defaultText: String = ""
) : DialogWrapper(true) {

    private val textArea = JTextArea(8, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        text = defaultText
    }

    var promptText: String = defaultText
        private set

    init {
        init()
        setTitle(title)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(500, 200)

        // Scroll pane with text area
        val scrollPane = JBScrollPane(textArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun doOKAction() {
        promptText = textArea.text.trim()
        super.doOKAction()
    }

    companion object {
        /**
         * Show dialog and return entered text, or null if canceled.
         *
         * @param title Dialog window title
         * @param label Label text above input (not used in current impl)
         * @param defaultText Default text in input area
         * @return Entered text if OK clicked, null if canceled
         */
        fun showAndGet(
            title: String,
            label: String = "Enter prompt:",
            defaultText: String = ""
        ): String? {
            val dialog = PromptDialog(title, label, defaultText)
            return if (dialog.showAndGet()) {
                val text = dialog.promptText
                if (text.isBlank()) null else text
            } else {
                null
            }
        }
    }
}

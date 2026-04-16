package pl.jclab.refio.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.api.PromptDto
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane

/**
 * Dialog for adding/editing a slash prompt
 */
class PromptEditDialog(
    project: Project?,
    private val existingPrompt: PromptDto? = null
) : DialogWrapper(project) {

    private val nameField = JBTextField(20)
    private val descriptionField = JBTextField(40)
    private val contentArea = JBTextArea(10, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val enabledCheckbox = JBCheckBox("Enabled", true)

    init {
        title = if (existingPrompt != null) "Edit Prompt" else "Add Prompt"
        init()

        // Load existing data
        existingPrompt?.let {
            nameField.text = it.name
            descriptionField.text = it.description ?: ""
            contentArea.text = it.content
            enabledCheckbox.isSelected = it.isEnabled
        }
    }

    override fun createCenterPanel(): JComponent {
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = LCATheme.insetsSmall
            }

            // Name
            add(JLabel("Prompt Name (with /):"), gbc)
            gbc.gridy++
            add(nameField, gbc)

            // Description
            gbc.gridy++
            gbc.insets = LCATheme.insetsGridBagDefault
            add(JLabel("Description:"), gbc)

            gbc.gridy++
            gbc.insets = LCATheme.insetsSmall
            add(descriptionField, gbc)

            // Content
            gbc.gridy++
            gbc.insets = LCATheme.insetsGridBagDefault
            add(JLabel("Prompt Content:"), gbc)

            gbc.gridy++
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            gbc.insets = LCATheme.insetsSmall
            add(JScrollPane(contentArea).apply {
                preferredSize = Dimension(500, 200)
            }, gbc)

            // Enabled
            gbc.gridy++
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = LCATheme.insetsGridBagDefault
            add(enabledCheckbox, gbc)

            // Info
            gbc.gridy++
            add(JLabel("<html><font color='gray'>" +
                "Prompts can be used in the prompt input by typing the prompt name" +
                "</font></html>"), gbc)
        }
    }

    fun getPromptName(): String {
        val name = nameField.text.trim()
        return if (name.startsWith("/")) name else "/$name"
    }

    fun getDescription(): String = descriptionField.text.trim()
    fun getContent(): String = contentArea.text.trim()
    fun isEnabled(): Boolean = enabledCheckbox.isSelected

    override fun doValidate(): ValidationInfo? {
        if (getPromptName().length <= 1) {  // only "/"
            return ValidationInfo("Prompt name cannot be empty", nameField)
        }
        if (getContent().isEmpty()) {
            return ValidationInfo("Content cannot be empty", contentArea)
        }
        return null
    }
}

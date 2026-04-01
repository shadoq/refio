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
 * Dialog for adding/editing rules
 */
class RuleEditDialog(
    project: Project?,
    private val existingRule: PromptDto? = null
) : DialogWrapper(project) {

    private val nameField = JBTextField(20)
    private val contentArea = JBTextArea(10, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val enabledCheckbox = JBCheckBox("Enabled", true)

    init {
        title = if (existingRule != null) "Edit Rule" else "Add Rule"
        init()

        // Load existing data
        existingRule?.let {
            nameField.text = it.name
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
                insets = LCATheme.insetsSmall
            }

            // Name
            add(JLabel("Rule Name:"), gbc)
            gbc.gridy++
            gbc.weightx = 1.0
            add(nameField, gbc)

            // Content
            gbc.gridy++
            gbc.weightx = 0.0
            gbc.insets = LCATheme.insetsGridBagDefault
            add(JLabel("Content:"), gbc)

            gbc.gridy++
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            gbc.insets = LCATheme.insetsSmall
            add(JScrollPane(contentArea).apply {
                preferredSize = Dimension(400, 200)
            }, gbc)

            // Enabled
            gbc.gridy++
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = LCATheme.insetsGridBagDefault
            add(enabledCheckbox, gbc)
        }
    }

    fun getRuleName(): String = nameField.text.trim()
    fun getContent(): String = contentArea.text.trim()
    fun isEnabled(): Boolean = enabledCheckbox.isSelected

    override fun doValidate(): ValidationInfo? {
        if (getRuleName().isEmpty()) {
            return ValidationInfo("Rule name cannot be empty", nameField)
        }
        if (getContent().isEmpty()) {
            return ValidationInfo("Content cannot be empty", contentArea)
        }
        return null
    }
}

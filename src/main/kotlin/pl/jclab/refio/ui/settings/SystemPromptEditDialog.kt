package pl.jclab.refio.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.api.PromptDto
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane

/**
 * Dialog for editing system prompt
 *
 * @param prompt Prompt to edit
 * @param defaultContent Default prompt content (read-only)
 */
class SystemPromptEditDialog(
    project: Project?,
    private val prompt: PromptDto,
    private val defaultContent: String
) : DialogWrapper(project) {

    private val customContentArea = JBTextArea(10, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val useDefaultCheckbox = JBCheckBox("Use Default Prompt", !prompt.isCustom)

    init {
        title = "Edit System Prompt: ${prompt.name}"
        init()

        // Load data
        customContentArea.text = prompt.content
        customContentArea.isEnabled = !useDefaultCheckbox.isSelected

        // Listener dla checkbox
        useDefaultCheckbox.addItemListener { event ->
            val useDefault = event.stateChange == ItemEvent.SELECTED
            customContentArea.isEnabled = !useDefault
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

            // Use Default checkbox
            add(useDefaultCheckbox, gbc)

            // Custom Content
            gbc.gridy++
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = LCATheme.insetsGridBagDefault
            add(JLabel("Custom Content:"), gbc)

            gbc.gridy++
            gbc.weighty = 0.6
            gbc.fill = GridBagConstraints.BOTH
            gbc.insets = LCATheme.insetsSmall
            add(JScrollPane(customContentArea).apply {
                preferredSize = Dimension(500, 250)
            }, gbc)

            // Info
            gbc.gridy++
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = LCATheme.insetsGridBagDefault
            add(JLabel("<html><font color='gray'>" +
                "Custom content overrides default when 'Use Default' is unchecked" +
                "</font></html>"), gbc)
        }
    }

    fun useDefault(): Boolean = useDefaultCheckbox.isSelected
    fun getCustomContent(): String = customContentArea.text.trim()

    override fun doValidate(): ValidationInfo? {
        if (!useDefault() && getCustomContent().isEmpty()) {
            return ValidationInfo("Custom content cannot be empty when not using default", customContentArea)
        }
        return null
    }
}

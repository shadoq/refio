package pl.jclab.refio.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.Gray
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
 * Dialog for editing built-in prompts (prompts defined in plugin code)
 *
 * @param prompt Prompt to edit
 * @param defaultContent Default content from plugin code (read-only)
 */
class BuiltinPromptEditDialog(
    project: Project?,
    private val prompt: PromptDto,
    private val defaultContent: String
) : DialogWrapper(project) {

    private val defaultContentArea = JBTextArea(10, 60).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = Gray._245
        text = defaultContent
    }

    private val customContentArea = JBTextArea(15, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        text = if (prompt.isCustom) prompt.content else defaultContent
    }

    private val useDefaultCheckbox = JBCheckBox("Use Default from Plugin", !prompt.isCustom)

    init {
        title = "Edit Prompt: ${prompt.name}"
        init()

        // Enable/disable custom content based on checkbox
        customContentArea.isEnabled = prompt.isCustom

        // Listener for checkbox
        useDefaultCheckbox.addItemListener { event ->
            val useDefault = event.stateChange == ItemEvent.SELECTED
            customContentArea.isEnabled = !useDefault

            // When switching to "use default", populate with default content
            if (useDefault && customContentArea.text.trim() != defaultContent.trim()) {
                customContentArea.text = defaultContent
            }
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

            // Info label
            gbc.gridwidth = 2
            add(JLabel("<html><b>Prompt:</b> ${prompt.name} (${prompt.type})</html>"), gbc)

            // Use Default checkbox
            gbc.gridy++
            gbc.insets = LCATheme.insetsGridBagDefault
            add(useDefaultCheckbox, gbc)

            // Default Content (read-only)
            gbc.gridy++
            gbc.insets = LCATheme.insetsGridBagDefault
            add(JLabel("Default Content (from plugin code):"), gbc)

            gbc.gridy++
            gbc.weighty = 0.35
            gbc.fill = GridBagConstraints.BOTH
            gbc.insets = LCATheme.insetsSmall
            add(JScrollPane(defaultContentArea).apply {
                preferredSize = Dimension(700, 200)
            }, gbc)

            // Custom Content
            gbc.gridy++
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = LCATheme.insetsGridBagDefault
            add(JLabel("Custom Content (editable):"), gbc)

            gbc.gridy++
            gbc.weighty = 0.65
            gbc.fill = GridBagConstraints.BOTH
            gbc.insets = LCATheme.insetsSmall
            add(JScrollPane(customContentArea).apply {
                preferredSize = Dimension(700, 350)
            }, gbc)

            // Info
            gbc.gridy++
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = LCATheme.insetsGridBagDefault
            add(JLabel("<html><font color='gray'>" +
                "When 'Use Default' is checked, the plugin will use the default content from code.<br>" +
                "Uncheck to save custom content to database." +
                "</font></html>"), gbc)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return customContentArea
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

package pl.jclab.refio.ui.dialogs

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import pl.jclab.refio.core.tools.PathSandbox
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Paths
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

class LLMPromptViewerDialog(
    private val project: Project,
    private val prompt: String
) : DialogWrapper(project) {

    private var editor: EditorEx? = null

    init {
        title = "LLM Context Prompt"
        setResizable(true)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.preferredSize = Dimension(900, 600)
        panel.border = JBUI.Borders.empty(8)

        val normalizedPrompt = StringUtil.convertLineSeparators(prompt)
        val document = EditorFactory.getInstance().createDocument(normalizedPrompt)
        val editorInstance = EditorFactory.getInstance()
            .createEditor(document, project) as EditorEx
        editorInstance.isViewer = true
        editorInstance.settings.apply {
            isLineNumbersShown = true
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isCaretRowShown = false
            isUseSoftWraps = true
        }
        editor = editorInstance
        panel.add(editorInstance.component, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        val copyAction = object : DialogWrapperAction("Copy") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                copyPrompt()
            }
        }
        val saveAction = object : DialogWrapperAction("Save") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                savePrompt()
            }
        }
        return arrayOf(copyAction, saveAction, okAction)
    }

    override fun dispose() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
        super.dispose()
    }

    private fun copyPrompt() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(prompt), null)
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to copy prompt: ${e.message}",
                "Copy LLM Prompt"
            )
        }
    }

    private fun savePrompt() {
        try {
            val fileChooser = JFileChooser().apply {
                dialogTitle = "Save LLM Context Prompt"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                addChoosableFileFilter(FileNameExtensionFilter("Text files (*.txt)", "txt"))
                addChoosableFileFilter(FileNameExtensionFilter("Markdown files (*.md)", "md"))
                addChoosableFileFilter(FileNameExtensionFilter("All files (*.*)", "*"))
                fileFilter = choosableFileFilters[0]
                selectedFile = File("llm_context_prompt_${System.currentTimeMillis()}.txt")
            }

            val result = fileChooser.showSaveDialog(null)
            if (result != JFileChooser.APPROVE_OPTION) {
                return
            }

            val file = fileChooser.selectedFile
            val finalFile = if (!file.name.contains(".")) {
                val selectedFilter = fileChooser.fileFilter as? FileNameExtensionFilter
                val extension = selectedFilter?.extensions?.firstOrNull() ?: "txt"
                File(file.parentFile, "${file.name}.$extension")
            } else {
                file
            }

            val projectRoot = project.basePath?.let { Paths.get(it) }
            if (projectRoot == null) {
                Messages.showErrorDialog(
                    project,
                    "Project root not found. Cannot save outside sandbox.",
                    "Save LLM Prompt"
                )
                return
            }

            val sandbox = PathSandbox(projectRoot)
            try {
                sandbox.validatePath(finalFile.toPath())
            } catch (e: SecurityException) {
                Messages.showErrorDialog(
                    project,
                    "Selected path is outside the project sandbox.",
                    "Save LLM Prompt"
                )
                return
            }

            finalFile.writeText(prompt)

            Messages.showInfoMessage(
                project,
                "LLM Context Prompt saved to:\n${finalFile.absolutePath}\n\nSize: ${prompt.length} characters",
                "Save LLM Prompt"
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to save prompt: ${e.message}",
                "Save LLM Prompt"
            )
        }
    }
}

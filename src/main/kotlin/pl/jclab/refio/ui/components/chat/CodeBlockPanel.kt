package pl.jclab.refio.ui.components.chat

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Panel for displaying code block with action buttons.
 *
 * Layout:
 * ┌────────────────────────────────────────────────────┐
 * │ filepath.kt        [📋 Copy] [📝 Insert] [📄 Create] │
 * ├────────────────────────────────────────────────────┤
 * │ class Service {                                    │
 * │     fun method() { }                               │
 * │ }                                                  │
 * └────────────────────────────────────────────────────┘
 */
class CodeBlockPanel(
    private val codeBlock: CodeBlock, private val project: Project
) : JBPanel<CodeBlockPanel>(BorderLayout()) {

    private val maxLinesCollapsed = 25
    private var isExpanded = false
    private lateinit var codeScrollPane: JBScrollPane
    private var editor: EditorEx? = null

    init {
        // Header with file path and action buttons
        val headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)

        // Code content
        val codePanel = createCodePanel()
        add(codePanel, BorderLayout.CENTER)

        // Footer with expand/collapse button (if needed)
        val linesCount = codeBlock.content.lines().size
        if (linesCount > maxLinesCollapsed) {
            val footerPanel = createFooterPanel()
            add(footerPanel, BorderLayout.SOUTH)
        }

        // Set initial maximum size on the panel itself (not just scroll pane)
        updatePanelSize(isExpanded = false)
    }

    private fun createHeaderPanel(): JBPanel<*> {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(4, 8)
            background = LCATheme.editorBackground
            foreground = LCATheme.editorForeground
        }

        val leftPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
        }

        leftPanel.add(JLabel(codeBlock.language).apply {
            foreground = LCATheme.descriptionForeground
            font = font.deriveFont(Font.BOLD, 11f)
        })

        // File path label (if present) - clickable to open file
        if (codeBlock.filePath != null) {
            leftPanel.add(JLabel(codeBlock.filePath).apply {
                foreground = LCATheme.codeBlockHighlight1
                font = font.deriveFont(Font.PLAIN, 11f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Click to open file in editor"

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        openFileInEditor()
                    }

                    override fun mouseEntered(e: java.awt.event.MouseEvent) {
                        foreground = LCATheme.codeBlockHighlight2
                    }

                    override fun mouseExited(e: java.awt.event.MouseEvent) {
                        foreground = LCATheme.codeBlockHighlight1
                    }
                })
            })
        }

        panel.add(leftPanel, BorderLayout.WEST)

        // Action buttons (icon-only, right-aligned)
        val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false

            // Insert at cursor button (only if file path provided)
            if (codeBlock.filePath != null) {
                add(createIconButton("📄", "Create new file with this code") {
                    createFile()
                })

                add(createIconButton("🆚", "Compare with existing file") {
                    compareWithFile()
                })

                add(createIconButton("📝", "Insert code at cursor position") {
                    insertAtCursor()
                })
            }

            // Copy button (always visible)
            add(createIconButton("📋", "Copy code to clipboard") {
                copyToClipboard()
            })
        }
        panel.add(buttonsPanel, BorderLayout.EAST)

        return panel
    }

    private fun createCodePanel(): JBScrollPane {
        val normalizedContent = codeBlock.content.replace("\r\n", "\n").replace("\r", "\n")

        val document = EditorFactory.getInstance().createDocument(normalizedContent)
        val fileType = resolveFileType()
        val createdEditor = EditorFactory.getInstance().createEditor(
            document,
            project,
            fileType,
            true
        ) as EditorEx
        editor = createdEditor
        createdEditor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)

        createdEditor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isCaretRowShown = false
            isWhitespacesShown = false
            isUseSoftWraps = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        createdEditor.backgroundColor = LCATheme.editorBackground
        createdEditor.setBorder(LCATheme.paddedBorder(8))

        codeScrollPane = JBScrollPane(createdEditor.component).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            // Size will be set by updatePanelSize()
        }

        return codeScrollPane
    }

    private fun resolveFileType(): FileType {
        val extFromPath = codeBlock.filePath
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()

        val ext = extFromPath ?: when (codeBlock.language.lowercase()) {
            "kotlin", "kt" -> "kt"
            "java" -> "java"
            "python", "py" -> "py"
            "typescript", "ts" -> "ts"
            "javascript", "js" -> "js"
            "json" -> "json"
            "yaml", "yml" -> "yml"
            "xml" -> "xml"
            "html" -> "html"
            "css" -> "css"
            "sql" -> "sql"
            "markdown", "md" -> "md"
            else -> null
        }

        if (ext == null) return PlainTextFileType.INSTANCE

        val ft = FileTypeManager.getInstance().getFileTypeByExtension(ext)
        return if (ft.isBinary) PlainTextFileType.INSTANCE else ft
    }

    /**
     * Create footer with expand/collapse button.
     */
    private fun createFooterPanel(): JBPanel<*> {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(0, 8, 4, 8)
            background = LCATheme.editorBackground
            foreground = LCATheme.editorForeground
        }

        val linesCount = codeBlock.content.lines().size
        val expandButton = JButton("▼ Show more ($linesCount lines)").apply {
            toolTipText = "Expand to show full code"
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            foreground = LCATheme.codeBlockHighlight3
            font = font.deriveFont(Font.PLAIN, 11f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            horizontalAlignment = SwingConstants.CENTER

            addActionListener {
                toggleExpanded(this)
            }
        }

        panel.add(expandButton, BorderLayout.CENTER)
        return panel
    }

    /**
     * Toggle expanded/collapsed state.
     */
    private fun toggleExpanded(button: JButton) {
        isExpanded = !isExpanded

        val linesCount = codeBlock.content.lines().size

        if (isExpanded) {
            button.text = "▲ Show less"
            button.toolTipText = "Collapse to show first $maxLinesCollapsed lines"
        } else {
            button.text = "▼ Show more ($linesCount lines)"
            button.toolTipText = "Expand to show more code"

            // Scroll to top after collapse
            SwingUtilities.invokeLater {
                editor?.caretModel?.moveToOffset(0)
                editor?.scrollingModel?.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
            }
        }

        // Update sizes on both scroll pane and panel
        updatePanelSize(isExpanded)

        // Force parent to revalidate
        val parent = this.parent
        parent?.revalidate()
        parent?.repaint()
    }

    /**
     * Update panel and scroll pane sizes based on expanded state.
     */
    private fun updatePanelSize(isExpanded: Boolean) {
        val linesCount = editor?.document?.lineCount ?: codeBlock.content.lines().size
        val estimatedLineHeight = editor?.lineHeight ?: 16  // fallback for viewer
        val headerHeight = 36  // Approximate header height
        val footerHeight = if (linesCount > maxLinesCollapsed) 28 else 0  // Footer button height

        val contentHeight = if (isExpanded) {
            (estimatedLineHeight * linesCount + 64).coerceAtMost(800)
        } else if (linesCount > maxLinesCollapsed) {
            estimatedLineHeight * maxLinesCollapsed + 64
        } else {
            estimatedLineHeight * linesCount + 64
        }

        val totalHeight = headerHeight + contentHeight + footerHeight

        // Set scroll pane size
        codeScrollPane.minimumSize = Dimension(Int.MAX_VALUE, contentHeight)
        codeScrollPane.preferredSize = Dimension(Int.MAX_VALUE, contentHeight)
        codeScrollPane.maximumSize = Dimension(Int.MAX_VALUE, contentHeight)

        // CRITICAL: Set maximum size on the CodeBlockPanel itself
        // This is what ChatView's BoxLayout will respect
        minimumSize = Dimension(Int.MAX_VALUE, totalHeight)
        preferredSize = Dimension(Int.MAX_VALUE, totalHeight)
        maximumSize = Dimension(Int.MAX_VALUE, totalHeight)

        codeScrollPane.revalidate()
        codeScrollPane.repaint()
        revalidate()
        repaint()
    }

    fun disposeEditor() {
        try {
            editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        } finally {
            editor = null
        }
    }

    /**
     * Create icon-only button with tooltip.
     */
    private fun createIconButton(
        icon: String, tooltip: String, action: () -> Unit
    ): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(28, 28)
            font = font.deriveFont(14f)
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            border = LCATheme.paddedBorder(4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = LCATheme.codeBlockComment
            addActionListener {
                try {
                    action()
                    showNotification("Success", "$tooltip completed")
                } catch (e: Exception) {
                    showNotification("Error", e.message ?: "Operation failed", NotificationType.ERROR)
                }
            }
        }
    }

    /**
     * Copy code to clipboard.
     */
    private fun copyToClipboard() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val stringSelection = StringSelection(codeBlock.content)
        clipboard.setContents(stringSelection, null)
    }

    /**
     * Insert code at current cursor position in active editor.
     */
    private fun insertAtCursor() {
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                showNotification("Error", "No active editor", NotificationType.WARNING)
                return@invokeLater
            }

            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val caretOffset = editor.caretModel.offset
                document.insertString(caretOffset, codeBlock.content)

                // Move caret to end of inserted text
                editor.caretModel.moveToOffset(caretOffset + codeBlock.content.length)
            }
        }
    }

    /**
     * Create new file with code content.
     */
    private fun createFile() {
        if (codeBlock.filePath == null) {
            showNotification("Error", "No file path specified", NotificationType.WARNING)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            // Get project base path
            val basePath = project.basePath ?: run {
                showNotification("Error", "Project path not found", NotificationType.ERROR)
                return@invokeLater
            }

            val targetPath = Paths.get(basePath, codeBlock.filePath)

            // Check if file exists
            if (Files.exists(targetPath)) {
                val result = Messages.showYesNoDialog(
                    project,
                    "File '${codeBlock.filePath}' already exists. Overwrite?",
                    "File Exists",
                    Messages.getQuestionIcon()
                )

                if (result != Messages.YES) {
                    return@invokeLater
                }
            }

            // Create parent directories
            Files.createDirectories(targetPath.parent)

            // Write file
            WriteCommandAction.runWriteCommandAction(project) {
                Files.writeString(targetPath, codeBlock.content, StandardCharsets.UTF_8)
            }

            // Refresh file system
            VirtualFileManager.getInstance().refreshAndFindFileByNioPath(targetPath)?.let { vFile ->
                // Open file in editor
                FileEditorManager.getInstance(project).openFile(vFile, true)
            }

            showNotification("Success", "File created: ${codeBlock.filePath}")
        }
    }

    /**
     * Open file in editor (when clicking on filename).
     */
    private fun openFileInEditor() {
        if (codeBlock.filePath == null) return

        ApplicationManager.getApplication().invokeLater {
            val basePath = project.basePath ?: run {
                showNotification("Error", "Project path not found", NotificationType.ERROR)
                return@invokeLater
            }

            val targetPath = Paths.get(basePath, codeBlock.filePath)

            if (!Files.exists(targetPath)) {
                showNotification("Error", "File not found: ${codeBlock.filePath}", NotificationType.WARNING)
                return@invokeLater
            }

            // Open file in editor
            VirtualFileManager.getInstance().refreshAndFindFileByNioPath(targetPath)?.let { vFile ->
                FileEditorManager.getInstance(project).openFile(vFile, true)
            }
        }
    }

    /**
     * Compare code block content with existing file using IDE's diff viewer.
     */
    private fun compareWithFile() {
        if (codeBlock.filePath == null) return

        ApplicationManager.getApplication().invokeLater {
            val basePath = project.basePath ?: run {
                showNotification("Error", "Project path not found", NotificationType.ERROR)
                return@invokeLater
            }

            val targetPath = Paths.get(basePath, codeBlock.filePath)

            if (!Files.exists(targetPath)) {
                showNotification(
                    "Info", "File doesn't exist yet. Create it first to compare.", NotificationType.INFORMATION
                )
                return@invokeLater
            }

            // Create temporary file with code block content
            val tempFile = Files.createTempFile("refio_", "_${codeBlock.filePath.substringAfterLast('/')}")
            Files.writeString(tempFile, codeBlock.content, StandardCharsets.UTF_8)

            // Get virtual files
            val existingVFile = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(targetPath)
            val tempVFile = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(tempFile)

            if (existingVFile == null || tempVFile == null) {
                showNotification("Error", "Could not open files for comparison", NotificationType.ERROR)
                return@invokeLater
            }

            // Open diff viewer
            val diffManager = com.intellij.diff.DiffManager.getInstance()
            val fileDocumentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()

            // Get documents from virtual files
            val existingDoc = fileDocumentManager.getDocument(existingVFile)
            val tempDoc = fileDocumentManager.getDocument(tempVFile)

            if (existingDoc == null || tempDoc == null) {
                showNotification("Error", "Could not load documents for comparison", NotificationType.ERROR)
                return@invokeLater
            }

            val diffRequest = com.intellij.diff.requests.SimpleDiffRequest(
                "Compare: ${codeBlock.filePath}",
                com.intellij.diff.contents.DocumentContentImpl(existingDoc),
                com.intellij.diff.contents.DocumentContentImpl(tempDoc),
                "Existing File",
                "AI Generated"
            )

            diffManager.showDiff(project, diffRequest)

            // Clean up temp file after a delay
            Thread {
                Thread.sleep(1000)
                Files.deleteIfExists(tempFile)
            }.start()
        }
    }

    private fun showNotification(
        title: String, content: String, type: NotificationType = NotificationType.INFORMATION
    ) {
        Notifications.Bus.notify(
            Notification("Refio", title, content, type), project
        )
    }
}

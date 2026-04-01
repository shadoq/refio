package pl.jclab.refio.ui.components.chat

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Dialog for showing file changes with diff viewer.
 *
 * Features:
 * - Shows diff between current file and snapshot
 * - Uses IntelliJ's built-in diff viewer
 * - Handles file not found gracefully
 * - Loads snapshot content asynchronously to avoid blocking EDT
 */
class ChangesDialog(
    private val project: Project,
    private val filePath: String,
    private val snapshotId: String? = null
) : DialogWrapper(project) {

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var contentPanel: JPanel

    init {
        title = "File Changes: $filePath"
        init()
    }

    override fun createCenterPanel(): JComponent {
        contentPanel = JPanel(BorderLayout())
        contentPanel.preferredSize = Dimension(800, 600)
        contentPanel.minimumSize = Dimension(600, 400)

        try {
            // Get project base path
            val basePath = project.basePath ?: throw IllegalStateException("Project path not found")

            // Construct full path
            val fullPath = Paths.get(basePath, filePath)

            if (!Files.exists(fullPath)) {
                return createErrorPanel("File not found: $filePath")
            }

            // Get VirtualFile
            val vFile = VirtualFileManager.getInstance().findFileByNioPath(fullPath)
                ?: return createErrorPanel("Could not load file: $filePath")

            if (snapshotId != null) {
                // Show loading state and load snapshot asynchronously
                contentPanel.add(createLoadingPanel(), BorderLayout.CENTER)
                loadSnapshotAndShowDiff(vFile)
            } else {
                // No snapshot needed — show diff synchronously
                val diffPanel = createNonSnapshotDiffPanel(vFile)
                contentPanel.add(diffPanel, BorderLayout.CENTER)
            }
        } catch (e: Exception) {
            return createErrorPanel("Error loading file: ${e.message}")
        }

        return contentPanel
    }

    /**
     * Load snapshot content on IO thread, then populate diff panel on EDT.
     */
    private fun loadSnapshotAndShowDiff(currentFile: VirtualFile) {
        coroutineScope.launch {
            val snapshotContent = try {
                val router = pl.jclab.refio.services.core.CoreConnectionManager.getInstance().getApiRouter()
                router.getSnapshotFileContent(snapshotId!!, filePath)
            } catch (e: Exception) {
                null
            }

            ApplicationManager.getApplication().invokeLater {
                contentPanel.removeAll()
                val panel = if (snapshotContent != null) {
                    createSnapshotDiffPanel(currentFile, snapshotContent)
                } else {
                    createErrorPanel("Snapshot not found: $snapshotId")
                }
                contentPanel.add(panel, BorderLayout.CENTER)
                contentPanel.revalidate()
                contentPanel.repaint()
            }
        }
    }

    /**
     * Create diff panel showing snapshot vs current file.
     * Must be called on EDT.
     */
    private fun createSnapshotDiffPanel(currentFile: VirtualFile, snapshotContent: String): JComponent {
        val contentFactory = DiffContentFactory.getInstance()
        val beforeContent = contentFactory.create(snapshotContent, currentFile.fileType)
        val afterContent = contentFactory.create(project, currentFile)

        val diffRequest = SimpleDiffRequest(
            "File Changes",
            beforeContent,
            afterContent,
            "Before (Snapshot)",
            "After (Current)"
        )

        return DiffManager.getInstance().createRequestPanel(project, disposable, null).also {
            it.setRequest(diffRequest)
        }.component
    }

    /**
     * Create diff panel for current file only (no snapshot).
     * Must be called on EDT.
     */
    private fun createNonSnapshotDiffPanel(currentFile: VirtualFile): JComponent {
        val contentFactory = DiffContentFactory.getInstance()
        val content = contentFactory.create(project, currentFile)

        return DiffManager.getInstance().createRequestPanel(project, disposable, null).also {
            val request = SimpleDiffRequest(
                "Current File",
                content,
                content,
                "Current",
                "Current"
            )
            it.setRequest(request)
        }.component
    }

    /**
     * Create a loading indicator panel.
     */
    private fun createLoadingPanel(): JComponent {
        return JPanel().apply {
            border = JBUI.Borders.empty(20)
            add(JBLabel("Loading snapshot..."))
        }
    }

    /**
     * Create error panel with message.
     */
    private fun createErrorPanel(message: String): JComponent {
        return JPanel().apply {
            border = JBUI.Borders.empty(20)
            add(JBLabel(message))
        }
    }

    override fun createActions() = arrayOf(okAction)

    override fun dispose() {
        coroutineScope.cancel()
        super.dispose()
    }
}

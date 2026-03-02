package pl.jclab.refio.ui.components.chat

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
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
 */
class ChangesDialog(
    private val project: Project,
    private val filePath: String,
    private val snapshotId: String? = null
) : DialogWrapper(project) {

    init {
        title = "File Changes: $filePath"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.preferredSize = Dimension(800, 600)
        panel.minimumSize = Dimension(600, 400)

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

            // Create diff viewer
            val diffPanel = createDiffPanel(vFile)
            panel.add(diffPanel)

        } catch (e: Exception) {
            return createErrorPanel("Error loading file: ${e.message}")
        }

        return panel
    }

    /**
     * Create diff panel using IntelliJ DiffManager
     */
    private fun createDiffPanel(currentFile: VirtualFile): JComponent {
        val contentFactory = DiffContentFactory.getInstance()

        return if (snapshotId != null) {
            // Show diff between snapshot and current file
            // TODO: Load snapshot content from database
            val snapshotContent = loadSnapshotContent(snapshotId, filePath)

            if (snapshotContent != null) {
                val beforeContent = contentFactory.create(snapshotContent, currentFile.fileType)
                val afterContent = contentFactory.create(project, currentFile)

                val diffRequest = SimpleDiffRequest(
                    "File Changes",
                    beforeContent,
                    afterContent,
                    "Before (Snapshot)",
                    "After (Current)"
                )

                DiffManager.getInstance().createRequestPanel(project, disposable, null).also {
                    it.setRequest(diffRequest)
                }.component
            } else {
                createErrorPanel("Snapshot not found: $snapshotId")
            }
        } else {
            // Show current file content only (no diff)
            val content = contentFactory.create(project, currentFile)

            DiffManager.getInstance().createRequestPanel(project, disposable, null).also {
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
    }

    /**
     * Load snapshot content from database
     */
    private fun loadSnapshotContent(snapshotId: String, filePath: String): String? {
        return try {
            val router = pl.jclab.refio.services.core.CoreConnectionManager.getInstance().getApiRouter()
            kotlinx.coroutines.runBlocking {
                router.getSnapshotFileContent(snapshotId, filePath)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create error panel with message
     */
    private fun createErrorPanel(message: String): JComponent {
        return JPanel().apply {
            border = JBUI.Borders.empty(20)
            add(JBLabel(message))
        }
    }

    override fun createActions() = arrayOf(okAction)
}

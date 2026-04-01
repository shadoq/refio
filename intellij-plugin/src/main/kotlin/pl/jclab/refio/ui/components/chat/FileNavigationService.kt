package pl.jclab.refio.ui.components.chat

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import pl.jclab.refio.services.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Paths

internal class FileNavigationService(
    private val project: Project
) {

    private val logger = dualLogger("FileNavigationService")
    private val coreManager = pl.jclab.refio.services.core.CoreConnectionManager.getInstance()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun openFileReference(path: String?) {
        val resolved = resolveAbsolutePath(path)
        if (resolved == null) {
            showNotification("File not found", "Cannot resolve path ${path ?: "(empty)"}", NotificationType.WARNING)
            return
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolved)
        if (virtualFile == null) {
            showNotification("File not found", "Cannot open $resolved", NotificationType.WARNING)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    fun selectFolderReference(path: String?) {
        val resolved = resolveAbsolutePath(path)
        if (resolved == null) {
            showNotification("Folder not found", "Cannot resolve path ${path ?: "(empty)"}", NotificationType.WARNING)
            return
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolved)
        if (virtualFile == null) {
            showNotification("Folder not found", "Cannot open $resolved", NotificationType.WARNING)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            ProjectView.getInstance(project).select(null, virtualFile, false)
        }
    }

    fun openDocsReference(path: String?) {
        if (path.isNullOrBlank()) {
            showNotification("Invalid URL", "Documentation reference has no URL.", NotificationType.WARNING)
            return
        }
        BrowserUtil.browse(path)
    }

    fun openContextReference(ref: ContextReference) {
        when (ref.type) {
            ContextType.FILE, ContextType.RULES, ContextType.OPEN -> openFileReference(ref.path)
            ContextType.FOLDER -> selectFolderReference(ref.path)
            ContextType.DOCS -> openDocsReference(ref.path)
            else -> {
                showNotification(
                    "Preview unavailable",
                    "Cannot open ${ref.type.name.lowercase()} references yet.",
                    NotificationType.INFORMATION
                )
            }
        }
    }

    fun openCodeChangesDiff(changes: CodeChangesData) {
        logger.info { "[DIFF] Opening diff viewer for: ${changes.filePath}" }
        logger.info { "[DIFF] Changes: +${changes.addedLines} -${changes.removedLines}, snapshotId=${changes.snapshotId}" }

        coroutineScope.launch {
            try {
                val snapshotContent = if (!changes.snapshotId.isNullOrBlank()) {
                    logger.info { "[DIFF] Loading snapshot: ${changes.snapshotId}" }
                    val content = loadSnapshotContent(changes.snapshotId, changes.filePath)
                    if (content != null) {
                        logger.info { "[DIFF] Snapshot loaded successfully: ${content.length} chars" }
                    } else {
                        logger.warn { "[DIFF] Snapshot content is null for: ${changes.snapshotId}" }
                    }
                    content
                } else {
                    logger.info { "[DIFF] No snapshot ID provided - will show empty vs current" }
                    null
                }

                ApplicationManager.getApplication().invokeLater {
                    try {
                        val basePath = project.basePath ?: run {
                            logger.error { "[DIFF] Project basePath is null!" }
                            showNotification("Error", "Project path not found", NotificationType.ERROR)
                            return@invokeLater
                        }
                        logger.info { "[DIFF] Project basePath: $basePath" }

                        val fullPath = Paths.get(basePath, changes.filePath)
                        logger.info { "[DIFF] Full path resolved: $fullPath" }
                        logger.info { "[DIFF] File exists: ${Files.exists(fullPath)}" }

                        if (!Files.exists(fullPath)) {
                            logger.warn { "[DIFF] File not found at: $fullPath" }
                            showNotification("Error", "File not found: ${changes.filePath}", NotificationType.ERROR)
                            return@invokeLater
                        }

                        logger.info { "[DIFF] Attempting to load VirtualFile from: $fullPath" }
                        val vFile = VirtualFileManager.getInstance().findFileByNioPath(fullPath) ?: run {
                            logger.error { "[DIFF] VirtualFileManager could not find file: $fullPath" }
                            showNotification("Error", "Could not load file: ${changes.filePath}", NotificationType.ERROR)
                            return@invokeLater
                        }
                        logger.info { "[DIFF] VirtualFile loaded: ${vFile.path}, fileType=${vFile.fileType.name}" }

                        val diffManager = DiffManager.getInstance()
                        val contentFactory = DiffContentFactory.getInstance()

                        val diffRequest = if (snapshotContent != null) {
                            logger.info { "[DIFF] Creating diff request: Before (snapshot) vs After (current)" }
                            val beforeContent = contentFactory.create(snapshotContent, vFile.fileType)
                            val afterContent = contentFactory.create(project, vFile)

                            SimpleDiffRequest(
                                "Changes: ${changes.filePath}",
                                beforeContent,
                                afterContent,
                                "Before",
                                "After"
                            )
                        } else {
                            logger.info { "[DIFF] Creating diff request: Empty vs Current (no snapshot)" }
                            val emptyContent = contentFactory.create("")
                            val currentContent = contentFactory.create(project, vFile)

                            SimpleDiffRequest(
                                if (changes.removedLines == 0 && changes.addedLines > 0) {
                                    "Created: ${changes.filePath}"
                                } else {
                                    "Changes: ${changes.filePath}"
                                },
                                emptyContent,
                                currentContent,
                                "Empty",
                                "Current"
                            )
                        }

                        logger.info { "[DIFF] Opening IntelliJ diff viewer" }
                        diffManager.showDiff(project, diffRequest)
                        logger.info { "[DIFF] Diff viewer opened successfully" }
                    } catch (e: Exception) {
                        logger.error(e) { "Error opening diff for file: ${changes.filePath}" }
                        showNotification("Error", "Could not open diff: ${e.message}", NotificationType.ERROR)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error loading snapshot for diff: ${changes.filePath}" }
                ApplicationManager.getApplication().invokeLater {
                    showNotification("Error", "Could not load snapshot: ${e.message}", NotificationType.ERROR)
                }
            }
        }
    }

    fun showFileChangesDialog(filePath: String, snapshotId: String? = null) {
        ApplicationManager.getApplication().invokeLater {
            logger.info { "Showing changes dialog: file=$filePath, snapshot=$snapshotId" }
            val dialog = ChangesDialog(project, filePath, snapshotId = snapshotId)
            dialog.show()
        }
    }

    fun resolveAbsolutePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val candidate = Paths.get(path)
            if (candidate.isAbsolute) {
                candidate.normalize().toString()
            } else {
                val base = project.basePath ?: return null
                Paths.get(base, path).normalize().toString()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to resolve path for reference: $path" }
            null
        }
    }

    private suspend fun loadSnapshotContent(snapshotId: String, filePath: String): String? {
        return try {
            logger.info { "[SNAPSHOT] Loading snapshot content for: snapshotId=$snapshotId, filePath=$filePath" }
            val router = coreManager.getApiRouter()
            val content = router.getSnapshotFileContent(snapshotId, filePath)
            if (content != null) {
                logger.info { "[SNAPSHOT] Loaded successfully: ${content.length} chars" }
            } else {
                logger.warn { "[SNAPSHOT] Router returned null for: snapshotId=$snapshotId, filePath=$filePath" }
            }
            content
        } catch (e: Exception) {
            logger.error(e) { "[SNAPSHOT] Failed to load snapshot: $snapshotId for file: $filePath" }
            null
        }
    }

    private fun showNotification(title: String, content: String, type: NotificationType = NotificationType.INFORMATION) {
        Notifications.Bus.notify(
            Notification("Refio", title, content, type),
            project
        )
    }
}

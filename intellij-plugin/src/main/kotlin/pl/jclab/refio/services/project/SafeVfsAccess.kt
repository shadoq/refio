package pl.jclab.refio.services.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import pl.jclab.refio.core.logging.DualLogger
import java.nio.file.Path

internal object SafeVfsAccess {
    fun refreshProjectRoot(project: Project, logger: DualLogger) {
        if (!canRefresh(project)) return

        try {
            project.guessProjectDir()?.refresh(true, true)
        } catch (e: NullPointerException) {
            if (isShutdownRefreshRace(project, e)) {
                logger.debug { "Skipping project VFS refresh during application shutdown" }
                return
            }
            throw e
        }
    }

    fun refreshAndFindFile(project: Project, path: Path, logger: DualLogger): VirtualFile? {
        if (!canRefresh(project)) return null

        return try {
            VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)
        } catch (e: NullPointerException) {
            if (isShutdownRefreshRace(project, e)) {
                logger.debug { "Skipping VFS refresh for $path during application shutdown" }
                null
            } else {
                throw e
            }
        }
    }

    private fun canRefresh(project: Project): Boolean {
        val app = ApplicationManager.getApplication()
        return !project.isDisposed && !app.isDisposed
    }

    private fun isShutdownRefreshRace(project: Project, error: NullPointerException): Boolean {
        val app = ApplicationManager.getApplication()
        val message = error.message.orEmpty()
        return project.isDisposed ||
            app.isDisposed ||
            (
                message.contains("LaterInvocator.ourNonBlockingEdtQueue") &&
                    message.contains("is null")
                )
    }
}

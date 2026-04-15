package pl.jclab.refio.services.project

import com.intellij.openapi.project.Project
import pl.jclab.refio.core.session.VfsRefresher
import pl.jclab.refio.core.logging.dualLogger

/**
 * Plugin-side implementacja [VfsRefresher] — używa [SafeVfsAccess] do odświeżenia
 * IntelliJ VFS po edycjach plików przez Core tools.
 */
internal class IntelliJVfsRefresher(private val project: Project) : VfsRefresher {

    private val logger = dualLogger("IntelliJVfsRefresher")

    override fun refreshProjectRoot() {
        SafeVfsAccess.refreshProjectRoot(project, logger)
    }
}

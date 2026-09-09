package pl.jclab.refio.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import pl.jclab.refio.services.core.CoreConnectionManager
import java.nio.file.Path

/**
 * Releases a project's core router and its MCP child processes when the project window closes.
 *
 * Routers are cached per project in an application-level service, so without this hook every
 * project ever opened stays fully alive until the IDE exits.
 */
class ProjectCloseListener : ProjectManagerListener {

    override fun projectClosed(project: Project) {
        val basePath = project.basePath ?: return
        CoreConnectionManager.getInstance().closeProject(Path.of(basePath))
    }
}

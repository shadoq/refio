package pl.jclab.refio.services.project

import com.intellij.openapi.project.Project
import pl.jclab.refio.core.project.ProjectHandle
import pl.jclab.refio.core.utils.ProjectIdGenerator
import java.nio.file.Path

/**
 * IntelliJ-specific implementation of [ProjectHandle].
 *
 * Wraps [com.intellij.openapi.project.Project] to provide platform-agnostic
 * access in core/ layer. The [platformProject] property returns the underlying
 * IntelliJ Project for IDE-specific operations (context providers, VFS, etc.).
 */
class IntelliJProjectHandle(private val project: Project) : ProjectHandle {

    override val id: String = ProjectIdGenerator.generate(rootPath)

    override val name: String
        get() = project.name

    override val rootPath: Path
        get() = Path.of(project.basePath ?: ".")

    override val platformProject: Any
        get() = project

    /**
     * Typed accessor for IntelliJ code that needs the concrete Project type.
     */
    val intellijProject: Project
        get() = project
}

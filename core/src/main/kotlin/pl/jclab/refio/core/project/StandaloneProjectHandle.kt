package pl.jclab.refio.core.project

import pl.jclab.refio.core.utils.ProjectIdGenerator
import java.nio.file.Path

/**
 * ProjectHandle implementation for standalone/CLI mode (no IntelliJ).
 */
class StandaloneProjectHandle(override val rootPath: Path) : ProjectHandle {
    override val id: String = ProjectIdGenerator.generate(rootPath)
    override val name: String = rootPath.fileName?.toString() ?: "project"
}

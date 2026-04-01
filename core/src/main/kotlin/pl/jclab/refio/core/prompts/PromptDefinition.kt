package pl.jclab.refio.core.prompts

import pl.jclab.refio.core.registry.DefinitionScope
import java.nio.file.Path

enum class PromptContentType {
    SYSTEM,
    TOOL
}

data class PromptDefinition(
    val name: String,
    val type: PromptContentType,
    val description: String,
    val content: String,
    val variables: List<String>,
    val mode: String?,
    val role: String?,
    val enabled: Boolean,
    val scope: DefinitionScope,
    val sourcePath: Path?
)

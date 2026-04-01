package pl.jclab.refio.core.prompts

import pl.jclab.refio.core.registry.DefinitionScope
import pl.jclab.refio.core.registry.FileBasedRegistry
import java.nio.file.Path

/**
 * Registry for prompt definitions loaded from MD files.
 *
 * Hierarchy (highest priority first):
 * 1. Project: .refio/prompts/<name>.md
 * 2. User: ~/.refio/prompts/<name>.md
 * 3. Builtin: resources/prompts/<name>.md
 */
class PromptRegistry(
    projectRoot: Path?
) : FileBasedRegistry<PromptDefinition>("prompts", projectRoot) {

    private val parser = PromptParser()

    override fun parseFile(content: String, sourcePath: Path?, scope: DefinitionScope): PromptDefinition {
        return parser.parse(content, sourcePath, scope)
    }

    override fun getName(item: PromptDefinition): String = item.name

    override fun isEnabled(item: PromptDefinition): Boolean = item.enabled
}

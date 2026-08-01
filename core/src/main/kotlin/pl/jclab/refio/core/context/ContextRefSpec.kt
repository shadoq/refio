package pl.jclab.refio.core.context

import pl.jclab.refio.api.models.ContextReference

/**
 * Parses the `--context-ref` command-line form into a [ContextReference].
 *
 * The syntax mirrors what a user types in the chat input, so the same reference reads the same in
 * both places:
 *
 *   `@file:src/Main.kt`      a project file
 *   `@folder:src/services`   a folder
 *   `@rules`                 project rules, optionally `@rules:path`
 *   `@clipboard`             a built-in context provider
 *   `@stubdocs:ktor`         an MCP server (provider id) with a query
 *
 * The leading `@` is optional, since a shell would otherwise need it quoted.
 *
 * Anything that is not a known keyword is treated as a provider id. That is deliberate: provider
 * ids are open-ended (every connected MCP server adds one), so a closed list would reject valid
 * references and there is no way to validate them here without the registry.
 */
object ContextRefSpec {

    fun parse(raw: String): ContextReference {
        val spec = raw.trim().removePrefix("@")
        require(spec.isNotBlank()) { "Empty context reference" }

        val keyword = spec.substringBefore(':').lowercase()
        val argument = if (spec.contains(':')) spec.substringAfter(':') else ""

        return when (keyword) {
            "file" -> {
                require(argument.isNotBlank()) { "@file needs a path, e.g. @file:src/Main.kt" }
                ContextReference.file(argument)
            }
            "folder" -> {
                require(argument.isNotBlank()) { "@folder needs a path, e.g. @folder:src" }
                ContextReference.folder(argument)
            }
            "docs" -> {
                require(argument.isNotBlank()) { "@docs needs a url" }
                ContextReference.docs(argument)
            }
            "rules" -> if (argument.isBlank()) ContextReference.rules() else ContextReference.rules(argument)
            "open" -> ContextReference.openFiles()
            else -> ContextReference.provider(
                providerId = keyword,
                query = argument,
                displayName = if (argument.isBlank()) keyword else "$keyword:$argument"
            )
        }
    }
}

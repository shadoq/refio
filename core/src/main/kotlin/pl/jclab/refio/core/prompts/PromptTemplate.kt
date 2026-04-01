package pl.jclab.refio.core.prompts

/**
 * Template for prompts with {{variable}} substitution.
 *
 * Example:
 * ```
 * val template = PromptTemplate("Hello {{name}}, you are {{age}} years old.")
 * val result = template.render(mapOf("name" to "Alice", "age" to 30))
 * // Result: "Hello Alice, you are 30 years old."
 * ```
 */
class PromptTemplate(private val template: String) {

    private val variables: Set<String> = extractVariables(template)

    /**
     * Extract all {{variable}} names from template.
     */
    private fun extractVariables(template: String): Set<String> {
        val regex = """\{\{(\w+)\}\}""".toRegex()
        return regex.findAll(template)
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Render template with provided variables.
     *
     * @param kwargs Variable values to substitute
     * @return Rendered string
     * @throws IllegalArgumentException If required variables are missing
     */
    fun render(kwargs: Map<String, Any>): String {
        val missing = variables - kwargs.keys
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "Missing required variables: ${missing.sorted().joinToString(", ")}"
            )
        }

        var result = template
        kwargs.forEach { (key, value) ->
            result = result.replace("{{$key}}", value.toString())
        }

        return result
    }

    /**
     * Get set of required variable names.
     */
    fun getVariables(): Set<String> = variables.toSet()

    override fun toString(): String = "PromptTemplate(variables=$variables)"
}

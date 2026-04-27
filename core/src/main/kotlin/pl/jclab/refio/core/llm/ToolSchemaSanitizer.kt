package pl.jclab.refio.core.llm

import pl.jclab.refio.core.tools.base.ToolSchema

/**
 * Provider-specific schema normalization for native tool calling.
 *
 * The source schemas come from tool definitions and are intentionally provider-agnostic.
 * Each provider accepts a slightly different subset:
 * - OpenAI strict mode requires fully strict object schemas.
 * - Anthropic accepts JSON Schema objects but is picky about composition/meta keywords.
 * - Gemini expects an OpenAPI-style schema shape and nullable fields instead of JSON Schema unions.
 */
object ToolSchemaSanitizer {

    data class OpenAIToolSchema(
        val tool: ToolSchema,
        val strict: Boolean,
        val strictIncompatibilities: List<String>
    )

    fun forOpenAI(tool: ToolSchema): OpenAIToolSchema {
        val sanitized = sanitizeForOpenAI(tool.parametersJsonSchema)
        val incompatibilities = mutableListOf<String>()
        collectOpenAIStrictIncompatibilities(
            node = sanitized,
            path = "\$",
            incompatibilities = incompatibilities
        )
        return OpenAIToolSchema(
            tool = tool.copy(parametersJsonSchema = sanitized),
            strict = incompatibilities.isEmpty(),
            strictIncompatibilities = incompatibilities
        )
    }

    fun forAnthropic(tool: ToolSchema): ToolSchema =
        tool.copy(parametersJsonSchema = sanitizeForAnthropic(tool.parametersJsonSchema))

    fun forGemini(tool: ToolSchema): ToolSchema =
        tool.copy(parametersJsonSchema = sanitizeForGemini(tool.parametersJsonSchema))

    private fun sanitizeForOpenAI(schema: Map<String, Any>): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return sanitizeOpenAINode(schema) as Map<String, Any>
    }

    private fun sanitizeOpenAINode(node: Any?): Any? {
        return when (node) {
            is Map<*, *> -> {
                val result = LinkedHashMap<String, Any?>()
                for ((rawKey, rawValue) in node) {
                    val key = rawKey as? String ?: continue
                    if (key == "\$schema" || key == "default") continue
                    result[key] = sanitizeOpenAINode(rawValue)
                }
                result
            }
            is List<*> -> node.map { sanitizeOpenAINode(it) }
            else -> node
        }
    }

    private fun collectOpenAIStrictIncompatibilities(
        node: Any?,
        path: String,
        incompatibilities: MutableList<String>
    ) {
        when (node) {
            is Map<*, *> -> {
                val typeValue = node["type"]
                val properties = node["properties"] as? Map<*, *>
                val additionalProperties = node["additionalProperties"]
                val required = (node["required"] as? List<*>)?.mapNotNull { it as? String }?.toSet()
                val propertyNames = properties?.keys?.mapNotNull { it as? String }?.toSet().orEmpty()

                if (node.containsKey("oneOf") || node.containsKey("allOf") || node.containsKey("anyOf")) {
                    incompatibilities += "$path uses schema composition keywords"
                }

                if (isObjectType(typeValue) || properties != null) {
                    if (additionalProperties != false) {
                        incompatibilities += "$path is object-like without additionalProperties=false"
                    }
                    if (required != propertyNames) {
                        incompatibilities += "$path required keys do not exactly match properties"
                    }
                }

                if (additionalProperties is Map<*, *>) {
                    incompatibilities += "$path uses dynamic object properties"
                    collectOpenAIStrictIncompatibilities(
                        additionalProperties,
                        "$path.additionalProperties",
                        incompatibilities
                    )
                }

                if (properties != null) {
                    for ((rawPropName, rawPropSchema) in properties) {
                        val propName = rawPropName as? String ?: continue
                        collectOpenAIStrictIncompatibilities(
                            rawPropSchema,
                            "$path.properties.$propName",
                            incompatibilities
                        )
                    }
                }

                for ((rawKey, rawValue) in node) {
                    val key = rawKey as? String ?: continue
                    if (key == "properties" || key == "additionalProperties") continue
                    collectOpenAIStrictIncompatibilities(rawValue, "$path.$key", incompatibilities)
                }
            }
            is List<*> -> node.forEachIndexed { index, item ->
                collectOpenAIStrictIncompatibilities(item, "$path[$index]", incompatibilities)
            }
        }
    }

    private fun sanitizeForAnthropic(schema: Map<String, Any>): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return sanitizeAnthropicNode(schema) as Map<String, Any>
    }

    private fun sanitizeAnthropicNode(node: Any?): Any? {
        val forbidden = setOf("oneOf", "allOf", "anyOf", "\$schema", "default")
        return when (node) {
            is Map<*, *> -> {
                val result = LinkedHashMap<String, Any?>()
                for ((rawKey, rawValue) in node) {
                    val key = rawKey as? String ?: continue
                    if (key in forbidden) continue
                    result[key] = sanitizeAnthropicNode(rawValue)
                }
                result
            }
            is List<*> -> node.map { sanitizeAnthropicNode(it) }
            else -> node
        }
    }

    private fun sanitizeForGemini(schema: Map<String, Any>): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return sanitizeGeminiNode(schema) as Map<String, Any>
    }

    private fun sanitizeGeminiNode(node: Any?): Any? {
        val forbidden = setOf("oneOf", "allOf", "anyOf", "\$schema", "additionalProperties", "default")
        return when (node) {
            is Map<*, *> -> {
                val result = LinkedHashMap<String, Any?>()
                val originalType = node["type"]
                val nullable = isNullableType(originalType)
                for ((rawKey, rawValue) in node) {
                    val key = rawKey as? String ?: continue
                    if (key in forbidden) continue
                    result[key] = when (key) {
                        "type" -> toGeminiType(rawValue)
                        else -> sanitizeGeminiNode(rawValue)
                    }
                }
                if (nullable) {
                    result["nullable"] = true
                }
                result
            }
            is List<*> -> node.map { sanitizeGeminiNode(it) }
            else -> node
        }
    }

    private fun isObjectType(typeValue: Any?): Boolean {
        return when (typeValue) {
            is String -> typeValue.equals("object", ignoreCase = true)
            is List<*> -> typeValue.filterIsInstance<String>().any { it.equals("object", ignoreCase = true) }
            else -> false
        }
    }

    private fun isNullableType(typeValue: Any?): Boolean {
        return when (typeValue) {
            is List<*> -> typeValue.filterIsInstance<String>().any { it.equals("null", ignoreCase = true) }
            else -> false
        }
    }

    private fun toGeminiType(typeValue: Any?): Any? {
        val normalized = when (typeValue) {
            is String -> typeValue
            is List<*> -> typeValue
                .filterIsInstance<String>()
                .firstOrNull { !it.equals("null", ignoreCase = true) }
                ?: typeValue.filterIsInstance<String>().firstOrNull()
            else -> null
        } ?: return typeValue

        return when (normalized.lowercase()) {
            "string" -> "STRING"
            "integer" -> "INTEGER"
            "boolean" -> "BOOLEAN"
            "number" -> "NUMBER"
            "array" -> "ARRAY"
            "object" -> "OBJECT"
            else -> normalized.uppercase()
        }
    }
}

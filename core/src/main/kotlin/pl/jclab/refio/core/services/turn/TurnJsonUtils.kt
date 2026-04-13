package pl.jclab.refio.core.services.turn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON utilities for parsing LLM responses.
 * Stateless object - all methods are pure functions.
 */
object TurnJsonUtils {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private enum class ContainerType {
        OBJECT,
        ARRAY
    }

    private enum class ObjectState {
        EXPECT_KEY_OR_END,
        EXPECT_COLON,
        EXPECT_VALUE,
        EXPECT_COMMA_OR_END
    }

    private enum class ArrayState {
        EXPECT_VALUE_OR_END,
        EXPECT_COMMA_OR_END
    }

    private sealed interface ContainerState {
        val type: ContainerType
    }

    private data class ObjectContainer(
        var state: ObjectState = ObjectState.EXPECT_KEY_OR_END
    ) : ContainerState {
        override val type: ContainerType = ContainerType.OBJECT
    }

    private data class ArrayContainer(
        var state: ArrayState = ArrayState.EXPECT_VALUE_OR_END
    ) : ContainerState {
        override val type: ContainerType = ContainerType.ARRAY
    }

    private enum class StringRole {
        KEY,
        VALUE
    }

    /**
     * Parse JSON string to Map.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseJsonToMap(jsonString: String): Map<String, Any> {
        return try {
            val element = json.parseToJsonElement(jsonString)
            jsonElementToMap(element)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Convert JsonElement to native Map.
     */
    @Suppress("UNCHECKED_CAST")
    fun jsonElementToMap(element: JsonElement): Map<String, Any> {
        return when (element) {
            is JsonObject -> {
                element.mapValues { (_, v) -> jsonElementToAny(v) }
            }
            else -> emptyMap()
        }
    }

    /**
     * Convert JsonElement to native Any (Map, List, or primitive).
     */
    fun jsonElementToAny(element: JsonElement): Any {
        return when (element) {
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content.contains(".") -> element.content.toDoubleOrNull() ?: element.content
                    else -> element.content.toLongOrNull() ?: element.content
                }
            }
            is JsonArray -> {
                element.map { jsonElementToAny(it) }
            }
            is JsonObject -> {
                element.mapValues { (_, v) -> jsonElementToAny(v) }
            }
            else -> element.toString()
        }
    }

    /**
     * Convert Map to kotlinx.serialization JsonElement (for JsonExtractor fallback).
     */
    fun parseMapToJsonElement(map: Map<String, Any>): JsonElement {
        fun anyToJsonElement(any: Any): JsonElement {
            return when (any) {
                is String -> JsonPrimitive(any)
                is Number -> JsonPrimitive(any)
                is Boolean -> JsonPrimitive(any)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    JsonObject(
                        (any as Map<String, Any>).mapValues { (_, v) -> anyToJsonElement(v) }
                    )
                }
                is List<*> -> {
                    JsonArray(any.map { anyToJsonElement(it!!) })
                }
                else -> JsonPrimitive(any.toString())
            }
        }
        @Suppress("UNCHECKED_CAST")
        return JsonObject(
            map.mapValues { (_, v) -> anyToJsonElement(v) }
        )
    }

    /**
     * Attempt to repair JSON arguments by fixing invalid escape sequences.
     * Returns repaired JSON string if valid, null otherwise.
     */
    fun attemptRepairJsonArguments(arguments: String): String? {
        val repaired = repairMalformedJson(arguments)
        return try {
            json.parseToJsonElement(repaired)
            repaired
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Repair invalid JSON escape sequences in input string.
     * Fixes invalid escapes like \S, \d, etc. in regex patterns.
     */
    fun repairInvalidJsonEscapes(input: String): String {
        val sb = StringBuilder()
        var inString = false
        var i = 0

        while (i < input.length) {
            val ch = input[i]
            if (ch == '"') {
                val isEscaped = isEscaped(input, i)
                if (!isEscaped) {
                    inString = !inString
                }
                sb.append(ch)
                i += 1
                continue
            }

            if (ch == '\\' && inString) {
                val next = input.getOrNull(i + 1)
                if (next != null && !isValidJsonEscape(next)) {
                    sb.append("\\\\")
                    sb.append(next)
                    i += 2
                    continue
                }
            }

            sb.append(ch)
            i += 1
        }

        return sb.toString()
    }

    /**
     * Repair malformed JSON produced by LLMs:
     * - invalid escape sequences like \S
     * - raw newlines inside strings
     * - unescaped quotes inside string values
     *
     * The quote repair is context-aware and tries to close strings only when the
     * following significant character is structurally valid for the current position.
     */
    fun repairMalformedJson(input: String): String {
        val escapedInput = repairInvalidJsonEscapes(input)
        val out = StringBuilder(escapedInput.length + 64)
        val stack = ArrayDeque<ContainerState>()

        var inString = false
        var escape = false
        var stringRole = StringRole.VALUE
        var i = 0

        while (i < escapedInput.length) {
            val ch = escapedInput[i]

            if (inString) {
                if (escape) {
                    out.append(ch)
                    escape = false
                    i += 1
                    continue
                }

                when (ch) {
                    '\\' -> {
                        out.append(ch)
                        escape = true
                    }
                    '\r' -> out.append("\\r")
                    '\n' -> out.append("\\n")
                    '"' -> {
                        val next = nextSignificantChar(escapedInput, i + 1)
                        if (isValidStringTerminator(next, stringRole, stack.lastOrNull())) {
                            out.append(ch)
                            inString = false
                            onStringClosed(stack.lastOrNull(), stringRole)
                        } else {
                            out.append("\\\"")
                        }
                    }
                    else -> out.append(ch)
                }
                i += 1
                continue
            }

            when (ch) {
                '"' -> {
                    stringRole = inferStringRole(stack.lastOrNull())
                    inString = true
                    out.append(ch)
                }
                '{' -> {
                    stack.addLast(ObjectContainer())
                    out.append(ch)
                }
                '[' -> {
                    stack.addLast(ArrayContainer())
                    out.append(ch)
                }
                ':' -> {
                    (stack.lastOrNull() as? ObjectContainer)?.state = ObjectState.EXPECT_VALUE
                    out.append(ch)
                }
                ',' -> {
                    onComma(stack.lastOrNull())
                    out.append(ch)
                }
                '}' -> {
                    closeContainer(stack, ContainerType.OBJECT)
                    out.append(ch)
                }
                ']' -> {
                    closeContainer(stack, ContainerType.ARRAY)
                    out.append(ch)
                }
                else -> out.append(ch)
            }

            i += 1
        }

        if (inString) {
            out.append('"')
            inString = false
        }

        while (stack.isNotEmpty()) {
            when (stack.removeLast().type) {
                ContainerType.OBJECT -> out.append('}')
                ContainerType.ARRAY -> out.append(']')
            }
        }

        return out.toString()
    }

    /**
     * Check if character at index is escaped (preceded by odd number of backslashes).
     */
    fun isEscaped(input: String, index: Int): Boolean {
        var backslashCount = 0
        var pos = index - 1
        while (pos >= 0 && input[pos] == '\\') {
            backslashCount += 1
            pos -= 1
        }
        return backslashCount % 2 == 1
    }

    /**
     * Check if character is a valid JSON escape sequence.
     */
    fun isValidJsonEscape(ch: Char): Boolean {
        return ch == '"' || ch == '\\' || ch == '/' ||
            ch == 'b' || ch == 'f' || ch == 'n' || ch == 'r' || ch == 't' || ch == 'u'
    }

    private fun nextSignificantChar(input: String, startIndex: Int): Char? {
        var i = startIndex
        while (i < input.length) {
            val ch = input[i]
            if (!ch.isWhitespace()) {
                return ch
            }
            i += 1
        }
        return null
    }

    private fun inferStringRole(container: ContainerState?): StringRole {
        return when (container) {
            is ObjectContainer -> {
                if (container.state == ObjectState.EXPECT_KEY_OR_END) StringRole.KEY else StringRole.VALUE
            }
            else -> StringRole.VALUE
        }
    }

    private fun isValidStringTerminator(
        nextSignificant: Char?,
        role: StringRole,
        container: ContainerState?
    ): Boolean {
        return when (role) {
            StringRole.KEY -> nextSignificant == ':'
            StringRole.VALUE -> when (container) {
                is ObjectContainer -> nextSignificant == null || nextSignificant == ',' || nextSignificant == '}'
                is ArrayContainer -> nextSignificant == null || nextSignificant == ',' || nextSignificant == ']'
                null -> nextSignificant == null || nextSignificant == ',' || nextSignificant == '}' || nextSignificant == ']'
            }
        }
    }

    private fun onStringClosed(container: ContainerState?, role: StringRole) {
        when (container) {
            is ObjectContainer -> {
                container.state = when (role) {
                    StringRole.KEY -> ObjectState.EXPECT_COLON
                    StringRole.VALUE -> ObjectState.EXPECT_COMMA_OR_END
                }
            }
            is ArrayContainer -> {
                if (role == StringRole.VALUE) {
                    container.state = ArrayState.EXPECT_COMMA_OR_END
                }
            }
            null -> Unit
        }
    }

    private fun onComma(container: ContainerState?) {
        when (container) {
            is ObjectContainer -> container.state = ObjectState.EXPECT_KEY_OR_END
            is ArrayContainer -> container.state = ArrayState.EXPECT_VALUE_OR_END
            null -> Unit
        }
    }

    private fun closeContainer(stack: ArrayDeque<ContainerState>, expectedType: ContainerType) {
        val container = stack.removeLastOrNull() ?: return
        if (container.type != expectedType) {
            return
        }

        when (val parent = stack.lastOrNull()) {
            is ObjectContainer -> {
                if (parent.state == ObjectState.EXPECT_VALUE) {
                    parent.state = ObjectState.EXPECT_COMMA_OR_END
                }
            }
            is ArrayContainer -> {
                if (parent.state == ArrayState.EXPECT_VALUE_OR_END) {
                    parent.state = ArrayState.EXPECT_COMMA_OR_END
                }
            }
            null -> Unit
        }
    }
}

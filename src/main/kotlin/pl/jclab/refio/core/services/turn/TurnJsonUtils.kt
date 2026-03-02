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
                        (any as Map<String, Any>).mapValues { (_, v) -> anyToJsonElement(v!!) }
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
        val repaired = repairInvalidJsonEscapes(arguments)
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
}

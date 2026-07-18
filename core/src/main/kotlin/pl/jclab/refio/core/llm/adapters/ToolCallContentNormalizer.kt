package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.llm.NativeToolCall
import pl.jclab.refio.core.llm.NativeToolCallDelta
import pl.jclab.refio.core.utils.GsonInstance.gson

internal object ToolCallContentNormalizer {
    data class NamedCall(
        val name: String,
        val arguments: Any?
    )

    internal class OpenAiStreamingToolCallAccumulator {
        private data class CallBuffer(
            var id: String? = null,
            var name: String? = null,
            val argumentsBuilder: StringBuilder = StringBuilder()
        )

        private val callsByIndex = linkedMapOf<Int, CallBuffer>()

        /**
         * Consume one streaming `delta` and return the per-call increments observed in it,
         * so the adapter can surface progressive tool-call building via
         * [StreamChunk.toolCallDelta]. The accumulator remains the source of truth for the final
         * [toNativeToolCalls]; the returned list is purely for live progress.
         */
        fun consumeDelta(delta: Map<String, Any?>?): List<NativeToolCallDelta> {
            if (delta == null) return emptyList()
            return consumeToolCalls(delta["tool_calls"])
        }

        /**
         * JsonObject variant of [consumeDelta] used by the SSE loop, which parses each
         * stream line into a Gson tree instead of nested Maps. Only the `tool_calls`
         * subtree (rare in a content stream) is converted to the Map shape the
         * accumulator understands.
         */
        fun consumeDelta(delta: com.google.gson.JsonObject?): List<NativeToolCallDelta> {
            val toolCalls = delta?.get("tool_calls") ?: return emptyList()
            if (toolCalls.isJsonNull) return emptyList()
            @Suppress("UNCHECKED_CAST")
            val asMaps = gson.fromJson(toolCalls, List::class.java) as? List<Map<String, Any?>>
                ?: return emptyList()
            return consumeToolCalls(asMaps)
        }

        fun consumeToolCalls(rawToolCalls: Any?): List<NativeToolCallDelta> {
            @Suppress("UNCHECKED_CAST")
            val toolCalls = rawToolCalls as? List<Map<String, Any?>> ?: return emptyList()

            val deltas = mutableListOf<NativeToolCallDelta>()
            for ((fallbackIndex, toolCall) in toolCalls.withIndex()) {
                val index = (toolCall["index"] as? Number)?.toInt() ?: fallbackIndex
                val buffer = callsByIndex.getOrPut(index) { CallBuffer() }

                val id = toolCall["id"] as? String
                if (!id.isNullOrBlank()) buffer.id = id

                @Suppress("UNCHECKED_CAST")
                val function = toolCall["function"] as? Map<String, Any?> ?: continue
                val rawName = function["name"] as? String
                var nameDelta: String? = null
                if (!rawName.isNullOrBlank()) {
                    buffer.name = normalizeToolName(rawName)
                    nameDelta = buffer.name
                }

                val args = function["arguments"]
                var argumentsDelta: String? = null
                when (args) {
                    is String -> {
                        buffer.argumentsBuilder.append(args)
                        argumentsDelta = args
                    }
                    is Map<*, *> -> {
                        if (buffer.argumentsBuilder.isEmpty()) {
                            val asJson = gson.toJson(args)
                            buffer.argumentsBuilder.append(asJson)
                            argumentsDelta = asJson
                        }
                    }
                }

                // Only surface a progress delta when something renderable arrived this chunk.
                if (nameDelta != null || !argumentsDelta.isNullOrEmpty()) {
                    deltas.add(
                        NativeToolCallDelta(
                            index = index,
                            idDelta = id,
                            nameDelta = nameDelta,
                            argumentsDelta = argumentsDelta,
                        )
                    )
                }
            }
            return deltas
        }

        fun toNativeToolCalls(toolsWereRequested: Boolean): List<NativeToolCall>? {
            if (!toolsWereRequested) return null
            return callsByIndex
                .toSortedMap()
                .values
                .mapNotNull { buffer ->
                    val name = buffer.name ?: return@mapNotNull null
                    NativeToolCall(
                        id = buffer.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        argumentsJson = buffer.argumentsBuilder.toString().ifEmpty { "{}" }
                    )
                }
        }

        fun toCanonicalJson(): String? {
            val namedCalls = callsByIndex
                .toSortedMap()
                .values
                .mapNotNull { buffer ->
                    val name = buffer.name ?: return@mapNotNull null
                    NamedCall(name = name, arguments = parseArguments(buffer.argumentsBuilder.toString()))
                }

            return toCanonicalJson(namedCalls)
        }
    }

    fun fromOpenAiToolCalls(rawToolCalls: Any?): String? {
        @Suppress("UNCHECKED_CAST")
        val toolCalls = rawToolCalls as? List<Map<String, Any?>> ?: return null

        val namedCalls = toolCalls.mapNotNull { toolCall ->
            @Suppress("UNCHECKED_CAST")
            val function = toolCall["function"] as? Map<String, Any?> ?: return@mapNotNull null
            val rawName = function["name"] as? String ?: return@mapNotNull null
            NamedCall(
                name = normalizeToolName(rawName),
                arguments = parseArguments(function["arguments"])
            )
        }
        return toCanonicalJson(namedCalls)
    }

    fun fromAnthropicContentBlocks(contentBlocks: List<Map<String, Any?>>): String? {
        val namedCalls = contentBlocks.mapNotNull { block ->
            if (block["type"] != "tool_use") return@mapNotNull null
            val name = block["name"] as? String ?: return@mapNotNull null
            NamedCall(
                name = normalizeToolName(name),
                arguments = parseArguments(block["input"])
            )
        }
        return toCanonicalJson(namedCalls)
    }

    fun fromGeminiParts(parts: List<Map<String, Any?>>): String? {
        val namedCalls = parts.mapNotNull { part ->
            @Suppress("UNCHECKED_CAST")
            val functionCall = part["functionCall"] as? Map<String, Any?> ?: return@mapNotNull null
            val name = functionCall["name"] as? String ?: return@mapNotNull null
            NamedCall(
                name = normalizeToolName(name),
                arguments = parseArguments(functionCall["args"])
            )
        }
        return toCanonicalJson(namedCalls)
    }

    fun normalizeToolName(rawName: String): String {
        return rawName.substringAfterLast('.').substringAfterLast('/')
    }

    private fun toCanonicalJson(namedCalls: List<NamedCall>): String? {
        if (namedCalls.isEmpty()) return null
        val actions = namedCalls.map {
            mapOf(
                "tool" to it.name,
                "arguments" to normalizeArgumentsObject(it.arguments)
            )
        }
        return gson.toJson(
            mapOf(
                "actions" to actions,
                "response" to ""
            )
        )
    }

    private fun parseArguments(raw: Any?): Any? {
        return when (raw) {
            null -> emptyMap<String, Any?>()
            is Map<*, *> -> raw.entries.associate { (k, v) -> k.toString() to v }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return emptyMap<String, Any?>()
                try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(trimmed, Map::class.java) as? Map<String, Any?> ?: mapOf("raw" to raw)
                } catch (_: Exception) {
                    mapOf("raw" to raw)
                }
            }
            else -> mapOf("value" to raw.toString())
        }
    }

    private fun normalizeArgumentsObject(args: Any?): Any {
        return when (args) {
            is Map<*, *> -> args.entries.associate { (k, v) -> k.toString() to v }
            null -> emptyMap<String, Any?>()
            else -> mapOf("value" to args.toString())
        }
    }
}


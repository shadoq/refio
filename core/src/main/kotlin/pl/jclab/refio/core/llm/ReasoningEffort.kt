package pl.jclab.refio.core.llm

/**
 * User-selectable reasoning strength. Replaces the legacy boolean thinking toggle.
 *
 * OFF means no extended reasoning. LOW/MEDIUM/HIGH scale the provider-native knob:
 * OpenAI / OpenRouter reasoning effort, Anthropic / Gemini token budget, Ollama on/off
 * (no magnitude). The translation from a level to that knob is done per adapter.
 */
enum class ReasoningEffort {
    OFF, LOW, MEDIUM, HIGH;

    val isOn: Boolean get() = this != OFF

    /**
     * Wire value handed to adapters via the `thinking` kwarg: `null` for OFF, otherwise the
     * lowercase effort string ("low"/"medium"/"high") that adapters already recognize.
     */
    fun toEffortString(): String? = when (this) {
        OFF -> null
        LOW -> "low"
        MEDIUM -> "medium"
        HIGH -> "high"
    }

    companion object {
        /** Parse a stored config value ("OFF"/"LOW"/...); null if unrecognized (e.g. legacy "true"). */
        fun parse(raw: String?): ReasoningEffort? =
            raw?.trim()?.uppercase()?.let { runCatching { valueOf(it) }.getOrNull() }

        /** Map an adapter-level effort string ("low"/"medium"/"high") back to a level; null otherwise. */
        fun fromEffortString(raw: String?): ReasoningEffort? = when (raw?.trim()?.lowercase()) {
            "low" -> LOW
            "medium" -> MEDIUM
            "high" -> HIGH
            else -> null
        }

        /**
         * Interpret the `thinking` kwarg an adapter receives. It is either a Boolean `true`
         * (legacy on, no magnitude -> MEDIUM), an effort String, or absent/false/blank (OFF).
         * A non-blank String that isn't a known level also means "on" -> MEDIUM.
         */
        fun fromThinkingKwarg(raw: Any?): ReasoningEffort = when (raw) {
            is String -> fromEffortString(raw) ?: if (raw.isNotBlank()) MEDIUM else OFF
            true -> MEDIUM
            else -> OFF
        }
    }
}

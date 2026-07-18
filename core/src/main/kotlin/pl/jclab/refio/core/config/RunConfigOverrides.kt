package pl.jclab.refio.core.config

/**
 * Parses CLI run-scope config overrides: `--config key=value` pairs plus optional
 * `--config-file` content, into a validated map applied as the highest-priority, read-only config
 * layer (see `ConfigResolver.runOverrides`).
 *
 * Fail-loud: an unknown key, an entry without `=`, or a value that fails the key's parser/validator
 * throws [IllegalArgumentException] naming the offending entry — never a silent default. Inline
 * `--config` pairs win over `--config-file` on duplicate keys.
 */
object RunConfigOverrides {

    /**
     * @param pairs raw `key=value` strings from repeated `--config` flags
     * @param fileContent optional `--config-file` body (already read); `#` comments and blank
     *   lines are ignored, remaining lines are `key=value`
     * @return validated key → raw-value map, suitable for `ConfigService(runConfigOverrides = …)`
     */
    fun parse(pairs: List<String>, fileContent: String? = null): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fileContent?.lineSequence()?.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val (key, value) = splitPair(trimmed)
            result[key] = validated(key, value)
        }
        // Inline pairs are applied last so they override anything from the file.
        pairs.forEach { raw ->
            val (key, value) = splitPair(raw.trim())
            result[key] = validated(key, value)
        }
        return result
    }

    private fun splitPair(entry: String): Pair<String, String> {
        val idx = entry.indexOf('=')
        require(idx > 0) { "Invalid config override '$entry' — expected key=value" }
        return entry.substring(0, idx).trim() to entry.substring(idx + 1).trim()
    }

    private fun validated(key: String, value: String): String {
        val configKey = ConfigKeys.byKey(key)
            ?: throw IllegalArgumentException("Unknown config key '$key' (not registered in ConfigKeys)")
        require(configKey.acceptsRaw(value)) {
            "Invalid value for config key '$key=$value' (failed parse/validation)"
        }
        return value
    }
}

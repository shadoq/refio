package pl.jclab.refio.core.services

/**
 * Helper object for working with configuration keys in UI panels.
 *
 * Provides utilities to split full configuration keys (e.g. "limits.api_call_timeout")
 * into section and key components for use with SettingsView.onSettingChanged() callback.
 *
 * This ensures all configuration keys use constants from ConfigService and eliminates
 * hardcoded strings in UI panels.
 */
object ConfigKeyUtil {

    /**
     * Split a full configuration key into (section, key) pair.
     *
     * Examples:
     * - "limits.api_call_timeout" -> ("limits", "api_call_timeout")
     * - "ui.thinking_enabled" -> ("ui", "thinking_enabled")
     * - "general.streaming_enabled" -> ("general", "streaming_enabled")
     *
     * @param fullKey Full configuration key from ConfigService constants
     * @return Pair of (section, key)
     * @throws IllegalArgumentException if key doesn't contain a dot separator
     */
    fun split(fullKey: String): Pair<String, String> {
        val parts = fullKey.split(".", limit = 2)
        require(parts.size == 2) { "Invalid config key format: $fullKey (expected 'section.key')" }
        return Pair(parts[0], parts[1])
    }

    /**
     * Get section from full configuration key.
     *
     * @param fullKey Full configuration key from ConfigService constants
     * @return Section part (e.g. "limits", "ui", "general")
     */
    fun section(fullKey: String): String = split(fullKey).first

    /**
     * Get key from full configuration key.
     *
     * @param fullKey Full configuration key from ConfigService constants
     * @return Key part (e.g. "api_call_timeout", "thinking_enabled")
     */
    fun key(fullKey: String): String = split(fullKey).second

    /**
     * Join section and key into full configuration key.
     *
     * @param section Configuration section
     * @param key Configuration key
     * @return Full configuration key (e.g. "limits.api_call_timeout")
     */
    fun join(section: String, key: String): String = "$section.$key"
}

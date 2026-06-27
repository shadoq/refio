package pl.jclab.refio.core.config

/**
 * Renders the resolved Refio configuration for the CLI `--print-config` flag (docs/0063) — a
 * deterministic, LLM-free view of what each [ConfigKey] resolves to after run-scope overrides are
 * applied (override > DB > YAML > default). Secrets are redacted; overridden keys are marked.
 */
object ConfigPrintView {

    data class Entry(val key: String, val value: String, val isOverride: Boolean)

    /** True for keys whose value is sensitive and must never be printed verbatim. */
    fun isSecret(key: String): Boolean =
        key.contains("api_key", ignoreCase = true) ||
            key.contains("secret", ignoreCase = true) ||
            key.contains("token", ignoreCase = true)

    fun render(entries: List<Entry>): String = buildString {
        appendLine("# Resolved Refio config (override > DB > YAML > default)")
        entries.forEach { e ->
            val shown = if (isSecret(e.key) && e.value.isNotBlank()) "***redacted***" else e.value
            val marker = if (e.isOverride) "  [override]" else ""
            appendLine("${e.key} = $shown$marker")
        }
    }
}

package pl.jclab.refio.core.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Shared Gson instances with disabled HTML escaping for better readability in logs.
 *
 * By default, Gson escapes HTML characters like <, >, &, ', " as Unicode escape sequences
 * (\u003c, \u003e, etc.) which makes logs hard to read.
 *
 * Use these instances instead of creating new Gson() everywhere.
 *
 * Example:
 * ```kotlin
 * // Before: private val gson = Gson()
 * // After:  import pl.jclab.refio.core.utils.GsonInstance.gson
 *
 * val json = gson.toJson(data)
 * ```
 */
object GsonInstance {
    /**
     * Standard Gson instance with HTML escaping disabled.
     * Use for general JSON serialization/deserialization.
     */
    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()  // Don't escape <, >, &, ', " as \uXXXX
        .create()

    /**
     * Pretty-printing Gson instance with HTML escaping disabled.
     * Use for debug logs and human-readable output.
     */
    val prettyGson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()
}

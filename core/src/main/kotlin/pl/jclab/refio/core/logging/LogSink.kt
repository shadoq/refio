package pl.jclab.refio.core.logging

/**
 * Platform-agnostic log sink for UI/panel display.
 *
 * IntelliJ implements this via PluginLoggerSink (wrapping PluginLogger),
 * CLI can implement via TUI panel, ANSI output, or leave null for SLF4J-only logging.
 *
 * DualLogger delegates to this interface instead of directly calling PluginLogger,
 * breaking the compile-time dependency on IntelliJ APIs in the core module.
 */
interface LogSink {
    fun debug(component: String, message: String)
    fun info(component: String, message: String)
    fun warn(component: String, message: String)
    fun error(component: String, message: String, throwable: Throwable? = null)
}

package pl.jclab.refio.core.logging

/**
 * Global registry for [LogSink].
 *
 * Platform layer registers its implementation at startup:
 * - IntelliJ: registers PluginLoggerSink in ProjectStartupActivity
 * - CLI: registers CliLogSink or leaves null (SLF4J-only)
 *
 * If no sink is registered, [DualLogger] gracefully degrades to kotlin-logging only.
 */
object LogSinkRegistry {
    @Volatile
    private var sink: LogSink? = null

    fun register(sink: LogSink) {
        this.sink = sink
    }

    fun get(): LogSink? = sink

    fun clear() {
        sink = null
    }
}

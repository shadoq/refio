package pl.jclab.refio.services.logging

import pl.jclab.refio.core.logging.LogSink

/**
 * IntelliJ-specific [LogSink] implementation that delegates to [PluginLogger].
 *
 * Registered in [LogSinkRegistry] during plugin startup to enable
 * DualLogger UI output in IntelliJ environment.
 */
class PluginLoggerSink : LogSink {

    private val pluginLogger: PluginLogger?
        get() = try {
            PluginLogger.getInstance()
        } catch (e: Exception) {
            null
        }

    override fun debug(component: String, message: String) {
        pluginLogger?.debug(component, message)
    }

    override fun info(component: String, message: String) {
        pluginLogger?.info(component, message)
    }

    override fun warn(component: String, message: String) {
        pluginLogger?.warn(component, message)
    }

    override fun error(component: String, message: String, throwable: Throwable?) {
        pluginLogger?.error(component, message, throwable)
    }
}

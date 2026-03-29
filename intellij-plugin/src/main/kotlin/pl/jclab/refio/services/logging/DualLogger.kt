@file:Suppress("unused")

package pl.jclab.refio.services.logging

import mu.KotlinLogging

/**
 * Backward-compatibility re-exports from [pl.jclab.refio.core.logging].
 *
 * DualLogger has been moved to [pl.jclab.refio.core.logging.DualLogger]
 * to break the compile-time dependency on IntelliJ APIs in the core module.
 *
 * These typealiases and functions ensure that existing code outside core/
 * (UI, services, actions) continues to compile without import changes.
 */
typealias DualLogger = pl.jclab.refio.core.logging.DualLogger

/**
 * Re-export dualLogger() factory for backward compatibility.
 *
 * New code should import from [pl.jclab.refio.core.logging.dualLogger].
 */
inline fun <reified T : Any> T.dualLogger(): pl.jclab.refio.core.logging.DualLogger {
    val componentName = T::class.simpleName ?: "Unknown"
    return pl.jclab.refio.core.logging.DualLogger(
        kotlinLogger = KotlinLogging.logger(T::class.java.name),
        component = componentName
    )
}

/**
 * Re-export dualLogger(component) factory for backward compatibility.
 *
 * New code should import from [pl.jclab.refio.core.logging.dualLogger].
 */
fun dualLogger(component: String): pl.jclab.refio.core.logging.DualLogger {
    return pl.jclab.refio.core.logging.dualLogger(component)
}

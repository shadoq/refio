package pl.jclab.refio.core.services

import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.logging.dualLogger

/**
 * Runs [ConfigKey.validator] for every key in the registry against the currently-resolved value.
 *
 * Invoked at startup (after defaults/YAML bootstrap) and after every YAML reload. A single
 * failure aborts the run with [InvalidConfigException] — broken configuration should fail loud,
 * not silently fall back to defaults.
 */
class ConfigValidator(private val configService: ConfigService) {

    private val logger = dualLogger("ConfigValidator")

    fun validateAll() {
        val failures = mutableListOf<ValidationFailure>()
        for (key in ConfigKeys.allKeys()) {
            val failure = validate(key)
            if (failure != null) failures += failure
        }
        if (failures.isNotEmpty()) {
            logger.error { "Config validation failed for ${failures.size} key(s):" }
            for (f in failures) logger.error { "  ${f.key}: rejected value '${f.rawValue}'" }
            throw InvalidConfigException(failures)
        }
        logger.info { "Config validation passed (${ConfigKeys.allKeys().size} keys)" }
    }

    private fun <T> validate(key: ConfigKey<T>): ValidationFailure? {
        val resolved = configService.getTyped(key)
        return if (!key.validator(resolved)) {
            ValidationFailure(key = key.key, rawValue = resolved?.toString() ?: "null")
        } else null
    }

    data class ValidationFailure(val key: String, val rawValue: String)

    class InvalidConfigException(val failures: List<ValidationFailure>) :
        RuntimeException("Invalid config: " + failures.joinToString { "${it.key}='${it.rawValue}'" })
}

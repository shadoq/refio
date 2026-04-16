package pl.jclab.refio.core.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File

/**
 * Load / save / serialize operations for [ConfigYaml].
 *
 * Kept separate from the data-class declaration so that `ConfigYaml.kt` stays a thin record
 * of the schema, while parsing/encoding details live here.
 */
internal object ConfigYamlIO {

    private val yaml = Yaml(
        configuration = YamlConfiguration(strictMode = false)
    )

    fun loadFromPath(configFile: File): ConfigYaml? {
        if (!configFile.exists()) return null

        return try {
            decode(configFile.readText())
        } catch (e: Exception) {
            println("Error loading config YAML from ${configFile.absolutePath}: ${e.message}")
            null
        }
    }

    fun toYamlString(config: ConfigYaml): String =
        yaml.encodeToString(ConfigYaml.serializer(), config)

    fun saveToFile(config: ConfigYaml, file: File, withComments: Boolean) {
        file.parentFile?.mkdirs()
        val content = if (withComments) ConfigYamlEmitter.createCommentedYaml(config) else toYamlString(config)
        file.writeText(content)
    }

    private fun decode(yamlContent: String): ConfigYaml {
        val firstAttempt = runCatching {
            yaml.decodeFromString(ConfigYaml.serializer(), yamlContent)
        }
        if (firstAttempt.isSuccess) return firstAttempt.getOrThrow()

        val sanitizedEscapes = YamlSanitizer.sanitizeInvalidDoubleQuotedEscapes(yamlContent)
        val sanitizedBrokenLines = YamlSanitizer.sanitizeBrokenStandaloneEmptyQuotedLines(sanitizedEscapes)

        if (sanitizedBrokenLines == yamlContent) {
            throw firstAttempt.exceptionOrNull() ?: IllegalStateException("Unknown YAML parsing error")
        }

        val secondAttempt = runCatching {
            yaml.decodeFromString(ConfigYaml.serializer(), sanitizedBrokenLines)
        }
        if (secondAttempt.isSuccess) {
            if (sanitizedEscapes != yamlContent) {
                println("Config YAML parser fallback: sanitized invalid double-quoted escape sequences")
            }
            if (sanitizedBrokenLines != sanitizedEscapes) {
                println("Config YAML parser fallback: repaired broken standalone empty-quoted lines")
            }
            return secondAttempt.getOrThrow()
        }

        throw secondAttempt.exceptionOrNull()
            ?: firstAttempt.exceptionOrNull()
            ?: IllegalStateException("Unknown YAML parsing error")
    }
}

package pl.jclab.refio.core.services

import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository

/**
 * Pure lookup/write layer for configuration values.
 *
 * Owns the hierarchy resolution (TASK > PROJECT > APP > YAML > default), the
 * cache, and cache invalidation — factored out of [ConfigService] so the
 * facade keeps only the high-level helpers and delegates lookup concerns here.
 */
internal class ConfigResolver(
    private val configRepository: ConfigRepository,
    private val yamlLoader: HierarchicalConfigLoader,
    private val cache: ConfigCache,
    private val defaultProjectId: String?,
    /**
     * Run-scope overrides (key → raw string), highest priority, read-only. Checked before
     * cache/DB/YAML/default and never persisted. See [pl.jclab.refio.core.services.ConfigService].
     */
    private val runOverrides: Map<String, String> = emptyMap(),
) {

    /**
     * Get a typed configuration value using a [ConfigKey] descriptor.
     *
     * Lookup order (highest priority first):
     * 1. Database value (task-scoped, then project-scoped, then app-scoped)
     * 2. YAML config value via the key's yamlAccessor
     * 3. The key's built-in default
     */
    fun <T> getTyped(configKey: ConfigKey<T>, taskId: String? = null): T {
        // Run-scope override wins over everything (cache/DB/YAML/default) and is never cached or
        // persisted. A non-parseable override falls through to the normal chain (the CLI layer
        // validates and rejects bad values loudly upstream).
        runOverrides[configKey.key]?.let { raw ->
            configKey.parser(raw)?.let { return it }
        }
        val cacheKey = "typed:${configKey.key}:task=${taskId.orEmpty()}"
        return cache.getOrCompute(cacheKey) {
            val dbConfig = getConfigWithPrecedence(key = configKey.key, taskId = taskId)
            if (dbConfig?.value != null) {
                val parsed = configKey.parser(dbConfig.value)
                if (parsed != null) return@getOrCompute parsed
            }

            val yamlValue = configKey.yamlAccessor?.invoke(yamlLoader)
            if (yamlValue != null) {
                val parsed = configKey.parser(yamlValue.toString())
                if (parsed != null) return@getOrCompute parsed
            }

            configKey.default
        }
    }

    fun <T> setTyped(configKey: ConfigKey<T>, value: T, scope: ConfigScope = ConfigScope.APP, taskId: String? = null) {
        val serialized = configKey.serializer(value)
        configRepository.set(
            key = configKey.key,
            value = serialized,
            scope = scope,
            taskId = taskId,
            description = null,
        )
        invalidate(configKey.key)
    }

    /**
     * Raw string lookup with hierarchy resolution.
     *
     * Useful for dynamic keys not backed by a [ConfigKey] descriptor (provider
     * keys, etc.). Returns null when no DB or YAML value exists.
     */
    fun get(
        key: String,
        scope: ConfigScope = ConfigScope.APP,
        taskId: String? = null,
        projectId: String? = null,
    ): String? {
        // Run-scope override wins over DB/YAML regardless of scope; read-only, never persisted.
        runOverrides[key]?.let { return it }
        val resolvedProject = resolveProjectId(projectId)
        val cacheKey = "raw:$key:scope=${scope.name}:task=${taskId.orEmpty()}:project=${resolvedProject.orEmpty()}"
        return cache.getOrCompute(cacheKey) {
            val dbConfig = when {
                taskId != null -> getConfigWithPrecedence(key = key, taskId = taskId, projectId = projectId)
                scope == ConfigScope.PROJECT ->
                    resolvedProject?.let { configRepository.get(key, ConfigScope.PROJECT, projectId = it) }
                else -> configRepository.get(key, scope)
            }
            dbConfig?.value ?: getFromYaml(key)
        }
    }

    fun getFromYaml(key: String): String? {
        val cacheKey = "yaml:$key"
        return cache.getOrCompute(cacheKey) {
            val configKey = ConfigKeys.byKey(key) ?: return@getOrCompute null
            configKey.yamlAccessor?.invoke(yamlLoader)?.toString()
        }
    }

    fun set(
        key: String,
        value: String,
        scope: ConfigScope = ConfigScope.APP,
        taskId: String? = null,
        projectId: String? = null,
    ) {
        val resolvedProjectId = resolveProjectId(projectId)
        configRepository.set(
            key = key,
            value = value,
            scope = scope,
            projectId = if (scope == ConfigScope.PROJECT) resolvedProjectId else null,
            taskId = taskId,
            description = null,
        )
        invalidate(key)
    }

    fun getConfigWithPrecedence(
        key: String,
        taskId: String? = null,
        projectId: String? = null,
    ) = configRepository.getWithPrecedence(
        key = key,
        taskId = taskId,
        projectId = resolveProjectId(projectId),
    )

    fun invalidate(key: String) {
        cache.invalidateByPrefix("typed:$key:")
        cache.invalidateByPrefix("raw:$key:")
        cache.invalidate("yaml:$key")
    }

    private fun resolveProjectId(projectId: String?): String? = projectId ?: defaultProjectId
}

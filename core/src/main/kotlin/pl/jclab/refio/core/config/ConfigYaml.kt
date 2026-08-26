package pl.jclab.refio.core.config

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Path

/**
 * Data model for YAML configuration file.
 *
 * Configuration hierarchy (from lowest to highest priority):
 * 1. Built-in defaults (hardcoded in ConfigService)
 * 2. User config: ~/.refio/config.yaml (Linux/macOS) or %USERPROFILE%\.refio\config.yaml (Windows)
 * 3. Project config: <project_root>/.refio/config.yaml (project-specific settings only)
 * 4. Database overrides (settings changed via Settings UI)
 *
 * Project-specific settings (only in project config):
 * - prompts (system prompts, commands, rules)
 * - mcp (MCP server configurations)
 * - models.visibility (which models to show for this project)
 * - rag (project-specific RAG settings)
 *
 * User-level settings (in user config):
 * - providers (API keys, endpoints)
 * - models.default (default model selections)
 * - general (UI preferences)
 * - limits (timeouts, size limits)
 * - advanced (security settings)
 * - tools (tool permissions)
 * - terminal (terminal command whitelist)
 */
@Serializable
data class ConfigYaml(
    val general: GeneralConfig? = null,
    val providers: ProvidersConfig? = null,
    val models: ModelsConfig? = null,
    val limits: LimitsConfig? = null,
    val advanced: AdvancedConfig? = null,
    val tools: ToolsConfig? = null,
    val rag: RagConfig? = null,
    val ui: UiConfig? = null,
    val prompts: PromptsConfig? = null,
    val mcp: McpConfig? = null,
    val hooks: HooksConfig? = null,
    val context: ContextConfig? = null,
    val docs: DocsConfig? = null,
    val verify: VerifyConfig? = null
) {
    companion object {
        fun getUserConfigPath(): File = RefioHome.resolve("config.yaml").toFile()

        fun getProjectConfigPath(projectRoot: Path): File =
            projectRoot.resolve(".refio").resolve("config.yaml").toFile()

        /** Legacy alias for [getUserConfigPath]. */
        fun getConfigPath(): File = getUserConfigPath()

        fun load(): ConfigYaml? = ConfigYamlIO.loadFromPath(getUserConfigPath())

        fun loadUserConfig(): ConfigYaml? = ConfigYamlIO.loadFromPath(getUserConfigPath())

        fun loadProjectConfig(projectRoot: Path): ConfigYaml? =
            ConfigYamlIO.loadFromPath(getProjectConfigPath(projectRoot))

        /** Merge two configs — values from `override` take precedence over `base`. */
        fun merge(base: ConfigYaml?, override: ConfigYaml?): ConfigYaml =
            ConfigYamlMerger.merge(base, override)

        fun toYamlString(config: ConfigYaml): String = ConfigYamlIO.toYamlString(config)

        fun saveToFile(config: ConfigYaml, file: File, withComments: Boolean = true) =
            ConfigYamlIO.saveToFile(config, file, withComments)

        /** Static, fully-documented template for the "Example config" UI panel. */
        fun createExampleConfig(): String = ConfigYamlEmitter.createExampleConfig()
    }
}

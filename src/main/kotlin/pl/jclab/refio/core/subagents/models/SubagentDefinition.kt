package pl.jclab.refio.core.subagents.models

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.services.ConfigService
import java.nio.file.Path

/**
 * Scope subagenta - określa skąd został załadowany.
 */
enum class SubagentScope {
    PROJECT,    // .refio/agents/
    USER,       // ~/.refio/agents/
    BUILTIN     // Wbudowany (z resources)
}

/**
 * Definicja subagenta sparsowana z pliku .md.
 * Zgodna z formatem Claude Code Sub-agents.
 *
 * @see <a href="https://code.claude.com/docs/en/sub-agents">Claude Code Sub-agents</a>
 */
data class SubagentDefinition(
    /**
     * Unikalna nazwa subagenta (snake_case lub kebab-case).
     * Używana do wywoływania: !agent-name
     */
    val name: String,

    /**
     * Krótki opis kiedy agent powinien być wywołany.
     * Używany przy auto-delegacji i w UI.
     */
    val description: String,

    /**
     * System prompt dla subagenta (treść po YAML frontmatter).
     */
    val systemPrompt: String,

    /**
     * Lista dozwolonych narzędzi (whitelist).
     * null = dziedziczy wszystkie z rodzica (inherit).
     */
    val allowedTools: List<String>? = null,

    /**
     * Lista narzędzi do wykluczenia (blacklist).
     * Używana tylko gdy allowedTools == null.
     */
    val disallowedTools: List<String>? = null,

    /**
     * Model do użycia.
     * Wartości: "inherit", "sonnet", "opus", "haiku", "default", "plan", "coding", "weak"
     * lub konkretny model ID.
     */
    val model: String = "default",

    /**
     * Lista skills do auto-załadowania.
     * Subagenty NIE dziedziczą skills z głównej konwersacji.
     */
    val skills: List<String> = emptyList(),

    /**
     * Czy subagent jest aktywny.
     */
    val enabled: Boolean = true,

    /**
     * Priorytet przy auto-detekcji (wyższy = wcześniej).
     */
    val priority: Int = 0,

    /**
     * Ścieżka do pliku źródłowego (null dla wbudowanych).
     */
    val sourcePath: Path? = null,

    /**
     * Skąd pochodzi definicja.
     */
    val scope: SubagentScope = SubagentScope.PROJECT,

    /**
     * Tryb wykonania subagenta.
     * - single_shot: jeden cykl LLM (domyślny)
     * - multi_step: własna pętla wykonania z planem
     */
    val executionMode: SubagentExecutionMode = SubagentExecutionMode.SINGLE_SHOT,

    /**
     * Maksymalna liczba kroków (tylko dla multi_step).
     */
    val maxSteps: Int = 10
) {
    /**
     * Rozwiązuje alias modelu na rzeczywisty ID.
     * Zgodne z Claude Code: inherit, sonnet, opus, haiku + aliasy Refio.
     *
     * @param configService Serwis konfiguracji
     * @param parentModel Model z głównej konwersacji (dla "inherit")
     * @return Pair(modelId, provider)
     */
    fun resolveModel(configService: ConfigService, parentModel: String? = null): Pair<String, String> {
        return when (model.lowercase()) {
            // Claude Code model aliases
            "inherit" -> {
                if (parentModel != null) {
                    parseModelString(parentModel)
                } else {
                    configService.getModel(ModelOperation.DEFAULT)
                }
            }
            "sonnet" -> Pair("claude-3-5-sonnet-20241022", "anthropic")
            "opus" -> Pair("claude-3-opus-20240229", "anthropic")
            "haiku" -> Pair("claude-3-haiku-20240307", "anthropic")

            "default" -> configService.getModel(ModelOperation.DEFAULT)
            "plan" -> configService.getModel(ModelOperation.PLAN)
            "coding" -> configService.getModel(ModelOperation.CODING)
            "weak" -> configService.getModel(ModelOperation.WEAK)

            else -> parseModelString(model)
        }
    }

    /**
     * Sprawdza czy definicja używa whitelist dla narzędzi.
     */
    fun usesToolWhitelist(): Boolean = allowedTools != null

    /**
     * Sprawdza czy definicja używa blacklist dla narzędzi.
     */
    fun usesToolBlacklist(): Boolean = disallowedTools != null && allowedTools == null

    /**
     * Parsuje string modelu w formacie "provider/model" lub "model".
     */
    private fun parseModelString(modelString: String): Pair<String, String> {
        if (modelString.contains("/")) {
            val parts = modelString.split("/", limit = 2)
            return Pair(parts[1], parts[0])
        }

        // Infer provider from model name patterns
        val provider = when {
            modelString.startsWith("gpt-") || modelString.startsWith("o1") || modelString.startsWith("o3") -> "openai"
            modelString.startsWith("claude-") -> "anthropic"
            modelString.startsWith("gemini-") -> "gemini"
            else -> "ollama"
        }

        return Pair(modelString, provider)
    }
}

/**
 * Tryb wykonania subagenta.
 */
enum class SubagentExecutionMode {
    /**
     * Jeden cykl LLM z możliwością wywołania narzędzi.
     * Domyślny dla prostych zadań analitycznych.
     */
    SINGLE_SHOT,

    /**
     * Własna pętla wykonania z generowaniem planu.
     * Dla złożonych zadań wymagających wielu operacji.
     */
    MULTI_STEP
}

package pl.jclab.refio.core.benchmarks

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.config.ConfigYaml
import pl.jclab.refio.core.config.ConfigYamlIO
import pl.jclab.refio.core.config.GeneralConfig
import pl.jclab.refio.core.config.LimitsConfig
import pl.jclab.refio.core.config.OllamaConfig
import pl.jclab.refio.core.config.OpenAIConfig
import pl.jclab.refio.core.config.ProvidersConfig
import pl.jclab.refio.core.llm.ModelDefinitions
import java.io.File
import java.nio.file.Path
import kotlin.system.measureNanoTime

/**
 * Lekkie benchmarki baseline dla Sprint 0.
 *
 * **Cel**: zarejestrować referencyjny czas wykonania operacji które będą refaktorowane
 * w Sprint 1-3. Po każdym sprincie porównujemy — regresja >20% = flag do analizy.
 *
 * **Zasady**:
 * - `measureNanoTime` × N iteracji, mediana (bardziej stabilna niż średnia).
 * - Warmup przed pomiarem (JIT kompiluje).
 * - Tagged `benchmark` — nie uruchamia się w normalnym `./gradlew test`.
 *   Uruchamiać: `./gradlew :core:test --tests "*Benchmark*"`.
 *
 * **NIE jest to JMH** — dla lokalnego pluginu to overkill. Szukamy regresji >20%,
 * nie mikroskopijnych różnic.
 */
@Tag("benchmark")
class BenchmarkBaseline {

    private val warmupIterations = 50
    private val measureIterations = 200

    @Test
    fun `benchmark config yaml load and parse`(@TempDir tempDir: Path) {
        val config = ConfigYaml(
            general = GeneralConfig(formatMarkdown = true, streamingEnabled = true, advancedView = false),
            providers = ProvidersConfig(
                openai = OpenAIConfig(apiKey = "sk-test-key-1234567890"),
                ollama = OllamaConfig(endpoint = "http://localhost:11434", contextSize = 32768)
            ),
            limits = LimitsConfig(apiCallTimeout = 120, maxOutputSize = 8192)
        )
        val file = tempDir.resolve("config.yaml").toFile()
        ConfigYaml.saveToFile(config, file, withComments = false)

        // Warmup
        repeat(warmupIterations) { decodePrivate(file) }

        // Measure
        val samples = (1..measureIterations).map {
            measureNanoTime { decodePrivate(file) }
        }

        reportBenchmark("config_yaml_load_and_parse", samples)
    }

    @Test
    fun `benchmark ModelDefinitions getDefinition lookup`() {
        val knownIds = listOf("gpt-4o-mini", "gpt-4o", "gpt-5.1", "o1", "o3")

        // Warmup
        repeat(warmupIterations) {
            knownIds.forEach { ModelDefinitions.getDefinition("openai", it) }
        }

        // Measure
        val samples = (1..measureIterations).map {
            measureNanoTime {
                knownIds.forEach { ModelDefinitions.getDefinition("openai", it) }
            }
        }

        reportBenchmark("model_definitions_lookup_5_models", samples)
    }

    @Test
    fun `benchmark ModelDefinitions syntheticDefinitionFor`() {
        val unknownIds = listOf("gpt-9999", "gpt-fake", "future-model", "unknown-1", "unknown-2")

        repeat(warmupIterations) {
            unknownIds.forEach { ModelDefinitions.syntheticDefinitionFor("openai", it, 32768) }
        }

        val samples = (1..measureIterations).map {
            measureNanoTime {
                unknownIds.forEach { ModelDefinitions.syntheticDefinitionFor("openai", it, 32768) }
            }
        }

        reportBenchmark("model_definitions_synthetic_5_models", samples)
    }

    private fun decodePrivate(file: File): ConfigYaml =
        checkNotNull(ConfigYamlIO.loadFromPath(file)) { "Failed to load benchmark config from $file" }

    private fun reportBenchmark(name: String, samplesNs: List<Long>) {
        val sorted = samplesNs.sorted()
        val medianNs = sorted[sorted.size / 2]
        val p95Ns = sorted[(sorted.size * 95) / 100]
        val minNs = sorted.first()
        val maxNs = sorted.last()

        println(
            "BENCHMARK[$name]: " +
                "median=${medianNs / 1_000}µs " +
                "p95=${p95Ns / 1_000}µs " +
                "min=${minNs / 1_000}µs " +
                "max=${maxNs / 1_000}µs " +
                "(${samplesNs.size} samples)"
        )
    }
}

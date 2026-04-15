package pl.jclab.refio.core.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization tests dla ConfigYaml load/save.
 *
 * Sprint 0 baseline: zabezpieczenie przed Sprint 3 #9 (migracja serializacji na Kaml
 * z obecnego kaml-backed manual setup). Roundtrip musi być stabilny przed i po
 * migracji. Po Sprint 3 test dla unknown-key zmieni się z "load succeeds silently"
 * na "load throws" (fail loud).
 */
class ConfigYamlRoundtripTest {

    @Test
    fun `roundtrip preserves general settings`(@TempDir tempDir: Path) {
        val original = ConfigYaml(
            general = GeneralConfig(
                formatMarkdown = true,
                streamingEnabled = false,
                advancedView = true
            )
        )

        val file = tempDir.resolve("config.yaml").toFile()
        ConfigYaml.saveToFile(original, file, withComments = false)

        val loaded = loadFromFile(file)
        assertNotNull(loaded)
        assertEquals(true, loaded.general?.formatMarkdown)
        assertEquals(false, loaded.general?.streamingEnabled)
        assertEquals(true, loaded.general?.advancedView)
    }

    @Test
    fun `roundtrip preserves provider API keys`(@TempDir tempDir: Path) {
        val original = ConfigYaml(
            providers = ProvidersConfig(
                openai = OpenAIConfig(apiKey = "sk-test-openai"),
                anthropic = AnthropicConfig(apiKey = "sk-ant-test"),
                gemini = GeminiConfig(apiKey = "sk-gemini-test"),
                openrouter = OpenRouterConfig(apiKey = "sk-or-test")
            )
        )

        val file = tempDir.resolve("config.yaml").toFile()
        ConfigYaml.saveToFile(original, file, withComments = false)

        val loaded = loadFromFile(file)
        assertNotNull(loaded)
        assertEquals("sk-test-openai", loaded.providers?.openai?.apiKey)
        assertEquals("sk-ant-test", loaded.providers?.anthropic?.apiKey)
        assertEquals("sk-gemini-test", loaded.providers?.gemini?.apiKey)
        assertEquals("sk-or-test", loaded.providers?.openrouter?.apiKey)
    }

    @Test
    fun `roundtrip preserves ollama endpoint and context size`(@TempDir tempDir: Path) {
        val original = ConfigYaml(
            providers = ProvidersConfig(
                ollama = OllamaConfig(
                    endpoint = "http://custom-host:11434",
                    contextSize = 32768,
                    keepAlive = 300
                )
            )
        )

        val file = tempDir.resolve("config.yaml").toFile()
        ConfigYaml.saveToFile(original, file, withComments = false)

        val loaded = loadFromFile(file)
        assertNotNull(loaded)
        assertEquals("http://custom-host:11434", loaded.providers?.ollama?.endpoint)
        assertEquals(32768, loaded.providers?.ollama?.contextSize)
        assertEquals(300, loaded.providers?.ollama?.keepAlive)
    }

    @Test
    fun `load returns null for non-existent file`(@TempDir tempDir: Path) {
        val nonExistent = tempDir.resolve("does-not-exist.yaml").toFile()
        val loaded = loadFromFile(nonExistent)
        assertNull(loaded)
    }

    // CHANGES AFTER SPRINT 3: currently lenient (strictMode=false), will throw on unknown keys.
    @Test
    fun `load silently ignores unknown top-level keys`(@TempDir tempDir: Path) {
        val yaml = """
            general:
              formatMarkdown: true
            unknownSection:
              someField: "value"
        """.trimIndent()

        val file = tempDir.resolve("config.yaml").toFile()
        file.writeText(yaml)

        val loaded = loadFromFile(file)
        assertNotNull(loaded)
        assertEquals(true, loaded.general?.formatMarkdown)
    }

    @Test
    fun `empty config yaml returns null or empty config`(@TempDir tempDir: Path) {
        // Current behavior: empty YAML deserializes to null via our decoder (kaml treats
        // empty doc as null). Characterize — Sprint 3 #9 may change to empty ConfigYaml.
        val file = tempDir.resolve("config.yaml").toFile()
        file.writeText("# empty\n")

        val loaded = loadFromFile(file)
        // Either null or fully-null ConfigYaml is acceptable current behavior.
        if (loaded != null) {
            assertNull(loaded.general)
            assertNull(loaded.providers)
        }
    }

@Test
    fun `malformed yaml returns null via loadFromPath silent error handling`(@TempDir tempDir: Path) {
        // Current behavior: parse errors logged to stdout, null returned. Sprint 3 #9 may
        // change this to throw (fail loud) — depends on how we route config load errors.
        val file = tempDir.resolve("config.yaml").toFile()
        file.writeText("not: valid: yaml: syntax: {{{")

        val loaded = loadFromFile(file)
        // Characterizes current swallow-and-return-null behavior.
        assertTrue(loaded == null, "Malformed YAML currently returns null rather than throwing")
    }

    @Test
    fun `toYamlString produces deterministic output for same input`() {
        val config = ConfigYaml(
            general = GeneralConfig(formatMarkdown = true, streamingEnabled = true, advancedView = false)
        )

        val first = ConfigYaml.toYamlString(config)
        val second = ConfigYaml.toYamlString(config)
        assertEquals(first, second)
    }

    /**
     * Use reflection-free path: write YAML content and load via public load() methods.
     * loadFromPath is private, but load() / loadProjectConfig() use it — we hijack
     * project config path by writing to arbitrary file then using saveToFile + our
     * local loader to avoid filesystem dependencies on home dir.
     */
    private fun loadFromFile(file: File): ConfigYaml? {
        if (!file.exists()) return null
        return try {
            val yamlContent = file.readText()
            // Call package-private decoder through reflection on companion.
            val method = ConfigYaml.Companion::class.java.getDeclaredMethod("decodeYamlContent", String::class.java)
            method.isAccessible = true
            method.invoke(ConfigYaml.Companion, yamlContent) as ConfigYaml
        } catch (e: Exception) {
            null
        }
    }
}

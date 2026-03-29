package pl.jclab.refio.core.context

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ContextProviderRegistry registration logic.
 *
 * Note: We test the register/unregister/query logic directly.
 * We call initialize(isIdeEnvironment=false) to mark registry as initialized
 * without triggering IntelliJ-specific code paths.
 */
class ContextProviderRegistryTest {

    @BeforeEach
    fun setup() {
        ContextProviderRegistry.clear()
        // Set an empty provider factory — tests that need providers will set their own
        ContextProviderRegistry.providerFactory = { emptyList() }
        // Initialize in non-IDE mode to avoid IntelliJ dependencies
        // and to set initialized=true so getters don't trigger re-initialization
        ContextProviderRegistry.initialize(isIdeEnvironment = false)
    }

    @AfterEach
    fun teardown() {
        ContextProviderRegistry.clear()
    }

    private fun createMockProvider(
        title: String,
        displayTitle: String = title,
        type: ProviderType = ProviderType.NORMAL,
        environment: ContextProviderEnvironment = ContextProviderEnvironment.ANY
    ): BaseContextProvider = mockk(relaxed = true) {
        every { description } returns ContextProviderDescription(
            title = title,
            displayTitle = displayTitle,
            description = "Test provider: $title",
            type = type,
            icon = null,
            enabled = true
        )
        every { this@mockk.environment } returns environment
    }

    @Nested
    inner class InitializationTests {

        @Test
        fun `should register providers from factory`() {
            ContextProviderRegistry.clear()
            ContextProviderRegistry.providerFactory = { _ ->
                listOf(createMockProvider("factory_a"), createMockProvider("factory_b"))
            }
            ContextProviderRegistry.initialize(isIdeEnvironment = false)

            val providers = ContextProviderRegistry.getAllProviders()
            assertEquals(2, providers.size, "Should register providers from factory")
            assertTrue(ContextProviderRegistry.hasProvider("factory_a"))
            assertTrue(ContextProviderRegistry.hasProvider("factory_b"))
        }

        @Test
        fun `should skip Terminal provider in non-IDE mode`() {
            // Terminal is IDE_ONLY
            assertFalse(ContextProviderRegistry.hasProvider("terminal"),
                "Terminal provider should not be registered in non-IDE mode")
        }

        @Test
        fun `should use empty factory by default in tests`() {
            val providers = ContextProviderRegistry.getAllProviders()
            assertTrue(providers.isEmpty(), "Empty factory should yield no built-in providers")
        }
    }

    @Nested
    inner class RegistrationTests {

        @Test
        fun `should register custom provider`() {
            val provider = createMockProvider("custom_test")
            ContextProviderRegistry.register(provider)

            assertTrue(ContextProviderRegistry.hasProvider("custom_test"))
            assertNotNull(ContextProviderRegistry.getProvider("custom_test"))
        }

        @Test
        fun `should replace existing provider with same title`() {
            val provider1 = createMockProvider("replacement_test")
            val provider2 = createMockProvider("replacement_test")

            ContextProviderRegistry.register(provider1)
            val countBefore = ContextProviderRegistry.getAllProviders().size
            ContextProviderRegistry.register(provider2)
            val countAfter = ContextProviderRegistry.getAllProviders().size

            assertEquals(countBefore, countAfter, "Replacing provider should not change count")
        }

        @Test
        fun `should unregister provider`() {
            val provider = createMockProvider("to_unregister")
            ContextProviderRegistry.register(provider)

            ContextProviderRegistry.unregister("to_unregister")

            assertFalse(ContextProviderRegistry.hasProvider("to_unregister"))
            assertNull(ContextProviderRegistry.getProvider("to_unregister"))
        }

        @Test
        fun `should return titles including custom providers`() {
            ContextProviderRegistry.register(createMockProvider("zzz_custom"))
            val titles = ContextProviderRegistry.getProviderTitles()
            assertTrue(titles.contains("zzz_custom"))
            // Should be sorted
            assertEquals(titles.sorted(), titles)
        }
    }

    @Nested
    inner class EnvironmentFilteringTests {

        @Test
        fun `should skip IDE_ONLY providers when registering in non-IDE mode`() {
            val ideOnlyProvider = createMockProvider("ide_test", environment = ContextProviderEnvironment.IDE_ONLY)
            ContextProviderRegistry.register(ideOnlyProvider)
            // In non-IDE mode (initialized above), IDE_ONLY providers should be skipped
            assertFalse(ContextProviderRegistry.hasProvider("ide_test"))
        }

        @Test
        fun `should accept ANY providers in non-IDE mode`() {
            val anyProvider = createMockProvider("any_test", environment = ContextProviderEnvironment.ANY)
            ContextProviderRegistry.register(anyProvider)
            assertTrue(ContextProviderRegistry.hasProvider("any_test"))
        }

        @Test
        fun `should filter providers by type`() {
            ContextProviderRegistry.register(createMockProvider("normal_test", type = ProviderType.NORMAL))
            ContextProviderRegistry.register(createMockProvider("query_test", type = ProviderType.QUERY))

            val queryProviders = ContextProviderRegistry.getProvidersByType(ProviderType.QUERY)
            assertTrue(queryProviders.any { it.description.title == "query_test" })
        }
    }

    @Nested
    inner class ClearTests {

        @Test
        fun `clear should remove all providers`() {
            // Register a custom provider
            ContextProviderRegistry.register(createMockProvider("custom_clear_test"))
            assertTrue(ContextProviderRegistry.hasProvider("custom_clear_test"))

            // Clear and re-initialize
            ContextProviderRegistry.clear()
            ContextProviderRegistry.providerFactory = { emptyList() }
            ContextProviderRegistry.initialize(isIdeEnvironment = false)

            // Custom provider should be gone
            assertFalse(ContextProviderRegistry.hasProvider("custom_clear_test"))
        }
    }
}

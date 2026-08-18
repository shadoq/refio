package pl.jclab.refio.core.api.modules

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.Config
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.context.WorkingMemoryEntry
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `working_memory.max_facts` is seeded, validated and exported, so it has to actually bound the
 * working memory. It used to be ignored: the service was built with its hardcoded default while
 * the configured value went nowhere.
 */
class SupportServicesModuleTest {

    private fun moduleWithMaxFacts(maxFacts: String): SupportServicesModule {
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
        every {
            configRepository.getWithPrecedence(ConfigKeys.WORKING_MEMORY_MAX_FACTS.key, any(), any())
        } returns Config(
            key = ConfigKeys.WORKING_MEMORY_MAX_FACTS.key,
            value = maxFacts,
            scope = ConfigScope.APP,
            projectId = null,
            taskId = null,
            description = null,
            createdAt = 0,
            updatedAt = 0,
        )

        return SupportServicesModule(
            projectRoot = null,
            chatMessageRepository = mockk<ChatMessageRepository>(relaxed = true),
            llmClient = mockk<LLMClient>(relaxed = true),
            promptsService = mockk<PromptsService>(relaxed = true),
            configService = ConfigService(configRepository),
        )
    }

    @Test
    fun `working memory honours the configured fact limit`() {
        val module = moduleWithMaxFacts("1")

        module.workingMemoryService.recordEntries(
            "task-1",
            listOf(
                WorkingMemoryEntry(iteration = 1, key = "facts", value = "least important", importance = 1),
                WorkingMemoryEntry(iteration = 1, key = "facts", value = "most important", importance = 10),
            )
        )

        val section = module.workingMemoryService.buildWorkingMemorySection("task-1", maxTokens = 500)
        assertTrue(section.contains("most important"))
        assertFalse(section.contains("least important"))
    }
}

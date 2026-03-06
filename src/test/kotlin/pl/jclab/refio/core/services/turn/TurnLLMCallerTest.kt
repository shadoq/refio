package pl.jclab.refio.core.services.turn

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.ConfigService

class TurnLLMCallerTest {

    private val caller = TurnLLMCaller(
        llmClient = mockk<LLMClient>(relaxed = true),
        configService = mockk<ConfigService>(relaxed = true)
    )

    @Test
    fun `should disable json response format for local providers`() {
        assertNull(caller.resolveResponseFormat(TaskMode.AGENT, "ollama"))
        assertNull(caller.resolveResponseFormat(TaskMode.PLAN, "lmstudio"))
    }

    @Test
    fun `should keep json response format for remote providers`() {
        assertEquals(
            mapOf("type" to "json_object"),
            caller.resolveResponseFormat(TaskMode.AGENT, "openai")
        )
    }
}

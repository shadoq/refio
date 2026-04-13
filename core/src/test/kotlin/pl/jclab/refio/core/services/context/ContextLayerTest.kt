package pl.jclab.refio.core.services.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for context layer classification and caching (Unit 5).
 */
class ContextLayerTest {

    @Test
    fun `ContextSection STABLE layer includes project and system sections`() {
        assertEquals(ContextLayer.STABLE, ContextSection.SYSTEM_PROMPT.contextLayer)
        assertEquals(ContextLayer.STABLE, ContextSection.TOOL_DESCRIPTIONS.contextLayer)
        assertEquals(ContextLayer.STABLE, ContextSection.PROJECT_CONTEXT.contextLayer)
        assertEquals(ContextLayer.STABLE, ContextSection.REFERENCE.contextLayer)
    }

    @Test
    fun `ContextSection ACCUMULATED layer includes working memory and recent work`() {
        assertEquals(ContextLayer.ACCUMULATED, ContextSection.WORKING_MEMORY.contextLayer)
        assertEquals(ContextLayer.ACCUMULATED, ContextSection.RECENT_WORK.contextLayer)
    }

    @Test
    fun `ContextSection EPHEMERAL layer includes user context`() {
        assertEquals(ContextLayer.EPHEMERAL, ContextSection.USER_CONTEXT.contextLayer)
        assertEquals(ContextLayer.EPHEMERAL, ContextSection.CONVERSATION.contextLayer)
    }

    @Test
    fun `all ContextSections have a defined layer`() {
        ContextSection.entries.forEach { section ->
            assertNotNull(section.contextLayer, "Section $section should have a layer")
        }
    }
}

class ContextLayerCacheTest {

    @Test
    fun `putStableContext and getStableContext round-trip works`() {
        val cache = ContextLayerCache()
        cache.putStableContext("task-1", "<STABLE>content</STABLE>", 100)

        val cached = cache.getStableContext("task-1")
        assertNotNull(cached)
        assertEquals("<STABLE>content</STABLE>", cached.content)
        assertEquals(100, cached.tokensUsed)
    }

    @Test
    fun `getStableContext returns null for unknown task`() {
        val cache = ContextLayerCache()
        assertNull(cache.getStableContext("unknown"))
    }

    @Test
    fun `invalidateStable removes cached context`() {
        val cache = ContextLayerCache()
        cache.putStableContext("task-1", "content", 50)
        cache.invalidateStable("task-1")

        assertNull(cache.getStableContext("task-1"))
    }

    @Test
    fun `invalidateAll clears everything`() {
        val cache = ContextLayerCache()
        cache.putStableContext("task-1", "content-1", 50)
        cache.putStableContext("task-2", "content-2", 60)
        cache.putToolDescriptions("task-1", "tools")
        cache.invalidateAll()

        assertNull(cache.getStableContext("task-1"))
        assertNull(cache.getStableContext("task-2"))
        assertNull(cache.getToolDescriptions("task-1"))
    }

    @Test
    fun `tool descriptions cache is version-aware`() {
        val cache = ContextLayerCache()
        cache.putToolDescriptions("task-1", "tool descriptions v1")

        assertEquals("tool descriptions v1", cache.getToolDescriptions("task-1"))

        // Invalidate bumps version, so cached descriptions are stale
        cache.invalidateStable("task-1")
        assertNull(cache.getToolDescriptions("task-1"))
    }

    @Test
    fun `contextVersion increments on invalidation`() {
        val cache = ContextLayerCache()
        val v0 = cache.currentContextVersion()
        cache.invalidateStable("task-1")
        val v1 = cache.currentContextVersion()
        assertTrue(v1 > v0)
    }

    @Test
    fun `getContextStabilityPercent returns 100 when cached and version matches`() {
        val cache = ContextLayerCache()
        cache.putStableContext("task-1", "content", 50)

        assertEquals(100, cache.getContextStabilityPercent("task-1"))
    }

    @Test
    fun `getContextStabilityPercent returns 0 when version changed`() {
        val cache = ContextLayerCache()
        cache.putStableContext("task-1", "content", 50)
        cache.invalidateStable("task-2") // bumps version

        assertEquals(0, cache.getContextStabilityPercent("task-1"))
    }

    @Test
    fun `getContextStabilityPercent returns 0 for unknown task`() {
        val cache = ContextLayerCache()
        assertEquals(0, cache.getContextStabilityPercent("unknown"))
    }
}

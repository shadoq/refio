package configdemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigPrecedenceTest {

    private val key = ModelSelector.MODEL_KEY

    private fun seeded(): InMemoryConfigRepository = InMemoryConfigRepository().apply {
        set(key, "app-model", ConfigScope.APP)
        set(key, "project-model", ConfigScope.PROJECT, projectId = "proj-1")
        set(key, "task-model", ConfigScope.TASK, taskId = "task-7")
    }

    @Test
    fun `task value wins over project and application`() {
        val resolver = ConfigResolver(seeded(), defaultProjectId = "proj-1")
        assertEquals("task-model", resolver.get(key, taskId = "task-7"))
        assertEquals("task-model", resolver.getConfigWithPrecedence(key, taskId = "task-7")?.value)
        assertEquals("task-model", ModelSelector(resolver).modelFor("task-7"))
    }

    @Test
    fun `project value wins over application when the task has no value`() {
        val resolver = ConfigResolver(seeded(), defaultProjectId = "proj-1")
        assertEquals("project-model", resolver.get(key, taskId = "task-other"))
        assertEquals("project-model", resolver.getConfigWithPrecedence(key, taskId = "task-other")?.value)
        assertEquals("project-model", ModelSelector(resolver).modelFor("task-other"))
    }

    @Test
    fun `application value is used for a project without its own value`() {
        val resolver = ConfigResolver(seeded(), defaultProjectId = "proj-2")
        assertEquals("app-model", resolver.get(key))
        assertEquals("app-model", resolver.getConfigWithPrecedence(key)?.value)
        assertEquals("app-model", ModelSelector(resolver).modelFor())
    }

    @Test
    fun `explicit project id takes the place of the default project`() {
        val resolver = ConfigResolver(seeded(), defaultProjectId = "proj-2")
        assertEquals("project-model", resolver.get(key, projectId = "proj-1"))
        assertEquals("project-model", resolver.getConfigWithPrecedence(key, projectId = "proj-1")?.value)
    }

    @Test
    fun `missing key yields null and the consumer falls back to its default`() {
        val resolver = ConfigResolver(InMemoryConfigRepository(), defaultProjectId = "proj-1")
        assertNull(resolver.get(key))
        assertNull(resolver.getConfigWithPrecedence(key))
        assertEquals(ModelSelector.DEFAULT_MODEL, ModelSelector(resolver).modelFor())
    }

    @Test
    fun `every set is one persistent write`() {
        val repository = InMemoryConfigRepository()
        val resolver = ConfigResolver(repository, defaultProjectId = "proj-1")
        resolver.set(key, "saved-model")
        resolver.set(key, "saved-project-model", ConfigScope.PROJECT)
        assertEquals(2, repository.writeCount)
        assertEquals("saved-project-model", resolver.get(key))
        assertEquals("proj-1", resolver.getConfigWithPrecedence(key)?.projectId)
    }
}

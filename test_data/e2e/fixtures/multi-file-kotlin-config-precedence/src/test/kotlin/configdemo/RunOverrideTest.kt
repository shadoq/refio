package configdemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunOverrideTest {

    private val key = ModelSelector.MODEL_KEY

    private fun seeded(): InMemoryConfigRepository = InMemoryConfigRepository().apply {
        set(key, "app-model", ConfigScope.APP)
        set(key, "project-model", ConfigScope.PROJECT, projectId = "proj-1")
        set(key, "task-model", ConfigScope.TASK, taskId = "task-7")
        set("editor.theme", "dark", ConfigScope.APP)
    }

    private fun withOverride(repository: InMemoryConfigRepository, value: String = "run-model") =
        ConfigResolver(repository, defaultProjectId = "proj-1", runOverrides = mapOf(key to value))

    @Test
    fun `ordinary read returns the run override over every stored scope`() {
        val resolver = withOverride(seeded())
        assertEquals("run-model", resolver.get(key))
        assertEquals("run-model", resolver.get(key, taskId = "task-7"))
    }

    @Test
    fun `row read returns the run override over every stored scope`() {
        val resolver = withOverride(seeded())
        assertEquals("run-model", resolver.getConfigWithPrecedence(key)?.value)
        assertEquals("run-model", resolver.getConfigWithPrecedence(key, taskId = "task-7")?.value)
        assertEquals("run-model", resolver.getConfigWithPrecedence(key, taskId = "task-7", projectId = "proj-1")?.value)
    }

    @Test
    fun `row read carries the key it was asked for`() {
        val resolver = withOverride(seeded())
        assertEquals(key, resolver.getConfigWithPrecedence(key, taskId = "task-7")?.key)
    }

    @Test
    fun `row read returns the run override for a key with no stored value`() {
        val resolver = withOverride(InMemoryConfigRepository())
        assertEquals("run-model", resolver.get(key))
        assertEquals("run-model", resolver.getConfigWithPrecedence(key)?.value)
    }

    @Test
    fun `model selection uses the run override`() {
        val selector = ModelSelector(withOverride(seeded()))
        assertEquals("run-model", selector.modelFor())
        assertEquals("run-model", selector.modelFor("task-7"))
    }

    @Test
    fun `run override is never written to the repository`() {
        val repository = seeded()
        val writesBefore = repository.writeCount
        val rowsBefore = repository.allRows()
        val resolver = withOverride(repository)

        resolver.get(key, taskId = "task-7")
        resolver.getConfigWithPrecedence(key, taskId = "task-7")
        ModelSelector(resolver).modelFor("task-7")

        assertEquals(writesBefore, repository.writeCount)
        assertEquals(rowsBefore, repository.allRows())
        assertTrue(repository.allRows().none { it.value == "run-model" })
    }

    @Test
    fun `next run without the override reads the stored values again`() {
        val repository = seeded()
        val thisRun = withOverride(repository)
        ModelSelector(thisRun).modelFor("task-7")
        thisRun.getConfigWithPrecedence(key)

        val nextRun = ConfigResolver(repository, defaultProjectId = "proj-1")
        assertEquals("task-model", nextRun.get(key, taskId = "task-7"))
        assertEquals("task-model", nextRun.getConfigWithPrecedence(key, taskId = "task-7")?.value)
        assertEquals("project-model", nextRun.getConfigWithPrecedence(key)?.value)
        assertEquals("project-model", ModelSelector(nextRun).modelFor())
    }

    @Test
    fun `blank run override counts as absent for every read`() {
        val resolver = withOverride(seeded(), value = "  ")
        assertEquals("task-model", resolver.get(key, taskId = "task-7"))
        assertEquals("task-model", resolver.getConfigWithPrecedence(key, taskId = "task-7")?.value)
        assertEquals("project-model", ModelSelector(resolver).modelFor())
    }

    @Test
    fun `run override of one key leaves other keys alone`() {
        val resolver = withOverride(seeded())
        assertEquals("dark", resolver.get("editor.theme"))
        assertEquals("dark", resolver.getConfigWithPrecedence("editor.theme")?.value)
    }
}

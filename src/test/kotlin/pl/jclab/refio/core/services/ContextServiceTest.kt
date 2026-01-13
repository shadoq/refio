package pl.jclab.refio.core.services

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextServiceTest {

    private lateinit var projectAnalyzer: ProjectAnalyzerService
    private lateinit var taskRepository: TaskRepository
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var fileAnalyzerService: FileAnalyzerService
    private lateinit var configService: ConfigService
    private lateinit var ragSearchService: RagSearchService

    private lateinit var service: ContextService

    @BeforeEach
    fun setup() {
        projectAnalyzer = mockk()
        taskRepository = mockk()
        chatMessageRepository = mockk()
        subtaskRepository = mockk()
        fileAnalyzerService = mockk()
        configService = mockk()
        ragSearchService = mockk()

        mockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
        every { transaction(any(), any<Function1<Transaction, Any>>()) } answers {
            val block = arg<Transaction.() -> Any>(1)
            block(mockk())
        }
        every { transaction(any<Int>(), any<Boolean>(), any(), any<Function1<Transaction, Any>>()) } answers {
            val block = arg<Transaction.() -> Any>(3)
            block(mockk())
        }

        service = ContextService(
            projectAnalyzer = projectAnalyzer,
            taskRepository = taskRepository,
            chatMessageRepository = chatMessageRepository,
            subtaskRepository = subtaskRepository,
            fileAnalyzerService = fileAnalyzerService,
            configService = configService,
            ragSearchService = ragSearchService
        )
    }

    @AfterEach
    fun teardown() {
        unmockkStatic("org.jetbrains.exposed.sql.transactions.ThreadLocalTransactionManagerKt")
    }

    @Nested
    inner class FormatContextReferencesTests {

        @Test
        fun `should format file context reference`() {
            // Given
            val refs = listOf(
                ContextReference(
                    type = ContextType.FILE,
                    path = "/src/main/Main.kt",
                    displayName = "Main.kt",
                    content = "fun main() {}"
                )
            )

            // When
            val formatted = service.formatContextReferencesForLLM(refs)

            // Then
            assertTrue(formatted.contains("Main.kt"))
            assertTrue(formatted.contains("fun main()"))
        }

        @Test
        fun `should format folder context reference`() {
            // Given
            val refs = listOf(
                ContextReference(
                    type = ContextType.FOLDER,
                    path = "/src/main",
                    displayName = "main",
                    content = "file1.kt\nfile2.kt"
                )
            )

            // When
            val formatted = service.formatContextReferencesForLLM(refs)

            // Then
            assertTrue(formatted.contains("main"))
        }

        @Test
        fun `should handle empty list`() {
            // When
            val formatted = service.formatContextReferencesForLLM(emptyList())

            // Then
            assertTrue(formatted.isEmpty())
        }

        @Test
        fun `should format multiple references`() {
            // Given
            val refs = listOf(
                ContextReference(type = ContextType.FILE, path = "/a.kt", displayName = "a.kt", content = "// A"),
                ContextReference(type = ContextType.FILE, path = "/b.kt", displayName = "b.kt", content = "// B")
            )

            // When
            val formatted = service.formatContextReferencesForLLM(refs)

            // Then
            assertTrue(formatted.contains("a.kt"))
            assertTrue(formatted.contains("b.kt"))
        }
    }

    @Nested
    inner class ConvertStringRefsToContextReferencesTests {

        @Test
        fun `should convert file path to context reference`() {
            // When
            val refs = ContextService.convertStringRefsToContextReferences(listOf("@file:/src/Main.kt"))

            // Then
            assertEquals(1, refs.size)
            assertEquals(ContextType.FILE, refs.first().type)
            assertEquals("/src/Main.kt", refs.first().path)
        }

        @Test
        fun `should detect folder type for directory paths`() {
            // When
            val refs = ContextService.convertStringRefsToContextReferences(listOf("@folder:/src/main/"))

            // Then
            assertEquals(1, refs.size)
            assertEquals(ContextType.FOLDER, refs.first().type)
        }

        @Test
        fun `should handle empty list`() {
            // When
            val refs = ContextService.convertStringRefsToContextReferences(emptyList())

            // Then
            assertTrue(refs.isEmpty())
        }
    }

    @Nested
    inner class UpdateRagSearchConfigTests {

        @Test
        fun `should update RAG search configuration`() {
            // Given
            val newService = mockk<RagSearchService>()

            // When
            service.updateRagSearchConfig(newService, "model-123", "openai")

            // Then - verify no exception and service can be used
            // The actual update is verified indirectly through buildProjectContext
        }

        @Test
        fun `should accept null values`() {
            // When/Then - should not throw
            service.updateRagSearchConfig(null, null, null)
        }
    }
}

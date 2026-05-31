package pl.jclab.refio.core.services

import pl.jclab.refio.core.services.analysis.ClassElement
import pl.jclab.refio.core.services.analysis.CodeElements
import pl.jclab.refio.core.services.analysis.FunctionElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [SemanticChunkingStrategy] content-dedup.
 *
 * Regression anchor (2026-05, session 1fc544f9): a single-class file produced a full-file
 * chunk and a class chunk that both truncated (maxChunkChars) to the SAME leading text.
 * Identical text → identical embedding → rag_search returned 5 copies of one fragment for
 * every query, sending a weak model into a 15-iteration re-search loop. The strategy must not
 * emit byte-identical chunks.
 */
class SemanticChunkingStrategyTest {

    private val strategy = SemanticChunkingStrategy()

    @Test
    fun `does not emit byte-identical chunks when full-file and class collapse to the same text`() {
        // A file small enough that the full-file chunk and the single class chunk (whole file)
        // produce identical content — exactly the collapse that generated the duplicate fragments.
        val content = (1..5).joinToString("\n") { "line$it" }
        val elements = CodeElements(
            classes = listOf(
                ClassElement(
                    name = "Foo",
                    type = "class",
                    startLine = 1,
                    endLine = 5
                )
            )
        )

        val chunks = strategy.createChunks(content, elements, language = "kotlin", maxChunkChars = 2000)

        val distinctContents = chunks.map { it.content }.toSet()
        assertEquals(
            distinctContents.size,
            chunks.size,
            "Chunker emitted duplicate-content chunks: ${chunks.map { it.type }}"
        )
    }

    @Test
    fun `keeps distinct method chunks alongside the class chunk`() {
        // Guard: dedup must be narrow — a method whose body differs from the class header
        // is a legitimately distinct chunk and must survive.
        val content = (1..20).joinToString("\n") { "line$it" }
        val elements = CodeElements(
            classes = listOf(
                ClassElement(
                    name = "Bar",
                    type = "class",
                    startLine = 3,  // class starts below the file head → distinct from full_file
                    endLine = 20,
                    methods = listOf(
                        FunctionElement(name = "doWork", startLine = 10, endLine = 15)
                    )
                )
            )
        )

        val chunks = strategy.createChunks(content, elements, language = "kotlin", maxChunkChars = 2000)

        // full_file (1-20), class (3-20) and method (10-15) all have different text → all survive.
        assertEquals(3, chunks.size, "Distinct regions must not be collapsed: ${chunks.map { it.type }}")
        assertTrue(chunks.any { it.type == "function" }, "Method chunk must be retained")
    }
}

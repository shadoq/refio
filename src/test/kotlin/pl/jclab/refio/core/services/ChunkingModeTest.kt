package pl.jclab.refio.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkingModeTest {

    @Test
    fun `fromConfig should recognize line based aliases`() {
        assertEquals(ChunkingMode.LINE_BASED, ChunkingMode.fromConfig("line_based"))
        assertEquals(ChunkingMode.LINE_BASED, ChunkingMode.fromConfig("line-based"))
        assertEquals(ChunkingMode.LINE_BASED, ChunkingMode.fromConfig("line"))
    }

    @Test
    fun `fromConfig should default to semantic for unknown values`() {
        assertEquals(ChunkingMode.SEMANTIC, ChunkingMode.fromConfig("semantic"))
        assertEquals(ChunkingMode.SEMANTIC, ChunkingMode.fromConfig("unknown"))
    }
}

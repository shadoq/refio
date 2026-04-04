package pl.jclab.refio.core.services

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize

class DocumentReadService {
    companion object {
        const val INLINE_MAX_PAGES = 5
        const val INLINE_MAX_BYTES = 1_000_000L
        const val PAGE_RANGE_MAX = 30
    }

    sealed class DocumentResult {
        data class InlineText(val text: String, val pageCount: Int) : DocumentResult()
        data class PageRange(val text: String, val range: IntRange, val totalPages: Int) : DocumentResult()
        data class Reference(val path: String, val pageCount: Int, val sizeBytes: Long) : DocumentResult()
    }

    fun read(path: Path, requestedPages: IntRange? = null): DocumentResult {
        val sizeBytes = path.fileSize()
        val pageCount = countPages(path)

        return when {
            pageCount <= INLINE_MAX_PAGES && sizeBytes < INLINE_MAX_BYTES -> {
                DocumentResult.InlineText(extractText(path), pageCount)
            }
            requestedPages != null || pageCount <= PAGE_RANGE_MAX -> {
                val range = sanitizeRange(requestedPages ?: (1..minOf(INLINE_MAX_PAGES, pageCount)), pageCount)
                DocumentResult.PageRange(extractPages(path, range), range, pageCount)
            }
            else -> {
                DocumentResult.Reference(path.toString(), pageCount, sizeBytes)
            }
        }
    }

    private fun sanitizeRange(range: IntRange, pageCount: Int): IntRange {
        val start = range.first.coerceIn(1, pageCount)
        val end = range.last.coerceIn(start, minOf(pageCount, start + PAGE_RANGE_MAX - 1))
        return start..end
    }

    private fun extractText(path: Path): String {
        PDDocument.load(Files.newInputStream(path).readAllBytes()).use { doc ->
            return PDFTextStripper().getText(doc).trim()
        }
    }

    private fun extractPages(path: Path, range: IntRange): String {
        PDDocument.load(Files.newInputStream(path).readAllBytes()).use { doc ->
            val stripper = PDFTextStripper()
            stripper.startPage = range.first
            stripper.endPage = range.last
            return stripper.getText(doc).trim()
        }
    }

    private fun countPages(path: Path): Int {
        PDDocument.load(Files.newInputStream(path).readAllBytes()).use { doc ->
            return doc.numberOfPages
        }
    }
}

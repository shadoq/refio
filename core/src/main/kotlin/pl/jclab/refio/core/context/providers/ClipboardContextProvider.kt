package pl.jclab.refio.core.context.providers

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.logging.dualLogger
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

private val logger = dualLogger("ClipboardContextProvider")

/**
 * Provider for clipboard content.
 *
 * Usage: @clipboard
 * Returns current clipboard text content.
 */
class ClipboardContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "clipboard",
        displayTitle = "Clipboard",
        description = "Current clipboard content",
        type = ProviderType.NORMAL,
        icon = "📋"
    )

    override suspend fun getContextItems(
        query: String,
        extras: ContextProviderExtras
    ): List<ContextItem> {
        logger.debug { "Getting clipboard content" }

        val clipboardContent = getClipboardContent()

        if (clipboardContent.isEmpty()) {
            logger.debug { "Clipboard is empty" }
            return emptyList()
        }

        logger.debug { "Clipboard content length: ${clipboardContent.length} chars" }

        return listOf(
            ContextItem(
                description = "Clipboard Content (${clipboardContent.length} chars)",
                content = clipboardContent,
                name = "Clipboard",
                uri = ContextUri(
                    type = "clipboard",
                    value = "current"
                )
            )
        )
    }

    private fun getClipboardContent(): String {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                val data = clipboard.getData(DataFlavor.stringFlavor)
                data?.toString() ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to read clipboard" }
            "[Error reading clipboard: ${e.message}]"
        }
    }
}

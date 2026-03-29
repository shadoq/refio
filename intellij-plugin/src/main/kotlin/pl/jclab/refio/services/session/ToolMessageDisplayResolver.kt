package pl.jclab.refio.services.session

internal data class ToolMessageDisplay(
    val content: String,
    val toolStreamContent: String?
)

internal object ToolMessageDisplayResolver {
    fun resolve(
        role: String,
        content: String,
        isSummarized: Boolean,
        rawOutput: String?
    ): ToolMessageDisplay {
        val streamContent = if (role == "tool" && isSummarized && !rawOutput.isNullOrBlank()) {
            rawOutput
        } else {
            null
        }

        return ToolMessageDisplay(
            content = content,
            toolStreamContent = streamContent
        )
    }
}

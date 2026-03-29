package pl.jclab.refio.services.session

internal class IncrementalToolCallStreamFilter(
    private val tailSize: Int = 128
) {
    private var filteredAccumulated = ""
    private var previousRawAccumulated = ""
    private var rawTail = ""

    fun filter(delta: String, accumulated: String, isComplete: Boolean): String {
        val shouldRecompute = shouldRecompute(delta, accumulated, isComplete)

        filteredAccumulated = when {
            shouldRecompute -> ToolCallContentSanitizer.sanitize(accumulated)
            delta.isEmpty() -> filteredAccumulated
            else -> filteredAccumulated + delta
        }

        previousRawAccumulated = accumulated
        rawTail = (rawTail + delta).takeLast(tailSize)
        return filteredAccumulated
    }

    private fun shouldRecompute(delta: String, accumulated: String, isComplete: Boolean): Boolean {
        if (previousRawAccumulated.isEmpty()) return true
        if (accumulated.length < previousRawAccumulated.length) return true
        if (isComplete) return true
        if (delta.isBlank()) return false

        val boundaryWindow = (rawTail + delta).takeLast(tailSize)
        return ToolCallContentSanitizer.isToolProtocolBoundary(delta) ||
            ToolCallContentSanitizer.isToolProtocolBoundary(boundaryWindow)
    }
}

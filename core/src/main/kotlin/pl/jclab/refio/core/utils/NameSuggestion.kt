package pl.jclab.refio.core.utils

/**
 * Finds the closest known name for a hallucinated or misspelled identifier so weak models can
 * self-correct instead of repeating an invalid tool / subagent name (observed: a model calling
 * `architecture-reviewer` when the real subagent is `architect-reviewer`). Pure, no side effects.
 */
object NameSuggestion {

    /**
     * Returns the candidate closest to [input] by case-insensitive Levenshtein distance, but only
     * when the match is close enough to be a plausible typo (distance <= max(2, len/3)). Returns
     * null when nothing is close - the caller should then fall back to listing the valid names, so
     * a wildly-invented name never gets a misleading "did you mean".
     */
    fun closest(input: String, candidates: Collection<String>): String? {
        val needle = input.trim().lowercase()
        if (needle.isEmpty() || candidates.isEmpty()) return null

        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in candidates) {
            val distance = levenshtein(needle, candidate.trim().lowercase())
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }

        val threshold = maxOf(2, needle.length / 3)
        return if (best != null && bestDistance <= threshold) best else null
    }

    /** Iterative two-row Levenshtein edit distance. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,                  // deletion
                    curr[j - 1] + 1,              // insertion
                    prev[j - 1] + substitutionCost // substitution
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}

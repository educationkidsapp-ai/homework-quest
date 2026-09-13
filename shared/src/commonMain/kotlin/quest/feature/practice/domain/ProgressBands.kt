package quest.feature.practice.domain

/** Progress bands from the dev prompt §5. Computed over first-try results of the last 14 attempts. */
enum class Band { GOING_WELL, GETTING_THERE, NEEDS_ANOTHER_LOOK }

object ProgressBands {
    const val WINDOW = 14

    fun accuracy(firstTryResults: List<Boolean>): Double? {
        val window = firstTryResults.take(WINDOW)
        if (window.isEmpty()) return null
        return window.count { it }.toDouble() / window.size
    }

    fun band(firstTryResults: List<Boolean>): Band? = accuracy(firstTryResults)?.let { band(it) }

    fun band(accuracy: Double): Band = when {
        accuracy >= 0.85 -> Band.GOING_WELL
        accuracy >= 0.60 -> Band.GETTING_THERE
        else -> Band.NEEDS_ANOTHER_LOOK
    }

    /** Words, never a percentage (child-facing rule kept in parent mode too). */
    fun accuracyWords(accuracy: Double): String = when {
        accuracy >= 0.95 -> "almost every time"
        accuracy >= 0.85 -> "most of the time"
        accuracy >= 0.60 -> "more than half the time"
        accuracy >= 0.35 -> "some of the time"
        else -> "not yet"
    }
}

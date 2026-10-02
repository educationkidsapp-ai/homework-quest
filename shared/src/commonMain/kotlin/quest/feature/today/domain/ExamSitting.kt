package quest.feature.today.domain

/**
 * M3 — an exam sitting in progress, as the system shows it outside the app: a Live Activity on iOS, an ongoing
 * notification on Android. It is started, updated and ended by the app alone, on the device — no push, no server.
 *
 * [closesAt] is the end of the exam's window (epoch millis) and drives the countdown the *system* draws; it is null
 * when the end is not known (a re-opened sitting), and then only the count is shown. Nothing here says how any
 * question was answered: [answered] of [total] is the same count the exam screen shows.
 */
data class ExamSitting(val lessonId: String, val title: String, val childName: String, val closesAt: Long?, val answered: Int, val total: Int) {
    companion object {
        /** The longest a sitting whose end is unknown is shown outside the app: two hours, the system's own ceiling for a re-opened paper. */
        const val LONGEST_MILLIS = 2 * 60 * 60_000L
    }
}

interface ExamSittingPresenter {
    /** Shows the sitting, or replaces what is shown with its new count. */
    fun show(sitting: ExamSitting)
    /** The paper was handed in, refused, or left: remove it. */
    fun end()
}

object NoExamSittingPresenter : ExamSittingPresenter {
    override fun show(sitting: ExamSitting) = Unit
    override fun end() = Unit
}

/**
 * The windows of the exams on the last home page, by lesson id. The exam player knows the paper but not its window —
 * that travels on the map — so the home page leaves it here for the sitting's countdown.
 */
class ExamWindows {
    private var closes: Map<String, Long> = emptyMap()
    fun remember(closesByLesson: Map<String, Long>) { closes = closesByLesson }
    /** Null when unknown or already past (a re-opening): no countdown is better than a wrong one. */
    fun closesAt(lessonId: String, now: Long): Long? = closes[lessonId]?.takeIf { it > now }
}

package quest.feature.today.domain

/**
 * M3 — an exam sitting in progress, as the system shows it outside the app: a Live Activity on iOS, an ongoing
 * notification on Android. It is started, updated and ended by the app alone, on the device — no push, no server.
 *
 * [closesAt] is the end of the exam's window (epoch millis) and drives the countdown the *system* draws; it is null
 * when the end is not known (a re-opened sitting), and then only the count is shown. Nothing here says how any
 * question was answered: [answered] of [total] is the same count the exam screen shows. [windowEnded] is what is shown
 * once the system has marked it stale, in the language the parent chose.
 */
data class ExamSitting(
    val childId: String, val lessonId: String, val title: String, val childName: String, val closesAt: Long?, val answered: Int, val total: Int,
    val windowEnded: String = "",
) {
    /**
     * Which sitting this is — the student and the paper — never shown. What the system still shows after the app was
     * killed is taken over only by the same sitting; anything left by another paper or another child is taken down.
     */
    val key: String get() = "$childId/$lessonId"

    /** When the system takes it down (Android) or marks it stale (iOS): the window's end, or [LONGEST_MILLIS] after [now] when that is unknown. */
    fun takeDownAt(now: Long): Long = closesAt ?: (now + LONGEST_MILLIS)

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

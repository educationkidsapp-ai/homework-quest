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
    val key: String get() = sittingKey(childId, lessonId)

    /** When the system takes it down (Android) or marks it stale (iOS): the window's end, or [LONGEST_MILLIS] after [now] when that is unknown. */
    fun takeDownAt(now: Long): Long = closesAt ?: (now + LONGEST_MILLIS)

    companion object {
        /** The longest a sitting whose end is unknown is shown outside the app: two hours, the system's own ceiling for a re-opened paper. */
        const val LONGEST_MILLIS = 2 * 60 * 60_000L
    }
}

/**
 * The sitting's key as the system stores it: a 64-bit FNV-1a hash of the student and the paper, in hex. What the system
 * keeps (a Live Activity's attributes, a notification's tag) is outside the app's sandbox, so it holds no raw ids —
 * only enough to tell one sitting from another.
 */
fun sittingKey(childId: String, lessonId: String): String {
    var hash = -0x340d631b7bdddcdbL                       // FNV-1a 64-bit offset basis
    "$childId/$lessonId".encodeToByteArray().forEach { b -> hash = (hash xor (b.toLong() and 0xff)) * 0x100000001b3L }
    return hash.toULong().toString(16).padStart(16, '0')
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

    /**
     * [at] is when the home page was loaded (the same instant the card's REOPENED decision uses). A window already shut then is a re-opened sitting —
     * `examWindow` carries the exam's own times, not the student's extension — so it is not remembered: neither the
     * Live Activity nor the exam screen's "Closes at" line (M4, D8) may claim an end the server did not give.
     */
    fun remember(closesByLesson: Map<String, Long>, at: Long = Long.MIN_VALUE) { closes = closesByLesson.filterValues { it > at } }
    /** Null when unknown or already past (a re-opening): no countdown is better than a wrong one. */
    fun closesAt(lessonId: String, now: Long): Long? = closes[lessonId]?.takeIf { it > now }
}

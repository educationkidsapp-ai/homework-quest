package quest.feature.today.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState

/**
 * M3 — everything the home-screen "Today" widget draws, written by the app and read by the widget.
 *
 * The widget never signs in and never talks to the network: it is handed this one small record — no token, no id of
 * anyone, nothing a lock-screen glance should not show — and the app replaces it whenever the home page loads and
 * removes it when the parent signs out. [labels] carries the words in the language the parent chose, so the widget
 * (a separate process on iOS) says the same thing the app does without owning a copy of the translations.
 */
@Serializable
data class TodaySnapshot(
    val childName: String,
    /** Lessons still to do, and the first two of their titles. Exams are counted apart. */
    val lessonsToDo: Int,
    val lessonTitles: List<String> = emptyList(),
    val nextExamTitle: String? = null,
    /** When that exam's window closes, epoch millis; null when the teacher re-opened it (no end is known). */
    val nextExamClosesAt: Long? = null,
    /** The parent's unread messages, as a count only. */
    val unreadMessages: Int = 0,
    val rtl: Boolean = false,
    val labels: TodayLabels = TodayLabels(),
    val updatedAt: Long = 0,
)

@Serializable
data class TodayLabels(
    val title: String = "Today",
    val lessonsToDo: String = "{n} lessons to do",
    val oneLessonToDo: String = "1 lesson to do",
    val allDone: String = "All lessons done",
    val exam: String = "Exam",
    val examUntil: String = "until {time}",
    val unread: String = "{n} unread messages",
    val oneUnread: String = "1 unread message",
    val signedOut: String = "Open MySchool to sign in",
)

/**
 * How the snapshot is written: every field, defaults included. The iOS widget decodes it in Swift, which has no notion
 * of "left out because it is the default", and unknown keys are ignored so an older widget reads a newer app's record.
 */
val TodayJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** Where the snapshot lives on this platform — shared defaults of the App Group on iOS, app preferences on Android. */
interface TodaySnapshotStore {
    /** Writes [snapshot] and asks the system to redraw the widget; null removes it (signed out). */
    suspend fun write(snapshot: TodaySnapshot?)
}

/** A widget tap, carried into the app: which screen to land on once the lock (if any) has been lifted. */
enum class TodayLink(val key: String) {
    HOME("home"), MESSAGES("messages");
    companion object { fun of(key: String?): TodayLink? = entries.firstOrNull { it.key == key } }
}

object TodaySnapshots {
    /**
     * The snapshot of one home page. [exams] are the lesson ids that are exams; an exam the student can still sit is
     * the "next exam" (the one closing soonest first), and is never counted as a lesson.
     */
    fun of(childName: String, islands: List<Island>, exams: Set<String>, unreadMessages: Int, now: Long, labels: TodayLabels, rtl: Boolean): TodaySnapshot {
        val open = islands.filter { it.kind != IslandKind.LOCKED && it.state != IslandState.DONE && it.state != IslandState.LOCKED }
        val lessons = open.filter { it.lessonId !in exams }
        val exam = open.filter { it.lessonId in exams && it.examWindow != null && now >= it.examWindow!!.opensAt }
            .minByOrNull { it.examWindow!!.closesAt.takeIf { closes -> closes > now } ?: Long.MAX_VALUE }
        return TodaySnapshot(
            childName = childName, lessonsToDo = lessons.size, lessonTitles = lessons.take(2).map { it.title },
            nextExamTitle = exam?.title, nextExamClosesAt = exam?.examWindow?.closesAt?.takeIf { it > now },
            unreadMessages = unreadMessages.coerceAtLeast(0), rtl = rtl, labels = labels, updatedAt = now,
        )
    }
}

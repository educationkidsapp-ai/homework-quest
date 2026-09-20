package quest.api.dto

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable data class SkillRef(val id: String, val name: String, val subject: Subject, val method: String)
@Serializable data class PageImage(val id: String, val url: String, val width: Int, val height: Int, val description: String = "")

/**
 * `GET /lessons/{id}` — immutable per `version`, cacheable indefinitely.
 *
 * N4.3 adds the four exam fields. For an exam ([type] `"exam"`) the player must honour all four.
 *
 * **They are not backward compatible on the wire, whatever their defaults say.** A Kotlin default makes a field
 * optional when *decoding*; it does nothing when encoding, and [quest.api.validation.SchemaValidator.json] sets
 * `encodeDefaults = true`, so the server writes `type`, `hintsOff`, `numbersOff` and `examPlay` into every lesson
 * body including a homework's. The app decodes with that same strict `Json` (`ignoreUnknownKeys = false`), so an
 * app build older than these fields does not read a homework "unchanged" — it throws on the first unknown key.
 *
 * Until the app relaxes `ignoreUnknownKeys`, app and server ship together and a field added here is a release-note
 * item: see `docs/runbook.md` § "The app and the contract" for the list of DTOs this already applies to.
 */
@Serializable
data class PublishedLesson(
    val id: String,
    val version: Int,
    val course: Course,
    val subject: Subject,
    val date: LocalDate,
    val title: String,
    val kind: SourceKind,
    val theme: Theme,
    val skills: List<SkillRef>,
    val plays: List<Play>,          // levels 1, 2, 3 (variant 0)
    val variant: Play,              // level 1, variant 1 — "Again"
    val parentPanel: ParentPanel,
    val pageImages: List<PageImage> = emptyList(),
    /** `homework` or `exam` (§8). */
    val type: String = "homework",
    /** §8: no hints, no "Again", no "Harder" while a child sits an exam. */
    val hintsOff: Boolean = false,
    /** §8: no red X, no percentage, no score and no timer on screen — stars and the sticker only. */
    val numbersOff: Boolean = false,
    /**
     * The one play an exam is sat over, and null for a homework. For a single-level exam it is that level's play;
     * for a *mixed* one it is the paper assembled from the three generated levels, and its stops keep the ids their
     * own level gave them so an attempt lands where the scorer already looks.
     *
     * The app plays exactly this and ignores [plays] and [variant] — there is no level chooser in an exam.
     */
    val examPlay: Play? = null,
) {
    fun play(level: Int, variant: Int = 0): Play? = if (variant == 1 && level == 1) this.variant else plays.firstOrNull { it.level == level }
}

/**
 * The window an exam island is alive in (§8's "only between open and close"), carried on [Island.examWindow].
 *
 * [level] is one of `1`, `2`, `3`, `mixed` and is the teacher's description of the paper, not something the player
 * chooses. [hintsOff] and [numbersOff] repeat what [PublishedLesson] says so the map can already draw the island as
 * a test before the lesson body is downloaded.
 */
@Serializable
data class ExamWindow(
    val opensAt: Long,
    val closesAt: Long,
    val level: String = "mixed",
    val durationMinutes: Int? = null,
    val hintsOff: Boolean = true,
    val numbersOff: Boolean = true,
)

/**
 * A "From your teacher" island (§6 screen 14, "Mobile app additions"): one per question of a teacher of the child's
 * class whose date window contains today. [answered] is how many of [stopsCount] the child has already done, so the
 * island can show "3 of 5" and the app can resume rather than restart.
 *
 * The stops themselves are `GET /children/{id}/teacher-questions/{questionId}`; this carries only what the island
 * draws. Both are behind the `teacherQuestions` flag and are 404 while it is off for the child's school.
 */
@Serializable
data class TeacherIsland(
    val questionId: String,
    val teacherName: String,
    val teacherPhotoUrl: String? = null,
    val title: String,
    val from: LocalDate,
    val to: LocalDate,
    val stopsCount: Int = 0,
    val answered: Int = 0,
)

/**
 * `GET /children/{id}/map` (`MapResponse.schema.json`).
 *
 * [teacherIslands] is null rather than an empty list when there are none, and the shared codec's `explicitNulls =
 * false` then leaves the field out of the JSON altogether — which is what keeps a map the §7 assembler produced
 * valid against `MapResponse.schema.json`, whose root is `additionalProperties: false`. Read it as
 * `teacherIslands.orEmpty()`. Adding the property to that schema is the follow-up that lets it be a plain
 * `emptyList()` default like every other list here.
 */
@Serializable
data class MapResponse(
    val childId: String,
    val course: Course,
    val from: LocalDate,
    val to: LocalDate,
    val today: LocalDate,
    val islands: List<Island>,
    val teacherIslands: List<TeacherIsland>? = null,
)

@Serializable
data class Island(
    val id: String,
    val kind: IslandKind,
    val date: LocalDate,
    val state: IslandState,
    val title: String,
    val subject: Subject? = null,
    val lessonId: String? = null,
    val lessonVersion: Int? = null,
    val levelsUnlocked: List<Int>? = null,
    val completedLevels: List<Int>? = null,
    val starsEarned: Int? = null,
    val starsTotal: Int? = null,
    val skillId: String? = null,
    val playId: String? = null,
    /**
     * N4.3: present only on the island of an exam, and only while the window is open — the server leaves the whole
     * lesson out of the map before it opens and after it closes, so an island that carries this is one the child
     * may sit right now. Its presence is what makes the island a test rather than a homework.
     */
    val examWindow: ExamWindow? = null,
)

/** What the map assembler needs to know about a child's history (mirrors LessonCompletion + bands). */
@Serializable
data class LessonCompletionInfo(val lessonId: String, val level: Int, val starsEarned: Int, val starsTotal: Int, val mostStopsTwoStars: Boolean)

@Serializable
data class PublishedLessonSummary(val id: String, val version: Int, val course: Course, val subject: Subject, val date: LocalDate, val title: String, val stopsPerPlay: Int, val skillIds: List<String>)

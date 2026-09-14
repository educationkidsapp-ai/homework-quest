package quest.api.dto

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable data class SkillRef(val id: String, val name: String, val subject: Subject, val method: String)
@Serializable data class PageImage(val id: String, val url: String, val width: Int, val height: Int, val description: String = "")

/** `GET /lessons/{id}` — immutable per `version`, cacheable indefinitely. */
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
) {
    fun play(level: Int, variant: Int = 0): Play? = if (variant == 1 && level == 1) this.variant else plays.firstOrNull { it.level == level }
}

/** `GET /children/{id}/map` (`MapResponse.schema.json`). */
@Serializable
data class MapResponse(
    val childId: String,
    val course: Course,
    val from: LocalDate,
    val to: LocalDate,
    val today: LocalDate,
    val islands: List<Island>,
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
)

/** What the map assembler needs to know about a child's history (mirrors LessonCompletion + bands). */
@Serializable
data class LessonCompletionInfo(val lessonId: String, val level: Int, val starsEarned: Int, val starsTotal: Int, val mostStopsTwoStars: Boolean)

@Serializable
data class PublishedLessonSummary(val id: String, val version: Int, val course: Course, val subject: Subject, val date: LocalDate, val title: String, val stopsPerPlay: Int, val skillIds: List<String>)

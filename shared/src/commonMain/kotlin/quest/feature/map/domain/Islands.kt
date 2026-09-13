package quest.feature.map.domain

import kotlinx.datetime.LocalDate
import quest.api.dto.Subject
import quest.feature.lesson.domain.LessonRepository
import quest.feature.lesson.domain.Skill
import quest.feature.practice.domain.PracticeRepository

enum class IslandStatus { TODAY, DONE, REPLAY, ASLEEP }

data class Island(
    val id: String,
    val name: String,
    val subject: Subject?,
    val status: IslandStatus,
    val stars: Int = 0,
    val total: Int = 7,
    val setId: String? = null,
)

data class MapData(val islands: List<Island>, val todayCount: Int) {
    val isEmpty: Boolean get() = islands.none { it.status != IslandStatus.ASLEEP }
}

/** Builds the world map: today's skills glow, finished ones show stars, older ones can be replayed, and a couple of islands stay asleep. */
class IslandsUseCase(private val lessons: LessonRepository, private val practice: PracticeRepository) {
    suspend operator fun invoke(today: LocalDate): MapData {
        val todays = lessons.confirmedSkillsFor(today)
        val all = lessons.allConfirmedSkills()
        val todayIds = todays.map { it.id }.toSet()
        val ordered = todays + all.filter { it.id !in todayIds }
        val islands = ordered.mapNotNull { skill -> island(skill, skill.id in todayIds) }
        val asleep = List(2) { i -> Island("asleep-$i", "Still asleep", null, IslandStatus.ASLEEP) }
        return MapData(islands + asleep, todays.size)
    }

    private suspend fun island(skill: Skill, isToday: Boolean): Island? {
        val set = lessons.latestSet(skill.id) ?: return if (isToday) Island(skill.id, skill.name, skill.subject, IslandStatus.ASLEEP) else null
        val stars = practice.completionStars(set.id!!)
        val status = when {
            stars != null && isToday -> IslandStatus.DONE
            stars != null -> IslandStatus.REPLAY
            isToday -> IslandStatus.TODAY
            else -> IslandStatus.REPLAY
        }
        return Island(skill.id, skill.name, skill.subject, status, stars ?: 0, set.questions.size, set.id)
    }
}

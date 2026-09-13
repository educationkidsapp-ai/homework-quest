package quest.feature.rewards.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

data class Sticker(val id: String, val key: String, val earnedAt: Long)
data class Streak(val currentDays: Int, val lastPlayedDate: LocalDate?)

interface RewardsRepository {
    suspend fun stickers(): List<Sticker>
    suspend fun addSticker(key: String): Sticker
    suspend fun streak(): Streak
    suspend fun saveStreak(streak: Streak)
}

/** Every completed set awards the next sticker in the design's list; the list repeats after 10. */
class AwardStickerUseCase(private val repo: RewardsRepository, private val keys: List<String>) {
    suspend operator fun invoke(): Sticker {
        val count = repo.stickers().size
        return repo.addSticker(keys[count % keys.size])
    }
}

/** Streak: consecutive calendar days with at least one completed set. */
class UpdateStreakUseCase(private val repo: RewardsRepository) {
    suspend operator fun invoke(today: LocalDate): Streak {
        val current = repo.streak()
        val next = when (current.lastPlayedDate) {
            today -> current
            today.minus(1, DateTimeUnit.DAY) -> Streak(current.currentDays + 1, today)
            else -> Streak(1, today)
        }
        if (next != current) repo.saveStreak(next)
        return next
    }

    companion object {
        /** Treasure chest milestones (docs/design.md §6, screen 15). */
        val chestDays = listOf(3, 7, 14)
    }
}

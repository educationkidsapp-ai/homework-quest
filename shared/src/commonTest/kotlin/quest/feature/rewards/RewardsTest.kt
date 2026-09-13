package quest.feature.rewards

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import quest.feature.rewards.domain.AwardStickerUseCase
import quest.feature.rewards.domain.RewardsRepository
import quest.feature.rewards.domain.Sticker
import quest.feature.rewards.domain.Streak
import quest.feature.rewards.domain.UpdateStreakUseCase
import kotlin.test.Test
import kotlin.test.assertEquals

class RewardsTest {
    private class MemoryRepo : RewardsRepository {
        val stickers = mutableListOf<Sticker>()
        var streak = Streak(0, null)
        override suspend fun stickers() = stickers.toList()
        override suspend fun addSticker(key: String) = Sticker("${stickers.size}", key, 0).also { stickers += it }
        override suspend fun streak() = streak
        override suspend fun saveStreak(streak: Streak) { this.streak = streak }
    }

    @Test fun stickersAreAwardedInOrderAndRepeat() = runTest {
        val repo = MemoryRepo()
        val award = AwardStickerUseCase(repo, listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c", "a"), List(4) { award().key })
    }

    @Test fun streakCountsConsecutiveDays() = runTest {
        val repo = MemoryRepo()
        val update = UpdateStreakUseCase(repo)
        assertEquals(1, update(LocalDate(2026, 9, 14)).currentDays)
        assertEquals(1, update(LocalDate(2026, 9, 14)).currentDays)   // same day: unchanged
        assertEquals(2, update(LocalDate(2026, 9, 15)).currentDays)
        assertEquals(1, update(LocalDate(2026, 9, 18)).currentDays)   // gap resets
    }
}

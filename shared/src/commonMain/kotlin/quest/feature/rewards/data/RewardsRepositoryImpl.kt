package quest.feature.rewards.data

import kotlinx.datetime.LocalDate
import quest.core.db.Db
import quest.core.platform.Ids
import quest.core.platform.Today
import quest.feature.rewards.domain.RewardsRepository
import quest.feature.rewards.domain.Sticker
import quest.feature.rewards.domain.Streak

class RewardsRepositoryImpl(private val db: Db, private val childId: suspend () -> String) : RewardsRepository {
    override suspend fun stickers(): List<Sticker> {
        val child = childId()
        return db.read { selectStickers(child).executeAsList().map { Sticker(it.id, it.key, it.earnedAt) } }
    }

    override suspend fun addSticker(key: String): Sticker {
        val sticker = Sticker(Ids.random(), key, Today.epochMillis())
        val child = childId()
        db.write { insertSticker(sticker.id, child, sticker.key, sticker.earnedAt) }
        return sticker
    }

    override suspend fun streak(): Streak {
        val child = childId()
        return db.read { selectStreak(child).executeAsOneOrNull()?.let { Streak(it.currentDays.toInt(), it.lastPlayedDate?.let(LocalDate::parse)) } } ?: Streak(0, null)
    }

    override suspend fun saveStreak(streak: Streak) {
        val child = childId()
        db.write { upsertStreak(child, streak.currentDays.toLong(), streak.lastPlayedDate?.toString()) }
    }
}

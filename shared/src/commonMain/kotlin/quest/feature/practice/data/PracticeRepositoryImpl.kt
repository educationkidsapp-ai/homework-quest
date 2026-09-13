package quest.feature.practice.data

import quest.api.dto.QuestionSet
import quest.core.db.Db
import quest.core.db.QuestionSetDao
import quest.core.platform.Ids
import quest.core.platform.Today
import quest.feature.practice.domain.PracticeRepository
import quest.feature.practice.domain.SkillProgress

class PracticeRepositoryImpl(private val db: Db, private val sets: QuestionSetDao, private val childId: suspend () -> String) : PracticeRepository {
    override suspend fun loadSet(setId: String): QuestionSet? = sets.load(setId)

    override suspend fun recordAttempt(questionId: String, skillId: String, chosenOptionId: String?, correct: Boolean, attemptNumber: Int) {
        val child = childId()
        db.write { insertAttempt(Ids.random(), questionId, child, skillId, chosenOptionId, if (correct) 1 else 0, attemptNumber.toLong(), Today.epochMillis()) }
    }

    override suspend fun recordCompletion(setId: String, stars: Int) {
        val child = childId()
        db.write { upsertCompletion(setId, child, stars.toLong(), Today.epochMillis()) }
    }

    override suspend fun completionStars(setId: String): Int? = db.read { selectCompletion(setId).executeAsOneOrNull()?.stars?.toInt() }

    override suspend fun progress(skillId: String): SkillProgress = db.read {
        SkillProgress(
            firstTryResults = selectFirstTryResults(skillId).executeAsList().map { it == 1L },
            lastAnsweredAt = selectLastAnsweredAt(skillId).executeAsOneOrNull()?.MAX,
            attempts = countAttemptsForSkill(skillId).executeAsOne(),
        )
    }
}

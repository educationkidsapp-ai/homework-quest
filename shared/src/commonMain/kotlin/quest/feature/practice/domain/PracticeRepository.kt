package quest.feature.practice.domain

import quest.api.dto.QuestionSet

data class SkillProgress(val firstTryResults: List<Boolean>, val lastAnsweredAt: Long?, val attempts: Long) {
    val accuracy: Double? get() = ProgressBands.accuracy(firstTryResults)
    val band: Band? get() = ProgressBands.band(firstTryResults)
}

interface PracticeRepository {
    suspend fun loadSet(setId: String): QuestionSet?
    suspend fun recordAttempt(questionId: String, skillId: String, chosenOptionId: String?, correct: Boolean, attemptNumber: Int)
    suspend fun recordCompletion(setId: String, stars: Int)
    suspend fun completionStars(setId: String): Int?
    suspend fun progress(skillId: String): SkillProgress
}

class RecordAttemptUseCase(private val repo: PracticeRepository) {
    suspend operator fun invoke(result: PracticeSession.Result, skillId: String) =
        repo.recordAttempt(result.questionId, skillId, result.chosenOptionId, result.correct, result.attemptNumber)
}

class CompleteSetUseCase(private val repo: PracticeRepository) {
    suspend operator fun invoke(setId: String, stars: Int) = repo.recordCompletion(setId, stars)
}

class SkillProgressUseCase(private val repo: PracticeRepository) {
    suspend operator fun invoke(skillId: String): SkillProgress = repo.progress(skillId)
}

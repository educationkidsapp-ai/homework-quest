package quest.feature.evaluations.data

import quest.api.ContentApi
import quest.api.dto.ParentEvaluation
import quest.feature.evaluations.domain.ChildEvaluationsRepository

/** Feature 33: `GET /children/{id}/evaluations`, straight through the contract's [ContentApi]. */
class ChildEvaluationsRepositoryImpl(private val api: ContentApi) : ChildEvaluationsRepository {
    override suspend fun evaluations(childId: String): List<ParentEvaluation> = api.childEvaluations(childId)
}

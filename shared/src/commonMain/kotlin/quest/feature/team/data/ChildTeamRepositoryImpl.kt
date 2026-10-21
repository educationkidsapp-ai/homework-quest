package quest.feature.team.data

import quest.api.ContentApi
import quest.api.dto.TeamMember
import quest.feature.team.domain.ChildTeamRepository

/** Feature 32: `GET /children/{id}/team`, straight through the contract's [ContentApi]. */
class ChildTeamRepositoryImpl(private val api: ContentApi) : ChildTeamRepository {
    override suspend fun team(childId: String): List<TeamMember> = api.childTeam(childId)
}

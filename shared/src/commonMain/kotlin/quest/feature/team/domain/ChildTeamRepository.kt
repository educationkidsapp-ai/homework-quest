package quest.feature.team.domain

import quest.api.dto.ChatPeerRole
import quest.api.dto.TeamMember

/**
 * Feature 32: everyone who looks after one child, as one page reads them — `GET /children/{id}/team`.
 *
 * There is nothing to write here: a row's **Message** is the ordinary one-to-one send the chat already does, so this
 * repository only reads. The server decides who is on the list, in what order, and whether a row may be written to;
 * the app groups and draws what it is given and invents no membership of its own.
 */
interface ChildTeamRepository {
    suspend fun team(childId: String): List<TeamMember>
}

/** The four headings the page reads in, in the spec's order. */
enum class TeamGroup { TEACHERS, COORDINATORS, FLOOR_SUPERVISOR, MANAGER }

/**
 * Which heading a row sits under. The Admin is a managerial row like the department manager — a parent is never shown
 * two words for one office — and anybody else is a teacher, which is what an older server's row decoded as.
 */
fun groupOf(role: ChatPeerRole): TeamGroup = when (role) {
    ChatPeerRole.COORDINATOR -> TeamGroup.COORDINATORS
    ChatPeerRole.FLOOR_SUPERVISOR -> TeamGroup.FLOOR_SUPERVISOR
    ChatPeerRole.MANAGERIAL, ChatPeerRole.ADMIN -> TeamGroup.MANAGER
    ChatPeerRole.TEACHER, ChatPeerRole.PARENT -> TeamGroup.TEACHERS
}

/**
 * The page's sections, in the spec's order, with the empty ones dropped — the server's own order is kept inside each
 * one, because it sorts the teachers by subject and the app has no better idea.
 */
fun grouped(members: List<TeamMember>): List<Pair<TeamGroup, List<TeamMember>>> =
    TeamGroup.entries.map { group -> group to members.filter { groupOf(it.role) == group } }.filter { it.second.isNotEmpty() }

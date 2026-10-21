package quest.feature.team.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.TeamMember
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.chat.domain.officeHoursBanner
import quest.feature.chat.domain.staffRoleOf
import quest.feature.chat.presentation.staffLabel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.GateFallback
import quest.feature.team.domain.ChildTeamRepository
import quest.feature.team.domain.TeamGroup
import quest.feature.team.domain.grouped
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * Feature 32: **My child's team** — the one page a parent opens to see everyone who looks after her child, when each
 * of them answers, and to write to any of them in one more tap.
 *
 * It owns no list of its own: `GET /children/{id}/team` decides who is on it, in what order, and which rows may be
 * written to, so a floor supervisor appearing (or not) is the server's answer and not a rule written twice.
 */
object ChildTeamContract {
    data class State(
        val loading: Boolean = true,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
        val childId: String? = null,
        val members: List<TeamMember> = emptyList(),
    ) : MviState {
        /** The sections the page draws, in the spec's order, the empty ones dropped. */
        val groups: List<Pair<TeamGroup, List<TeamMember>>> get() = grouped(members)
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
    }

    sealed interface Effect : MviEffect
}

class ChildTeamViewModel(
    private val children: ChildrenRepository,
    private val team: ChildTeamRepository,
) : MviViewModel<ChildTeamContract.State, ChildTeamContract.Intent, ChildTeamContract.Effect>(ChildTeamContract.State()) {

    override suspend fun handle(intent: ChildTeamContract.Intent) {
        when (intent) {
            ChildTeamContract.Intent.Load -> load()
        }
    }

    private suspend fun load() {
        val child = children.currentChild.value
        if (child == null) {
            reduce { copy(loading = false, childId = null, members = emptyList()) }
            return
        }
        try {
            val rows = team.team(child.id)
            reduce { copy(loading = false, childId = child.id, childNotPlaced = false, errorMessage = null, members = rows) }
        } catch (e: ApiException) {
            // A child with no class yet has nobody on her team; that is an answer, not a failure, and it is the one
            // the thread list already words for her.
            val notPlaced = e.error.code == "child_not_placed"
            reduce {
                copy(
                    loading = false, childId = child.id, childNotPlaced = notPlaced,
                    errorMessage = if (notPlaced) null else e.message, members = emptyList(),
                )
            }
        } catch (e: Throwable) {
            reduce { copy(loading = false, childId = child.id, errorMessage = e.message, members = emptyList()) }
        }
    }
}

@Composable
fun ChildTeamRoute(
    onBack: () -> Unit,
    onMessage: (TeamMember) -> Unit,
) {
    val vm: ChildTeamViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.dispatch(ChildTeamContract.Intent.Load) }

    // §4: the entry point is gated too, so this only fires on a deep link or a switch turned off while she was here.
    GateFallback(Flags.CHILD_TEAM, onBack)
    FeatureGate(Flags.CHAT) {
        FeatureGate(Flags.CHILD_TEAM) {
            ParentShell(title = { it.teamChannel.teamTitle }, onBack = onBack) { strings ->
                ChildTeamScreen(state = state, strings = strings, onMessage = onMessage)
            }
        }
    }
}

@Composable
fun ChildTeamScreen(
    state: ChildTeamContract.State,
    strings: Strings,
    onMessage: (TeamMember) -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
    ) {
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                quest.ui.design.AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
            }
            return
        }

        val problem = when {
            state.childNotPlaced -> strings.childNotPlaced
            state.errorMessage != null -> strings.somethingWrong
            state.members.isEmpty() -> strings.teamChannel.teamEmpty
            else -> null
        }
        if (problem != null) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(problem, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
            }
            return
        }

        state.groups.forEach { (group, rows) ->
            SectionTitle(headingOf(group, strings))
            rows.forEach { member -> TeamMemberCard(member, strings, onMessage) }
        }
        Spacer(Modifier.height(Dimens.s16))
    }
}

/** The same four headings the thread list writes — one spelling of each, so the two pages read alike. */
fun headingOf(group: TeamGroup, strings: Strings): String = when (group) {
    TeamGroup.TEACHERS -> strings.teachersGroup
    TeamGroup.COORDINATORS -> strings.coordinatorsGroup
    TeamGroup.FLOOR_SUPERVISOR -> strings.floorSupervisorsGroup
    TeamGroup.MANAGER -> strings.managersGroup
}

/**
 * Feature 19's line on a row: nothing while `chatOfficeHours` is off (the server sends no status at all), "Available
 * now" while she is inside her hours, and otherwise the banner's own sentence — when she will answer, worked out from
 * the school's date and never from this phone's clock.
 */
fun officeHoursLine(member: TeamMember, strings: Strings): String? {
    val status = member.officeHours ?: return null
    if (status.open) return strings.teamChannel.availableNow
    return officeHoursBanner(member.name, status, strings.officeHours)?.outside
}

/** One person: her name, her role and subjects, when she answers, and Message. */
@Composable
private fun TeamMemberCard(member: TeamMember, strings: Strings, onMessage: (TeamMember) -> Unit) {
    val label = staffLabel(staffRoleOf(member.role), member.subjects, className = null, strings = strings)
    val hours = officeHoursLine(member, strings)
    ParentCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.s8)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(member.name, label, hours).joinToString(", ")
            },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    member.name, style = MaterialTheme.typography.titleMedium,
                    color = DashboardTokens.ink, fontWeight = FontWeight.SemiBold,
                )
                if (label.isNotBlank()) {
                    Spacer(Modifier.height(Dimens.s4))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                }
                if (hours != null) {
                    Spacer(Modifier.height(Dimens.s4))
                    Text(hours, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                }
            }
            // A row she may read but not write to simply has no button: a send that would be refused is never offered.
            if (member.canMessage) {
                Spacer(Modifier.width(Dimens.s12))
                ParentButton(
                    strings.teamChannel.message,
                    onClick = { onMessage(member) },
                    modifier = Modifier.width(120.dp),
                    primary = false,
                )
            }
        }
    }
}

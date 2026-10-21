package quest.feature.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.ChatPeerRole
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.OfficeHoursStatus
import quest.api.dto.TeamMember
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.Strings
import quest.feature.team.domain.ChildTeamRepository
import quest.feature.team.domain.TeamGroup
import quest.feature.team.domain.grouped
import quest.feature.team.presentation.ChildTeamContract
import quest.feature.team.presentation.ChildTeamViewModel
import quest.feature.team.presentation.officeHoursLine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 32: **My child's team** on the app side — the four groups the page reads in, what a row carries, the
 * office-hours line (feature 19, never from this phone's clock), and the answers that are not failures.
 */
class ChildTeamViewModelTest {

    private val maya = Child("c1", "Maya", "sky", Curriculum.BRITISH, 1)

    private class Kids(child: Child?) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun children(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
    }

    private class Team(private val answer: () -> List<TeamMember>) : ChildTeamRepository {
        var asked: String? = null
        override suspend fun team(childId: String): List<TeamMember> {
            asked = childId
            return answer()
        }
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(kids: ChildrenRepository, team: ChildTeamRepository) =
        ChildTeamViewModel(kids, team).also { built += it }

    private suspend fun settle(state: StateFlow<ChildTeamContract.State>): ChildTeamContract.State {
        repeat(400) { if (!state.value.loading) return state.value; delay(5) }
        error("the team page never finished loading; last was ${state.value}")
    }

    private fun member(id: String, name: String, role: ChatPeerRole, subjects: String? = null, canMessage: Boolean = true, threadId: String? = null) =
        TeamMember(staffId = id, name = name, role = role, subjects = subjects, threadId = threadId, canMessage = canMessage)

    private val everyone = listOf(
        member("t-sara", "Ms. Sara", ChatPeerRole.TEACHER, "math", threadId = "th-1"),
        member("t-noor", "Ms. Noor", ChatPeerRole.TEACHER, "english"),
        member("co-lina", "Ms. Lina", ChatPeerRole.COORDINATOR, "math"),
        member("fs-huda", "Ms. Huda", ChatPeerRole.FLOOR_SUPERVISOR),
        member("mg-nour", "Ms. Nour", ChatPeerRole.MANAGERIAL),
    )

    // ---- 1. the page: the server's own list, in the spec's four groups

    @Test fun theTeamIsGroupedTeachersCoordinatorsSupervisorManagerInThatOrder() = runBlocking {
        val vm = viewModel(Kids(maya), Team { everyone })
        vm.dispatch(ChildTeamContract.Intent.Load)
        val state = settle(vm.state)
        assertEquals(
            listOf(TeamGroup.TEACHERS, TeamGroup.COORDINATORS, TeamGroup.FLOOR_SUPERVISOR, TeamGroup.MANAGER),
            state.groups.map { it.first },
        )
        assertEquals(listOf("Ms. Sara", "Ms. Noor"), state.groups.first().second.map { it.name }, "the server's order inside a group is kept")
        assertEquals("c1", state.childId)
    }

    @Test fun anEmptyGroupIsNotAHeadingAtAll() {
        // A school with no floor supervisor and no manager draws two headings, not four with nothing under them.
        val some = everyone.filter { it.role == ChatPeerRole.TEACHER || it.role == ChatPeerRole.COORDINATOR }
        assertEquals(listOf(TeamGroup.TEACHERS, TeamGroup.COORDINATORS), grouped(some).map { it.first })
        assertEquals(emptyList(), grouped(emptyList()))
    }

    @Test fun theAdminIsReadAsAManagerialRowRatherThanAFifthHeading() {
        // A parent is never shown two words for one office.
        assertEquals(TeamGroup.MANAGER, grouped(listOf(member("ad", "Admin", ChatPeerRole.ADMIN))).single().first)
    }

    // ---- 2. a row: the thread she already has, and whether she may write at all

    @Test fun aRowCarriesTheThreadWhenOneExistsAndNothingWhenItDoesNot() = runBlocking {
        val vm = viewModel(Kids(maya), Team { everyone })
        vm.dispatch(ChildTeamContract.Intent.Load)
        val teachers = settle(vm.state).groups.first().second
        assertEquals("th-1", teachers[0].threadId, "the thread her first message already made")
        assertNull(teachers[1].threadId, "no thread yet: her first message creates it")
    }

    @Test fun aRowSheMayNotWriteToSaysSoSoTheScreenHidesTheButton() = runBlocking {
        val vm = viewModel(Kids(maya), Team { listOf(member("mg", "Ms. Nour", ChatPeerRole.MANAGERIAL, canMessage = false)) })
        vm.dispatch(ChildTeamContract.Intent.Load)
        // A send that would be refused is never offered.
        assertTrue(!settle(vm.state).members.single().canMessage)
    }

    // ---- 3. feature 19: when she answers, worked out from the school's date and never from this phone

    @Test fun theOfficeHoursLineIsAbsentOffAvailableInsideAndTheBannerOutside() {
        val strings = Strings.en
        val sara = member("t", "Ms. Sara", ChatPeerRole.TEACHER)
        assertNull(officeHoursLine(sara, strings), "chatOfficeHours off: no status on the row, so no line")
        assertEquals(
            strings.teamChannel.availableNow,
            officeHoursLine(sara.copy(officeHours = OfficeHoursStatus(open = true, today = "2026-10-21")), strings),
        )
        val outside = sara.copy(officeHours = OfficeHoursStatus(open = false, nextStart = "2026-10-22T07:30", today = "2026-10-21"))
        val line = officeHoursLine(outside, strings)
        assertTrue(line != null && line.contains("Ms. Sara") && line.contains("tomorrow 7:30 AM"), "said from the school's own date: $line")
    }

    // ---- 4. the answers that are not failures

    @Test fun aChildWithNoClassYetIsSaidInWordsRatherThanAsAnError() = runBlocking {
        val vm = viewModel(Kids(maya), Team { throw ApiException(ApiError("child_not_placed", "no class")) })
        vm.dispatch(ChildTeamContract.Intent.Load)
        val state = settle(vm.state)
        assertTrue(state.childNotPlaced)
        assertNull(state.errorMessage)
        assertEquals(emptyList(), state.members)
    }

    @Test fun aFailureIsToldApartFromAnEmptyTeam() = runBlocking {
        val vm = viewModel(Kids(maya), Team { throw ApiException(ApiError(ApiError.NOT_FOUND, "off")) })
        vm.dispatch(ChildTeamContract.Intent.Load)
        val state = settle(vm.state)
        assertTrue(!state.childNotPlaced)
        assertTrue(state.errorMessage != null, "'we could not ask' is not 'there is nobody'")
    }

    @Test fun withNoChildSelectedNothingIsAskedAtAll() = runBlocking {
        val team = Team { everyone }
        val vm = viewModel(Kids(null), team)
        vm.dispatch(ChildTeamContract.Intent.Load)
        val state = settle(vm.state)
        assertNull(team.asked)
        assertEquals(emptyList(), state.members)
    }
}

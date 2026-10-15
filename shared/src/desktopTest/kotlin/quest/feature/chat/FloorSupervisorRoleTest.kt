package quest.feature.chat

import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.feature.chat.domain.Resolver
import quest.feature.chat.domain.resolverOf
import quest.feature.chat.presentation.ChatThreadsContract
import quest.feature.chat.presentation.staffLabel
import quest.feature.chat.presentation.typingLine
import quest.feature.parent.presentation.Strings
import quest.feature.staff.domain.StaffRole
import quest.feature.staff.presentation.StaffStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 20: the floor supervisor (مشرفة الدور), read from the two ends the app draws — the parent, who sees a
 * thread *she* started, and the staff side, where the supervisor signs in to the app herself.
 */
class FloorSupervisorRoleTest {

    private fun row(role: ChatStaffRole, peer: ChatPeerRole? = null) = ChatThread(
        id = "th-floor", childId = "c1", childName = "Maya", teacherId = "fs-hala", teacherName = "Ms. Hala",
        className = "1A British", subject = null, unread = 0, staffRole = role, peerRole = peer,
    )

    // ---- 1. the parent's row and conversation name her by her own role, in both languages

    @Test
    fun aParentsRowNamesTheFloorSupervisor() {
        assertEquals("Floor supervisor · 1A British", staffLabel(row(ChatStaffRole.FLOOR_SUPERVISOR), Strings.en))
        assertEquals("مشرفة الدور · 1A British", staffLabel(row(ChatStaffRole.FLOOR_SUPERVISOR), Strings.ar))
        // She holds no subject, so a row without a class still says who she is rather than nothing.
        assertEquals("Floor supervisor", staffLabel(ChatStaffRole.FLOOR_SUPERVISOR, null, null, Strings.en))
    }

    /** Her thread must sit under a heading of its own — otherwise it is in the list and invisible. */
    @Test
    fun herThreadGetsItsOwnGroup() {
        val state = ChatThreadsContract.State(
            loading = false,
            threads = listOf(row(ChatStaffRole.FLOOR_SUPERVISOR), row(ChatStaffRole.TEACHER)),
        )
        assertEquals(listOf("th-floor"), state.floorSupervisorThreads.map { it.id })
        assertTrue(state.coordinatorThreads.isEmpty())
        assertTrue(state.managerThreads.isEmpty())
        assertTrue(state.adminThreads.isEmpty())
    }

    /** M7: "who is typing" names her, not the department manager she used to fall through to. */
    @Test
    fun typingNamesHerRatherThanAManager() {
        assertEquals(
            Resolver.FLOOR_SUPERVISOR,
            resolverOf(ChatPeerRole.FLOOR_SUPERVISOR, ChatStaffRole.FLOOR_SUPERVISOR, withAdmin = false),
        )
        // A server older than N1 sends no `peerRole`; the staff role alone still resolves to her.
        assertEquals(Resolver.FLOOR_SUPERVISOR, resolverOf(null, ChatStaffRole.FLOOR_SUPERVISOR, withAdmin = false))
        assertEquals("Floor supervisor is typing…", typingLine(Resolver.FLOOR_SUPERVISOR, Strings.en))
        assertEquals("مشرفة الدور تكتب الآن…", typingLine(Resolver.FLOOR_SUPERVISOR, Strings.ar))
    }

    // ---- 2. the staff side: she signs in to the app and has messages

    @Test
    fun theStaffSideKnowsHerRole() {
        assertEquals(StaffRole.FLOOR_SUPERVISOR, StaffRole.of("FLOOR_SUPERVISOR"))
        assertEquals(StaffRole.FLOOR_SUPERVISOR, StaffRole.of("floor_supervisor"))
        assertEquals("FLOOR_SUPERVISOR", StaffRole.FLOOR_SUPERVISOR.wire)
        // Unlike a WORKER she has people to talk to: parents of her floor, its teachers, the managers and the Admin.
        assertTrue(StaffRole.FLOOR_SUPERVISOR.hasChat)
        assertEquals("Floor supervisor", StaffStrings.en.roleName("FLOOR_SUPERVISOR"))
        assertEquals("مشرفة الدور", StaffStrings.ar.roleName("FLOOR_SUPERVISOR"))
    }
}

package quest.feature.chat.domain

import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.OfficeHoursStatus

/**
 * R8: everything a conversation needs to draw itself before its first request — who is on the other side and which
 * thread it is.
 *
 * The list row the parent tapped already carried all of it, so this exists to stop the conversation asking the server
 * again for what it was just told. B6 / M8: a Messages thread is never a complaint and has no status — complaints are
 * their own conversations on the Complaints page.
 */
data class ChatPeer(
    val childId: String,
    val staffId: String,
    val staffName: String,
    val staffRole: ChatStaffRole = ChatStaffRole.TEACHER,
    val subject: String? = null,
    /** The thread behind the row, when one exists — what a `message`, `read` or `typing` frame is matched against. */
    val threadId: String? = null,
    /** S1: the school administration is the staff side ([staffRole] is `MANAGERIAL` on the wire). */
    val withAdmin: Boolean = false,
    /**
     * M4 (D6): T1's `peerOnline` from the row — the only source of the header's presence besides the `presence` frame.
     * Null is "nobody said", and then the header shows no presence at all.
     */
    val peerOnline: Boolean? = null,
    /** M4 (D7): N1's `peerRole` — who the parent is talking to, which names who is typing (M7). */
    val peerRole: ChatPeerRole? = null,
    /**
     * Feature 19: whether the staff member is inside her office hours, and when the next working period starts
     * ([officeHoursBanner]). Absent with `chatOfficeHours` off and on every staff-side row.
     */
    val officeHours: OfficeHoursStatus? = null,
) {
    companion object {
        fun of(thread: ChatThread): ChatPeer = ChatPeer(
            childId = thread.childId,
            staffId = thread.teacherId,
            staffName = thread.teacherName,
            staffRole = thread.staffRole,
            subject = thread.subject,
            threadId = thread.id,
            withAdmin = thread.withAdmin == true,
            peerOnline = thread.peerOnline,
            peerRole = thread.peerRole,
            officeHours = thread.officeHours,
        )
    }
}

/**
 * Who is on the other end — the person on the thread ([ChatPeer.peerRole]), or, from a server older than N1, the staff
 * side's role; the admin is named as the school administration. M7 names who is typing by it.
 */
enum class Resolver { TEACHER, COORDINATOR, MANAGER, ADMIN, FLOOR_SUPERVISOR }

fun resolverOf(peerRole: ChatPeerRole?, staffRole: ChatStaffRole, withAdmin: Boolean): Resolver = when {
    withAdmin || peerRole == ChatPeerRole.ADMIN -> Resolver.ADMIN
    peerRole == ChatPeerRole.TEACHER -> Resolver.TEACHER
    peerRole == ChatPeerRole.COORDINATOR -> Resolver.COORDINATOR
    peerRole == ChatPeerRole.MANAGERIAL -> Resolver.MANAGER
    // Feature 20: she is her own role on both sides of the wire, so "who is typing" names her rather than a manager.
    peerRole == ChatPeerRole.FLOOR_SUPERVISOR -> Resolver.FLOOR_SUPERVISOR
    staffRole == ChatStaffRole.TEACHER -> Resolver.TEACHER
    staffRole == ChatStaffRole.COORDINATOR -> Resolver.COORDINATOR
    staffRole == ChatStaffRole.FLOOR_SUPERVISOR -> Resolver.FLOOR_SUPERVISOR
    else -> Resolver.MANAGER
}

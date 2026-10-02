package quest.feature.chat.domain

import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic

/**
 * R8: everything a conversation needs to draw itself before its first request — who is on the other side, what the
 * thread is about, and whether the staff side has resolved it.
 *
 * The list row the parent tapped already carried all four (R4 appended them to `ChatThread` with defaults), so this
 * exists to stop the conversation asking the server again for what it was just told. Defaults reproduce the C3 shape:
 * a teacher, a question, open — which is exactly what a row from a server predating R4 decodes to.
 */
data class ChatPeer(
    val childId: String,
    val staffId: String,
    val staffName: String,
    val staffRole: ChatStaffRole = ChatStaffRole.TEACHER,
    val subject: String? = null,
    val topic: ChatTopic = ChatTopic.QUESTION,
    val resolved: Boolean = false,
    /** The thread behind the row, when one exists — what a `status`, `read` or `typing` frame is matched against. */
    val threadId: String? = null,
    /** M1: the parent chose "Complaint" in New message, so the conversation opens with the complaint toggle on. */
    val startAsComplaint: Boolean = false,
    /** S1: the school administration is the staff side ([staffRole] is `MANAGERIAL` on the wire). */
    val withAdmin: Boolean = false,
    /**
     * M4 (D6): T1's `peerOnline` from the row — the only source of the header's presence besides the `presence` frame.
     * Null is "nobody said", and then the header shows no presence at all.
     */
    val peerOnline: Boolean? = null,
    /** M4 (D7): N1's `peerRole` — who the parent is talking to, which is who resolves her complaint. */
    val peerRole: ChatPeerRole? = null,
) {
    companion object {
        fun of(thread: ChatThread): ChatPeer = ChatPeer(
            childId = thread.childId,
            staffId = thread.teacherId,
            staffName = thread.teacherName,
            staffRole = thread.staffRole,
            subject = thread.subject,
            topic = thread.topic,
            resolved = thread.status == ChatThreadStatus.RESOLVED,
            threadId = thread.id,
            withAdmin = thread.withAdmin == true,
            peerOnline = thread.peerOnline,
            peerRole = thread.peerRole,
        )
    }
}

/**
 * M4 (D7): who answered a resolved thread, as the banner says it — the person on the other end ([ChatPeer.peerRole]),
 * or, from a server older than N1, the staff side's role. The admin is named as the school administration.
 */
enum class Resolver { TEACHER, COORDINATOR, MANAGER, ADMIN }

fun resolverOf(peerRole: ChatPeerRole?, staffRole: ChatStaffRole, withAdmin: Boolean): Resolver = when {
    withAdmin || peerRole == ChatPeerRole.ADMIN -> Resolver.ADMIN
    peerRole == ChatPeerRole.TEACHER -> Resolver.TEACHER
    peerRole == ChatPeerRole.COORDINATOR -> Resolver.COORDINATOR
    peerRole == ChatPeerRole.MANAGERIAL -> Resolver.MANAGER
    staffRole == ChatStaffRole.TEACHER -> Resolver.TEACHER
    staffRole == ChatStaffRole.COORDINATOR -> Resolver.COORDINATOR
    else -> Resolver.MANAGER
}

package quest.feature.staff.domain

import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatThread

/**
 * Feature 13: the Koin name of the staff end of `ChatConversationViewModel`. Both ends are the same class, so the
 * two definitions are told apart by this rather than by their parameter type, which Koin does not look at.
 */
const val STAFF_CONVERSATION = "staffConversation"

/**
 * Feature 13: everything a staff conversation needs before its first request — which thread it is, who is on the
 * other end, and which half of the app is "me".
 *
 * The list row she tapped carried all of it, as the parent's [quest.feature.chat.domain.ChatPeer] does, so this
 * exists to stop the conversation asking the server again for what it was just told.
 */
data class StaffThreadKey(
    val threadId: String,
    /** Her own user id, from `GET /staff/me` — which bubbles are on the right. */
    val myUserId: String,
    /** The person on the other end, for the `presence` frame; empty when the row named nobody. */
    val peerUserId: String = "",
    /** What to call her on the header: a parent's name, or a colleague's. */
    val peerName: String = "",
    /** A parent thread's child, which the line under the name says. */
    val childName: String? = null,
    /** A parent thread's child, which is how the socket keys a **teacher's** typing command. */
    val childId: String? = null,
    /** True on a teacher's parent thread: her `typing` names the child, not the thread (`ChatSocketHandler`). */
    val typeByChild: Boolean = false,
    /** True when the other end is a parent — the typing line then says so. */
    val withParent: Boolean = false,
    /** `ChatThread.peerRole`: on a staff thread it names who is typing, as it does on the parent side. */
    val peerRole: ChatPeerRole? = null,
) {
    companion object {
        /**
         * One of her rows. On a parent thread the facade names *her* in `teacherId`/`teacherName` and the parent in
         * `parentName`, so the header takes the parent (falling back to the child's name when the school recorded
         * none); on a staff thread the row already names the colleague.
         */
        fun of(thread: ChatThread, myUserId: String, myRole: StaffRole?): StaffThreadKey {
            val withParent = thread.peerRole == ChatPeerRole.PARENT
            val child = thread.childId.takeIf { it.isNotBlank() }
            return StaffThreadKey(
                threadId = thread.id.orEmpty(),
                myUserId = myUserId,
                peerUserId = if (withParent) "" else thread.teacherId,
                peerName = if (withParent) thread.parentName?.takeIf { it.isNotBlank() } ?: thread.childName else thread.teacherName,
                childName = thread.childName.takeIf { it.isNotBlank() },
                childId = child,
                typeByChild = withParent && myRole == StaffRole.TEACHER && child != null,
                withParent = withParent,
                peerRole = thread.peerRole,
            )
        }
    }
}

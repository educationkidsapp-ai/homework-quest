package quest.feature.chat.domain

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
        )
    }
}

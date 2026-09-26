package quest.server.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * V15: one conversation per (child, teacher) and its messages (C1 `backend/chat-websocket`), widened by V20 (R4,
 * DR3) to a conversation between a parent and any staff peer, or between two staff members.
 */
public final class Entities {
    private Entities() {}

    /**
     * Tenant table: the `school` filter scopes every read to the caller's school, and `schoolId` is the child's,
     * written by {@link ChatService} and never taken from a request. The two unread counts are the callers' badges;
     * {@link ChatThreadRepository} bumps and clears them with single-row updates rather than through the entity, so
     * two senders never overwrite each other's increment.
     *
     * <p>V20 (R4, DR3) generalised the staff side. {@code teacherId} is the staff peer whatever {@code staffRole}
     * says, {@code teacherUnread} is that person's badge and {@code parentUnread} the counterpart's;
     * {@code peerUserId} is the second staff member of a coordinator-to-manager thread and null on every parent
     * thread, which is the only shape that carries a {@code childId} at all.
     */
    @Entity(name = "ChatThreadEntity") @Table(name = "chat_threads")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ChatThreadEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "child_id") private String childId;
        @Column(name = "teacher_id", nullable = false) private String teacherId;
        @Column(name = "staff_role", nullable = false) private String staffRole;
        @Column(name = "peer_user_id") private String peerUserId;
        @Column(nullable = false) private String topic;
        @Column(nullable = false) private String status;
        @Column(name = "resolved_at") private Instant resolvedAt;
        @Column(name = "resolved_by") private String resolvedBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "last_message_at") private Instant lastMessageAt;
        @Column(name = "parent_unread", nullable = false) private int parentUnread;
        @Column(name = "teacher_unread", nullable = false) private int teacherUnread;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getLastMessageAt() { return lastMessageAt; } public void setLastMessageAt(Instant v) { lastMessageAt = v; }
        public int getParentUnread() { return parentUnread; } public void setParentUnread(int v) { parentUnread = v; }
        public int getTeacherUnread() { return teacherUnread; } public void setTeacherUnread(int v) { teacherUnread = v; }
        public String getStaffRole() { return staffRole; } public void setStaffRole(String v) { staffRole = v; }
        public String getPeerUserId() { return peerUserId; } public void setPeerUserId(String v) { peerUserId = v; }
        public String getTopic() { return topic; } public void setTopic(String v) { topic = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public Instant getResolvedAt() { return resolvedAt; } public void setResolvedAt(Instant v) { resolvedAt = v; }
        public String getResolvedBy() { return resolvedBy; } public void setResolvedBy(String v) { resolvedBy = v; }
    }

    /**
     * One message: plain text, ≤ 2000 characters, never interpreted as HTML. `senderRole` is `parent`, `teacher` —
     * the thread's `teacherId` holder, whatever staff role she has — or, since R4, `peer`: the `peerUserId` holder
     * of a staff-to-staff thread. Two names are needed there because both sides are staff and a read receipt has to
     * know whose messages it is marking.
     */
    @Entity(name = "ChatMessageEntity") @Table(name = "chat_messages")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ChatMessageEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "thread_id", nullable = false) private String threadId;
        @Column(name = "sender_role", nullable = false) private String senderRole;
        @Column(name = "sender_id", nullable = false) private String senderId;
        @Column(nullable = false, length = 4000) private String body;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "read_at") private Instant readAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getThreadId() { return threadId; } public void setThreadId(String v) { threadId = v; }
        public String getSenderRole() { return senderRole; } public void setSenderRole(String v) { senderRole = v; }
        public String getSenderId() { return senderId; } public void setSenderId(String v) { senderId = v; }
        public String getBody() { return body; } public void setBody(String v) { body = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getReadAt() { return readAt; } public void setReadAt(Instant v) { readAt = v; }
    }
}

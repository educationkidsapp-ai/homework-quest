package quest.server.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * V15: one conversation per (child, teacher) and its messages (C1 `backend/chat-websocket`), widened by V20 (R4,
 * DR3) to a conversation between a parent and any staff peer, or between two staff members. V34 (B6) makes a
 * complaint a conversation of its own beside them, with its status history in `complaint_events`.
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
     *
     * <p>V34 (B6): {@code threadKey} is `""` on a Messages thread — one per (child, staff), as before — and the row's
     * own id on a complaint (`topic` `complaint`), so a parent may hold any number of complaints beside her one
     * Messages thread with the same person; {@code title} is a complaint's subject line and {@code subject} the recipient's
     * subjects on the child's section when it was opened, both null on a Messages thread.
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
        @Column(name = "thread_key", nullable = false) private String threadKey = "";
        @Column private String title;
        @Column private String subject;
        /**
         * V55 (feature 32): `direct` on every conversation — the only kind there was until today — and
         * `class_channel` on a class's channel, whose {@code classId} is the section and whose {@code childId} and
         * {@code peerUserId} are null. Every query of a conversation names `direct`, so a channel is in no thread
         * list, no inbox and no support view; the channel's own routes read it by {@code classId}.
         */
        @Column(nullable = false) private String kind = ChatService.DIRECT;
        @Column(name = "class_id") private String classId;
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
        public String getThreadKey() { return threadKey; } public void setThreadKey(String v) { threadKey = v; }
        public String getTitle() { return title; } public void setTitle(String v) { title = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public String getKind() { return kind; } public void setKind(String v) { kind = v; }
        public String getClassId() { return classId; } public void setClassId(String v) { classId = v; }
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
        /** V33 (B5): the message's `ChatAttachment` list as JSON, written once at send; null when it has none. */
        @Column private String attachments;
        /** V35 (B5b): the sender's `clientId`, so a retried send answers this row instead of writing a second one. */
        @Column(name = "client_id") private String clientId;
        /**
         * V55 (feature 32): when a <strong>channel post</strong> was taken down, by its author or by the Admin. The
         * row stays so paging cursors and unread counts hold, and the clients draw a tombstone. Nothing else in the
         * chat deletes a message: on every conversation's message this is null and always will be.
         */
        @Column(name = "deleted_at") private Instant deletedAt;
        @Column(name = "deleted_by") private String deletedBy;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getThreadId() { return threadId; } public void setThreadId(String v) { threadId = v; }
        public String getSenderRole() { return senderRole; } public void setSenderRole(String v) { senderRole = v; }
        public String getSenderId() { return senderId; } public void setSenderId(String v) { senderId = v; }
        public String getBody() { return body; } public void setBody(String v) { body = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getReadAt() { return readAt; } public void setReadAt(Instant v) { readAt = v; }
        public String getAttachments() { return attachments; } public void setAttachments(String v) { attachments = v; }
        public String getClientId() { return clientId; } public void setClientId(String v) { clientId = v; }
        public Instant getDeletedAt() { return deletedAt; } public void setDeletedAt(Instant v) { deletedAt = v; }
        public String getDeletedBy() { return deletedBy; } public void setDeletedBy(String v) { deletedBy = v; }
    }

    /**
     * V34 (B6): one status change of a complaint — `resolved` or `open` (reopened), by `parent` or `staff`, with the
     * person's id. Written by {@link ComplaintService} only, never from a request; `schoolId` is the complaint's.
     */
    @Entity(name = "ComplaintEventEntity") @Table(name = "complaint_events")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ComplaintEventEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "thread_id", nullable = false) private String threadId;
        @Column(nullable = false) private String status;
        @Column(name = "actor_role", nullable = false) private String actorRole;
        @Column(name = "actor_id", nullable = false) private String actorId;
        @Column(name = "changed_at", nullable = false) private Instant changedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getThreadId() { return threadId; } public void setThreadId(String v) { threadId = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public String getActorRole() { return actorRole; } public void setActorRole(String v) { actorRole = v; }
        public String getActorId() { return actorId; } public void setActorId(String v) { actorId = v; }
        public Instant getChangedAt() { return changedAt; } public void setChangedAt(Instant v) { changedAt = v; }
    }

    /**
     * V41 (feature 19): one row per parent message that reached a member of staff outside her office hours.
     * {@code dueDay} is her next working day in the school's zone, and {@code summarizedAt} is set by
     * {@link OfficeHoursSummaryJob} when it claims the row for a summary — one statement for all of a recipient's due
     * rows, so a rerun or a second instance sends nothing twice. {@code messageId} is unique, so one message is
     * counted once however many times it is delivered.
     */
    @Entity(name = "ChatAfterHoursEntity") @Table(name = "chat_after_hours")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ChatAfterHoursEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "user_id", nullable = false) private String userId;
        @Column(name = "thread_id", nullable = false) private String threadId;
        @Column(name = "message_id", nullable = false) private String messageId;
        @Column(name = "received_at", nullable = false) private Instant receivedAt;
        @Column(name = "due_day", nullable = false) private LocalDate dueDay;
        @Column(name = "summarized_at") private Instant summarizedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
        public String getThreadId() { return threadId; } public void setThreadId(String v) { threadId = v; }
        public String getMessageId() { return messageId; } public void setMessageId(String v) { messageId = v; }
        public Instant getReceivedAt() { return receivedAt; } public void setReceivedAt(Instant v) { receivedAt = v; }
        public LocalDate getDueDay() { return dueDay; } public void setDueDay(LocalDate v) { dueDay = v; }
        public Instant getSummarizedAt() { return summarizedAt; } public void setSummarizedAt(Instant v) { summarizedAt = v; }
    }

    /**
     * V49 (feature 27): what the AI made of one complaint — <strong>one row per complaint</strong>, which is what the
     * complaint's own id being the primary key says. The triage runs once, after the complaint has committed and on a
     * task thread, and is never rewritten; a complaint with no row is the ordinary case for a school with the switch
     * off, for the seconds before the answer arrives, and for a model that failed.
     *
     * <p><strong>Staff only.</strong> {@link ComplaintService} fills {@code Complaint.triage} for a staff viewer and
     * leaves it null for the parent: a guess about her complaint is the school's working note, and the suggested
     * reply is a draft nobody has agreed to.
     */
    @Entity(name = "ComplaintTriageEntity") @Table(name = "complaint_triage")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ComplaintTriageEntity {
        @Id @Column(name = "complaint_id") private String complaintId;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(nullable = false) private String category;
        @Column(nullable = false) private String urgency;
        @Column(name = "summary_ar", nullable = false) private String summaryAr;
        @Column(name = "summary_en", nullable = false) private String summaryEn;
        @Column(name = "reply_ar", nullable = false) private String replyAr;
        @Column(name = "reply_en", nullable = false) private String replyEn;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getComplaintId() { return complaintId; } public void setComplaintId(String v) { complaintId = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getCategory() { return category; } public void setCategory(String v) { category = v; }
        public String getUrgency() { return urgency; } public void setUrgency(String v) { urgency = v; }
        public String getSummaryAr() { return summaryAr; } public void setSummaryAr(String v) { summaryAr = v; }
        public String getSummaryEn() { return summaryEn; } public void setSummaryEn(String v) { summaryEn = v; }
        public String getReplyAr() { return replyAr; } public void setReplyAr(String v) { replyAr = v; }
        public String getReplyEn() { return replyEn; } public void setReplyEn(String v) { replyEn = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /**
     * V55 (feature 32): the one channel of one section — the map from `class_id` to the `chat_threads` row that holds
     * its posts, written the first time the channel is used ({@link ClassChannelService}). The unique index on
     * (school, class) is what makes two first posts land in one channel; the row is never updated.
     *
     * <p>A table rather than a partial unique index on `chat_threads`, because H2 — the test profile — has none.
     */
    @Entity(name = "ClassChannelEntity") @Table(name = "class_channels")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ClassChannelEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "class_id", nullable = false) private String classId;
        @Column(name = "thread_id", nullable = false) private String threadId;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getClassId() { return classId; } public void setClassId(String v) { classId = v; }
        public String getThreadId() { return threadId; } public void setThreadId(String v) { threadId = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /**
     * V55 (feature 32): one parent's read mark in one channel, as V22's `broadcast_reads` keeps a reader's — the
     * channel's unread count cannot be a column on the thread, because a channel has as many unread counts as it has
     * parents. {@code lastReadMessageId} is the post she had read, and the unread count is the posts after it.
     */
    @Entity(name = "ChannelReadEntity") @Table(name = "channel_reads")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ChannelReadEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "thread_id", nullable = false) private String threadId;
        @Column(name = "parent_id", nullable = false) private String parentId;
        @Column(name = "last_read_message_id") private String lastReadMessageId;
        @Column(name = "read_at", nullable = false) private Instant readAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getThreadId() { return threadId; } public void setThreadId(String v) { threadId = v; }
        public String getParentId() { return parentId; } public void setParentId(String v) { parentId = v; }
        public String getLastReadMessageId() { return lastReadMessageId; } public void setLastReadMessageId(String v) { lastReadMessageId = v; }
        public Instant getReadAt() { return readAt; } public void setReadAt(Instant v) { readAt = v; }
    }
}

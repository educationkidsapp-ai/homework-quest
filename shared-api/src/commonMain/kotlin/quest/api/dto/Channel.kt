package quest.api.dto

import kotlinx.serialization.Serializable

/**
 * Feature 32 `a child's team and the class channel` (docs/features/32-child-team-class-channel.md).
 *
 * Two shapes on one foundation, and **no second chat system**: the child's team is the people a parent may already
 * write to, listed on one page ([TeamMember]), and the class channel is a `chat_threads` row of its own kind whose
 * posts are ordinary chat messages ([ChannelPost]), with [ChatAttachment]s read over `GET /media/attachments/{id}`
 * exactly as a message's are.
 *
 * Behind `childTeam` and `classChannel` respectively, and both also behind `chat`: 404 while any of them is off.
 *
 * **No staff phone number appears in either shape.** Neither carries a `phone` field, and the server builds both
 * field by field rather than from a user row, so one cannot arrive by accident.
 * [TeamMember.officeHours] carries the *school's* number for urgent matters ([OfficeHoursStatus.schoolPhone]),
 * which is the one number a parent is given.
 */

/**
 * One person who looks after the child — `GET /children/{id}/team`, in the order the page reads: the subject
 * teachers, the subject coordinators, the floor supervisor, the department manager.
 *
 * [subjects] is what she teaches or coordinates on that section ("math, english"), absent for a manager and a floor
 * supervisor, who hold no subject on it. [officeHours] is present only while `chatOfficeHours` is on. [threadId] is
 * the one-to-one thread the parent already has with her, absent until one exists — **Message** then starts it over
 * the ordinary `POST /children/{id}/chat/threads/{staffId}/messages`. [canMessage] is false for a row she may read
 * but not write to; the clients hide the button rather than letting a send be refused.
 *
 * Feature 33 adds [latestEvaluation] to a teacher's row: the newest evaluation of this child she has written that
 * the parent may read — approved, of a released round — and `null` on every other row. It is **defaulted**, so a
 * client built before feature 33 decodes a row that carries it, and an older server's row decodes here.
 */
@Serializable
data class TeamMember(
    val staffId: String,
    val name: String,
    val role: ChatPeerRole,
    val subjects: String? = null,
    val officeHours: OfficeHoursStatus? = null,
    val threadId: String? = null,
    val canMessage: Boolean = true,
    val latestEvaluation: TeamLatestEvaluation? = null,
)

/**
 * Feature 33: "Latest evaluation" on a teacher's row of "My child's team" — the round it belongs to, in both
 * languages, and the subject it is about. Released and approved data only, and **no rating, no state and no
 * reviewer**: the words themselves are read on the evaluations screen ([ParentEvaluation]).
 */
@Serializable
data class TeamLatestEvaluation(val roundName: String, val roundNameAr: String, val subject: String)

/**
 * One post in a class channel. Written by a teacher of the section (or its homeroom teacher) and read by the parents
 * of the children in it; **parents never post**, so there is no parent sender here at all — a parent answers with
 * "Reply privately", which is her ordinary one-to-one thread with [authorId].
 *
 * [subject] is the author's subject on that section, so a parent can tell two teachers' posts apart. [bodyAr] is
 * absent unless the author wrote an Arabic body herself; a machine translation is feature 29's
 * `POST /children/{id}/translate`, which is the record of what was translated and by whom. [deleted] is `true` on a
 * post the author or the Admin took down — the row stays so paging and unread counts hold, with an empty [body] and
 * no attachments — and absent on every other post.
 */
@Serializable
data class ChannelPost(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorRole: ChatPeerRole,
    val subject: String? = null,
    val body: String,
    val bodyAr: String? = null,
    val attachments: List<ChatAttachment>? = null,
    val createdAt: Long,
    val deleted: Boolean? = null,
)

/**
 * A page of a class channel, oldest post first — `GET /children/{id}/channel` for the parent and
 * `GET /teacher/classes/{classId}/channel` for the staff side, both paged with `before` and `since` on a post id as
 * a chat thread is.
 *
 * [unread] is **the caller's own** count of posts she has not marked read (`POST /children/{id}/channel/read`), and
 * 0 on the staff side, which keeps no read state of its own.
 */
@Serializable
data class ChannelPage(
    val posts: List<ChannelPost>,
    val unread: Int = 0,
    val classId: String? = null,
    val className: String? = null,
)

/**
 * `POST /teacher/classes/{classId}/channel/posts`. [body] is 1–2000 characters of plain text, or may be empty with
 * at least one attachment; [attachmentIds] are up to five ids from `POST /teacher/classes/{classId}/channel/attachments`,
 * and [clientId] makes a retried post the same post, exactly as [SendChatMessageRequest.clientId] does.
 */
@Serializable
data class ChannelPostRequest(
    val body: String = "",
    val clientId: String? = null,
    val attachmentIds: List<String>? = null,
)

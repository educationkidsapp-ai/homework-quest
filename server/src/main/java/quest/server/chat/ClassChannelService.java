package quest.server.chat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.ChannelPage;
import quest.api.dto.ChannelPost;
import quest.api.dto.ChannelPostRequest;
import quest.api.dto.ChatAttachment;
import quest.api.dto.ChatPeerRole;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.files.Entities.AttachmentEntity;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 32, second half: <strong>the class channel</strong> — one channel per section, written by the teachers who
 * teach in it and read by the parents of the children in it.
 *
 * <p><strong>There is no second chat system.</strong> The channel is a {@code chat_threads} row of kind
 * `class_channel` ({@link ClassChannels}), a post is an ordinary {@code chat_messages} row written through
 * {@link ChatService#send} — so the `clientId` dedupe, {@code MAX_BODY}, {@link ChatRateLimiter} and
 * {@link ChatAttachments#claim} are the chat's own and not a copy of them — and a post's files are chat attachments
 * read over `GET /media/attachments/{id}` ({@link #mayReadFiles}).
 *
 * <p><strong>Who may do what.</strong>
 * <ul>
 *   <li><strong>Post:</strong> a teacher who holds an assignment on the section, or its homeroom teacher
 *       ({@code classes.teacher_id}). Anyone else — a teacher of another class included — gets the 404 an unknown
 *       class gets, because a teacher is told of no section that is not hers.</li>
 *   <li><strong>Read, as staff:</strong> the same people, plus the <strong>ADMIN</strong> scoped to the school with
 *       `X-School-Id` (D6), who reaches every class page and moderates there. She reads and deletes on the very
 *       `/teacher/classes/{classId}/channel` routes rather than a second set of her own: `permissions.json` grants
 *       her `channel.read` and `channel.moderate` and <em>not</em> `channel.post`, which is where "the Admin
 *       moderates but does not write in a teacher's name" is enforced.</li>
 *   <li><strong>Read, as a parent:</strong> the parent of a child placed in the section, over her own
 *       `/children/{id}/channel`. A parent of another class gets 404 for the page and for its files.</li>
 *   <li><strong>Delete:</strong> the post's author, or the ADMIN. Another teacher of the same section is refused —
 *       403, not 404: she may read the post, so its existence is no secret from her.</li>
 *   <li><strong>Parents never post.</strong> There is no parent write route at all; "Reply privately" is her
 *       ordinary one-to-one thread with the author, which is why parents never see each other.</li>
 * </ul>
 *
 * <p><strong>Both switches.</strong> `classChannel` is the controller's {@link quest.server.flags.FeatureFlag}, and
 * `chat` is checked here beside it — a channel is chat, so a school with chat off has none.
 */
@Service
public class ClassChannelService {
    private final ChatService chat; private final ClassChannels channels; private final ChatMessageRepository messages;
    private final ChannelReadRepository reads; private final ClassRepository classes; private final TeacherScope scope;
    private final ChildRepository children; private final UserRepository users; private final ChatAttachments files;
    private final NotificationService bells; private final FeatureFlags flags; private final Clock clock;

    public ClassChannelService(ChatService chat, ClassChannels channels, ChatMessageRepository messages, ChannelReadRepository reads,
                               ClassRepository classes, TeacherScope scope, ChildRepository children, UserRepository users,
                               ChatAttachments files, NotificationService bells, FeatureFlags flags, Clock clock) {
        this.chat = chat; this.channels = channels; this.messages = messages; this.reads = reads; this.classes = classes;
        this.scope = scope; this.children = children; this.users = users; this.files = files; this.bells = bells;
        this.flags = flags; this.clock = clock;
    }

    // ---------------------------------------------------------------- the parent (app)

    /** Her child's class channel, oldest post first, with her own unread count; an empty page while nobody has posted. */
    public ChannelPage parentChannel(Principals.Parent parent, String childId, String before, String since, Integer limit) {
        var section = sectionOf(parent, childId);
        var thread = channels.find(section.getId()).orElse(null);
        if (thread == null) return empty(section);
        return page(thread, section, unread(thread.getId(), parent.parentId()), before, since, limit);
    }

    /**
     * Everything in the channel is read, as of now — one row per (channel, parent), V22's `broadcast_reads` one
     * feature over. Her mark is the newest post at this moment, so a post written while she reads it is still unread.
     */
    @Transactional
    public ChannelPage markRead(Principals.Parent parent, String childId) {
        var section = sectionOf(parent, childId);
        var thread = channels.find(section.getId()).orElse(null);
        if (thread == null) return empty(section);
        var newest = messages.newest(thread.getId(), PageRequest.of(0, 1));
        mark(thread, parent.parentId(), newest.isEmpty() ? null : newest.getFirst().getId());
        return page(thread, section, 0, null, null, null);
    }

    // ---------------------------------------------------------------- the staff side (dashboard)

    /** The channel of one of her classes — the teachers of the section, and the ADMIN moderating it. */
    public ChannelPage staffChannel(Principals.User caller, String classId, String before, String since, Integer limit) {
        var section = requireSection(caller, classId);
        var thread = channels.find(section.getId()).orElse(null);
        if (thread == null) return empty(section);
        return page(thread, section, 0, before, since, limit);
    }

    /** A photo or a PDF for the next post, filed under her school exactly as a chat message's file is. */
    public AttachmentEntity upload(Principals.User caller, String classId, MultipartFile file) {
        requireSection(caller, classId);
        return files.uploadForStaff(caller, file);
    }

    /**
     * One post. The write path is the chat's — a retry with the same `clientId` answers the post already stored and
     * writes nothing — and the parents are told after it commits, once each, by {@code once_key} on the post's id, so
     * a retry notifies nobody twice ({@link NotificationService#parentsOfChannelPost}).
     */
    @Transactional
    public ChannelPost post(Principals.User caller, String classId, ChannelPostRequest request) {
        var section = requireSection(caller, classId);
        var thread = channels.getOrCreate(section);
        var message = chat.send(thread, null, ChatService.TEACHER, caller.userId(), request.getBody(),
                request.getAttachmentIds(), request.getClientId(), false);
        var author = users.findById(caller.userId()).orElse(null);
        var attached = message.getAttachments() == null ? List.<ChatAttachment>of() : message.getAttachments();
        bells.parentsOfChannelPost(section.getSchoolId(), parentsOf(section), message.getId(), ChatService.name(author),
                ChatAttachments.preview(message.getBody(), attached, false), ChatAttachments.preview(message.getBody(), attached, true));
        return one(message.getId(), caller.userId(), message.getBody(), message.getCreatedAt(), attached, null,
                author, subjectsOn(section).get(caller.userId()));
    }

    /** The author takes her post down, or the ADMIN does; the row stays as a tombstone so a page still holds. */
    @Transactional
    public void deletePost(Principals.User caller, String classId, String messageId) {
        var section = requireSection(caller, classId);
        var thread = channels.find(section.getId()).orElseThrow(() -> ApiException.notFound("post"));
        var post = messages.findOneById(messageId).filter(m -> thread.getId().equals(m.getThreadId()))
                .orElseThrow(() -> ApiException.notFound("post"));
        if (!caller.isAdmin() && !caller.userId().equals(post.getSenderId()))
            throw ApiException.forbidden("Only the teacher who wrote this post, or the Admin, can take it down.");
        messages.softDelete(post.getId(), clock.instant(), caller.userId());
    }

    // ---------------------------------------------------------------- the files of a post

    /**
     * Whether the caller may read a file sent in a channel — {@link ChatAttachments#readable}'s channel branch. The
     * parents of the section's children may, and on the staff side the section's own teachers and the ADMIN; a
     * manager or a coordinator holds `media.attachment.read` but no reach into a channel, so she may not. A post that
     * has been taken down answers to nobody, which {@code readable} settles before it reaches here.
     */
    public boolean mayReadFiles(Entities.ChatThreadEntity thread, Principals.Parent parent, Principals.User user) {
        if (thread.getClassId() == null || !flags.isOn(thread.getSchoolId(), FlagKeys.CLASS_CHANNEL)) return false;
        if (user != null) {
            if (user.isAdmin()) return true;
            var section = classes.findOneById(thread.getClassId()).filter(ClassEntity::isSection).orElse(null);
            return section != null && teaches(user, section);
        }
        if (parent == null) return false;
        return parentsOf(thread.getClassId()).stream().anyMatch(c -> parent.parentId().equals(c.getParentId()));
    }

    // ---------------------------------------------------------------- who reaches what

    /** The child's section: hers, her school's `chat` and `classChannel` on, and she is placed in a class. */
    private ClassEntity sectionOf(Principals.Parent parent, String childId) {
        var child = chat.placed(parent, childId);
        requireOn(child.getSchoolId());
        return classes.findOneById(child.getClassId()).orElseThrow(() -> ApiException.notFound("class"));
    }

    /**
     * A section whose channel this caller reaches: 404 for another school's (the `school` filter), for a pre-V7 row,
     * and for a section a TEACHER neither teaches in nor is the homeroom teacher of — the same answer a class that
     * does not exist gets, because a teacher is told of no section that is not hers. The ADMIN reading the school
     * with `X-School-Id` passes, which is what lets her moderate from the class page (D6).
     */
    private ClassEntity requireSection(Principals.User caller, String classId) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        String schoolId = scope.writeSchoolId();
        chat.requireOn(schoolId);
        requireOn(schoolId);
        var section = scope.section(classId);
        if (!scope.isTeacher(caller)) return section;
        if (!teaches(caller, section)) throw ApiException.notFound("class");
        return section;
    }

    private boolean teaches(Principals.User caller, ClassEntity section) {
        return caller.userId().equals(section.getTeacherId())
                || scope.assignmentsOn(section.getId()).stream().anyMatch(a -> caller.userId().equals(a.getTeacherId()));
    }

    /** The same body an unknown route gets while the school has the channel switched off. */
    private void requireOn(String schoolId) {
        if (!flags.isOn(schoolId, FlagKeys.CLASS_CHANNEL)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
    }

    // ---------------------------------------------------------------- shapes

    private static ChannelPage empty(ClassEntity section) {
        return new ChannelPage(List.of(), 0, section.getId(), section.getName());
    }

    private ChannelPage page(Entities.ChatThreadEntity thread, ClassEntity section, int unread, String before, String since, Integer limit) {
        var rows = chat.pageRows(thread.getId(), before, since, limit);
        return new ChannelPage(posts(section, rows), unread, section.getId(), section.getName());
    }

    /** One page of posts: three statements whatever its size — the rows, their authors, the section's assignments. */
    private List<ChannelPost> posts(ClassEntity section, List<Entities.ChatMessageEntity> rows) {
        if (rows.isEmpty()) return List.of();
        var authors = new LinkedHashMap<String, UserEntity>();
        for (var user : users.findAllById(rows.stream().map(Entities.ChatMessageEntity::getSenderId).distinct().toList()))
            authors.put(user.getId(), user);
        var subjects = subjectsOn(section);
        var out = new ArrayList<ChannelPost>(rows.size());
        for (var row : rows)
            out.add(one(row.getId(), row.getSenderId(), row.getBody(), row.getCreatedAt().toEpochMilli(),
                    ChatAttachments.decode(row.getAttachments()), row.getDeletedAt(), authors.get(row.getSenderId()),
                    subjects.get(row.getSenderId())));
        return List.copyOf(out);
    }

    /**
     * One post. A post that was taken down keeps its author, its role and its time — so a reader sees <em>who</em>
     * took what down and the page still pages — and carries no body and no files: the body was cleared where the row
     * was, and the files answer to nobody any more.
     */
    private ChannelPost one(String id, String authorId, String body, long createdAt, List<ChatAttachment> attached,
                            java.time.Instant deletedAt, UserEntity author, String subject) {
        boolean deleted = deletedAt != null;
        var role = author == null ? null : ChatService.peerRole(author.getRole());
        return new ChannelPost(id, authorId, ChatService.name(author), role == null ? ChatPeerRole.TEACHER : role,
                subject == null || subject.isBlank() ? null : subject, deleted ? "" : body, null,
                deleted || attached == null || attached.isEmpty() ? null : attached, createdAt, deleted ? Boolean.TRUE : null);
    }

    /** `[teacherId -> "math, english"]` on this section, so a parent can tell two teachers' posts apart. */
    private Map<String, String> subjectsOn(ClassEntity section) {
        var out = new LinkedHashMap<String, Set<String>>();
        for (var a : scope.assignmentsOn(section.getId()))
            if (a.getSubject() != null && !a.getSubject().isBlank())
                out.computeIfAbsent(a.getTeacherId(), k -> new LinkedHashSet<>()).add(a.getSubject().trim());
        var joined = new LinkedHashMap<String, String>();
        out.forEach((teacherId, subjects) -> joined.put(teacherId, String.join(", ", subjects)));
        return joined;
    }

    private List<ChildEntity> parentsOf(ClassEntity section) { return parentsOf(section.getId()); }

    /** The children of the section whose parents read the channel — the active ones, as an announcement's are. */
    private List<ChildEntity> parentsOf(String classId) {
        return children.findByClassIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(classId);
    }

    /** Her unread count: the posts after her mark, or every post while she has never marked one. */
    private int unread(String threadId, String parentId) {
        var cursor = reads.findOne(threadId, parentId)
                .map(Entities.ChannelReadEntity::getLastReadMessageId)
                .flatMap(id -> id == null ? Optional.<Entities.ChatMessageEntity>empty() : messages.findOneById(id))
                .orElse(null);
        long count = cursor == null ? messages.countIn(threadId) : messages.countAfter(threadId, cursor.getCreatedAt(), cursor.getId());
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    /** Her mark, written or moved. Two devices marking at once resolve on `channel_reads_thread_parent`. */
    private void mark(Entities.ChatThreadEntity thread, String parentId, String lastReadMessageId) {
        var row = reads.findOne(thread.getId(), parentId).orElse(null);
        if (row == null) {
            row = new Entities.ChannelReadEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(thread.getSchoolId());
            row.setThreadId(thread.getId()); row.setParentId(parentId);
        }
        row.setLastReadMessageId(lastReadMessageId); row.setReadAt(clock.instant());
        try { reads.saveAndFlush(row); }
        catch (DataIntegrityViolationException raced) {
            var winner = reads.findOne(thread.getId(), parentId).orElseThrow(() -> raced);
            winner.setLastReadMessageId(lastReadMessageId); winner.setReadAt(clock.instant());
            reads.save(winner);
        }
    }
}

package quest.server.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import quest.api.dto.ChatMessage;
import quest.api.dto.ChatReadReceipt;
import quest.api.dto.ChatSender;
import quest.api.dto.ChatThread;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.chat.Entities.ChatMessageEntity;
import quest.server.chat.Entities.ChatThreadEntity;
import quest.server.children.ChildRepository;
import quest.server.children.ChildService;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * C1: who may talk to whom, and the four things they do — list, page, send, read. REST and the socket both land
 * here, so the rules are checked once.
 *
 * <p><strong>Who reaches a thread.</strong> A thread exists only between a child's parent and a teacher who holds an
 * assignment on the child's section, checked from both ends: the parent's side resolves the child as hers
 * ({@link ChildService#owned}, 404 otherwise) and the teacher through the section's assignments
 * ({@link TeacherScope#assignmentsOn}, 404 — a parent is not told which teachers exist); the teacher's side resolves
 * the child through the `school` filter (404 for another school's) and her reach through
 * {@link TeacherScope#requireClass} (403 for a section she does not teach). A child on no section yet has no
 * teachers to write to and no teacher who may write to her: `409 child_not_placed`, from either side.
 *
 * <p><strong>Delivery.</strong> Every write publishes a {@link ChatEvent} on the {@link ChatBus} <em>after commit</em>
 * — the socket never announces a row that could still roll back — and every instance's {@link ChatHub} turns it
 * into frames for the sessions it holds. The flag is checked here too, not only by the interceptor, because a
 * socket command never passes an interceptor.
 */
@Service
public class ChatService {
    static final int MAX_BODY = 2000; static final int DEFAULT_PAGE = 50; static final int MAX_PAGE = 200;
    static final String PARENT = "parent", TEACHER = "teacher";
    /** D26: every dashboard role shares one socket key, so the hub finds a person's sessions without knowing her role. */
    public static final String USER = "user";

    private final ChatThreadRepository threads; private final ChatMessageRepository messages; private final ChatThreads threadRows;
    private final ChatRateLimiter limiter; private final ChatBus bus; private final ChildService childService; private final ChildRepository children;
    private final ClassRepository classes; private final UserRepository users; private final TeacherScope scope; private final TenantContext tenant;
    private final FeatureFlags flags; private final Clock clock; private final Json json;

    public ChatService(ChatThreadRepository threads, ChatMessageRepository messages, ChatThreads threadRows, ChatRateLimiter limiter, ChatBus bus,
                       ChildService childService, ChildRepository children, ClassRepository classes, UserRepository users, TeacherScope scope,
                       TenantContext tenant, FeatureFlags flags, Clock clock, Json json) {
        this.threads = threads; this.messages = messages; this.threadRows = threadRows; this.limiter = limiter; this.bus = bus;
        this.childService = childService; this.children = children; this.classes = classes; this.users = users; this.scope = scope;
        this.tenant = tenant; this.flags = flags; this.clock = clock; this.json = json;
    }

    /**
     * The key a socket session registers under, and the one an event names its sender by: `parent:<parentId>` for a
     * parent, `user:<userId>` for anyone on the dashboard. D26 widened the second from `teacher:` — the socket
     * carries notifications for ADMIN and MANAGERIAL too, and one key per person is what lets the hub reach every
     * session of hers whichever role she holds.
     */
    public static String key(String role, String id) { return (PARENT.equals(role) ? PARENT : USER) + ":" + id; }

    // ---------------------------------------------------------------- the parent (app)

    /** One row per teacher of the child's section, unread first; a teacher nobody has written to yet has `id = null`. */
    public List<ChatThread> parentThreads(Principals.Parent parent, String childId) {
        var child = placed(parent, childId);
        var assignments = scope.assignmentsOn(child.getClassId());
        var teacherIds = assignments.stream().map(TeachingAssignmentEntity::getTeacherId).distinct().toList();
        var teachers = byId(users.findAllById(teacherIds), UserEntity::getId);
        var section = classes.findOneById(child.getClassId()).map(ClassEntity::getName).orElse(null);
        var byTeacher = byId(threads.findByChildIdOrderByLastMessageAtDesc(childId), ChatThreadEntity::getTeacherId);
        var last = lastMessages(byTeacher.values());
        var subjects = assignments.stream().collect(Collectors.groupingBy(TeachingAssignmentEntity::getTeacherId, LinkedHashMap::new,
                Collectors.mapping(TeachingAssignmentEntity::getSubject, Collectors.joining(", "))));
        var rows = new ArrayList<ChatThread>();
        for (String teacherId : teacherIds) {
            var t = byTeacher.get(teacherId); var teacher = teachers.get(teacherId);
            rows.add(new ChatThread(t == null ? null : t.getId(), child.getId(), child.getName(), teacherId, name(teacher), section,
                    subjects.get(teacherId), t == null ? 0 : t.getParentUnread(), t == null ? null : last.get(t.getId())));
        }
        rows.sort(order());
        return rows;
    }

    public List<ChatMessage> parentMessages(Principals.Parent parent, String childId, String teacherId, String before, String since, Integer limit) {
        var child = placed(parent, childId); requireTeacherOf(child, teacherId);
        return threads.findByChildIdAndTeacherId(childId, teacherId).map(t -> page(t.getId(), before, since, limit)).orElse(List.of());
    }

    @Transactional
    public ChatMessage parentSend(Principals.Parent parent, String childId, String teacherId, String body, String clientId) {
        var child = placed(parent, childId); requireTeacherOf(child, teacherId);
        return send(child, teacherId, PARENT, parent.parentId(), body, clientId);
    }

    @Transactional
    public ChatReadReceipt parentRead(Principals.Parent parent, String childId, String teacherId) {
        var child = placed(parent, childId); requireTeacherOf(child, teacherId);
        return read(child, threadOf(childId, teacherId), PARENT, parent.parentId());
    }

    public void parentTyping(Principals.Parent parent, String childId, String teacherId) {
        var child = placed(parent, childId); requireTeacherOf(child, teacherId);
        threads.findByChildIdAndTeacherId(childId, teacherId).ifPresent(t -> publish(ChatEvent.typing(child.getSchoolId(), t.getId(), child.getId(), teacherId, child.getParentId(), key(PARENT, parent.parentId()), PARENT)));
    }

    // ---------------------------------------------------------------- the teacher (dashboard)

    /** Her conversations across the sections she is assigned to, unread first then newest. */
    public List<ChatThread> teacherThreads(Principals.User caller) {
        var teacher = TeacherScope.require(caller);
        requireOn(tenant.writeSchoolId());
        var subjects = scope.assignmentsOf(teacher).stream().collect(Collectors.groupingBy(TeachingAssignmentEntity::getClassId, LinkedHashMap::new,
                Collectors.mapping(TeachingAssignmentEntity::getSubject, Collectors.joining(", "))));
        var mine = threads.findForTeacher(teacher.userId());
        var kids = byId(children.findAllById(mine.stream().map(ChatThreadEntity::getChildId).toList()), ChildEntity::getId);
        var live = mine.stream().filter(t -> { var c = kids.get(t.getChildId()); return c != null && c.getDeletedAt() == null && c.getClassId() != null && subjects.containsKey(c.getClassId()); }).toList();
        var sections = byId(classes.findAllById(live.stream().map(t -> kids.get(t.getChildId()).getClassId()).distinct().toList()), ClassEntity::getId);
        var last = lastMessages(live);
        String teacherName = users.findById(teacher.userId()).map(ChatService::name).orElse(teacher.email());
        return live.stream().map(t -> { var c = kids.get(t.getChildId()); var k = sections.get(c.getClassId());
            return new ChatThread(t.getId(), c.getId(), c.getName(), teacher.userId(), teacherName, k == null ? null : k.getName(),
                    subjects.get(c.getClassId()), t.getTeacherUnread(), last.get(t.getId())); }).toList();
    }

    public List<ChatMessage> teacherMessages(Principals.User caller, String childId, String before, String since, Integer limit) {
        var teacher = TeacherScope.require(caller); var child = childOf(teacher, childId);
        return threads.findByChildIdAndTeacherId(child.getId(), teacher.userId()).map(t -> page(t.getId(), before, since, limit)).orElse(List.of());
    }

    @Transactional
    public ChatMessage teacherSend(Principals.User caller, String childId, String body, String clientId) {
        var teacher = TeacherScope.require(caller); var child = childOf(teacher, childId);
        return send(child, teacher.userId(), TEACHER, teacher.userId(), body, clientId);
    }

    @Transactional
    public ChatReadReceipt teacherRead(Principals.User caller, String childId) {
        var teacher = TeacherScope.require(caller); var child = childOf(teacher, childId);
        return read(child, threadOf(child.getId(), teacher.userId()), TEACHER, teacher.userId());
    }

    public void teacherTyping(Principals.User caller, String childId) {
        var teacher = TeacherScope.require(caller); var child = childOf(teacher, childId);
        threads.findByChildIdAndTeacherId(child.getId(), teacher.userId()).ifPresent(t -> publish(ChatEvent.typing(child.getSchoolId(), t.getId(), child.getId(), teacher.userId(), child.getParentId(), key(TEACHER, teacher.userId()), TEACHER)));
    }

    // ---------------------------------------------------------------- support (Admin, read-only, `X-School-Id`)

    public List<ChatThread> supportThreads() {
        String schoolId = requireSchool(); requireOn(schoolId);
        var all = threads.findAllByOrderByLastMessageAtDesc();
        var kids = byId(children.findAllById(all.stream().map(ChatThreadEntity::getChildId).distinct().toList()), ChildEntity::getId);
        var teachers = byId(users.findAllById(all.stream().map(ChatThreadEntity::getTeacherId).distinct().toList()), UserEntity::getId);
        var sections = byId(classes.findAllById(kids.values().stream().map(ChildEntity::getClassId).filter(Objects::nonNull).distinct().toList()), ClassEntity::getId);
        var last = lastMessages(all);
        return all.stream().map(t -> { var c = kids.get(t.getChildId()); var k = c == null || c.getClassId() == null ? null : sections.get(c.getClassId());
            return new ChatThread(t.getId(), t.getChildId(), c == null ? "" : c.getName(), t.getTeacherId(), name(teachers.get(t.getTeacherId())),
                    k == null ? null : k.getName(), null, t.getParentUnread() + t.getTeacherUnread(), last.get(t.getId())); }).toList();
    }

    public List<ChatMessage> supportMessages(String threadId, String before, String since, Integer limit) {
        String schoolId = requireSchool(); requireOn(schoolId);
        var t = threads.findOneById(threadId).orElseThrow(() -> ApiException.notFound("thread"));
        return page(t.getId(), before, since, limit);
    }

    // ---------------------------------------------------------------- the four things

    private ChatMessage send(ChildEntity child, String teacherId, String role, String senderId, String rawBody, String clientId) {
        String body = clean(rawBody);
        limiter.record(key(role, senderId));
        var thread = threadRows.getOrCreate(child, teacherId);
        var m = new ChatMessageEntity();
        m.setId(UUID.randomUUID().toString()); m.setSchoolId(child.getSchoolId()); m.setThreadId(thread.getId());
        m.setSenderRole(role); m.setSenderId(senderId); m.setBody(body); m.setCreatedAt(clock.instant());
        messages.save(m);
        if (PARENT.equals(role)) threads.bumpTeacherUnread(thread.getId(), m.getCreatedAt()); else threads.bumpParentUnread(thread.getId(), m.getCreatedAt());
        var dto = dto(m);
        publish(ChatEvent.message(child.getSchoolId(), thread.getId(), child.getId(), teacherId, child.getParentId(), key(role, senderId), clientId,
                dto.getId(), json.encodeShared(dto, ChatMessage.Companion.serializer())));
        return dto;
    }

    private ChatReadReceipt read(ChildEntity child, ChatThreadEntity thread, String role, String readerId) {
        Instant now = clock.instant();
        messages.markRead(thread.getId(), PARENT.equals(role) ? TEACHER : PARENT, now);
        if (PARENT.equals(role)) threads.clearParentUnread(thread.getId()); else threads.clearTeacherUnread(thread.getId());
        publish(ChatEvent.read(child.getSchoolId(), thread.getId(), child.getId(), thread.getTeacherId(), child.getParentId(), key(role, readerId), role, now.toEpochMilli()));
        return new ChatReadReceipt(thread.getId(), sender(role), now.toEpochMilli());
    }

    /**
     * Oldest first. `before` pages backwards from a message id (the newest page when absent); `since` answers
     * everything after a message id, which is the reconnect refetch. Either cursor must belong to the thread.
     */
    List<ChatMessage> page(String threadId, String before, String since, Integer limit) {
        int n = limit == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, limit));
        var pageable = PageRequest.of(0, n);
        if (since != null && !since.isBlank()) {
            var cursor = cursor(threadId, since, "since");
            return messages.since(threadId, cursor.getCreatedAt(), cursor.getId(), pageable).stream().map(ChatService::dto).toList();
        }
        List<ChatMessageEntity> newestFirst;
        if (before == null || before.isBlank()) newestFirst = messages.newest(threadId, pageable);
        else { var cursor = cursor(threadId, before, "before"); newestFirst = messages.before(threadId, cursor.getCreatedAt(), cursor.getId(), pageable); }
        var oldestFirst = new ArrayList<>(newestFirst); Collections.reverse(oldestFirst);
        return oldestFirst.stream().map(ChatService::dto).toList();
    }

    private ChatMessageEntity cursor(String threadId, String id, String name) {
        return messages.findOneById(id).filter(m -> m.getThreadId().equals(threadId))
                .orElseThrow(() -> ApiException.badRequest(name + " must be the id of a message in this thread"));
    }

    // ---------------------------------------------------------------- who reaches what

    /** The parent's child, on a section: 404 when she is not hers, 409 `child_not_placed` when she sits nowhere yet. */
    private ChildEntity placed(Principals.Parent parent, String childId) {
        var child = childService.owned(childId, parent);
        requireOn(child.getSchoolId());
        if (child.getClassId() == null) throw notPlaced();
        return child;
    }

    /** A teacher of the child's section, or 404: a parent is not told which teachers exist beyond her child's. */
    private void requireTeacherOf(ChildEntity child, String teacherId) {
        if (scope.assignmentsOn(child.getClassId()).stream().noneMatch(a -> a.getTeacherId().equals(teacherId))) throw ApiException.notFound("teacher");
    }

    /** The teacher's child: 404 for another school's (the filter), 409 unplaced, 403 for a section she does not teach. */
    private ChildEntity childOf(Principals.User teacher, String childId) {
        requireOn(tenant.writeSchoolId());
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("child"));
        if (child.getClassId() == null) throw notPlaced();
        scope.requireClass(teacher, child.getClassId());
        return child;
    }

    private ChatThreadEntity threadOf(String childId, String teacherId) {
        return threads.findByChildIdAndTeacherId(childId, teacherId).orElseThrow(() -> ApiException.notFound("thread"));
    }

    private String requireSchool() {
        String schoolId = tenant.schoolId();
        if (schoolId == null) throw ApiException.badRequest("Send X-School-Id: chat threads are read one school at a time.");
        return schoolId;
    }

    /** The same body an unknown route gets, so a socket command on a school without the feature reads as REST does. */
    private void requireOn(String schoolId) {
        if (!flags.isOn(schoolId, FlagKeys.CHAT)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
    }

    private static ApiException notPlaced() {
        return ApiException.conflict("child_not_placed", "This child is not in a class yet. Ask the teacher to place her, then try again.");
    }

    // ---------------------------------------------------------------- shapes

    /** Trimmed plain text, 1–2000 characters, with control characters other than line breaks and tabs removed. */
    static String clean(String raw) {
        if (raw == null) throw ApiException.badRequest("body is required");
        String body = raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t\\r]]", "").strip();
        if (body.isEmpty()) throw ApiException.badRequest("Write something first.");
        if (body.length() > MAX_BODY) throw ApiException.badRequest("Keep it under " + MAX_BODY + " characters.");
        return body;
    }

    static ChatMessage dto(ChatMessageEntity m) {
        return new ChatMessage(m.getId(), m.getThreadId(), sender(m.getSenderRole()), m.getSenderId(), m.getBody(), m.getCreatedAt().toEpochMilli(),
                m.getReadAt() == null ? null : m.getReadAt().toEpochMilli());
    }

    static ChatSender sender(String role) { return PARENT.equals(role) ? ChatSender.PARENT : ChatSender.TEACHER; }

    private static String name(UserEntity u) { return u == null ? "" : u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getEmail() : u.getDisplayName(); }

    private Map<String, ChatMessage> lastMessages(java.util.Collection<ChatThreadEntity> ts) {
        var ids = ts.stream().map(ChatThreadEntity::getId).toList();
        if (ids.isEmpty()) return Map.of();
        var out = new HashMap<String, ChatMessage>();
        for (var m : messages.lastOf(ids)) out.putIfAbsent(m.getThreadId(), dto(m));
        return out;
    }

    private static <T> Map<String, T> byId(List<T> rows, Function<T, String> id) { return rows.stream().collect(Collectors.toMap(id, r -> r, (a, b) -> a, LinkedHashMap::new)); }

    private static Comparator<ChatThread> order() {
        return Comparator.<ChatThread>comparingInt(t -> t.getUnread() > 0 ? 0 : 1)
                .thenComparing(t -> t.getLastMessage() == null ? Long.MIN_VALUE : t.getLastMessage().getCreatedAt(), Comparator.reverseOrder())
                .thenComparing(ChatThread::getTeacherName);
    }

    /** After the commit, or now when there is no transaction (typing): the socket never announces a row that could still roll back. */
    private void publish(ChatEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { bus.publish(event); } });
        else bus.publish(event);
    }
}

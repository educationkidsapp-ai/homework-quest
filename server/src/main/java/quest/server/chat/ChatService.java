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
import quest.api.dto.ChatStaffRole;
import quest.api.dto.ChatThread;
import quest.api.dto.ChatThreadStatus;
import quest.api.dto.ChatTopic;
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
import quest.server.tenancy.CoordinatorScope;
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
    /**
     * `chat_messages.sender_role`. `peer` is R4's third name: on a coordinator-to-manager thread both sides are
     * staff, and a read receipt has to know whose messages it is marking.
     */
    static final String PARENT = "parent", TEACHER = "teacher", PEER = "peer";
    /** V20 `chat_threads.staff_role` — which staff member holds the dashboard side (R4, DR3). */
    public static final String ROLE_TEACHER = "TEACHER", COORDINATOR = "COORDINATOR", MANAGERIAL = "MANAGERIAL";
    /** V20 `chat_threads.topic` and `.status`, two words each. */
    public static final String QUESTION = "question", COMPLAINT = "complaint", OPEN = "open", RESOLVED = "resolved";
    /** D26: every dashboard role shares one socket key, so the hub finds a person's sessions without knowing her role. */
    public static final String USER = "user";

    private final ChatThreadRepository threads; private final ChatMessageRepository messages; private final ChatThreads threadRows;
    private final ChatRateLimiter limiter; private final ChatBus bus; private final ChildService childService; private final ChildRepository children;
    private final ClassRepository classes; private final UserRepository users; private final TeacherScope scope; private final TenantContext tenant;
    private final CoordinatorScope coordinatorScope; private final ChatPeers peers;
    private final FeatureFlags flags; private final Clock clock; private final Json json;

    public ChatService(ChatThreadRepository threads, ChatMessageRepository messages, ChatThreads threadRows, ChatRateLimiter limiter, ChatBus bus,
                       ChildService childService, ChildRepository children, ClassRepository classes, UserRepository users, TeacherScope scope,
                       TenantContext tenant, CoordinatorScope coordinatorScope, ChatPeers peers, FeatureFlags flags, Clock clock, Json json) {
        this.threads = threads; this.messages = messages; this.threadRows = threadRows; this.limiter = limiter; this.bus = bus;
        this.childService = childService; this.children = children; this.classes = classes; this.users = users; this.scope = scope;
        this.tenant = tenant; this.coordinatorScope = coordinatorScope; this.peers = peers; this.flags = flags; this.clock = clock; this.json = json;
    }

    /**
     * The key a socket session registers under, and the one an event names its sender by: `parent:<parentId>` for a
     * parent, `user:<userId>` for anyone on the dashboard. D26 widened the second from `teacher:` — the socket
     * carries notifications for ADMIN and MANAGERIAL too, and one key per person is what lets the hub reach every
     * session of hers whichever role she holds.
     */
    public static String key(String role, String id) { return (PARENT.equals(role) ? PARENT : USER) + ":" + id; }

    // ---------------------------------------------------------------- the parent (app)

    /**
     * One row per teacher of the child's section, unread first; a teacher nobody has written to yet has `id = null`.
     * R4 appends the coordinator threads that already exist — a coordinator is not one of the child's teachers, so
     * she appears here once there is something to show and is <em>started</em> from {@link #parentCoordinators}.
     */
    public List<ChatThread> parentThreads(Principals.Parent parent, String childId) {
        var child = placed(parent, childId);
        var assignments = scope.assignmentsOn(child.getClassId());
        var teacherIds = assignments.stream().map(TeachingAssignmentEntity::getTeacherId).distinct().toList();
        var byStaff = byId(threads.findByChildIdOrderByLastMessageAtDesc(childId), ChatThreadEntity::getTeacherId);
        var staffThreads = byStaff.values().stream().filter(t -> !ROLE_TEACHER.equals(t.getStaffRole())).toList();
        var lookup = new ArrayList<>(teacherIds);
        staffThreads.forEach(t -> lookup.add(t.getTeacherId()));
        var staff = byId(users.findAllById(lookup.stream().distinct().toList()), UserEntity::getId);
        var section = classes.findOneById(child.getClassId()).map(ClassEntity::getName).orElse(null);
        var last = lastMessages(byStaff.values());
        var subjects = assignments.stream().collect(Collectors.groupingBy(TeachingAssignmentEntity::getTeacherId, LinkedHashMap::new,
                Collectors.mapping(TeachingAssignmentEntity::getSubject, Collectors.joining(", "))));
        var rows = new ArrayList<ChatThread>();
        for (String teacherId : teacherIds) {
            var t = byStaff.get(teacherId);
            rows.add(row(t, child.getId(), child.getName(), teacherId, name(staff.get(teacherId)), section,
                    subjects.get(teacherId), t == null ? 0 : t.getParentUnread(), t == null ? null : last.get(t.getId()), ROLE_TEACHER));
        }
        for (var t : staffThreads)
            rows.add(row(t, child.getId(), child.getName(), t.getTeacherId(), name(staff.get(t.getTeacherId())), section,
                    null, t.getParentUnread(), last.get(t.getId()), t.getStaffRole()));
        rows.sort(order());
        return rows;
    }

    /**
     * `GET /children/{id}/coordinators` (R4, DR3): the coordinators of the subjects taught in her child's section,
     * as thread rows — `id` null until she writes the first message, exactly as an unwritten teacher row is. The
     * scope is read from `staff_scopes` ({@link ChatPeers#coordinatorsOn}), never from anything the app sends.
     */
    public List<ChatThread> parentCoordinators(Principals.Parent parent, String childId) {
        var child = placed(parent, childId);
        var section = classes.findOneById(child.getClassId()).orElse(null);
        if (section == null) return List.of();
        var byStaff = byId(threads.findByChildIdOrderByLastMessageAtDesc(childId), ChatThreadEntity::getTeacherId);
        var last = lastMessages(byStaff.values());
        var rows = new ArrayList<ChatThread>();
        for (var coordinator : peers.coordinatorsOn(child.getSchoolId(), section)) {
            var t = byStaff.get(coordinator.user().getId());
            rows.add(row(t, child.getId(), child.getName(), coordinator.user().getId(), name(coordinator.user()), section.getName(),
                    coordinator.subjects(), t == null ? 0 : t.getParentUnread(), t == null ? null : last.get(t.getId()), COORDINATOR));
        }
        rows.sort(order());
        return rows;
    }

    public List<ChatMessage> parentMessages(Principals.Parent parent, String childId, String staffId, String before, String since, Integer limit) {
        var child = placed(parent, childId); requireStaffOf(child, staffId);
        return threads.findByChildIdAndTeacherId(childId, staffId).map(t -> page(t.getId(), before, since, limit)).orElse(List.of());
    }

    @Transactional
    public ChatMessage parentSend(Principals.Parent parent, String childId, String staffId, String body, String clientId, ChatTopic topic) {
        var child = placed(parent, childId);
        var thread = threadRows.getOrCreate(child, staffId, requireStaffOf(child, staffId), topic == null ? QUESTION : key(topic));
        return send(thread, child.getParentId(), PARENT, parent.parentId(), body, clientId);
    }

    @Transactional
    public ChatReadReceipt parentRead(Principals.Parent parent, String childId, String staffId) {
        var child = placed(parent, childId); requireStaffOf(child, staffId);
        return read(threadOf(childId, staffId), child.getParentId(), PARENT, parent.parentId());
    }

    public void parentTyping(Principals.Parent parent, String childId, String staffId) {
        var child = placed(parent, childId); requireStaffOf(child, staffId);
        threads.findByChildIdAndTeacherId(childId, staffId).ifPresent(t -> publish(ChatEvent.typing(t.getSchoolId(), t.getId(), child.getId(),
                t.getTeacherId(), child.getParentId(), t.getPeerUserId(), key(PARENT, parent.parentId()), PARENT)));
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
            return row(t, c.getId(), c.getName(), teacher.userId(), teacherName, k == null ? null : k.getName(),
                    subjects.get(c.getClassId()), t.getTeacherUnread(), last.get(t.getId()), ROLE_TEACHER); }).toList();
    }

    public List<ChatMessage> teacherMessages(Principals.User caller, String childId, String before, String since, Integer limit) {
        var teacher = TeacherScope.require(caller); var child = childOf(teacher, childId);
        return threads.findByChildIdAndTeacherId(child.getId(), teacher.userId()).map(t -> page(t.getId(), before, since, limit)).orElse(List.of());
    }

    @Transactional
    public ChatMessage teacherSend(Principals.User caller, String childId, String body, String clientId) {
        var teacher = TeacherScope.require(caller); var child = childOf(teacher, childId);
        return send(threadRows.getOrCreate(child, teacher.userId(), ROLE_TEACHER, QUESTION), child.getParentId(), TEACHER, teacher.userId(), body, clientId);
    }

    @Transactional
    public ChatReadReceipt teacherRead(Principals.User caller, String childId) {
        var teacher = TeacherScope.require(caller); var child = childOf(teacher, childId);
        return read(threadOf(child.getId(), teacher.userId()), child.getParentId(), TEACHER, teacher.userId());
    }

    public void teacherTyping(Principals.User caller, String childId) {
        var teacher = TeacherScope.require(caller); var child = childOf(teacher, childId);
        threads.findByChildIdAndTeacherId(child.getId(), teacher.userId()).ifPresent(t -> publish(ChatEvent.typing(t.getSchoolId(), t.getId(),
                child.getId(), teacher.userId(), child.getParentId(), t.getPeerUserId(), key(TEACHER, teacher.userId()), TEACHER)));
    }

    // ---------------------------------------------------------------- support (Admin, read-only, `X-School-Id`)

    public List<ChatThread> supportThreads() {
        String schoolId = requireSchool(); requireOn(schoolId);
        var all = threads.findAllByOrderByLastMessageAtDesc();
        var kids = byId(children.findAllById(all.stream().map(ChatThreadEntity::getChildId).filter(Objects::nonNull).distinct().toList()), ChildEntity::getId);
        var teachers = byId(users.findAllById(all.stream().map(ChatThreadEntity::getTeacherId).distinct().toList()), UserEntity::getId);
        var sections = byId(classes.findAllById(kids.values().stream().map(ChildEntity::getClassId).filter(Objects::nonNull).distinct().toList()), ClassEntity::getId);
        var last = lastMessages(all);
        return all.stream().map(t -> { var c = kids.get(t.getChildId()); var k = c == null || c.getClassId() == null ? null : sections.get(c.getClassId());
            return row(t, t.getChildId() == null ? "" : t.getChildId(), c == null ? "" : c.getName(), t.getTeacherId(), name(teachers.get(t.getTeacherId())),
                    k == null ? null : k.getName(), null, t.getParentUnread() + t.getTeacherUnread(), last.get(t.getId()), t.getStaffRole()); }).toList();
    }

    public List<ChatMessage> supportMessages(String threadId, String before, String since, Integer limit) {
        String schoolId = requireSchool(); requireOn(schoolId);
        var t = threads.findOneById(threadId).orElseThrow(() -> ApiException.notFound("thread"));
        return page(t.getId(), before, since, limit);
    }

    // ---------------------------------------------------------------- the coordinator (dashboard, R4)

    /**
     * Her conversations, named by thread id rather than by child, because one of them has no child on it: the parents
     * who wrote to her about a child in a section she supervises, and the manager of her department. `topic` and
     * `status` are the Complaints inbox's two filters.
     *
     * <p>A parent thread drops out of the list when the child leaves her scope — the thread is not deleted, it simply
     * stops being hers to answer, which is the rule {@link #teacherThreads} applies with the teacher's assignments.
     */
    public List<ChatThread> coordinatorThreads(Principals.User caller, String topic, String status) {
        var me = CoordinatorScope.require(caller);
        requireOn(tenant.writeSchoolId());
        var reach = coordinatorScope.reach(me);
        var mine = threads.findForStaff(me.userId()).stream()
                .filter(t -> topic == null || topic.equals(t.getTopic()))
                .filter(t -> status == null || status.equals(t.getStatus())).toList();
        var kids = byId(children.findAllById(mine.stream().map(ChatThreadEntity::getChildId).filter(Objects::nonNull).distinct().toList()), ChildEntity::getId);
        var live = mine.stream().filter(t -> mayAnswer(t, kids, reach)).toList();
        var last = lastMessages(live);
        var people = byId(users.findAllById(live.stream().map(t -> named(t, me.userId())).distinct().toList()), UserEntity::getId);
        var rows = new ArrayList<ChatThread>(live.size());
        for (var t : live) {
            var child = t.getChildId() == null ? null : kids.get(t.getChildId());
            var section = child == null || child.getClassId() == null ? null : reach.byId().get(child.getClassId());
            String person = named(t, me.userId());
            rows.add(row(t, child == null ? "" : child.getId(), child == null ? "" : child.getName(), person, name(people.get(person)),
                    section == null ? null : section.getName(), null, unreadFor(t, me.userId()), last.get(t.getId()), t.getStaffRole()));
        }
        return List.copyOf(rows);
    }

    public List<ChatMessage> coordinatorMessages(Principals.User caller, String threadId, String before, String since, Integer limit) {
        var t = ownThread(CoordinatorScope.require(caller), threadId);
        return page(t.getId(), before, since, limit);
    }

    @Transactional
    public ChatMessage coordinatorSend(Principals.User caller, String threadId, String body, String clientId) {
        var me = CoordinatorScope.require(caller); var t = ownThread(me, threadId);
        return send(t, parentOf(t), roleOn(t, me.userId()), me.userId(), body, clientId);
    }

    @Transactional
    public ChatReadReceipt coordinatorRead(Principals.User caller, String threadId) {
        var me = CoordinatorScope.require(caller); var t = ownThread(me, threadId);
        return read(t, parentOf(t), roleOn(t, me.userId()), me.userId());
    }

    public void coordinatorTyping(Principals.User caller, String threadId) {
        var me = CoordinatorScope.require(caller); var t = ownThread(me, threadId);
        String role = roleOn(t, me.userId());
        publish(ChatEvent.typing(t.getSchoolId(), t.getId(), t.getChildId(), t.getTeacherId(), parentOf(t), t.getPeerUserId(), key(role, me.userId()), role));
    }

    /**
     * `PATCH /coordinator/chat/threads/{id}/status`: `open` or `resolved`, and the parent is told over her own socket
     * with a `status` frame so her app can show that the complaint was answered without refetching the list.
     */
    @Transactional
    public ChatThread coordinatorStatus(Principals.User caller, String threadId, String wanted) {
        var me = CoordinatorScope.require(caller); var t = ownThread(me, threadId);
        String status = switch (wanted == null ? "" : wanted.trim().toLowerCase(java.util.Locale.ROOT)) {
            case OPEN -> OPEN;
            case RESOLVED -> RESOLVED;
            default -> throw ApiException.badRequest("status must be " + OPEN + " or " + RESOLVED + ".");
        };
        Instant now = clock.instant();
        t.setStatus(status);
        t.setResolvedAt(RESOLVED.equals(status) ? now : null);
        t.setResolvedBy(RESOLVED.equals(status) ? me.userId() : null);
        threads.save(t);
        publish(ChatEvent.status(t.getSchoolId(), t.getId(), t.getChildId(), t.getTeacherId(), parentOf(t), t.getPeerUserId(), status, now.toEpochMilli()));
        return one(t, me.userId());
    }

    /**
     * `POST /coordinator/chat/threads`: her thread with one manager of her own department (DR5). The manager is
     * resolved through {@link ChatPeers#managersFor} — a manager of the other track is 404, because she is not told
     * which managers exist outside her department any more than a parent is told which teachers exist.
     */
    @Transactional
    public ChatThread coordinatorStaffThread(Principals.User caller, String managerUserId) {
        var me = CoordinatorScope.require(caller);
        String schoolId = tenant.writeSchoolId();
        requireOn(schoolId);
        if (managerUserId == null || managerUserId.isBlank()) throw ApiException.badRequest("managerUserId is required");
        var manager = peers.managersFor(me).stream().filter(u -> u.getId().equals(managerUserId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("manager"));
        return one(threadRows.getOrCreateStaff(schoolId, me.userId(), manager.getId()), me.userId());
    }

    // ---------------------------------------------------------------- who reaches a coordinator's thread

    /**
     * One thread with her on it: 404 for another school's (the filter), another person's, a child who has left her
     * scope, or a manager whose department no longer meets hers. Every `/coordinator/chat` handler goes through it,
     * which is what puts {@link CoordinatorScope} in the reach of each one.
     */
    private ChatThreadEntity ownThread(Principals.User me, String threadId) {
        requireOn(tenant.writeSchoolId());
        var t = threads.findOneById(threadId).orElseThrow(() -> ApiException.notFound("thread"));
        if (!me.userId().equals(t.getTeacherId()) && !me.userId().equals(t.getPeerUserId())) throw ApiException.notFound("thread");
        if (t.getChildId() != null) coordinatorScope.requireChild(me, t.getChildId());
        else if (peers.managersFor(me).stream().noneMatch(u -> u.getId().equals(named(t, me.userId())))) throw ApiException.notFound("thread");
        return t;
    }

    /** A thread she may still answer: a staff thread always, a parent thread while the child sits in her scope. */
    private static boolean mayAnswer(ChatThreadEntity t, Map<String, ChildEntity> kids, CoordinatorScope.Reach reach) {
        if (t.getChildId() == null) return true;
        var child = kids.get(t.getChildId());
        return child != null && child.getDeletedAt() == null && child.getClassId() != null && reach.byId().containsKey(child.getClassId());
    }

    /** Whom `teacherId` names on a staff caller's row: herself on a parent thread, the other person on a staff one. */
    private static String named(ChatThreadEntity t, String meId) {
        if (t.getPeerUserId() == null) return t.getTeacherId();
        return t.getTeacherId().equals(meId) ? t.getPeerUserId() : t.getTeacherId();
    }

    /** `teacher` for the thread's own staff peer, `peer` for the second staff member of a staff thread. */
    private static String roleOn(ChatThreadEntity t, String meId) { return t.getTeacherId().equals(meId) ? TEACHER : PEER; }

    private static int unreadFor(ChatThreadEntity t, String meId) { return t.getTeacherId().equals(meId) ? t.getTeacherUnread() : t.getParentUnread(); }

    /** The parent a thread event reaches, or null on a staff thread; the child is the one the thread names. */
    private String parentOf(ChatThreadEntity t) {
        if (t.getChildId() == null) return null;
        return children.findOneById(t.getChildId()).map(ChildEntity::getParentId).orElse(null);
    }

    /** One row, for the two writes that answer a single thread rather than a list. */
    private ChatThread one(ChatThreadEntity t, String meId) {
        var child = t.getChildId() == null ? null : children.findOneById(t.getChildId()).orElse(null);
        var section = child == null || child.getClassId() == null ? null : classes.findOneById(child.getClassId()).orElse(null);
        String person = named(t, meId);
        return row(t, child == null ? "" : child.getId(), child == null ? "" : child.getName(), person,
                users.findById(person).map(ChatService::name).orElse(""), section == null ? null : section.getName(),
                null, unreadFor(t, meId), lastMessages(List.of(t)).get(t.getId()), t.getStaffRole());
    }

    // ---------------------------------------------------------------- the four things

    /**
     * One message into a thread that already exists — the callers create it, because only they know which shape it is.
     * `teacher_unread` is the staff peer's badge and `parent_unread` the counterpart's, whether that counterpart is
     * the child's parent or the manager on the other end of a staff thread.
     */
    private ChatMessage send(ChatThreadEntity thread, String parentId, String role, String senderId, String rawBody, String clientId) {
        String body = clean(rawBody);
        limiter.record(key(role, senderId));
        var m = new ChatMessageEntity();
        m.setId(UUID.randomUUID().toString()); m.setSchoolId(thread.getSchoolId()); m.setThreadId(thread.getId());
        m.setSenderRole(role); m.setSenderId(senderId); m.setBody(body); m.setCreatedAt(clock.instant());
        messages.save(m);
        if (TEACHER.equals(role)) threads.bumpParentUnread(thread.getId(), m.getCreatedAt()); else threads.bumpTeacherUnread(thread.getId(), m.getCreatedAt());
        var dto = dto(m);
        publish(ChatEvent.message(thread.getSchoolId(), thread.getId(), thread.getChildId(), thread.getTeacherId(), parentId, thread.getPeerUserId(),
                key(role, senderId), clientId, dto.getId(), json.encodeShared(dto, ChatMessage.Companion.serializer())));
        return dto;
    }

    private ChatReadReceipt read(ChatThreadEntity thread, String parentId, String role, String readerId) {
        Instant now = clock.instant();
        messages.markRead(thread.getId(), counterpart(thread, role), now);
        if (TEACHER.equals(role)) threads.clearTeacherUnread(thread.getId()); else threads.clearParentUnread(thread.getId());
        publish(ChatEvent.read(thread.getSchoolId(), thread.getId(), thread.getChildId(), thread.getTeacherId(), parentId, thread.getPeerUserId(),
                key(role, readerId), role, now.toEpochMilli()));
        return new ChatReadReceipt(thread.getId(), sender(role), now.toEpochMilli());
    }

    /** Whose messages a read receipt marks: the staff peer reads the other side's, and the other side reads hers. */
    private static String counterpart(ChatThreadEntity thread, String role) {
        if (!TEACHER.equals(role)) return TEACHER;
        return thread.getPeerUserId() == null ? PARENT : PEER;
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

    /**
     * A staff member the parent may write to about this child, and which kind she is: a teacher assigned to the
     * child's section, or — R4 — a coordinator of one of that section's subjects. 404 otherwise, because a parent is
     * not told which staff exist beyond her own child's.
     */
    private String requireStaffOf(ChildEntity child, String staffId) {
        if (scope.assignmentsOn(child.getClassId()).stream().anyMatch(a -> a.getTeacherId().equals(staffId))) return ROLE_TEACHER;
        var section = classes.findOneById(child.getClassId()).orElseThrow(() -> ApiException.notFound("teacher"));
        if (peers.coordinatorsOn(child.getSchoolId(), section).stream().anyMatch(c -> c.user().getId().equals(staffId))) return COORDINATOR;
        throw ApiException.notFound("teacher");
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

    /**
     * One list row. V20's four fields come off the thread, or take their C1 defaults when there is no thread yet —
     * a coordinator a parent has not written to is `question` / `open` like the teacher rows beside her.
     */
    private static ChatThread row(ChatThreadEntity t, String childId, String childName, String staffId, String staffName,
                                  String className, String subject, int unread, ChatMessage last, String staffRole) {
        return new ChatThread(t == null ? null : t.getId(), childId, childName, staffId, staffName, className, subject, unread, last,
                staffRole(t == null ? staffRole : t.getStaffRole()), topic(t == null ? QUESTION : t.getTopic()),
                status(t == null ? OPEN : t.getStatus()),
                t == null || t.getResolvedAt() == null ? null : t.getResolvedAt().toEpochMilli());
    }

    static ChatStaffRole staffRole(String role) {
        return switch (role == null ? ROLE_TEACHER : role) { case COORDINATOR -> ChatStaffRole.COORDINATOR; case MANAGERIAL -> ChatStaffRole.MANAGERIAL; default -> ChatStaffRole.TEACHER; };
    }
    static ChatTopic topic(String topic) { return COMPLAINT.equals(topic) ? ChatTopic.COMPLAINT : ChatTopic.QUESTION; }
    static ChatThreadStatus status(String status) { return RESOLVED.equals(status) ? ChatThreadStatus.RESOLVED : ChatThreadStatus.OPEN; }
    /** The wire word a contract enum serialises to, which is the word the column holds. */
    static String key(ChatTopic topic) { return topic == ChatTopic.COMPLAINT ? COMPLAINT : QUESTION; }

    static String name(UserEntity u) { return u == null ? "" : u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getEmail() : u.getDisplayName(); }

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

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
import java.util.Set;
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
import quest.api.dto.ChatPeerRole;
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
import quest.server.tenancy.ManagerScope;
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
    /** S1: `staff_role` on a thread the platform admin opened with a parent, a teacher or a coordinator. */
    public static final String ADMIN = "ADMIN";
    /** S1: what a parent's row calls the admin — her own name and address are not the parent's to read. */
    static final String ADMINISTRATION = "School administration";
    /** V20 `chat_threads.topic` and `.status`, two words each. */
    public static final String QUESTION = "question", COMPLAINT = "complaint", OPEN = "open", RESOLVED = "resolved";
    /** D26: every dashboard role shares one socket key, so the hub finds a person's sessions without knowing her role. */
    public static final String USER = "user";

    private final ChatThreadRepository threads; private final ChatMessageRepository messages; private final ChatThreads threadRows;
    private final ChatRateLimiter limiter; private final ChatBus bus; private final ChildService childService; private final ChildRepository children;
    private final ClassRepository classes; private final UserRepository users; private final TeacherScope scope; private final TenantContext tenant;
    private final CoordinatorScope coordinatorScope; private final ManagerScope managerScope; private final ChatPeers peers;
    private final FeatureFlags flags; private final Clock clock; private final Json json;
    private final quest.server.auth.ParentRepository parents;
    private final ChatPresence presence; private final quest.server.notifications.NotificationService bells;
    private final StaffDirectory directory;

    public ChatService(ChatThreadRepository threads, ChatMessageRepository messages, ChatThreads threadRows, ChatRateLimiter limiter, ChatBus bus,
                       ChildService childService, ChildRepository children, ClassRepository classes, UserRepository users, TeacherScope scope,
                       TenantContext tenant, CoordinatorScope coordinatorScope, ManagerScope managerScope,
                       ChatPeers peers, FeatureFlags flags, Clock clock, Json json, quest.server.auth.ParentRepository parents,
                       ChatPresence presence, quest.server.notifications.NotificationService bells, StaffDirectory directory) {
        this.threads = threads; this.messages = messages; this.threadRows = threadRows; this.limiter = limiter; this.bus = bus;
        this.childService = childService; this.children = children; this.classes = classes; this.users = users; this.scope = scope;
        this.tenant = tenant; this.coordinatorScope = coordinatorScope; this.managerScope = managerScope; this.peers = peers; this.flags = flags; this.clock = clock;
        this.json = json; this.parents = parents; this.presence = presence; this.bells = bells; this.directory = directory;
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
        var sectionRow = classes.findOneById(child.getClassId()).orElse(null);
        var section = sectionRow == null ? null : sectionRow.getName();
        // RM2: a coordinator row here carries her subjects too, as `GET /children/{id}/coordinators` already does —
        // the app labels the thread "Lina · maths" whether the parent reached it from the chooser or from this list.
        var coordinatorSubjects = new HashMap<String, String>();
        if (sectionRow != null && staffThreads.stream().anyMatch(t -> COORDINATOR.equals(t.getStaffRole())))
            for (var c : peers.coordinatorsOn(child.getSchoolId(), sectionRow)) coordinatorSubjects.put(c.user().getId(), c.subjects());
        var last = lastMessages(byStaff.values());
        var subjects = assignments.stream().collect(Collectors.groupingBy(TeachingAssignmentEntity::getTeacherId, LinkedHashMap::new,
                Collectors.mapping(TeachingAssignmentEntity::getSubject, Collectors.joining(", "))));
        String parentName = parentNames(List.of(child)).get(child.getId());
        var rows = new ArrayList<ChatThread>();
        for (String teacherId : teacherIds) {
            var t = byStaff.get(teacherId);
            rows.add(row(t, child.getId(), child.getName(), teacherId, name(staff.get(teacherId)), section,
                    subjects.get(teacherId), t == null ? 0 : t.getParentUnread(), t == null ? null : last.get(t.getId()), ROLE_TEACHER, parentName,
                    key(USER, teacherId), ChatPeerRole.TEACHER));
        }
        for (var t : staffThreads)
            rows.add(row(t, child.getId(), child.getName(), t.getTeacherId(),
                    ADMIN.equals(t.getStaffRole()) ? ADMINISTRATION : name(staff.get(t.getTeacherId())), section,
                    coordinatorSubjects.get(t.getTeacherId()), t.getParentUnread(), last.get(t.getId()), t.getStaffRole(), parentName,
                    key(USER, t.getTeacherId()), peerRole(t.getStaffRole())));
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
        String parentName = parentNames(List.of(child)).get(child.getId());
        var rows = new ArrayList<ChatThread>();
        for (var coordinator : peers.coordinatorsOn(child.getSchoolId(), section)) {
            var t = byStaff.get(coordinator.user().getId());
            rows.add(row(t, child.getId(), child.getName(), coordinator.user().getId(), name(coordinator.user()), section.getName(),
                    coordinator.subjects(), t == null ? 0 : t.getParentUnread(), t == null ? null : last.get(t.getId()), COORDINATOR, parentName,
                    key(USER, coordinator.user().getId()), ChatPeerRole.COORDINATOR));
        }
        rows.sort(order());
        return rows;
    }

    /**
     * `GET /children/{id}/managers` (RM2, DR5): the manager of the department her child's section is in, as thread rows
     * — whom she may write to about the school, the child or a coordinator. The same shape
     * {@link #parentCoordinators} answers, `id` null until she writes, and a `complaint` is allowed here too: a manager
     * is the person a complaint about a coordinator has to go to.
     */
    public List<ChatThread> parentManagers(Principals.Parent parent, String childId) {
        var child = placed(parent, childId);
        var section = classes.findOneById(child.getClassId()).orElse(null);
        if (section == null) return List.of();
        var byStaff = byId(threads.findByChildIdOrderByLastMessageAtDesc(childId), ChatThreadEntity::getTeacherId);
        var last = lastMessages(byStaff.values());
        String parentName = parentNames(List.of(child)).get(child.getId());
        var rows = new ArrayList<ChatThread>();
        for (var manager : peers.managersOn(child.getSchoolId(), section)) {
            var t = byStaff.get(manager.getId());
            rows.add(row(t, child.getId(), child.getName(), manager.getId(), name(manager), section.getName(),
                    null, t == null ? 0 : t.getParentUnread(), t == null ? null : last.get(t.getId()), MANAGERIAL, parentName,
                    key(USER, manager.getId()), ChatPeerRole.MANAGERIAL));
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
        String staffRole = requireStaffOf(child, staffId);
        var thread = threadRows.getOrCreate(child, staffId, staffRole, topic == null ? QUESTION : key(topic));
        // S1 (owner's list): a complaint may go to a teacher, a coordinator or a manager, and one thread per pair
        // means it often lands on a conversation that already exists — which then becomes an open complaint. Never
        // the other way: a `question` cannot take a complaint out of somebody's inbox.
        if (topic == ChatTopic.COMPLAINT && !(COMPLAINT.equals(thread.getTopic()) && OPEN.equals(thread.getStatus()))) {
            thread.setTopic(COMPLAINT); thread.setStatus(OPEN); thread.setResolvedAt(null); thread.setResolvedBy(null);
            thread = threads.save(thread);
        }
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
        var parentNames = parentNames(kids.values());
        return live.stream().map(t -> { var c = kids.get(t.getChildId()); var k = sections.get(c.getClassId());
            return row(t, c.getId(), c.getName(), teacher.userId(), teacherName, k == null ? null : k.getName(),
                    subjects.get(c.getClassId()), t.getTeacherUnread(), last.get(t.getId()), ROLE_TEACHER,
                    parentNames.get(c.getId()), c.getParentId() == null ? null : key(PARENT, c.getParentId()), ChatPeerRole.PARENT); }).toList();
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

    // ---------------------------------------------------------------- the teacher's staff threads (MG1, DR5)

    /**
     * <strong>Which end of a manager ↔ teacher thread is which.</strong> The subordinate holds `teacher_id` and the
     * supervisor `peer_user_id`, which is the rule R4 set for coordinator ↔ manager and RM2 for manager ↔ admin: a
     * coordinator is `teacher_id` to her manager, a manager is `teacher_id` to the admin, and so a teacher is
     * `teacher_id` to her manager. `staff_role` is `MANAGERIAL` on all three — it names the peer, the person on the
     * other end — and `teacher_unread` is therefore always the subordinate's badge, `parent_unread` the supervisor's.
     * One rule means {@code findForStaff} finds a person's threads whichever pair she is in, and whichever side opens
     * the conversation gets the one row.
     *
     * <p>`GET /teacher/chat/staff-threads`: keyed by thread, not by child, because a staff thread has no child on it.
     * `/teacher/chat/threads` stays the parent list and stays keyed by child, which is what the dashboard calls it by.
     */
    public List<ChatThread> teacherStaffThreads(Principals.User caller) {
        var me = TeacherScope.require(caller);
        requireOn(tenant.writeSchoolId());
        var allowed = supervisorsOf(me);
        return threads.findForStaff(me.userId()).stream().filter(t -> staffThreadOf(t, me.userId(), allowed))
                .map(t -> one(t, me.userId())).toList();
    }

    /**
     * `POST /teacher/chat/staff-threads`: her thread with **one manager of a department she teaches in, or (T1b) one
     * coordinator of a subject she teaches** — exactly one of the two ids, because the two are different people and a
     * request that named both would be asking for a thread that does not exist. Anyone outside her own reach is 404
     * through the directory that offered her the list: she is not told which staff exist beyond it, any more than a
     * parent is told which teachers exist outside her child's section.
     */
    @Transactional
    public ChatThread teacherStaffThread(Principals.User caller, String managerUserId, String coordinatorUserId) {
        var me = TeacherScope.require(caller);
        String schoolId = tenant.writeSchoolId();
        requireOn(schoolId);
        boolean manager = named(managerUserId), coordinator = named(coordinatorUserId);
        if (manager == coordinator) throw ApiException.badRequest("Send exactly one of managerUserId and coordinatorUserId.");
        if (coordinator) {
            if (!directory.coordinatorIdsForTeacher(me).contains(coordinatorUserId)) throw ApiException.notFound("coordinator");
            return one(threadRows.getOrCreateStaff(schoolId, me.userId(), coordinatorUserId, COORDINATOR), me.userId());
        }
        if (!managerIdsOf(me).contains(managerUserId)) throw ApiException.notFound("manager");
        return one(threadRows.getOrCreateStaff(schoolId, me.userId(), managerUserId), me.userId());
    }

    private static boolean named(String id) { return id != null && !id.isBlank(); }

    public List<ChatMessage> teacherStaffMessages(Principals.User caller, String threadId, String before, String since, Integer limit) {
        return page(ownTeacherThread(TeacherScope.require(caller), threadId).getId(), before, since, limit);
    }

    @Transactional
    public ChatMessage teacherStaffSend(Principals.User caller, String threadId, String body, String clientId) {
        var me = TeacherScope.require(caller); var t = ownTeacherThread(me, threadId);
        return send(t, parentOf(t), roleOn(t, me.userId()), me.userId(), body, clientId);
    }

    @Transactional
    public ChatReadReceipt teacherStaffRead(Principals.User caller, String threadId) {
        var me = TeacherScope.require(caller); var t = ownTeacherThread(me, threadId);
        return read(t, parentOf(t), roleOn(t, me.userId()), me.userId());
    }

    public void teacherStaffTyping(Principals.User caller, String threadId) {
        var me = TeacherScope.require(caller); var t = ownTeacherThread(me, threadId);
        String role = roleOn(t, me.userId());
        publish(ChatEvent.typing(t.getSchoolId(), t.getId(), t.getChildId(), t.getTeacherId(), parentOf(t), t.getPeerUserId(), key(role, me.userId()), role));
    }

    /**
     * MG1: `POST /teacher/messages/coordinator` keeps its notification and gains a conversation — her sentence is
     * appended to the staff thread with that manager, so the answer has somewhere to go. Answers the thread id, which
     * the notification's link names, or null when the school has chat off: the bell still rings, and no thread is
     * written for a Messages screen that school does not have.
     */
    @Transactional
    public String teacherStaffMessage(Principals.User caller, String managerUserId, String body) {
        String schoolId = tenant.writeSchoolId();
        if (!flags.isOn(schoolId, FlagKeys.CHAT)) return null;
        var t = threadRows.getOrCreateStaff(schoolId, caller.userId(), managerUserId);
        // No `chat.message` bell here: `TeacherMessageService` already writes the manager a `teacher.message` row for
        // this very sentence (MG1), and two bell entries for one note would be noise rather than news.
        send(t, null, TEACHER, caller.userId(), body, null, false);
        return t.getId();
    }

    /** MG1: the same list as a set of ids, for `TeacherMessageService` — it asks before it opens a thread. */
    public Set<String> managersOfTeacher(Principals.User caller) { return managerIdsOf(TeacherScope.require(caller)); }

    /** The managers she may hold a staff thread with, by id — one reach, asked once per request. */
    private Set<String> managerIdsOf(Principals.User teacher) {
        return peers.managersForTeacher(teacher).stream().map(m -> m.user().getId()).collect(Collectors.toSet());
    }

    /**
     * T1b: everyone above her she may hold a staff thread with — the managers of her departments and the coordinators
     * of her subjects, which is exactly what her two directory pages offer. One set, so the list, the reads and the
     * writes cannot disagree about who her peers are.
     */
    private Set<String> supervisorsOf(Principals.User teacher) {
        var out = new java.util.LinkedHashSet<>(managerIdsOf(teacher));
        out.addAll(directory.coordinatorIdsForTeacher(teacher));
        out.addAll(users.findActiveAdminIds());                                  // S1: a thread the admin opened with her
        return out;
    }

    /** Her side of a staff thread: no child, she is the `teacher_id`, and the peer is still a supervisor of hers. */
    private static boolean staffThreadOf(ChatThreadEntity t, String meId, Set<String> supervisors) {
        return t.getChildId() == null && meId.equals(t.getTeacherId()) && supervisors.contains(t.getPeerUserId());
    }

    /** One staff thread of hers: 404 for another school's (the filter), another person's, or another department's. */
    private ChatThreadEntity ownTeacherThread(Principals.User me, String threadId) {
        requireOn(tenant.writeSchoolId());
        var t = threads.findOneById(threadId).orElseThrow(() -> ApiException.notFound("thread"));
        if (!staffThreadOf(t, me.userId(), supervisorsOf(me))) throw ApiException.notFound("thread");
        return t;
    }

    // ---------------------------------------------------------------- support (Admin, read-only, `X-School-Id`)

    public List<ChatThread> supportThreads() {
        String schoolId = requireSchool(); requireOn(schoolId);
        var all = threads.findAllByOrderByLastMessageAtDesc();
        var kids = byId(children.findAllById(all.stream().map(ChatThreadEntity::getChildId).filter(Objects::nonNull).distinct().toList()), ChildEntity::getId);
        var teachers = byId(users.findAllById(all.stream().map(ChatThreadEntity::getTeacherId).distinct().toList()), UserEntity::getId);
        var sections = byId(classes.findAllById(kids.values().stream().map(ChildEntity::getClassId).filter(Objects::nonNull).distinct().toList()), ClassEntity::getId);
        var last = lastMessages(all);
        var parentNames = parentNames(kids.values());
        var admins = Set.copyOf(users.findActiveAdminIds());
        return all.stream().map(t -> { var c = kids.get(t.getChildId()); var k = c == null || c.getClassId() == null ? null : sections.get(c.getClassId());
            return row(t, t.getChildId() == null ? "" : t.getChildId(), c == null ? "" : c.getName(), t.getTeacherId(), name(teachers.get(t.getTeacherId())),
                    k == null ? null : k.getName(), null, t.getParentUnread() + t.getTeacherUnread(), last.get(t.getId()), t.getStaffRole(),
                    c == null ? null : parentNames.get(c.getId()), null, adminOn(t, admins), null); }).toList();
    }

    /**
     * S1 `GET /admin/chat/threads?mine=true`: the admin's <em>own</em> conversations in the school — the managers,
     * coordinators, teachers and parents she wrote to — with her own unread count on each, which the support list
     * above cannot give because it adds both sides together.
     */
    public List<ChatThread> adminThreads(Principals.User caller) {
        String schoolId = requireSchool(); requireOn(schoolId);
        return staffThreads(caller.userId(), null, null, null);
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
        return staffThreads(me.userId(), coordinatorScope.reach(me), topic, status);
    }

    /**
     * The list both supervising roles read, named by thread id: the parent threads whose child is inside {@code reach}
     * and every staff thread this person is on. One body for the two, because a manager's inbox is a coordinator's with
     * a wider reach — the only difference is which scope built it and which `require` proved she may hold one.
     */
    private List<ChatThread> staffThreads(String meId, CoordinatorScope.Reach reach, String topic, String status) {
        var mine = threads.findForStaff(meId).stream()
                .filter(t -> topic == null || topic.equals(t.getTopic()))
                .filter(t -> status == null || status.equals(t.getStatus())).toList();
        var kids = byId(children.findAllById(mine.stream().map(ChatThreadEntity::getChildId).filter(Objects::nonNull).distinct().toList()), ChildEntity::getId);
        // `reach` is null for the admin (S1): she answers every thread she is on, and her sections are read by id.
        var live = reach == null ? mine : mine.stream().filter(t -> mayAnswer(t, kids, reach)).toList();
        var last = lastMessages(live);
        var people = byId(users.findAllById(live.stream().map(t -> named(t, meId)).distinct().toList()), UserEntity::getId);
        var parentNames = parentNames(kids.values());
        var admins = Set.copyOf(users.findActiveAdminIds());
        Map<String, ClassEntity> sections = reach != null ? reach.byId()
                : byId(classes.findAllById(kids.values().stream().map(ChildEntity::getClassId).filter(Objects::nonNull).distinct().toList()), ClassEntity::getId);
        var rows = new ArrayList<ChatThread>(live.size());
        for (var t : live) {
            var child = t.getChildId() == null ? null : kids.get(t.getChildId());
            var section = child == null || child.getClassId() == null ? null : sections.get(child.getClassId());
            String person = named(t, meId);
            rows.add(row(t, child == null ? "" : child.getId(), child == null ? "" : child.getName(), person, name(people.get(person)),
                    section == null ? null : section.getName(), null, unreadFor(t, meId), last.get(t.getId()), t.getStaffRole(),
                    child == null ? null : parentNames.get(child.getId()), peerKey(child, person), adminOn(t, admins),
                    child != null ? ChatPeerRole.PARENT : peerRole(people.get(person), person, admins)));
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
        var me = CoordinatorScope.require(caller);
        return status(me, ownThread(me, threadId), wanted);
    }

    /** S1 `PATCH /management/chat/threads/{id}/status`: the same write on a thread of the manager's. */
    @Transactional
    public ChatThread managerStatus(Principals.User caller, String threadId, String wanted) {
        var me = ManagerScope.require(caller);
        return status(me, ownManagerThread(me, threadId), wanted);
    }

    private ChatThread status(Principals.User me, ChatThreadEntity t, String wanted) {
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
     *
     * <p>S1: or with one teacher of her subjects — exactly one of the two ids. It is the row T1b's
     * `POST /teacher/chat/staff-threads {coordinatorUserId}` creates from the other end (teacher on `teacher_id`, she
     * on `peer_user_id`, `staff_role` `COORDINATOR`), and a teacher outside her scope is 404.
     */
    @Transactional
    public ChatThread coordinatorStaffThread(Principals.User caller, String managerUserId, String teacherUserId) {
        var me = CoordinatorScope.require(caller);
        String schoolId = tenant.writeSchoolId();
        requireOn(schoolId);
        if (named(managerUserId) == named(teacherUserId)) throw ApiException.badRequest("Send exactly one of managerUserId and teacherUserId.");
        if (named(teacherUserId)) {
            if (!directory.coversTeacher(me, teacherUserId)) throw ApiException.notFound("teacher");
            return one(threadRows.getOrCreateStaff(schoolId, teacherUserId, me.userId(), COORDINATOR), me.userId());
        }
        var manager = peers.managersFor(me).stream().filter(u -> u.getId().equals(managerUserId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("manager"));
        return one(threadRows.getOrCreateStaff(schoolId, me.userId(), manager.getId()), me.userId());
    }

    // ---------------------------------------------------------------- the manager (dashboard, RM2, DR5)

    /**
     * Her conversations: the parents who wrote to her about a child of her department, the coordinators she manages,
     * and the admin she reports to. {@link #coordinatorThreads}' own list one scope wider — same body, same order,
     * `?status=` the same filter — because the difference between the two roles is which scope built the reach.
     */
    public List<ChatThread> managerThreads(Principals.User caller, String status) {
        var me = ManagerScope.require(caller);
        requireOn(tenant.writeSchoolId());
        return staffThreads(me.userId(), managerScope.reach(me), null, status);
    }

    /** S1 `GET /management/complaints`: the `complaint` threads parents opened with her, as the coordinator's inbox. */
    public List<ChatThread> managerComplaints(Principals.User caller, String status) {
        var me = ManagerScope.require(caller);
        requireOn(tenant.writeSchoolId());
        return staffThreads(me.userId(), managerScope.reach(me), COMPLAINT, status);
    }

    public List<ChatMessage> managerMessages(Principals.User caller, String threadId, String before, String since, Integer limit) {
        var t = ownManagerThread(ManagerScope.require(caller), threadId);
        return page(t.getId(), before, since, limit);
    }

    @Transactional
    public ChatMessage managerSend(Principals.User caller, String threadId, String body, String clientId) {
        var me = ManagerScope.require(caller); var t = ownManagerThread(me, threadId);
        return send(t, parentOf(t), roleOn(t, me.userId()), me.userId(), body, clientId);
    }

    @Transactional
    public ChatReadReceipt managerRead(Principals.User caller, String threadId) {
        var me = ManagerScope.require(caller); var t = ownManagerThread(me, threadId);
        return read(t, parentOf(t), roleOn(t, me.userId()), me.userId());
    }

    public void managerTyping(Principals.User caller, String threadId) {
        var me = ManagerScope.require(caller); var t = ownManagerThread(me, threadId);
        String role = roleOn(t, me.userId());
        publish(ChatEvent.typing(t.getSchoolId(), t.getId(), t.getChildId(), t.getTeacherId(), parentOf(t), t.getPeerUserId(), key(role, me.userId()), role));
    }

    /**
     * `POST /management/chat/threads`: her thread with one teacher of her department (MG1, owner's item 6), one
     * coordinator of it, or a platform admin (DR5: "the manager reports to and chats with the admin"). The teacher is
     * resolved through {@link ManagerScope#teachersOf} and the coordinator through
     * {@link ManagerScope#coordinatorsOf} — one of the other department's is 404 either way — and the admin
     * through {@code findById}, which Hibernate filters never touch, because the ADMIN row carries no school at all.
     *
     * <p>The subordinate holds the `teacher_id` side and the supervisor `peer_user_id`: the teacher and the
     * coordinator on their threads with her, and she on the admin's. See {@link #teacherStaffThreads} for the whole
     * rule and why it is one rule.
     *
     * <p>MH1 (owner's item 5) adds the fourth: `childId`, the parent thread <em>she</em> opens. Until now only the
     * parent could start one, so a manager reading the Children directory had nobody to press. It is the same row a
     * parent's first message creates — {@link ChatThreads#getOrCreate} de-duplicated on (child, staff) — with
     * `staff_role` `MANAGERIAL`, so it appears in the parent's app list beside her coordinator threads and in
     * `GET /management/chat/threads` beside everything else of hers. A child of the other department is the 403
     * {@link ManagerScope#requireChild} answers everywhere; a child with no registered parent is 404 `no_parent`,
     * because there is no account to deliver to and she is already entitled to know that child exists.
     */
    @Transactional
    public ChatThread managerStaffThread(Principals.User caller, String childId, String coordinatorUserId, String adminUserId, String teacherUserId) {
        var me = ManagerScope.require(caller);
        String schoolId = tenant.writeSchoolId();
        requireOn(schoolId);
        if (childId != null && !childId.isBlank()) {
            var child = managerScope.requireChild(me, childId);
            if (child.getParentId() == null)
                throw new ApiException(HttpStatus.NOT_FOUND, "no_parent", "No parent has registered for this child yet.");
            return one(threadRows.getOrCreate(child, me.userId(), MANAGERIAL, QUESTION), me.userId());
        }
        if (teacherUserId != null && !teacherUserId.isBlank()) {
            var teacher = managerScope.teachersOf(me).stream().filter(u -> u.getId().equals(teacherUserId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("teacher"));
            return one(threadRows.getOrCreateStaff(schoolId, teacher.getId(), me.userId()), me.userId());
        }
        if (coordinatorUserId != null && !coordinatorUserId.isBlank()) {
            var coordinator = managerScope.coordinatorsOf(me).keySet().stream().filter(u -> u.getId().equals(coordinatorUserId))
                    .findFirst().orElseThrow(() -> ApiException.notFound("coordinator"));
            return one(threadRows.getOrCreateStaff(schoolId, coordinator.getId(), me.userId()), me.userId());
        }
        if (adminUserId == null || adminUserId.isBlank()) throw ApiException.badRequest("Send childId, teacherUserId, coordinatorUserId or adminUserId.");
        var admin = admin(adminUserId);
        return one(threadRows.getOrCreateStaff(schoolId, me.userId(), admin.getId()), me.userId());
    }

    /**
     * `GET /management/admins`: whom `adminUserId` above may name — the active platform admins, in name order. Her own
     * departments are resolved first, so the route is a manager's and `ManagerScopeArchitectureTest` sees the check.
     */
    public List<UserEntity> admins(Principals.User caller) {
        managerScope.departments(ManagerScope.require(caller));
        return users.findActiveAdminIds().stream().map(id -> users.findById(id).orElse(null)).filter(Objects::nonNull).toList();
    }

    /** One thread with the manager on it; a parent thread also has to be about a child of her department (403 otherwise). */
    private ChatThreadEntity ownManagerThread(Principals.User me, String threadId) {
        requireOn(tenant.writeSchoolId());
        var t = threads.findOneById(threadId).orElseThrow(() -> ApiException.notFound("thread"));
        if (!me.userId().equals(t.getTeacherId()) && !me.userId().equals(t.getPeerUserId())) throw ApiException.notFound("thread");
        if (t.getChildId() != null) managerScope.requireChild(me, t.getChildId());
        return t;
    }

    // ---------------------------------------------------------------- the Admin's own threads with managers (RM2)

    /**
     * `POST /admin/chat/threads`: the admin's thread with **exactly one** of a manager, a coordinator or a teacher of
     * the school, or the registered parent of one of its children (S1, owner's list of 2026-10-01).
     *
     * <p><strong>The row shapes.</strong> A staff thread keeps the one rule: the subordinate on `teacher_id`, the
     * admin on `peer_user_id`. With a manager it is the row RM2 wrote (`staff_role` `MANAGERIAL`, the same one
     * `POST /management/chat/threads {adminUserId}` creates); with a coordinator or a teacher `staff_role` is `ADMIN`,
     * the peer's role, as T1b's `COORDINATOR` is. A parent thread is the manager's MH1 shape — the child, the admin
     * on `teacher_id`, `staff_role` `ADMIN` — so the app lists it beside the child's other staff threads.
     *
     * <p>The other side reads and answers through its own routes: `/management/chat/**`, `/coordinator/chat/**`,
     * `/teacher/chat/staff-threads/**` and the app's `/children/{id}/chat/**`.
     */
    @Transactional
    public ChatThread adminThread(Principals.User caller, String managerUserId, String coordinatorUserId, String teacherUserId, String childId) {
        String schoolId = requireSchool(); requireOn(schoolId);
        int sent = (named(managerUserId) ? 1 : 0) + (named(coordinatorUserId) ? 1 : 0) + (named(teacherUserId) ? 1 : 0) + (named(childId) ? 1 : 0);
        if (sent != 1) throw ApiException.badRequest("Send exactly one of managerUserId, coordinatorUserId, teacherUserId and childId.");
        if (named(childId)) {
            var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null && schoolId.equals(c.getSchoolId()))
                    .orElseThrow(() -> ApiException.notFound("child"));
            if (child.getParentId() == null)
                throw new ApiException(HttpStatus.NOT_FOUND, "no_parent", "No parent has registered for this child yet.");
            return one(threadRows.getOrCreate(child, caller.userId(), ADMIN, QUESTION), caller.userId());
        }
        if (named(managerUserId))
            return one(threadRows.getOrCreateStaff(schoolId, staff(managerUserId, MANAGERIAL, schoolId, "manager").getId(), caller.userId()), caller.userId());
        var person = named(coordinatorUserId) ? staff(coordinatorUserId, COORDINATOR, schoolId, "coordinator")
                : staff(teacherUserId, ROLE_TEACHER, schoolId, "teacher");
        return one(threadRows.getOrCreateStaff(schoolId, person.getId(), caller.userId(), ADMIN), caller.userId());
    }

    /** One member of the school's staff by id and role; 404 for anybody else, another school's included. */
    private UserEntity staff(String userId, String role, String schoolId, String what) {
        return users.findById(userId).filter(u -> role.equals(u.getRole()) && schoolId.equals(u.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound(what));
    }

    @Transactional
    public ChatMessage adminSend(Principals.User caller, String threadId, String body, String clientId) {
        var t = ownAdminThread(caller, threadId);
        return send(t, parentOf(t), roleOn(t, caller.userId()), caller.userId(), body, clientId);
    }

    @Transactional
    public ChatReadReceipt adminRead(Principals.User caller, String threadId) {
        var t = ownAdminThread(caller, threadId);
        return read(t, parentOf(t), roleOn(t, caller.userId()), caller.userId());
    }

    /**
     * A thread the admin is herself on, inside the school she named. Support still <em>reads</em> every thread of a
     * school ({@link #supportThreads}); writing is only ever into her own conversation, so a `POST` cannot put words
     * into a parent's or a teacher's thread.
     */
    private ChatThreadEntity ownAdminThread(Principals.User caller, String threadId) {
        String schoolId = requireSchool(); requireOn(schoolId);
        var t = threads.findOneById(threadId).orElseThrow(() -> ApiException.notFound("thread"));
        if (!caller.userId().equals(t.getTeacherId()) && !caller.userId().equals(t.getPeerUserId())) throw ApiException.notFound("thread");
        return t;
    }

    /** An active platform ADMIN by id. `findById` is the unfiltered lookup: the ADMIN row carries no `school_id`. */
    private UserEntity admin(String userId) {
        return users.findById(userId).filter(u -> "ADMIN".equals(u.getRole())).orElseThrow(() -> ApiException.notFound("admin"));
    }

    // ---------------------------------------------------------------- the socket's staff half

    /**
     * A COORDINATOR (R4), a MANAGERIAL or an ADMIN (RM2) names her thread by id rather than by child, because not all
     * of her threads are about one. Which scope proves the thread is hers is her role's, so {@link ChatSocketHandler}
     * holds no rule of its own; a role that reaches neither branch cannot send at all.
     */
    @Transactional
    public ChatMessage staffSend(Principals.User caller, String threadId, String body, String clientId) {
        if (CoordinatorScope.ROLE.equals(caller.role())) return coordinatorSend(caller, threadId, body, clientId);
        if (MANAGERIAL.equals(caller.role())) return managerSend(caller, threadId, body, clientId);
        return adminSend(caller, threadId, body, clientId);
    }

    @Transactional
    public ChatReadReceipt staffRead(Principals.User caller, String threadId) {
        if (CoordinatorScope.ROLE.equals(caller.role())) return coordinatorRead(caller, threadId);
        if (MANAGERIAL.equals(caller.role())) return managerRead(caller, threadId);
        return adminRead(caller, threadId);
    }

    public void staffTyping(Principals.User caller, String threadId) {
        if (CoordinatorScope.ROLE.equals(caller.role())) coordinatorTyping(caller, threadId);
        else if (MANAGERIAL.equals(caller.role())) managerTyping(caller, threadId);
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
        if (t.getChildId() != null) { coordinatorScope.requireChild(me, t.getChildId()); return t; }
        String other = named(t, me.userId());
        // Her staff threads are of two kinds since T1b: the one she opened with her manager, and the one a teacher of
        // her subjects opened with her. `findForStaff` already lists both, so refusing the second here would leave a
        // row in her inbox she could not open.
        boolean hers = peers.managersFor(me).stream().anyMatch(u -> u.getId().equals(other))
                || (me.userId().equals(t.getPeerUserId()) && directory.coversTeacher(me, other))
                || (ADMIN.equals(t.getStaffRole()) && other.equals(t.getPeerUserId()));   // S1: the admin wrote to her
        if (!hers) throw ApiException.notFound("thread");
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

    /** T1: whose presence a staff caller's row shows — the child's parent, or the colleague on a staff thread. */
    private static String peerKey(ChildEntity child, String person) {
        return child != null && child.getParentId() != null ? key(PARENT, child.getParentId()) : key(USER, person);
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
        var colleague = users.findById(person);
        Set<String> admins = t.getPeerUserId() == null ? Set.of() : Set.copyOf(users.findActiveAdminIds());
        return row(t, child == null ? "" : child.getId(), child == null ? "" : child.getName(), person,
                colleague.map(ChatService::name).orElse(""), section == null ? null : section.getName(),
                null, unreadFor(t, meId), lastMessages(List.of(t)).get(t.getId()), t.getStaffRole(),
                child == null ? null : parentNames(List.of(child)).get(child.getId()), peerKey(child, person),
                adminOn(t, admins), child != null ? ChatPeerRole.PARENT : peerRole(colleague.orElse(null), person, admins));
    }

    /** S1 `withAdmin`: the row says `ADMIN`, or — the manager's thread, which RM2 wrote as `MANAGERIAL` — its peer is one. */
    private static boolean adminOn(ChatThreadEntity t, Set<String> admins) {
        return ADMIN.equals(t.getStaffRole()) || (t.getPeerUserId() != null && admins.contains(t.getPeerUserId()));
    }

    // ---------------------------------------------------------------- the four things

    /**
     * One message into a thread that already exists — the callers create it, because only they know which shape it is.
     * `teacher_unread` is the staff peer's badge and `parent_unread` the counterpart's, whether that counterpart is
     * the child's parent or the manager on the other end of a staff thread.
     */
    private ChatMessage send(ChatThreadEntity thread, String parentId, String role, String senderId, String rawBody, String clientId) {
        return send(thread, parentId, role, senderId, rawBody, clientId, true);
    }

    private ChatMessage send(ChatThreadEntity thread, String parentId, String role, String senderId, String rawBody, String clientId, boolean bell) {
        String body = clean(rawBody);
        limiter.record(key(role, senderId));
        var m = new ChatMessageEntity();
        m.setId(UUID.randomUUID().toString()); m.setSchoolId(thread.getSchoolId()); m.setThreadId(thread.getId());
        m.setSenderRole(role); m.setSenderId(senderId); m.setBody(body); m.setCreatedAt(clock.instant());
        messages.save(m);
        if (TEACHER.equals(role)) threads.bumpParentUnread(thread.getId(), m.getCreatedAt()); else threads.bumpTeacherUnread(thread.getId(), m.getCreatedAt());
        var dto = dto(m);
        if (bell) bell(thread, role, senderId, body);
        publish(ChatEvent.message(thread.getSchoolId(), thread.getId(), thread.getChildId(), thread.getTeacherId(), parentId, thread.getPeerUserId(),
                key(role, senderId), clientId, dto.getId(), json.encodeShared(dto, ChatMessage.Companion.serializer())));
        return dto;
    }

    /**
     * T1 (the owner's bug: "no notification when a teacher messages a manager"). Until now a message was announced on
     * the socket and nowhere else, so a recipient whose dashboard was closed — or simply on another screen — had only
     * the thread's unread counter, and the bell said nothing at all. Every message now writes the recipient a
     * `chat.message` row as well, throttled to one unread row per thread, which the same socket delivers as a
     * `notification` frame.
     *
     * <p>The recipient is whoever is <em>not</em> the sender, when she is a dashboard user: the staff peer when a
     * parent wrote, the second staff member on a staff thread, and <strong>nobody</strong> when a staff member wrote
     * to a parent — parents have no bell, they have the app.
     */
    private void bell(ChatThreadEntity thread, String role, String senderId, String body) {
        String recipient = PARENT.equals(role) || PEER.equals(role) ? thread.getTeacherId() : thread.getPeerUserId();
        if (recipient == null || recipient.equals(senderId)) return;
        String from = PARENT.equals(role) ? parents.findById(senderId).map(ChatService::name).orElse(null)
                : users.findById(senderId).map(ChatService::name).orElse(null);
        bells.chatMessage(thread.getSchoolId(), recipient, thread.getId(), from, body);
    }

    private ChatReadReceipt read(ChatThreadEntity thread, String parentId, String role, String readerId) {
        Instant now = clock.instant();
        messages.markRead(thread.getId(), counterpart(thread, role), now);
        if (TEACHER.equals(role)) threads.clearTeacherUnread(thread.getId()); else threads.clearParentUnread(thread.getId());
        // T1: she has read the thread, so its bell entry is read too — a parent has none to clear.
        if (!PARENT.equals(role)) bells.markThreadRead(readerId, thread.getId());
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
        // RM2 (DR5): the manager of the department the section is in — "parents message the manager about the school, a
        // child or a coordinator". Anyone else is 404, because a parent is told of no staff beyond her own child's.
        if (peers.managersOn(child.getSchoolId(), section).stream().anyMatch(u -> u.getId().equals(staffId))) return MANAGERIAL;
        // S1: the admin, once she has written — a parent answers that thread and cannot start one.
        if (threads.findByChildIdAndTeacherId(child.getId(), staffId).filter(t -> ADMIN.equals(t.getStaffRole())).isPresent()) return ADMIN;
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
     * One list row. V20's four fields come off the thread, or take their C1 defaults when there is no thread yet — a
     * coordinator a parent has not written to is `question` / `open` like the teacher rows beside her. `parentName`
     * (RM1 addendum) lets the Complaints inbox name who wrote, and is null on a staff-to-staff thread, which has no
     * parent on it at all, and on a roster child nobody has claimed yet.
     *
     * <p>`peerKey` (T1) is the session key of the person on the *other* end — the parent on a parent thread, the
     * colleague on a staff one — and settles `peerOnline`; null on a row where presence means nothing (the Admin's
     * support list, which is about the thread rather than about a conversation of hers), and `peerOnline` is then
     * absent rather than false.
     */
    private ChatThread row(ChatThreadEntity t, String childId, String childName, String staffId, String staffName,
                           String className, String subject, int unread, ChatMessage last, String staffRole,
                           String parentName, String peerKey, ChatPeerRole peerRole) {
        return row(t, childId, childName, staffId, staffName, className, subject, unread, last, staffRole, parentName, peerKey,
                t != null && ADMIN.equals(t.getStaffRole()), peerRole);
    }

    private ChatThread row(ChatThreadEntity t, String childId, String childName, String staffId, String staffName,
                           String className, String subject, int unread, ChatMessage last, String staffRole,
                           String parentName, String peerKey, boolean withAdmin, ChatPeerRole peerRole) {
        return new ChatThread(t == null ? null : t.getId(), childId, childName, staffId, staffName, className, subject, unread, last,
                staffRole(t == null ? staffRole : t.getStaffRole()), topic(t == null ? QUESTION : t.getTopic()),
                status(t == null ? OPEN : t.getStatus()),
                t == null || t.getResolvedAt() == null ? null : t.getResolvedAt().toEpochMilli(),
                parentName == null || parentName.isBlank() ? null : parentName, peerKey == null ? null : presence.online(peerKey),
                withAdmin ? Boolean.TRUE : null, peerRole);
    }

    /** N1 `peerRole` from an account's role; null for a role this list does not name, or an account that is gone. */
    static ChatPeerRole peerRole(String role) {
        return switch (role == null ? "" : role) {
            case ROLE_TEACHER -> ChatPeerRole.TEACHER; case COORDINATOR -> ChatPeerRole.COORDINATOR;
            case MANAGERIAL -> ChatPeerRole.MANAGERIAL; case ADMIN -> ChatPeerRole.ADMIN; default -> null; };
    }
    /** The colleague on a staff thread. The platform admin has no school, so a school-scoped read of her account comes back empty: her id says it. */
    private static ChatPeerRole peerRole(UserEntity user, String userId, Set<String> admins) {
        return admins.contains(userId) ? ChatPeerRole.ADMIN : user == null ? null : peerRole(user.getRole());
    }

    /**
     * `[childId -> the name the inbox can call her parent by]` — one statement for a whole page of rows, never one per
     * thread. The name the Admin typed for her (V25) or, without one, her registered address, falling back to the address a teacher typed on the roster (`children.parent_email`) for a
     * child nobody has claimed yet, and absent when there is neither.
     */
    private java.util.Map<String, String> parentNames(java.util.Collection<ChildEntity> kids) {
        var out = new LinkedHashMap<String, String>();
        var ids = kids.stream().filter(Objects::nonNull).map(ChildEntity::getParentId).filter(Objects::nonNull).distinct().toList();
        var registered = new LinkedHashMap<String, String>();
        if (!ids.isEmpty()) parents.findAllById(ids).forEach(row -> registered.put(row.getId(), name(row)));
        for (var kid : kids) {
            if (kid == null) continue;
            String name = kid.getParentId() == null ? null : registered.get(kid.getParentId());
            if (name == null || name.isBlank()) name = kid.getParentEmail();
            if (name != null && !name.isBlank()) out.put(kid.getId(), name);
        }
        return out;
    }

    /** The wire word for a staff role; public because `AnnouncementService` labels a parent's card with it (RM2). */
    public static ChatStaffRole staffRole(String role) {
        return switch (role == null ? ROLE_TEACHER : role) { case COORDINATOR -> ChatStaffRole.COORDINATOR; case MANAGERIAL, ADMIN -> ChatStaffRole.MANAGERIAL; default -> ChatStaffRole.TEACHER; };
    }
    static ChatTopic topic(String topic) { return COMPLAINT.equals(topic) ? ChatTopic.COMPLAINT : ChatTopic.QUESTION; }
    static ChatThreadStatus status(String status) { return RESOLVED.equals(status) ? ChatThreadStatus.RESOLVED : ChatThreadStatus.OPEN; }
    /** The wire word a contract enum serialises to, which is the word the column holds. */
    static String key(ChatTopic topic) { return topic == ChatTopic.COMPLAINT ? COMPLAINT : QUESTION; }

    /** The name a chooser or a thread row shows: her display name, or her address until she has set one. */
    public static String name(UserEntity u) { return u == null ? "" : u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getEmail() : u.getDisplayName(); }

    /** The same rule for a parent (T1): the name the Admin typed, or her registered address until there is one. */
    static String name(quest.server.auth.Entities.ParentEntity p) {
        return p == null ? "" : p.getDisplayName() == null || p.getDisplayName().isBlank() ? p.getEmail() : p.getDisplayName();
    }

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

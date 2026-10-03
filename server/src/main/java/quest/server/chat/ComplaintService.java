package quest.server.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.ChatMessage;
import quest.api.dto.ChatPeerRole;
import quest.api.dto.ChatReadReceipt;
import quest.api.dto.ChatThreadStatus;
import quest.api.dto.Complaint;
import quest.api.dto.ComplaintActor;
import quest.api.dto.ComplaintArea;
import quest.api.dto.ComplaintDetail;
import quest.api.dto.ComplaintEvent;
import quest.api.dto.ComplaintList;
import quest.api.dto.ComplaintRecipient;
import quest.api.dto.CreateComplaintRequest;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.ParentRepository;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.chat.Entities.ChatThreadEntity;
import quest.server.chat.Entities.ComplaintEventEntity;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.notifications.NotificationService;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.ManagerScope;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * B6 (owner, 2026-10-03): complaints, apart from Messages. A complaint is a `chat_threads` row of its own —
 * `topic` `complaint`, `thread_key` its own id (V33) — opened by the parent with a title and a first message, never a
 * Messages thread relabelled. Its messages are ordinary `chat_messages` written through {@link ChatService#send}, so
 * the socket, the rate limit and the paging are the chat's; what is different is who reaches it, the bells it rings
 * and its status history ({@link ComplaintEventEntity}).
 *
 * <p><strong>Who reaches a complaint.</strong> The parent: the complaints about her own child ({@code ChildService.owned},
 * 404 otherwise). The recipient — the staff member it is addressed to, held on `teacher_id` as on every parent
 * thread — while the child is still hers to answer about: a teacher's section, a coordinator's reach, a manager's
 * department. Her supervisors read it and may resolve or reopen it but do not write in it: the coordinators whose
 * subject the addressed teacher teaches on the child's section ({@link CoordinatorScope.Reach#assignments}), and the
 * manager of the child's department for a complaint to one of its teachers or coordinators. The Admin reads every
 * complaint of the school she names, and writes nothing. Anyone else — another school, another parent, another
 * department — is 404, because the complaint is not hers to know about.
 *
 * <p><strong>Status.</strong> `open` → `resolved` by the recipient or a supervisor; `resolved` → `open` by the
 * recipient, a supervisor or the parent. Every real change is a {@link ComplaintEventEntity} (who, when), a socket
 * `status` frame to both parties, and a `complaint.status` row for whoever did not make it: the parent when staff
 * moved it, the recipient when the parent or a supervisor did. A message on a resolved complaint does not reopen it —
 * "thank you" is the usual last word, and reopening is a button of its own.
 */
@Service
public class ComplaintService {
    static final int TITLE_MAX = 120;
    static final String STAFF = "staff";

    private final ChatThreadRepository threads; private final ComplaintEventRepository events; private final ChatService chat;
    private final ChildRepository children; private final ClassRepository classes; private final UserRepository users;
    private final ParentRepository parents; private final TeacherScope scope; private final CoordinatorScope coordinatorScope;
    private final ManagerScope managerScope; private final ChatPeers peers; private final TenantContext tenant;
    private final NotificationService bells; private final Clock clock;

    public ComplaintService(ChatThreadRepository threads, ComplaintEventRepository events, ChatService chat, ChildRepository children,
                            ClassRepository classes, UserRepository users, ParentRepository parents, TeacherScope scope,
                            CoordinatorScope coordinatorScope, ManagerScope managerScope, ChatPeers peers, TenantContext tenant,
                            NotificationService bells, Clock clock) {
        this.threads = threads; this.events = events; this.chat = chat; this.children = children; this.classes = classes; this.users = users;
        this.parents = parents; this.scope = scope; this.coordinatorScope = coordinatorScope; this.managerScope = managerScope;
        this.peers = peers; this.tenant = tenant; this.bells = bells; this.clock = clock;
    }

    // ---------------------------------------------------------------- the parent (app)

    public ComplaintList parentList(Principals.Parent parent, String childId, String status) {
        String wanted = filter(status);
        var child = chat.placed(parent, childId);
        return list(threads.findComplaintsOf(child.getId()), parentViewer(parent, child), wanted);
    }

    /** Whom she may complain to about this child: the section's teachers, its subjects' coordinators, its department's manager. */
    public List<ComplaintRecipient> recipients(Principals.Parent parent, String childId) {
        var child = chat.placed(parent, childId);
        var section = classes.findOneById(child.getClassId()).orElse(null);
        var assignments = scope.assignmentsOn(child.getClassId());
        var subjects = assignments.stream().collect(Collectors.groupingBy(TeachingAssignmentEntity::getTeacherId, LinkedHashMap::new,
                Collectors.mapping(TeachingAssignmentEntity::getSubject, Collectors.joining(", "))));
        var teachers = ChatService.byId(users.findAllById(List.copyOf(subjects.keySet())), UserEntity::getId);
        var out = new ArrayList<ComplaintRecipient>();
        subjects.forEach((id, subject) -> { var u = teachers.get(id); if (u != null) out.add(new ComplaintRecipient(id, ChatService.name(u), ChatPeerRole.TEACHER, subject)); });
        if (section != null) {
            for (var c : peers.coordinatorsOn(child.getSchoolId(), section))
                out.add(new ComplaintRecipient(c.user().getId(), ChatService.name(c.user()), ChatPeerRole.COORDINATOR, c.subjects()));
            for (var m : peers.managersOn(child.getSchoolId(), section))
                out.add(new ComplaintRecipient(m.getId(), ChatService.name(m), ChatPeerRole.MANAGERIAL, null));
        }
        return List.copyOf(out);
    }

    /**
     * A new complaint: its own row, its first message, and the recipient's `complaint.new` bell. The recipient is
     * resolved exactly as a Messages send resolves one ({@link ChatService#requireStaffOf}) — a teacher of the section,
     * a coordinator of its subjects, its manager — and anyone else is 404; the Admin is not somebody a parent complains
     * to (she has no Complaints page), so her id is 404 here even where she has written to the parent.
     */
    @Transactional
    public ComplaintDetail create(Principals.Parent parent, String childId, CreateComplaintRequest req) {
        if (req.getStaffId() == null || req.getStaffId().isBlank()) throw ApiException.badRequest("staffId is required.");
        String title = title(req.getTitle());
        if (!req.getAttachmentIds().isEmpty()) throw ApiException.badRequest("Attachments are not accepted on this server yet.");
        var child = chat.placed(parent, childId);
        String role = chat.requireStaffOf(child, req.getStaffId());
        if (ChatService.ADMIN.equals(role)) throw ApiException.notFound("teacher");
        var t = new ChatThreadEntity();
        String id = UUID.randomUUID().toString();
        t.setId(id); t.setSchoolId(child.getSchoolId()); t.setChildId(child.getId()); t.setTeacherId(req.getStaffId()); t.setStaffRole(role);
        t.setTopic(ChatService.COMPLAINT); t.setStatus(ChatService.OPEN); t.setThreadKey(id); t.setTitle(title); t.setCreatedAt(clock.instant());
        threads.saveAndFlush(t);
        chat.send(t, child.getParentId(), ChatService.PARENT, parent.parentId(), req.getBody(), req.getClientId(), false);
        bells.complaintNew(t.getSchoolId(), t.getTeacherId(), id, parentName(parent.parentId()), title);
        return detail(threads.findOneById(id).orElseThrow(), parentViewer(parent, child), null, null, null);
    }

    public ComplaintDetail parentDetail(Principals.Parent parent, String childId, String complaintId, String before, String since, Integer limit) {
        var child = chat.placed(parent, childId);
        return detail(parentComplaint(child, complaintId), parentViewer(parent, child), before, since, limit);
    }

    @Transactional
    public ChatMessage parentSend(Principals.Parent parent, String childId, String complaintId, String body, String clientId) {
        var t = parentComplaint(chat.placed(parent, childId), complaintId);
        var m = chat.send(t, parent.parentId(), ChatService.PARENT, parent.parentId(), body, clientId, false);
        bells.complaintMessage(t.getSchoolId(), t.getTeacherId(), t.getId(), parentName(parent.parentId()), m.getBody());
        return m;
    }

    @Transactional
    public ChatReadReceipt parentRead(Principals.Parent parent, String childId, String complaintId) {
        var t = parentComplaint(chat.placed(parent, childId), complaintId);
        var receipt = chat.read(t, parent.parentId(), ChatService.PARENT, parent.parentId());
        bells.markComplaintRead(NotificationService.parentRecipient(parent.parentId()), t.getId());
        return receipt;
    }

    /** She reopens a resolved complaint; resolving is the school's word, not hers (403). */
    @Transactional
    public Complaint parentStatus(Principals.Parent parent, String childId, String complaintId, ChatThreadStatus wanted) {
        if (wanted != ChatThreadStatus.OPEN) throw ApiException.forbidden("Only the school marks a complaint resolved; you can reopen it.");
        var child = chat.placed(parent, childId);
        var t = parentComplaint(child, complaintId);
        var viewer = parentViewer(parent, child);
        if (move(t, ChatService.OPEN, ChatService.PARENT, parent.parentId(), parent.parentId()))
            bells.staffComplaintStatus(t.getSchoolId(), t.getTeacherId(), t.getId(), false, parentName(parent.parentId()));
        return rows(List.of(t), viewer).get(0);
    }

    /** The parent reads every complaint about her own child; the teachers' subjects come from that one section. */
    private Viewer parentViewer(Principals.Parent parent, ChildEntity child) {
        return new Viewer(null, parent.parentId(), false, (t, kid) -> true, null, scope.assignmentsOn(child.getClassId()));
    }

    /** One complaint about her own child ({@link ChatService#placed}: 404 for another parent's): 404 for another child's, or a Messages thread. */
    private ChatThreadEntity parentComplaint(ChildEntity child, String complaintId) {
        return threads.findOneById(complaintId).filter(ComplaintService::isComplaint).filter(t -> child.getId().equals(t.getChildId()))
                .orElseThrow(() -> ApiException.notFound("complaint"));
    }

    // ---------------------------------------------------------------- the staff (dashboard)

    /** `GET /{area}/complaints`: what this area shows her — see the class comment for who reaches what. */
    public ComplaintList staffList(ComplaintArea area, Principals.User caller, String status) {
        String wanted = filter(status);
        var viewer = viewer(area, caller);
        return list(viewer.visible(threads), viewer, wanted);
    }

    public ComplaintDetail staffDetail(ComplaintArea area, Principals.User caller, String complaintId, String before, String since, Integer limit) {
        var viewer = viewer(area, caller);
        return detail(require(viewer, complaintId), viewer, before, since, limit);
    }

    /** The recipient's reply. A supervisor or the Admin reads the conversation and does not write in it (403). */
    @Transactional
    public ChatMessage staffSend(ComplaintArea area, Principals.User caller, String complaintId, String body, String clientId) {
        var viewer = viewer(area, caller);
        var t = recipientOnly(viewer, require(viewer, complaintId));
        String parentId = parentOf(t);
        var m = chat.send(t, parentId, ChatService.TEACHER, caller.userId(), body, clientId, false);
        bells.parentComplaintMessage(t.getSchoolId(), parentId, t.getChildId(), t.getId(), staffName(caller.userId()), m.getBody());
        return m;
    }

    @Transactional
    public ChatReadReceipt staffRead(ComplaintArea area, Principals.User caller, String complaintId) {
        var viewer = viewer(area, caller);
        var t = recipientOnly(viewer, require(viewer, complaintId));
        var receipt = chat.read(t, parentOf(t), ChatService.TEACHER, caller.userId());
        bells.markComplaintRead(caller.userId(), t.getId());
        return receipt;
    }

    /** `resolved` or `open`, by the recipient or a supervisor in scope; never by the Admin's read-only support area. */
    @Transactional
    public Complaint staffStatus(ComplaintArea area, Principals.User caller, String complaintId, ChatThreadStatus wanted) {
        if (area == ComplaintArea.ADMIN) throw ApiException.forbidden("Support reads complaints; the school answers them.");
        if (wanted == null) throw ApiException.badRequest("status must be open or resolved.");
        var viewer = viewer(area, caller);
        var t = require(viewer, complaintId);
        boolean resolved = wanted == ChatThreadStatus.RESOLVED;
        if (move(t, resolved ? ChatService.RESOLVED : ChatService.OPEN, STAFF, caller.userId(), parentOf(t))) {
            String by = staffName(caller.userId());
            bells.complaintStatus(t.getSchoolId(), parentOf(t), t.getChildId(), t.getId(), resolved, by);
            if (!caller.userId().equals(t.getTeacherId())) bells.staffComplaintStatus(t.getSchoolId(), t.getTeacherId(), t.getId(), resolved, by);
        }
        return rows(List.of(t), viewer).get(0);
    }

    /** One complaint this staff member's area shows: 404 for another school's (the filter), a Messages thread, or one out of her scope. */
    private ChatThreadEntity require(Viewer viewer, String complaintId) {
        var t = threads.findOneById(complaintId).filter(ComplaintService::isComplaint).orElseThrow(() -> ApiException.notFound("complaint"));
        var kid = t.getChildId() == null ? null : children.findOneById(t.getChildId()).orElse(null);
        if (!viewer.sees(t, kid)) throw ApiException.notFound("complaint");
        return t;
    }

    private static ChatThreadEntity recipientOnly(Viewer viewer, ChatThreadEntity t) {
        if (!viewer.isRecipient(t)) throw ApiException.forbidden("Only the person this complaint is addressed to answers it.");
        return t;
    }

    /**
     * Which of the school's complaints this staff member's area shows, resolved through her role's scope — the
     * teacher's assignments, the coordinator's reach, the manager's department — so `TeacherScope`, `CoordinatorScope`
     * and `ManagerScope` each decide their own role's answer, as they do for every other route of theirs.
     */
    private Viewer viewer(ComplaintArea area, Principals.User caller) {
        var me = TeacherScope.require(caller);
        return switch (area) {
            case TEACHER -> {
                chat.requireOn(tenant.writeSchoolId());
                var hers = scope.assignmentsOf(me);
                var mine = hers.stream().map(TeachingAssignmentEntity::getClassId).collect(Collectors.toSet());
                yield new Viewer(me.userId(), null, false, (t, kid) -> me.userId().equals(t.getTeacherId()) && live(kid) && mine.contains(kid.getClassId()),
                        repo -> repo.findComplaintsTo(me.userId()), hers);
            }
            case COORDINATOR -> {
                chat.requireOn(tenant.writeSchoolId());
                var reach = coordinatorScope.reach(me);
                var supervised = reach.assignments().stream().map(a -> a.getClassId() + "\u0000" + a.getTeacherId()).collect(Collectors.toSet());
                yield new Viewer(me.userId(), null, false, (t, kid) -> live(kid) && reach.byId().containsKey(kid.getClassId())
                        && (me.userId().equals(t.getTeacherId())
                            || (ChatService.ROLE_TEACHER.equals(t.getStaffRole()) && supervised.contains(kid.getClassId() + "\u0000" + t.getTeacherId()))),
                        ChatThreadRepository::findComplaints, reach.assignments());
            }
            case MANAGEMENT -> {
                chat.requireOn(tenant.writeSchoolId());
                var reach = managerScope.reach(me);
                yield new Viewer(me.userId(), null, false, (t, kid) -> live(kid) && reach.byId().containsKey(kid.getClassId())
                        && (me.userId().equals(t.getTeacherId())
                            || ChatService.ROLE_TEACHER.equals(t.getStaffRole()) || ChatService.COORDINATOR.equals(t.getStaffRole())),
                        ChatThreadRepository::findComplaints, reach.assignments());
            }
            case ADMIN -> {
                chat.requireOn(chat.requireSchool());
                yield new Viewer(me.userId(), null, true, (t, kid) -> kid != null, ChatThreadRepository::findComplaints, scope.assignmentsOf(me));
            }
        };
    }

    // ---------------------------------------------------------------- status

    /**
     * Moves the complaint and records it — the event, the thread's latest resolution, the socket `status` frame to
     * both parties — and answers whether anything changed. Setting the status it already has changes nothing, so a
     * double tap rings no bell twice.
     */
    private boolean move(ChatThreadEntity t, String status, String actorRole, String actorId, String parentId) {
        if (status.equals(t.getStatus())) return false;
        Instant now = clock.instant();
        boolean resolved = ChatService.RESOLVED.equals(status);
        t.setStatus(status); t.setResolvedAt(resolved ? now : null); t.setResolvedBy(resolved ? actorId : null);
        threads.save(t);
        var e = new ComplaintEventEntity();
        e.setId(UUID.randomUUID().toString()); e.setSchoolId(t.getSchoolId()); e.setThreadId(t.getId()); e.setStatus(status);
        e.setActorRole(actorRole); e.setActorId(actorId); e.setChangedAt(now);
        events.save(e);
        chat.publish(ChatEvent.status(t.getSchoolId(), t.getId(), t.getChildId(), t.getTeacherId(), parentId, t.getPeerUserId(), status, now.toEpochMilli()));
        return true;
    }

    // ---------------------------------------------------------------- shapes

    private ComplaintList list(List<ChatThreadEntity> all, Viewer viewer, String wanted) {
        var kids = kidsOf(all);
        var visible = all.stream().filter(t -> viewer.sees(t, kids.get(t.getChildId()))).toList();
        int open = (int) visible.stream().filter(t -> ChatService.OPEN.equals(t.getStatus())).count();
        var kept = wanted == null ? visible : visible.stream().filter(t -> wanted.equals(t.getStatus())).toList();
        var rows = new ArrayList<>(rows(kept, viewer, kids));
        rows.sort(Comparator.<Complaint>comparingInt(c -> c.getUnread() > 0 ? 0 : 1)
                .thenComparing(c -> c.getLastMessage() == null ? c.getCreatedAt() : c.getLastMessage().getCreatedAt(), Comparator.reverseOrder()));
        return new ComplaintList(List.copyOf(rows), open, visible.size() - open);
    }

    private ComplaintDetail detail(ChatThreadEntity t, Viewer viewer, String before, String since, Integer limit) {
        var history = events.findByThreadIdOrderByChangedAtAscIdAsc(t.getId());
        var staffIds = history.stream().filter(e -> STAFF.equals(e.getActorRole())).map(ComplaintEventEntity::getActorId).distinct().toList();
        var staff = staff(staffIds);
        var parentIds = history.stream().filter(e -> ChatService.PARENT.equals(e.getActorRole())).map(ComplaintEventEntity::getActorId).distinct().toList();
        var parentNames = new HashMap<String, String>();
        if (!parentIds.isEmpty()) parents.findAllById(parentIds).forEach(p -> parentNames.put(p.getId(), ChatService.name(p)));
        var lines = history.stream().map(e -> {
            boolean byParent = ChatService.PARENT.equals(e.getActorRole());
            String name = byParent ? parentNames.getOrDefault(e.getActorId(), "") : ChatService.name(staff.get(e.getActorId()));
            return new ComplaintEvent(ChatService.status(e.getStatus()), byParent ? ComplaintActor.PARENT : ComplaintActor.STAFF, e.getActorId(), name,
                    e.getChangedAt().toEpochMilli());
        }).toList();
        return new ComplaintDetail(rows(List.of(t), viewer).get(0), chat.page(t.getId(), before, since, limit), lines);
    }

    private List<Complaint> rows(List<ChatThreadEntity> ts, Viewer viewer) { return rows(ts, viewer, kidsOf(ts)); }

    /** One statement per kind of thing a page of rows names — children, sections, staff, parents, last messages — never one per row. */
    private List<Complaint> rows(List<ChatThreadEntity> ts, Viewer viewer, Map<String, ChildEntity> kids) {
        if (ts.isEmpty()) return List.of();
        var sections = ChatService.byId(classes.findAllById(kids.values().stream().map(ChildEntity::getClassId).filter(Objects::nonNull).distinct().toList()),
                ClassEntity::getId);
        var people = new HashSet<String>();
        ts.forEach(t -> { people.add(t.getTeacherId()); if (t.getResolvedBy() != null) people.add(t.getResolvedBy()); });
        var accounts = staff(List.copyOf(people));
        var parentNames = chat.parentNames(kids.values());
        var last = chat.lastMessages(ts);
        var out = new ArrayList<Complaint>(ts.size());
        for (var t : ts) {
            var kid = kids.get(t.getChildId());
            var section = kid == null || kid.getClassId() == null ? null : sections.get(kid.getClassId());
            boolean resolved = ChatService.RESOLVED.equals(t.getStatus());
            int unread = viewer.parentId() != null ? t.getParentUnread() : viewer.isRecipient(t) ? t.getTeacherUnread() : 0;
            var recipient = accounts.get(t.getTeacherId());
            var resolver = t.getResolvedBy() == null ? null : accounts.get(t.getResolvedBy());
            out.add(new Complaint(t.getId(), t.getChildId(), kid == null ? "" : kid.getName(), t.getTitle() == null ? "" : t.getTitle(),
                    ChatService.status(t.getStatus()), t.getTeacherId(), ChatService.name(recipient),
                    recipientRole(t, recipient), t.getCreatedAt().toEpochMilli(), kid == null ? null : parentNames.get(kid.getId()),
                    section == null ? null : section.getName(), subject(t, kid, viewer.assignments()), last.get(t.getId()), unread,
                    resolved && t.getResolvedAt() != null ? t.getResolvedAt().toEpochMilli() : null,
                    resolved && resolver != null ? ChatService.name(resolver) : null,
                    viewer.parentId() != null || viewer.isRecipient(t)));
        }
        return out;
    }

    /**
     * The addressed teacher's subjects on the child's section, from the assignments the viewer's scope already read
     * (hers, her reach's, the child's section's) — no statement per row. Absent for a coordinator or a manager.
     */
    private static String subject(ChatThreadEntity t, ChildEntity kid, List<TeachingAssignmentEntity> assignments) {
        if (!ChatService.ROLE_TEACHER.equals(t.getStaffRole()) || kid == null || kid.getClassId() == null) return null;
        String subject = assignments.stream().filter(a -> kid.getClassId().equals(a.getClassId()) && t.getTeacherId().equals(a.getTeacherId()))
                .map(TeachingAssignmentEntity::getSubject).distinct().collect(Collectors.joining(", "));
        return subject.isEmpty() ? null : subject;
    }

    /** The recipient's role from her account, and from the row's `staff_role` when the account is gone. */
    private static ChatPeerRole recipientRole(ChatThreadEntity t, UserEntity recipient) {
        var role = recipient == null ? null : ChatService.peerRole(recipient.getRole());
        if (role != null) return role;
        return switch (t.getStaffRole() == null ? "" : t.getStaffRole()) {
            case ChatService.COORDINATOR -> ChatPeerRole.COORDINATOR; case ChatService.MANAGERIAL -> ChatPeerRole.MANAGERIAL; default -> ChatPeerRole.TEACHER; };
    }

    private Map<String, ChildEntity> kidsOf(List<ChatThreadEntity> ts) {
        return ChatService.byId(children.findAllById(ts.stream().map(ChatThreadEntity::getChildId).filter(Objects::nonNull).distinct().toList()), ChildEntity::getId);
    }

    /** `[userId -> account]`; an id the school filter hides (the platform admin's) is read with `findById`, which no filter touches. */
    private Map<String, UserEntity> staff(List<String> ids) {
        var out = new HashMap<String, UserEntity>();
        if (ids.isEmpty()) return out;
        users.findAllById(ids).forEach(u -> out.put(u.getId(), u));
        for (String id : ids) if (!out.containsKey(id)) users.findById(id).ifPresent(u -> out.put(id, u));
        return out;
    }

    private String staffName(String userId) { return users.findById(userId).map(ChatService::name).orElse(null); }
    private String parentName(String parentId) { return parents.findById(parentId).map(ChatService::name).orElse(null); }
    private String parentOf(ChatThreadEntity t) { return children.findOneById(t.getChildId()).map(ChildEntity::getParentId).orElse(null); }

    private static boolean isComplaint(ChatThreadEntity t) { return ChatService.COMPLAINT.equals(t.getTopic()); }
    private static boolean live(ChildEntity kid) { return kid != null && kid.getDeletedAt() == null && kid.getClassId() != null; }

    /** `?status=`: `open`, `resolved`, or `all` / absent (null: no filter). */
    static String filter(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "", "all" -> null;
            case ChatService.OPEN, ChatService.RESOLVED -> s;
            default -> throw ApiException.badRequest("status must be open, resolved or all.");
        };
    }

    /** A subject line: trimmed plain text, 1–120 characters, control characters removed. */
    static String title(String raw) {
        String t = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").strip();
        if (t.isEmpty()) throw ApiException.badRequest("Give the complaint a title.");
        if (t.length() > TITLE_MAX) throw ApiException.badRequest("Keep the title under " + TITLE_MAX + " characters.");
        return t;
    }

    /**
     * Who is reading: a parent ({@code parentId}), or a staff member ({@code userId}) with the predicate her area's
     * scope built, the query that lists the candidates and the assignments that scope already read. {@code readOnly}
     * is the Admin's support area.
     */
    private record Viewer(String userId, String parentId, boolean readOnly, Sees sees, Candidates candidates,
                          List<TeachingAssignmentEntity> assignments) {
        interface Sees { boolean test(ChatThreadEntity t, ChildEntity kid); }
        interface Candidates { List<ChatThreadEntity> of(ChatThreadRepository repo); }

        boolean sees(ChatThreadEntity t, ChildEntity kid) { return sees.test(t, kid); }
        boolean isRecipient(ChatThreadEntity t) { return userId != null && !readOnly && userId.equals(t.getTeacherId()); }
        List<ChatThreadEntity> visible(ChatThreadRepository repo) { return candidates.of(repo); }
    }
}

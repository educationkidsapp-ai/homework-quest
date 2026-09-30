package quest.server.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * R4 (DR3, DR5): <strong>who a chat thread's other end may be</strong> when it is not a teacher — the two lookups
 * {@link ChatService} needs and neither {@link CoordinatorScope} nor {@link quest.server.tenancy.TeacherScope} has,
 * because both are asked from the other direction: a parent wants the coordinators of her child's section, and a
 * coordinator wants the managers of her department.
 *
 * <p><strong>The rule is DR1's, read backwards.</strong> A coordinator's `staff_scopes` row covers a subject in a
 * track (`curriculum` null meaning both), so she belongs to a section when somebody teaches that subject in it and
 * the tracks match — the very predicate {@link CoordinatorScope#requireSection} applies from her side, so a
 * coordinator a parent is offered here is a coordinator that route would let read the child.
 *
 * <p><strong>Tenancy.</strong> Both queries name the school. The parent's side has to: a parent carries no tenant
 * scope at all and the school is her child's own, exactly as {@code AnnouncementService.forChild} takes it. The
 * coordinator's side names {@link TenantContext#writeSchoolId()}, her own school, never a UI parameter.
 */
@Component
public class ChatPeers {
    /** A coordinator a parent may write to, with the subjects of that section she actually coordinates. */
    public record Coordinator(UserEntity user, String subjects) {}

    private final StaffScopeRepository scopes; private final UserRepository users;
    private final TeacherScope teachers; private final CoordinatorScope coordinators; private final TenantContext tenant;

    public ChatPeers(StaffScopeRepository scopes, UserRepository users, TeacherScope teachers,
                     CoordinatorScope coordinators, TenantContext tenant) {
        this.scopes = scopes; this.users = users; this.teachers = teachers; this.coordinators = coordinators; this.tenant = tenant;
    }

    /**
     * The coordinators whose scope covers a subject taught in this section, in name order. Three statements: the
     * section's assignments, the school's coordinators, their scope rows. A section nobody teaches in has none.
     *
     * <p>The assignments come through {@link TeacherScope#assignmentsOn}, not from the repository:
     * `TeacherScopeArchitectureTest` keeps `teaching_assignments` behind that one class, and this is the same read
     * `ChatService.parentThreads` already makes to find the teachers of the child's section.
     */
    public List<Coordinator> coordinatorsOn(String schoolId, ClassEntity section) {
        var subjects = teachers.assignmentsOn(section.getId()).stream()
                .map(TeachingAssignmentEntity::getSubject).map(ChatPeers::normalise).collect(Collectors.toCollection(LinkedHashSet::new));
        if (subjects.isEmpty()) return List.of();
        var staff = users.findBySchoolIdAndRole(schoolId, CoordinatorScope.ROLE);
        if (staff.isEmpty()) return List.of();
        var rows = rowsOf(schoolId, staff);
        String curriculum = normalise(section.getCurriculum());
        var out = new ArrayList<Coordinator>();
        for (var person : staff) {
            var hers = new LinkedHashSet<String>();
            for (var row : rows.getOrDefault(person.getId(), List.of()))
                if (row.getSubject() != null && subjects.contains(normalise(row.getSubject()))
                        && (blank(row.getCurriculum()) || curriculum.equals(normalise(row.getCurriculum()))))
                    hers.add(normalise(row.getSubject()));
            if (!hers.isEmpty()) out.add(new Coordinator(person, String.join(", ", hers)));
        }
        out.sort(java.util.Comparator.comparing(c -> ChatService.name(c.user())));
        return List.copyOf(out);
    }

    /**
     * The managers whose department intersects the caller's scope (DR5: a MANAGERIAL row is `subject` null and
     * `curriculum` set). A coordinator of both tracks reaches both managers; a coordinator of one reaches one. An
     * ADMIN reading the coordinator's area holds no scope row and so is offered nobody — a staff thread is the
     * coordinator's own conversation, not a route the platform Admin writes on.
     */
    public List<UserEntity> managersFor(Principals.User caller) {
        return managerOptionsFor(caller).stream().map(Manager::user).toList();
    }

    /**
     * RM2 (DR5): the managers of the department a section belongs to — `GET /children/{id}/managers`, the parent's
     * chooser, and the check behind her first message. The mirror of {@link #coordinatorsOn}: a MANAGERIAL scope row is
     * `subject` NULL and `curriculum` set, so a manager belongs to a section when that row names the section's track.
     * The school is the child's own, as it is there, because a parent carries no tenant scope.
     */
    public List<UserEntity> managersOn(String schoolId, ClassEntity section) {
        var staff = users.findBySchoolIdAndRole(schoolId, ChatService.MANAGERIAL);
        if (staff.isEmpty()) return List.of();
        var rows = rowsOf(schoolId, staff);
        String curriculum = normalise(section.getCurriculum());
        var out = new ArrayList<UserEntity>();
        for (var person : staff)
            for (var row : rows.getOrDefault(person.getId(), List.of()))
                if (row.getSubject() == null && curriculum.equals(normalise(row.getCurriculum()))) { out.add(person); break; }
        out.sort(java.util.Comparator.comparing(ChatService::name));
        return List.copyOf(out);
    }

    /** A manager she may write to, with the department that made her reachable — `GET /coordinator/managers`. */
    public record Manager(UserEntity user, String curriculum) {}

    /**
     * The same people the chooser shows (RM1 addendum), each with her department, so the screen can say
     * "Nour · British" without a second request. One pass over the same two statements {@link #managersFor} uses; a
     * manager of two departments is named by the first of them her rows carry, in `curriculum` order.
     */
    public List<Manager> managerOptionsFor(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var mine = coordinators.scopesOf(caller);
        if (mine.isEmpty()) return List.of();
        boolean bothTracks = mine.stream().anyMatch(s -> s.curriculum() == null);
        Set<String> tracks = mine.stream().map(CoordinatorScope.Scope::curriculum).filter(Objects::nonNull).collect(Collectors.toSet());
        var staff = users.findBySchoolIdAndRole(schoolId, "MANAGERIAL");
        if (staff.isEmpty()) return List.of();
        var rows = rowsOf(schoolId, staff);
        var out = new ArrayList<Manager>();
        for (var person : staff) {
            String department = null;
            for (var row : rows.getOrDefault(person.getId(), List.of()))
                if (row.getSubject() == null && !blank(row.getCurriculum())
                        && (bothTracks || tracks.contains(normalise(row.getCurriculum())))) {
                    department = normalise(row.getCurriculum());
                    break;
                }
            if (department != null) out.add(new Manager(person, department));
        }
        out.sort(java.util.Comparator.comparing(m -> ChatService.name(m.user())));
        return List.copyOf(out);
    }

    /**
     * MG1 (DR5, owner's item 6): the managers of the departments this <em>teacher</em> teaches in — `GET
     * /teacher/managers`, and the check behind the staff thread she opens. {@link #managerOptionsFor}'s shape one
     * role over: her tracks come from {@link TeacherScope#classesOf}, her own assignments, so a manager of the other
     * department is not on the list and is 404 to her, exactly as a coordinator's is.
     *
     * <p>Three statements whatever her timetable holds: her sections, the school's managers, their scope rows. A
     * teacher with no section yet reaches nobody — there is no department she belongs to to reach one through.
     */
    public List<Manager> managersForTeacher(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var tracks = teachers.classesOf(caller).stream().map(k -> normalise(k.getCurriculum())).collect(Collectors.toSet());
        if (tracks.isEmpty()) return List.of();
        var staff = users.findBySchoolIdAndRole(schoolId, ChatService.MANAGERIAL);
        if (staff.isEmpty()) return List.of();
        var rows = rowsOf(schoolId, staff);
        var out = new ArrayList<Manager>();
        for (var person : staff)
            for (var row : rows.getOrDefault(person.getId(), List.of()))
                if (row.getSubject() == null && !blank(row.getCurriculum()) && tracks.contains(normalise(row.getCurriculum()))) {
                    out.add(new Manager(person, normalise(row.getCurriculum())));
                    break;
                }
        out.sort(java.util.Comparator.comparing(m -> ChatService.name(m.user())));
        return List.copyOf(out);
    }

    /** T1: the same two-statement read of `staff_scopes` for {@link StaffDirectory}, so the reach has one owner. */
    java.util.Map<String, List<StaffScopeEntity>> scopeRowsOf(String schoolId, List<UserEntity> staff) { return rowsOf(schoolId, staff); }

    private java.util.Map<String, List<StaffScopeEntity>> rowsOf(String schoolId, List<UserEntity> staff) {
        var out = new LinkedHashMap<String, List<StaffScopeEntity>>();
        for (var row : scopes.findBySchoolIdAndUserIdInOrderBySubjectAscCurriculumAsc(schoolId, staff.stream().map(UserEntity::getId).toList()))
            out.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(row);
        return out;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalise(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }
}

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
        String schoolId = tenant.writeSchoolId();
        var mine = coordinators.scopesOf(caller);
        if (mine.isEmpty()) return List.of();
        boolean bothTracks = mine.stream().anyMatch(s -> s.curriculum() == null);
        Set<String> tracks = mine.stream().map(CoordinatorScope.Scope::curriculum).filter(Objects::nonNull).collect(Collectors.toSet());
        var staff = users.findBySchoolIdAndRole(schoolId, "MANAGERIAL");
        if (staff.isEmpty()) return List.of();
        var rows = rowsOf(schoolId, staff);
        var out = new ArrayList<UserEntity>();
        for (var person : staff)
            if (rows.getOrDefault(person.getId(), List.of()).stream().anyMatch(row -> row.getSubject() == null
                    && !blank(row.getCurriculum()) && (bothTracks || tracks.contains(normalise(row.getCurriculum())))))
                out.add(person);
        out.sort(java.util.Comparator.comparing(ChatService::name));
        return List.copyOf(out);
    }

    private java.util.Map<String, List<StaffScopeEntity>> rowsOf(String schoolId, List<UserEntity> staff) {
        var out = new LinkedHashMap<String, List<StaffScopeEntity>>();
        for (var row : scopes.findBySchoolIdAndUserIdInOrderBySubjectAscCurriculumAsc(schoolId, staff.stream().map(UserEntity::getId).toList()))
            out.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(row);
        return out;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalise(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }
}

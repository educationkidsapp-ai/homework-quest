package quest.server.tenancy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.floors.Entities.FloorEntity;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;

/**
 * Feature 20: <strong>what a FLOOR_SUPERVISOR reaches</strong> — every class on the floors she holds, and nothing
 * else. {@link CoordinatorScope}'s sibling, and the one place the rule lives.
 *
 * <p><strong>The rule.</strong> A floor is a named group of sections ({@code floor_classes}); her `floor_supervisors`
 * rows say which floors are hers. A section is hers when it is on one of them — <em>whatever</em> is taught in it,
 * which is the whole difference between her and a coordinator: she looks across the classes of a floor rather than
 * down one subject. A teacher is hers when she holds an assignment in one of those sections; a child is hers when she
 * sits in one.
 *
 * <p><strong>The school is never checked here and never needs to be.</strong> Every query below is a repository query,
 * so the `school` Hibernate filter has already reduced `floors`, `floor_classes`, `floor_supervisors`, `classes`,
 * `teaching_assignments` and `children` to the caller's school: another school's row is `Optional.empty()` and answers
 * 404. Nothing here reads a school or a floor from the request — the scope comes from the caller's own user id.
 *
 * <p><strong>Who else passes.</strong> Only a FLOOR_SUPERVISOR is narrowed. The platform ADMIN scoped to the school
 * reaches all of it (D6), which is what makes `/floor/**` reviewable from the Admin dashboard; every other role is
 * refused by `permissions.json` before a handler runs, because the `floor.*` keys are granted to ADMIN and
 * FLOOR_SUPERVISOR only.
 *
 * <p><strong>{@link Reach} is {@link CoordinatorScope.Reach}</strong> on purpose: `ChatService.staffThreads(…)` and
 * `CoordinatorService.teachers(…)` are built on that shape, and a supervising role's inbox and Teachers list are the
 * same lists with a different reach behind them. Her `scopes()` is empty — she coordinates no subject.
 *
 * <p><strong>The architecture rule.</strong> `FloorScopeArchitectureTest` fails the build when a `@RestController`
 * serving `/floor/**`, or one of its handlers, cannot reach one of the checks below — so a new floor route cannot be
 * added without passing through the scope.
 */
@Component
public class FloorScope {
    /** The sixth role (feature 20). */
    public static final String ROLE = "FLOOR_SUPERVISOR";

    private final quest.server.floors.FloorRepository floors;
    private final quest.server.floors.FloorClassRepository floorClasses;
    private final quest.server.floors.FloorSupervisorRepository supervisors;
    private final ClassRepository classes; private final TeachingAssignmentRepository assignments;
    private final quest.server.children.ChildRepository children; private final TenantContext tenant;

    public FloorScope(quest.server.floors.FloorRepository floors, quest.server.floors.FloorClassRepository floorClasses,
                      quest.server.floors.FloorSupervisorRepository supervisors, ClassRepository classes,
                      TeachingAssignmentRepository assignments, quest.server.children.ChildRepository children,
                      TenantContext tenant) {
        this.floors = floors; this.floorClasses = floorClasses; this.supervisors = supervisors; this.classes = classes;
        this.assignments = assignments; this.children = children; this.tenant = tenant;
    }

    /** What a coordinator's reach is to her: the sections she supervises and the assignments inside them. */
    public record Reach(List<FloorEntity> floors, CoordinatorScope.Reach sections) {}

    /** True for a FLOOR_SUPERVISOR principal — the only role this class narrows. */
    public boolean isSupervisor(Principals.User caller) { return caller != null && ROLE.equals(caller.role()); }

    /** The caller, or 401 — every `/floor/**` controller starts with it. */
    public static Principals.User require(Principals.User caller) { return TeacherScope.require(caller); }

    /** Her floors, in name order; the whole school's for an ADMIN reading the area. */
    public List<FloorEntity> floorsOf(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        if (!isSupervisor(caller)) return floors.findBySchoolIdOrderByNameAsc(schoolId);
        var mine = supervisors.findBySchoolIdAndUserIdOrderByFloorIdAsc(schoolId, caller.userId()).stream()
                .map(quest.server.floors.Entities.FloorSupervisorEntity::getFloorId).distinct().toList();
        return mine.isEmpty() ? List.of() : floors.findBySchoolIdAndIdInOrderByNameAsc(schoolId, mine);
    }

    /**
     * Her floors and, inside them, her sections and their assignments — read in four statements whatever the size of
     * the school: her `floor_supervisors` rows, those floors, their `floor_classes` rows and the sections' assignments.
     * For an ADMIN this is every floor and the whole school, the answer {@link CoordinatorScope#reach} gives her.
     */
    public Reach reach(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var mine = floorsOf(caller);
        var sections = classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId);
        var byId = new LinkedHashMap<String, ClassEntity>();
        for (var section : sections) byId.put(section.getId(), section);
        if (!isSupervisor(caller)) {
            var all = assignments.findBySchoolIdOrderByClassIdAscSubjectAsc(schoolId).stream()
                    .filter(a -> byId.containsKey(a.getClassId())).toList();
            return new Reach(mine, new CoordinatorScope.Reach(List.of(), sections, all, Map.copyOf(byId)));
        }
        var onMyFloors = sectionIdsOn(schoolId, mine);
        var inScope = sections.stream().filter(k -> onMyFloors.contains(k.getId())).toList();
        var scopedById = new LinkedHashMap<String, ClassEntity>();
        for (var section : inScope) scopedById.put(section.getId(), section);
        var kept = inScope.isEmpty() ? List.<TeachingAssignmentEntity>of()
                : assignments.findByClassIdInOrderByClassIdAscSubjectAsc(List.copyOf(scopedById.keySet()));
        return new Reach(mine, new CoordinatorScope.Reach(List.of(), inScope, kept, Map.copyOf(scopedById)));
    }

    /** `[sectionId -> floorId]` for the floors given — which floor each class of hers is on, for a row's label. */
    public Map<String, String> floorOfSection(List<FloorEntity> mine) {
        var out = new LinkedHashMap<String, String>();
        if (mine.isEmpty()) return out;
        var ids = mine.stream().map(FloorEntity::getId).toList();
        for (var row : floorClasses.findBySchoolIdAndFloorIdInOrderBySectionIdAsc(tenant.writeSchoolId(), ids))
            out.put(row.getSectionId(), row.getFloorId());
        return out;
    }

    /**
     * A section she may read: 404 when it is another school's or a pre-V7 leftover, 403 when it is on no floor of
     * hers. The lookup comes first, so the refusal never doubles as confirmation that an id is real.
     */
    public ClassEntity requireSection(Principals.User caller, String classId) {
        var section = classes.findOneById(classId).filter(ClassEntity::isSection).orElseThrow(() -> ApiException.notFound("class"));
        if (!isSupervisor(caller)) return section;
        if (!sectionIdsOn(tenant.writeSchoolId(), floorsOf(caller)).contains(section.getId()))
            throw ApiException.forbidden(section.getName() + " is not on a floor you supervise.");
        return section;
    }

    /** A child she may read: one placed in a section on a floor of hers. A child on no roster is on no floor. */
    public quest.server.children.Entities.ChildEntity requireChild(Principals.User caller, String childId) {
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("child"));
        if (!isSupervisor(caller)) return child;
        if (child.getClassId() == null) throw ApiException.forbidden("That child is not in one of your classes.");
        requireSection(caller, child.getClassId());
        return child;
    }

    /** A teacher she may read or write to: one holding an assignment in a section of hers; 404 for anyone else. */
    public String requireTeacher(Principals.User caller, String teacherId) {
        if (teacherId == null || teacherId.isBlank()) throw ApiException.notFound("teacher");
        if (!isSupervisor(caller)) return teacherId;
        if (!teacherIdsOf(caller).contains(teacherId)) throw ApiException.notFound("teacher");
        return teacherId;
    }

    /** The teachers on her floors, by id — her Teachers list, her chat chooser, and feature 17's board scope. */
    public Set<String> teacherIdsOf(Principals.User caller) {
        var out = new LinkedHashSet<String>();
        for (var a : reach(caller).sections().assignments()) out.add(a.getTeacherId());
        return out;
    }

    /**
     * Feature 14/16, from the scheduler's thread: the academies of the floors one supervisor holds, so a lateness
     * alert about her reaches the director of the right academy. The school is a parameter rather than
     * {@link TenantContext}'s, because the job runs outside any tenant scope.
     */
    public Set<String> academiesOf(String schoolId, String userId) {
        var mine = supervisors.findBySchoolIdAndUserIdOrderByFloorIdAsc(schoolId, userId).stream()
                .map(quest.server.floors.Entities.FloorSupervisorEntity::getFloorId).distinct().toList();
        if (mine.isEmpty()) return Set.of();
        var out = new LinkedHashSet<String>();
        for (var floor : floors.findBySchoolIdAndIdInOrderByNameAsc(schoolId, mine))
            if (floor.getAcademy() != null && !floor.getAcademy().isBlank())
                out.add(floor.getAcademy().trim().toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /**
     * Feature 20, from the other end: the floor supervisors whose floors hold a section this teacher teaches in. It
     * is what puts her on the teacher's own staff-thread list, so a thread she opened is one the teacher can read and
     * answer — the rule {@code ChatService.supervisorsOf} applies to a manager and a coordinator.
     */
    public Set<String> supervisorsOfTeacher(String schoolId, String teacherId) {
        var sections = assignments.findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(schoolId, teacherId).stream()
                .map(TeachingAssignmentEntity::getClassId).distinct().toList();
        if (sections.isEmpty()) return Set.of();
        var floorIds = floorClasses.findBySchoolIdOrderBySectionIdAsc(schoolId).stream()
                .filter(r -> sections.contains(r.getSectionId()))
                .map(quest.server.floors.Entities.FloorClassEntity::getFloorId).distinct().toList();
        if (floorIds.isEmpty()) return Set.of();
        var out = new LinkedHashSet<String>();
        for (var row : supervisors.findBySchoolIdOrderByFloorIdAsc(schoolId))
            if (floorIds.contains(row.getFloorId())) out.add(row.getUserId());
        return out;
    }

    private Set<String> sectionIdsOn(String schoolId, List<FloorEntity> mine) {
        if (mine.isEmpty()) return Set.of();
        var ids = mine.stream().map(FloorEntity::getId).toList();
        return floorClasses.findBySchoolIdAndFloorIdInOrderBySectionIdAsc(schoolId, ids).stream()
                .map(quest.server.floors.Entities.FloorClassEntity::getSectionId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}

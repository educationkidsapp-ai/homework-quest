package quest.server.tenancy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;

/**
 * DR5: <strong>what a MANAGERIAL user reaches</strong> — one department (a curriculum: British or American), every
 * grade of it, every class, every teacher and every coordinator inside it, and nothing of the other track.
 * {@link CoordinatorScope}'s sibling, one axis over: a coordinator is narrow in subject and wide in grade, a manager
 * is wide in subject and narrow in track.
 *
 * <p><strong>The rule.</strong> Her `staff_scopes` rows are the ones with <em>no</em> subject (`subject NULL,
 * curriculum = department`), which is exactly how `SchoolSeed` writes `managers.csv` and how `/admin/managers` writes
 * one; {@link CoordinatorScope#scopesOf} skips those rows and this class reads only those. A section is hers when its
 * curriculum is one of her departments — every subject of it, unlike a coordinator's, so nothing here narrows by
 * subject and every delegate is handed {@link CoordinatorScope.Subjects#ALL}. A lesson is hers when its section is; a
 * child is hers when she sits in one. A coordinator is hers when one of her subject rows names a department of hers or
 * names none at all (a both-tracks coordinator belongs to both managers, DR5).
 *
 * <p><strong>The school is never checked here and never needs to be.</strong> Every query below is a repository query,
 * so the `school` Hibernate filter has already reduced `staff_scopes`, `classes`, `teaching_assignments`, `lessons`,
 * `children` and `users` to the caller's school. Nothing reads a school, a grade or a curriculum from the request —
 * the department comes from the caller's own user id.
 *
 * <p><strong>Who else passes.</strong> Only a MANAGERIAL user is narrowed. The platform ADMIN scoped to the school
 * reaches all of it (D6), which is what makes `/management/**` reviewable from the Admin dashboard; every other role
 * is refused by `permissions.json` and by `SecurityConfig` before a handler runs.
 *
 * <p><strong>Read-only.</strong> RM1 adds no write under `/management` at all — broadcasts and chat are RM2, staff
 * attendance is RM5 — so there is no `require…` here that a write would need.
 * `ManagerScopeArchitectureTest` fails the build when a `/management/**` handler cannot reach one of the checks below.
 */
@Component
public class ManagerScope {
    /** The third dashboard role, and the one DR5 calls a department manager. */
    public static final String ROLE = "MANAGERIAL";

    private final StaffScopeRepository scopes; private final ClassRepository classes;
    private final TeachingAssignmentRepository assignments; private final quest.server.content.LessonRepository lessons;
    private final quest.server.children.ChildRepository children; private final UserRepository users;
    private final TenantContext tenant;

    public ManagerScope(StaffScopeRepository scopes, ClassRepository classes, TeachingAssignmentRepository assignments,
                        quest.server.content.LessonRepository lessons, quest.server.children.ChildRepository children,
                        UserRepository users, TenantContext tenant) {
        this.scopes = scopes; this.classes = classes; this.assignments = assignments; this.lessons = lessons;
        this.children = children; this.users = users; this.tenant = tenant;
    }

    /** True for a MANAGERIAL principal — the only role this class narrows. */
    public boolean isManager(Principals.User caller) { return caller != null && ROLE.equals(caller.role()); }

    /** The caller, or 401 — every `/management/**` controller starts with it. */
    public static Principals.User require(Principals.User caller) { return TeacherScope.require(caller); }

    /**
     * Her departments, as the wire spells a curriculum. The subject rows are a coordinator's and are skipped here,
     * the mirror of {@link CoordinatorScope#scopesOf} skipping these. For a caller this class does not narrow it is
     * every track the school's own sections are in, so the Admin's `/management/me` names the school rather than
     * nothing at all.
     */
    public List<String> departments(Principals.User caller) {
        if (!isManager(caller)) return sectionsOf(caller).stream().map(k -> normalise(k.getCurriculum())).distinct().sorted().toList();
        return scopes.findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(tenant.writeSchoolId(), caller.userId()).stream()
                .filter(r -> (r.getSubject() == null || r.getSubject().isBlank()) && r.getCurriculum() != null && !r.getCurriculum().isBlank())
                .map(r -> normalise(r.getCurriculum())).distinct().sorted().toList();
    }

    /**
     * Everything her screens are built from, in {@link CoordinatorScope.Reach}'s shape and in its two statements: the
     * active sections of her departments in order, and every teaching assignment inside them. The record is the
     * coordinator's on purpose — `CoordinatorService` already builds the class cards, the calendar and the teacher
     * list out of one, and a manager's versions of those three screens are the same screens with a wider scope, so
     * they are read by the same code rather than by a second copy of it. `scopes()` is empty because a manager holds
     * no subject row; {@link #departments} is what her scope is made of.
     */
    public CoordinatorScope.Reach reach(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var mine = isManager(caller) ? departments(caller) : List.<String>of();
        var sections = classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId).stream()
                .filter(k -> !isManager(caller) || mine.contains(normalise(k.getCurriculum()))).toList();
        var byId = new LinkedHashMap<String, ClassEntity>();
        for (var section : sections) byId.put(section.getId(), section);
        var kept = assignments.findBySchoolIdOrderByClassIdAscSubjectAsc(schoolId).stream()
                .filter(a -> byId.containsKey(a.getClassId())).toList();
        return new CoordinatorScope.Reach(List.of(), sections, kept, Map.copyOf(byId));
    }

    /** Her sections, in curriculum then grade then name order — what `GET /management/classes` groups by grade. */
    public List<ClassEntity> sectionsOf(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var all = classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId);
        if (!isManager(caller)) return all;
        var mine = departments(caller);
        return all.stream().filter(k -> mine.contains(normalise(k.getCurriculum()))).toList();
    }

    /**
     * A section she may read: 404 when it is another school's or a pre-V7 leftover, 403 when it belongs to the other
     * department. The lookup comes first, so the refusal never doubles as confirmation that an id is real.
     */
    public ClassEntity requireSection(Principals.User caller, String classId) {
        var section = classes.findOneById(classId).filter(ClassEntity::isSection).orElseThrow(() -> ApiException.notFound("class"));
        if (isManager(caller) && !departments(caller).contains(normalise(section.getCurriculum())))
            throw ApiException.forbidden(section.getName() + " is outside the department you manage.");
        return section;
    }

    /**
     * A lesson she may read. The department is its section's, not the lesson's own: a lesson carries no curriculum
     * column, and a class is what a track is a property of. A lesson on no class belongs to no department, which is
     * the rule {@link CoordinatorScope#requireLesson} applies for the same reason.
     */
    public quest.server.content.Entities.LessonEntity requireLesson(Principals.User caller, String lessonId) {
        var lesson = lessons.findOneById(lessonId).orElseThrow(() -> ApiException.notFound("lesson"));
        if (!isManager(caller)) return lesson;
        if (lesson.getClassId() == null) throw ApiException.forbidden("That lesson is outside the department you manage.");
        requireSection(caller, lesson.getClassId());
        return lesson;
    }

    /** A child she may read: one placed in a section of her department. A child on no roster belongs to no manager. */
    public quest.server.children.Entities.ChildEntity requireChild(Principals.User caller, String childId) {
        var child = children.findOneById(childId).orElseThrow(() -> ApiException.notFound("child"));
        if (!isManager(caller)) return child;
        if (child.getClassId() == null) throw ApiException.forbidden("That child is not in one of your classes.");
        requireSection(caller, child.getClassId());
        return child;
    }

    /**
     * The coordinators she manages, with the scope rows that put them there: one of a coordinator's `(subject,
     * curriculum)` rows names a department of hers, or names no curriculum at all — a both-tracks coordinator reports
     * to both managers (DR5). Two statements, never one per person.
     *
     * @return `[coordinator -> her scope rows]`, in display-name order
     */
    public Map<UserEntity, List<CoordinatorScope.Scope>> coordinatorsOf(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var people = users.findBySchoolIdAndRole(schoolId, CoordinatorScope.ROLE).stream()
                .sorted(java.util.Comparator.comparing(quest.server.classes.SectionService::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
        if (people.isEmpty()) return Map.of();
        var mine = departments(caller);
        var rows = new LinkedHashMap<String, List<CoordinatorScope.Scope>>();
        for (var row : scopes.findBySchoolIdAndUserIdInOrderBySubjectAscCurriculumAsc(schoolId, people.stream().map(UserEntity::getId).toList())) {
            if (row.getSubject() == null || row.getSubject().isBlank()) continue;
            String curriculum = row.getCurriculum() == null || row.getCurriculum().isBlank() ? null : normalise(row.getCurriculum());
            if (isManager(caller) && curriculum != null && !mine.contains(curriculum)) continue;
            rows.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(new CoordinatorScope.Scope(normalise(row.getSubject()), curriculum));
        }
        var out = new LinkedHashMap<UserEntity, List<CoordinatorScope.Scope>>();
        for (var person : people) if (rows.containsKey(person.getId())) out.put(person, List.copyOf(rows.get(person.getId())));
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The teachers she manages: every one holding an assignment in a section of her department, in name order. */
    public List<UserEntity> teachersOf(Principals.User caller) {
        var ids = new LinkedHashSet<String>();
        for (var a : reach(caller).assignments()) ids.add(a.getTeacherId());
        if (ids.isEmpty()) return List.of();
        var rows = new ArrayList<UserEntity>();
        users.findAllById(List.copyOf(ids)).forEach(rows::add);
        rows.sort(java.util.Comparator.comparing(quest.server.classes.SectionService::displayName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(rows);
    }

    /** The teacher ids of one reach, for the quiet-teacher pass — {@link TeachingAssignmentEntity} keeps the names. */
    public static List<String> teacherIds(CoordinatorScope.Reach reach) {
        var ids = new LinkedHashSet<String>();
        for (var a : reach.assignments()) ids.add(a.getTeacherId());
        return List.copyOf(ids);
    }

    /** A curriculum as the wire spells it; the management package compares tracks with it. */
    public static String normalise(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}

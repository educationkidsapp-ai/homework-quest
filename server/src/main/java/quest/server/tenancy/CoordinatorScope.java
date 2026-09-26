package quest.server.tenancy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;

/**
 * DR1/DR2: <strong>what a COORDINATOR reaches</strong> — one subject across a track, or across both, in every grade
 * and every class of her school, and nothing else. {@link TeacherScope}'s sibling, and the one place the rule lives.
 *
 * <p><strong>The rule.</strong> Her `staff_scopes` rows are `(subject, curriculum)` pairs, `curriculum` null meaning
 * both tracks. A section is hers when somebody teaches one of those subjects in it and the section's curriculum
 * matches — so the unit of scope is the (section, subject) teaching assignment, exactly as it is for a teacher, and a
 * section of her track that nobody teaches her subject in is not hers to supervise. A lesson is hers when its own
 * subject and its section's curriculum match; a child is hers when she sits in a section that is.
 *
 * <p><strong>The school is never checked here and never needs to be.</strong> Every query below is a repository query,
 * so the `school` Hibernate filter has already reduced `staff_scopes`, `classes`, `teaching_assignments`, `lessons`
 * and `children` to the caller's school: another school's row is `Optional.empty()` and answers 404. Nothing here
 * reads a school, a subject or a curriculum from the request — the scope comes from the caller's own user id.
 *
 * <p><strong>Who else passes.</strong> Only a COORDINATOR is narrowed. The platform ADMIN scoped to the school
 * reaches all of it (D6), which is what makes `/coordinator/**` reviewable from the Admin dashboard; every other role
 * is refused by `permissions.json` before a handler runs, because the `coordinator.*` keys are granted to ADMIN and
 * COORDINATOR only.
 *
 * <p><strong>Read-only.</strong> This package adds no write route under `/coordinator` at all (DR2: writes are
 * communication, which is R4), so there is no `requireAssignment` here to mirror {@link TeacherScope}'s.
 *
 * <p><strong>The architecture rule.</strong> `CoordinatorScopeArchitectureTest` fails the build when a
 * `@RestController` serving `/coordinator/**`, or one of its handlers, cannot reach one of the checks below — so a new
 * coordinator route cannot be added without passing through the scope.
 */
@Component
public class CoordinatorScope {
    /** The fourth dashboard role (DR1). */
    public static final String ROLE = "COORDINATOR";

    private final StaffScopeRepository scopes; private final ClassRepository classes;
    private final TeachingAssignmentRepository assignments; private final quest.server.content.LessonRepository lessons;
    private final quest.server.children.ChildRepository children; private final TenantContext tenant;

    public CoordinatorScope(StaffScopeRepository scopes, ClassRepository classes, TeachingAssignmentRepository assignments,
                            quest.server.content.LessonRepository lessons, quest.server.children.ChildRepository children,
                            TenantContext tenant) {
        this.scopes = scopes; this.classes = classes; this.assignments = assignments; this.lessons = lessons;
        this.children = children; this.tenant = tenant;
    }

    /** One `staff_scopes` row as the rule reads it: a subject, and a track or both of them. */
    public record Scope(String subject, String curriculum) {
        boolean covers(String otherSubject, String otherCurriculum) {
            return subject.equals(normalise(otherSubject))
                    && (curriculum == null || curriculum.equals(normalise(otherCurriculum)));
        }
    }

    /**
     * Everything a coordinator's screens are built from, read in three statements whatever the size of the school:
     * her sections in order, the assignments of her subjects inside them, and the section each one names.
     */
    public record Reach(List<Scope> scopes, List<ClassEntity> sections, List<TeachingAssignmentEntity> assignments,
                        Map<String, ClassEntity> byId) {
        /** The (section, subject) pairs she supervises, as the lesson list's predicate reads them. */
        public Set<String> slots() {
            var out = new LinkedHashSet<String>();
            for (var a : assignments) out.add(slot(a.getClassId(), a.getSubject()));
            return out;
        }
        public List<String> sectionIds() { return sections.stream().map(ClassEntity::getId).toList(); }
    }

    /** True for a COORDINATOR principal — the only role this class narrows. */
    public boolean isCoordinator(Principals.User caller) { return caller != null && ROLE.equals(caller.role()); }

    /** The caller, or 401 — every `/coordinator/**` controller starts with it. */
    public static Principals.User require(Principals.User caller) { return TeacherScope.require(caller); }

    /** Her `(subject, curriculum)` pairs; the manager rows (`subject` null) belong to RM1 and are skipped here. */
    public List<Scope> scopesOf(Principals.User caller) {
        if (caller == null) return List.of();
        return scopes.findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(tenant.writeSchoolId(), caller.userId()).stream()
                .filter(r -> r.getSubject() != null && !r.getSubject().isBlank())
                .map(r -> new Scope(normalise(r.getSubject()), r.getCurriculum() == null || r.getCurriculum().isBlank() ? null : normalise(r.getCurriculum())))
                .toList();
    }

    /**
     * Her sections and the assignments inside them. For an ADMIN this is the whole school, which is the same answer
     * {@link TeacherScope#classesOf} gives her: `permissions.json` is what decides who may ask at all.
     */
    public Reach reach(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var sections = classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId);
        var byId = new LinkedHashMap<String, ClassEntity>();
        for (var section : sections) byId.put(section.getId(), section);
        var all = assignments.findBySchoolIdOrderByClassIdAscSubjectAsc(schoolId).stream()
                .filter(a -> byId.containsKey(a.getClassId())).toList();
        if (!isCoordinator(caller)) return new Reach(List.of(), sections, all, Map.copyOf(byId));

        var mine = scopesOf(caller);
        var kept = all.stream().filter(a -> covers(mine, a.getSubject(), byId.get(a.getClassId()).getCurriculum())).toList();
        var ids = kept.stream().map(TeachingAssignmentEntity::getClassId).collect(java.util.stream.Collectors.toSet());
        var inScope = sections.stream().filter(k -> ids.contains(k.getId())).toList();
        var scopedById = new LinkedHashMap<String, ClassEntity>();
        for (var section : inScope) scopedById.put(section.getId(), section);
        return new Reach(mine, inScope, kept, Map.copyOf(scopedById));
    }

    /** Her sections, in curriculum then grade then name order — `GET /coordinator/classes`'s rows. */
    public List<ClassEntity> sectionsOf(Principals.User caller) { return reach(caller).sections(); }

    /**
     * A section she may read: 404 when it is another school's or a pre-V7 leftover, 403 when nobody teaches one of
     * her subjects in it or it belongs to the other track. The lookup comes first, so the refusal never doubles as
     * confirmation that an id is real.
     */
    public ClassEntity requireSection(Principals.User caller, String classId) {
        var section = classes.findOneById(classId).filter(ClassEntity::isSection).orElseThrow(() -> ApiException.notFound("class"));
        if (!isCoordinator(caller)) return section;
        var mine = scopesOf(caller);
        boolean taught = assignments.findByClassIdOrderBySubjectAsc(classId).stream()
                .anyMatch(a -> covers(mine, a.getSubject(), section.getCurriculum()));
        if (!taught) throw notYours(section.getName());
        return section;
    }

    /**
     * A lesson she may read. The pair checked is the lesson's own subject and its section's curriculum, not the
     * section's assignments: a lesson of another subject sitting in a section she supervises is still not hers.
     */
    public quest.server.content.Entities.LessonEntity requireLesson(Principals.User caller, String lessonId) {
        var lesson = lessons.findOneById(lessonId).orElseThrow(() -> ApiException.notFound("lesson"));
        if (!isCoordinator(caller)) return lesson;
        var section = lesson.getClassId() == null ? null : classes.findOneById(lesson.getClassId()).filter(ClassEntity::isSection).orElse(null);
        if (section == null || !covers(scopesOf(caller), lesson.getSubject(), section.getCurriculum()))
            throw ApiException.forbidden("That lesson is outside the subject you coordinate.");
        return lesson;
    }

    /** A child she may read: one placed in a section of hers. A child on no roster belongs to no coordinator. */
    public quest.server.children.Entities.ChildEntity requireChild(Principals.User caller, String childId) {
        var child = children.findOneById(childId).orElseThrow(() -> ApiException.notFound("child"));
        if (!isCoordinator(caller)) return child;
        if (child.getClassId() == null) throw ApiException.forbidden("That child is not in one of your classes.");
        requireSection(caller, child.getClassId());
        return child;
    }

    /**
     * <strong>Which subjects of one section are hers</strong> (R3). {@link #requireSection} only proves that
     * <em>somebody</em> teaches one of her subjects in a section; in a section that also teaches another, the
     * teacher's own body for that class carries both — every published lesson of it, a level per subject, every exam.
     * A coordinator reads her own subject and nothing else (DR2), so the three reads that answer for a whole section
     * narrow by this before the teacher's service is asked anything.
     *
     * <p>The unit is the (section, subject) teaching assignment, which is the unit {@link Reach#slots} and R2's lesson
     * list already use, reduced by the same rule {@link #requireLesson} applies to one lesson: her scope covers the
     * subject and the section's track. {@link Subjects#ALL} for a caller this class does not narrow, so an ADMIN
     * reading the same route still gets the whole section.
     */
    public Subjects subjectsIn(Principals.User caller, ClassEntity section) {
        if (!isCoordinator(caller)) return Subjects.ALL;
        var mine = scopesOf(caller);
        var kept = new LinkedHashSet<String>();
        for (var a : assignments.findByClassIdOrderBySubjectAsc(section.getId()))
            if (covers(mine, a.getSubject(), section.getCurriculum())) kept.add(normalise(a.getSubject()));
        return new Subjects(Set.copyOf(kept), kept.isEmpty() ? null : kept.iterator().next());
    }

    /**
     * The subjects of one section a caller may read, or all of them. Passed into the grading and exam services in
     * place of a coordinator-shaped overload of each read: {@link #ALL} is what every other caller passes and means
     * "do not narrow", so a teacher's body is byte for byte what it was.
     *
     * @param only  the subjects to keep, already normalised; empty means every subject
     * @param label the one subject a body that carries a single subject name should be labelled with, or null for
     *              {@link TeacherScope#subjectOf}'s answer — never its "the section's first assignment" guess, which
     *              for a coordinator can name a subject she does not coordinate
     */
    public record Subjects(Set<String> only, String label) {
        /** Every subject of the section: what a teacher, a manager and the platform ADMIN read. */
        public static final Subjects ALL = new Subjects(Set.of(), null);

        /** True while nothing is being narrowed, so a caller can keep its own single-subject path. */
        public boolean all() { return only.isEmpty(); }

        public boolean covers(String subject) { return only.isEmpty() || only.contains(normalise(subject)); }
    }

    /** The (section, subject) key the lesson list and the calendar are built on. */
    public static String slot(String classId, String subject) { return classId + "\u0000" + normalise(subject); }

    private static boolean covers(List<Scope> scopes, String subject, String curriculum) {
        return scopes.stream().anyMatch(s -> s.covers(subject, curriculum));
    }

    private static ApiException notYours(String name) {
        return ApiException.forbidden(name + " is outside the subject and track you coordinate.");
    }

    static String normalise(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}

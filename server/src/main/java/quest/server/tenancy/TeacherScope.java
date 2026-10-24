package quest.server.tenancy;

import java.util.List;
import java.util.Locale;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;

/**
 * `docs/teacher-flow.md` §2: <strong>"everything the teacher sees and does is limited to her assignments. API calls
 * outside them return 403, whether or not the UI was bypassed."</strong> This is the one place that rule lives.
 *
 * <p>It replaces the profile-shaped rule {@link TenantGuard#lessonCreator} used to apply — "a subject in her
 * `subjects_json`, her curriculum, one of her grades" — with the only thing that is actually true of a teacher since
 * V7: a `(classId, subject)` pair she holds an assignment for. Her profile is now a description of her, not a
 * permission.
 *
 * <p><strong>What each check guarantees.</strong> The school is never checked here and never needs to be: every
 * query below is a repository query, so the `school` Hibernate filter has already reduced `classes` and
 * `teaching_assignments` to the caller's school. Another school's class is `Optional.empty()` and answers 404, join
 * code and all — the same silence another school's lesson gives. What this adds is the rule <em>inside</em> a
 * school:
 *
 * <ul>
 *   <li>a TEACHER reaches a class only through an assignment on it ({@link #requireClass}), and writes into a
 *       (class, subject) only through the assignment for that exact subject ({@link #requireAssignment});</li>
 *   <li>a MANAGERIAL user reads her school's classes and writes none — `permissions.json` gives her the `.read` keys
 *       only, and {@link #requireAssignment} refuses her outright as the second lock;</li>
 *   <li>the platform ADMIN reaches any class of the school she is scoped to (D6), and needs no assignment.</li>
 * </ul>
 *
 * <p><strong>The architecture rule.</strong> `TeacherScopeArchitectureTest` fails the build when a `@RestController`
 * whose mappings start with `/teacher` does not depend on this class, directly or through a service that does — so a
 * new teacher route cannot be added without passing through one of the three checks below.
 */
@Component
public class TeacherScope {
    private final ClassRepository classes; private final TeachingAssignmentRepository assignments; private final TenantContext tenant;
    private final quest.server.content.LessonRepository lessons;

    public TeacherScope(ClassRepository classes, TeachingAssignmentRepository assignments, TenantContext tenant,
                        quest.server.content.LessonRepository lessons) {
        this.classes = classes; this.assignments = assignments; this.tenant = tenant; this.lessons = lessons;
    }

    /** The dashboard user behind the request, or null (a parent, a background job, a service called from a test). */
    public Principals.User caller() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Principals.User u ? u : null;
    }

    /** The caller, or 401 — every `/teacher/**` controller starts with it. */
    public static Principals.User require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller;
    }

    /** True for a TEACHER principal — the only role whose reach is narrowed to her own assignments. */
    public boolean isTeacher(Principals.User caller) { return caller != null && "TEACHER".equals(caller.role()); }

    /** The school a write lands in: the caller's, or the one an Admin picked with `X-School-Id` (else the default). */
    public String writeSchoolId() { return tenant.writeSchoolId(); }

    /** Every assignment a teacher holds, in class then subject order. One statement. */
    public List<TeachingAssignmentEntity> assignmentsOf(String teacherId) {
        return assignments.findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(tenant.writeSchoolId(), teacherId);
    }

    /**
     * Every assignment of one named school, for a background job that has no caller and so no tenant scope to read
     * {@link #writeSchoolId()} from — feature 31's teaching material reminder is the first. A request never uses
     * this: it has a caller, and the caller is what narrows the rows.
     */
    public List<TeachingAssignmentEntity> assignmentsIn(String schoolId) {
        return assignments.findBySchoolIdOrderByClassIdAscSubjectAsc(schoolId);
    }

    /** The assignments of the signed-in teacher; every assignment of the school for ADMIN and MANAGERIAL. */
    public List<TeachingAssignmentEntity> assignmentsOf(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        return isTeacher(caller) ? assignments.findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(schoolId, caller.userId())
                                 : assignments.findBySchoolIdOrderByClassIdAscSubjectAsc(schoolId);
    }

    /**
     * The assignments on one section — who teaches a child, for the parent's side of a feature (C1 chat). The
     * parent has no tenant scope, so this runs unfiltered on purpose: the caller has already proved the child is
     * hers, and the class id comes from that child's row, never from the request.
     */
    public List<TeachingAssignmentEntity> assignmentsOn(String classId) { return assignments.findByClassIdOrderBySubjectAsc(classId); }

    /** The sections a caller may reach: the ones she is assigned to, or all of the school for ADMIN / MANAGERIAL. */
    public List<ClassEntity> classesOf(Principals.User caller) {
        var sections = classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(tenant.writeSchoolId());
        if (!isTeacher(caller)) return sections;
        var mine = assignmentsOf(caller).stream().map(TeachingAssignmentEntity::getClassId).collect(java.util.stream.Collectors.toSet());
        return sections.stream().filter(k -> mine.contains(k.getId())).toList();
    }

    /**
     * A section the caller may read: 404 when it is another school's or a pre-V7 leftover, 403 when it is a class a
     * TEACHER holds no assignment on at all.
     */
    public ClassEntity requireClass(Principals.User caller, String classId) {
        var section = section(classId);
        if (isTeacher(caller) && assignments.findByClassIdOrderBySubjectAsc(classId).stream().noneMatch(a -> a.getTeacherId().equals(caller.userId())))
            throw ApiException.forbidden("You do not teach " + section.getName() + ".");
        return section;
    }

    /**
     * A (section, subject) the caller may write into — publishing a lesson, adding an exam, editing the roster of
     * that subject's class. A MANAGERIAL caller is refused here whatever the row says; an ADMIN passes as long as
     * the section exists in her scope.
     */
    public ClassEntity requireAssignment(Principals.User caller, String classId, String subject) {
        if (caller != null && "MANAGERIAL".equals(caller.role()))
            throw ApiException.forbidden("Managerial accounts can read this school's classes but not post into them.");
        var section = section(classId);
        if (!isTeacher(caller)) return section;
        String wanted = normalise(subject);
        assignments.findByClassIdAndSubject(classId, wanted)
                .filter(a -> a.getTeacherId().equals(caller.userId()))
                .orElseThrow(() -> ApiException.forbidden(section.getName() + " · " + wanted + " is not one of your classes."));
        return section;
    }

    /**
     * A lesson the caller may read and change — the check behind every `/teacher/lessons/**` route (N2.1), which is
     * what lets the dashboard stop calling `/admin/**` as a teacher.
     *
     * <p>Two ways in, and no third: she wrote it (`lessons.teacher_id`), or she holds the assignment on its class
     * <em>and</em> its subject. The first matters because a teacher keeps her own drafts when an assignment is moved
     * mid-term; the second is what makes a co-teacher's lesson reachable and another teacher's 403. A lesson of
     * another school is `Optional.empty()` and answers 404, because this is a filtered query like every other lookup
     * here. An ADMIN scoped to the school passes, and a MANAGERIAL caller is stopped by
     * {@link TenantGuard#requireLessonWrite()} on the way into every write.
     */
    public quest.server.content.Entities.LessonEntity requireLesson(Principals.User caller, String lessonId) {
        var lesson = lessons.findOneById(lessonId).orElseThrow(() -> ApiException.notFound("lesson"));
        if (!isTeacher(caller)) return lesson;
        if (caller.userId().equals(lesson.getTeacherId())) return lesson;
        if (lesson.getClassId() != null)
            assignments.findByClassIdAndSubject(lesson.getClassId(), normalise(lesson.getSubject()))
                    .filter(a -> a.getTeacherId().equals(caller.userId()))
                    .orElseThrow(TeacherScope::notYours);
        else throw notYours();
        return lesson;
    }

    private static ApiException notYours() { return ApiException.forbidden("That lesson is not one of yours."); }

    /** The subject of a caller's only assignment on a class, for routes that name a class and no subject. */
    public String subjectOn(Principals.User caller, String classId) {
        var mine = assignments.findByClassIdOrderBySubjectAsc(classId).stream()
                .filter(a -> !isTeacher(caller) || a.getTeacherId().equals(caller.userId())).toList();
        if (mine.isEmpty()) throw ApiException.forbidden("You do not teach that class.");
        return mine.getFirst().getSubject();
    }

    /**
     * The subject a section's screens are about, for the routes that name a class and no subject and must answer
     * even when nobody teaches it: the caller's own assignment on it, else the section's first assignment, else the
     * pre-V7 `classes.subject`, else null. {@link #subjectOn} is the strict form, which refuses instead.
     */
    public String subjectOf(Principals.User caller, ClassEntity section) {
        var mine = assignments.findByClassIdOrderBySubjectAsc(section.getId());
        return mine.stream().filter(a -> a.getTeacherId().equals(caller == null ? null : caller.userId())).findFirst()
                .or(() -> isTeacher(caller) ? java.util.Optional.empty() : mine.stream().findFirst())
                .map(TeachingAssignmentEntity::getSubject)
                .orElseGet(() -> section.getSubject() == null || section.getSubject().isBlank() ? null : section.getSubject());
    }

    // ---------------------------------------------------------------- feature 36: moving an assignment

    /**
     * The assignment on one (section, subject), or empty — what feature 36's drag picks up and what it checks the
     * target slot for before it drops.
     */
    public java.util.Optional<TeachingAssignmentEntity> assignmentOn(String classId, String subject) {
        return assignments.findByClassIdAndSubject(classId, normalise(subject));
    }

    /**
     * Moves one assignment to another (section, subject) — the Admin's drag on the structure tree (feature 36).
     *
     * <p>It lives here rather than in `quest.server.structure` because this class is the only place allowed to touch
     * `teaching_assignments` at all (`TeacherScopeArchitectureTest`), and because a moved assignment is precisely a
     * change to what a teacher reaches: after this call her 403s and her lesson list follow the new row with no
     * second rule to keep in step. The caller has already resolved both sections, so the ids here are rows of the
     * school, never request parameters.
     */
    public TeachingAssignmentEntity moveAssignment(TeachingAssignmentEntity source, String toClassId, String toSubject) {
        source.setClassId(toClassId); source.setSubject(normalise(toSubject));
        return assignments.save(source);
    }

    /**
     * Takes one assignment away: the teacher a drag replaced, and a subject a class's new academy does not offer.
     *
     * <p>Flushed, because `teaching_assignments` is unique on (class, subject) and the very next statement of a
     * replace is the moved row arriving in that slot — Hibernate would otherwise order the insert before the delete
     * and the database would refuse a move the API had accepted.
     */
    public void removeAssignment(TeachingAssignmentEntity row) { assignments.delete(row); assignments.flush(); }

    /** A section of the caller's school, or 404: a legacy pre-V7 row is not a section and is never reachable. */
    public ClassEntity section(String classId) {
        return classes.findOneById(classId).filter(ClassEntity::isSection).orElseThrow(() -> ApiException.notFound("class"));
    }

    static String normalise(String subject) { return subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT); }
}

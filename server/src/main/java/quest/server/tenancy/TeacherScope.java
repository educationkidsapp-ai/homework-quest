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

    /** The assignments of the signed-in teacher; every assignment of the school for ADMIN and MANAGERIAL. */
    public List<TeachingAssignmentEntity> assignmentsOf(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        return isTeacher(caller) ? assignments.findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(schoolId, caller.userId())
                                 : assignments.findBySchoolIdOrderByClassIdAscSubjectAsc(schoolId);
    }

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

    /** A section of the caller's school, or 404: a legacy pre-V7 row is not a section and is never reachable. */
    public ClassEntity section(String classId) {
        return classes.findOneById(classId).filter(ClassEntity::isSection).orElseThrow(() -> ApiException.notFound("class"));
    }

    static String normalise(String subject) { return subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT); }
}

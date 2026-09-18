package quest.server.tenancy;

import java.util.List;
import java.util.Locale;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.config.ApiException;

/**
 * The role rules of §5, enforced in the services — `@PreAuthorize` on the controllers is the second lock, not the
 * first, and a service reached from anywhere else (a job, another service) has to refuse just the same.
 *
 * <ul>
 *   <li>MANAGERIAL reads her school's lessons and changes none of them.</li>
 *   <li>A TEACHER creates lessons only in a (class, subject) she holds a teaching assignment for (D14); the section
 *       the lesson lands in is one of hers.</li>
 *   <li>ADMIN is unrestricted (scoped by `X-School-Id` when she picks a school).</li>
 * </ul>
 */
@Component
public class TenantGuard {
    private final TenantContext tenant; private final TeacherScope scope; private final ClassService classes;
    private final TeachingAssignmentRepository assignments;

    public TenantGuard(TenantContext tenant, TeacherScope scope, ClassService classes, TeachingAssignmentRepository assignments) {
        this.tenant = tenant; this.scope = scope; this.classes = classes; this.assignments = assignments;
    }

    /** The dashboard user behind the request, or null (a parent, a background job, a test calling a service directly). */
    public Principals.User user() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Principals.User u ? u : null;
    }

    /**
     * Every write to a lesson, its files, skills, plays, stops, panel or publication state.
     *
     * <p>Only MANAGERIAL is refused, on purpose: within her own school a TEACHER may edit and publish a colleague's
     * lesson, including a subject outside her `subjects_json` — the row is already filtered to her school, so this is
     * not an isolation question. §2/P1.2 restricts lesson <em>creation</em> ({@link #lessonTarget}); who may edit
     * whose lesson inside a school is P4.0 `backend/teacher-contract`, with the rest of the teacher profile.
     */
    public void requireLessonWrite() {
        if ("MANAGERIAL".equals(tenant.role()))
            throw ApiException.forbidden("Managerial accounts can read this school's lessons but not change them.");
    }

    /**
     * The section a new lesson belongs in, and the teacher who wrote it, under the V7 rule: <strong>a teacher
     * publishes into a (class, subject) she holds an assignment for</strong> ({@link TeacherScope}), not into any
     * combination her profile happens to list. Her `subjects_json` / `curriculum` / `grades_json` are a description
     * of her for the island and the directory; they stopped being a permission with D14.
     *
     * <p>`classId` is what the dashboard sends. It may be absent, for the two callers that predate sections: an
     * ADMIN driving the old `webAdmin` shape, who gets the school's section for that (curriculum, grade), and a
     * TEACHER whose assignments name exactly one class for the combination she asked for. Two matching assignments
     * are a 400 — she has to say which class — and none is the 403 §2 promises.
     */
    public ClassAndAuthor lessonTarget(String classId, String curriculum, int grade, String subject) {
        requireLessonWrite();
        var user = user();
        String wanted = subject.toLowerCase(Locale.ROOT);
        if (classId != null && !classId.isBlank()) {
            var section = scope.requireAssignment(user, classId.trim(), wanted);
            return new ClassAndAuthor(section, authorOf(user, section, wanted));
        }
        if (user == null || !"TEACHER".equals(user.role()))
            return new ClassAndAuthor(classes.findOrCreateSection(tenant.writeSchoolId(), curriculum, grade), null);

        var mine = scope.assignmentsOf(user).stream().filter(a -> a.getSubject().equals(wanted)).toList();
        var sections = mine.stream().map(a -> scope.section(a.getClassId()))
                .filter(k -> k.getCurriculum().equalsIgnoreCase(curriculum) && k.getGrade() == grade).toList();
        if (sections.isEmpty()) throw ApiException.forbidden(
                "You teach " + describe(user) + " — not " + wanted + " in " + curriculum.toLowerCase(Locale.ROOT) + " grade " + grade + ".");
        if (sections.size() > 1) throw ApiException.badRequest("You teach " + wanted + " in more than one class of that grade — name the class.");
        return new ClassAndAuthor(sections.getFirst(), user.userId());
    }

    /** The section a lesson lands in and the teacher it is credited to (null when an Admin publishes into a free class). */
    public record ClassAndAuthor(Entities.ClassEntity section, String teacherId) {}

    /**
     * Who the lesson is by. A TEACHER is always the author of what she creates; an ADMIN publishing into a class
     * inherits whoever holds that subject there, because the app shows the teacher's name and photo on the card.
     */
    private String authorOf(Principals.User user, Entities.ClassEntity section, String subject) {
        if (user != null && "TEACHER".equals(user.role())) return user.userId();
        return assignments.findByClassIdAndSubject(section.getId(), subject)
                .map(Entities.TeachingAssignmentEntity::getTeacherId).orElse(null);
    }

    /** "1A \u00b7 math, 1B \u00b7 math" \u2014 what the refusal names, so the teacher can see what she was expected to ask for. */
    private String describe(Principals.User user) {
        var labels = scope.assignmentsOf(user).stream()
                .map(a -> scope.section(a.getClassId()).getName() + " \u00b7 " + a.getSubject()).distinct().toList();
        return labels.isEmpty() ? "no classes yet" : String.join(", ", labels);
    }
}

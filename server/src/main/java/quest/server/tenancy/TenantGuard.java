package quest.server.tenancy;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.auth.TeacherRepository;
import quest.server.config.ApiException;
import quest.server.config.Json;

/**
 * The role rules of §5, enforced in the services — `@PreAuthorize` on the controllers is the second lock, not the
 * first, and a service reached from anywhere else (a job, another service) has to refuse just the same.
 *
 * <ul>
 *   <li>MANAGERIAL reads her school's lessons and changes none of them.</li>
 *   <li>A TEACHER creates lessons only for a subject she teaches, her curriculum and one of her grades; the class the
 *       lesson lands in is hers.</li>
 *   <li>ADMIN is unrestricted (scoped by `X-School-Id` when she picks a school).</li>
 * </ul>
 */
@Component
public class TenantGuard {
    private final TenantContext tenant; private final TeacherRepository teachers; private final Json json;

    public TenantGuard(TenantContext tenant, TeacherRepository teachers, Json json) { this.tenant = tenant; this.teachers = teachers; this.json = json; }

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
     * not an isolation question. §2/P1.2 restricts lesson <em>creation</em> ({@link #lessonCreator}); who may edit
     * whose lesson inside a school is P4.0 `backend/teacher-contract`, with the rest of the teacher profile.
     */
    public void requireLessonWrite() {
        if ("MANAGERIAL".equals(tenant.role()))
            throw ApiException.forbidden("Managerial accounts can read this school's lessons but not change them.");
    }

    /**
     * Checks a new lesson against the teacher's profile and returns the teacher whose class it belongs in
     * (null for ADMIN and for callers without a dashboard token).
     */
    public String lessonCreator(String curriculum, int grade, String subject) {
        requireLessonWrite();
        if (!"TEACHER".equals(tenant.role())) return null;
        var user = user();
        if (user == null) return null;
        var profile = teachers.findById(user.userId())
                .orElseThrow(() -> ApiException.forbidden("subject: your teacher profile has no subjects yet — ask your school to fill it in."));
        var subjects = json.strings(profile.getSubjectsJson()).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        var grades = json.read(profile.getGradesJson(), new TypeReference<List<Integer>>() {});
        if (!subjects.contains(subject.toLowerCase(Locale.ROOT)))
            throw ApiException.forbidden("subject: you teach " + join(subjects) + ", not " + subject.toLowerCase(Locale.ROOT) + ".");
        if (profile.getCurriculum() != null && !profile.getCurriculum().equalsIgnoreCase(curriculum))
            throw ApiException.forbidden("curriculum: you teach the " + profile.getCurriculum() + " curriculum, not " + curriculum.toLowerCase(Locale.ROOT) + ".");
        if (!grades.contains(grade))
            throw ApiException.forbidden("grade: you teach grade " + join(grades.stream().map(String::valueOf).toList()) + ", not grade " + grade + ".");
        return user.userId();
    }

    private static String join(List<String> values) { return values.isEmpty() ? "nothing yet" : String.join(", ", values); }
}

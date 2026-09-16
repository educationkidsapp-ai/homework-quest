package quest.server.teacher;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.platform.SafeText;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TenantContext;

/**
 * §5's `Teacher(userId, displayName, photoUrl, subjects[], curriculum, grades[], bioAr?, bioEn?)` — the half of a
 * teacher's account that decides what she may publish, and what parents and children see on the teacher island.
 *
 * <p>It lives across two tables and always has: `users` owns the name and the photo (every role has those), and
 * `teachers` owns the teaching profile. Writing through here keeps them in step and creates the `teachers` row if an
 * older account never got one, so {@link quest.server.tenancy.TenantGuard#lessonCreator} always has something to
 * check a new lesson against.
 *
 * <p><strong>Scope.</strong> `users.findById` is deliberate on the read of the caller's own row — a Hibernate filter
 * does not touch `em.find`, and she is reading herself. Every other lookup goes through the scoped
 * {@link UserRepository#findBySchoolIdAndRole} or is an ADMIN route whose whole point is to reach any school.
 */
@Service
public class TeacherProfileService {
    private static final List<String> CURRICULA = List.of("american", "british");
    private static final List<String> SUBJECTS = List.of("math", "english");
    /** A teacher's biography on the island and the school page; two paragraphs, not an essay. */
    private static final int MAX_BIO = 2000;

    private final UserRepository users; private final TeacherRepository profiles; private final ClassRepository classes;
    private final TenantContext tenant; private final Json json;

    public TeacherProfileService(UserRepository users, TeacherRepository profiles, ClassRepository classes,
                                 TenantContext tenant, Json json) {
        this.users = users; this.profiles = profiles; this.classes = classes; this.tenant = tenant; this.json = json;
    }

    // ---------------------------------------------------------------- read

    /** `GET /teacher/profile`: the caller's own. Only a TEACHER has one. */
    public TeacherDto.TeacherProfile mine(Principals.User caller) { return dto(requireTeacherRow(caller.userId())); }

    /**
     * `GET /admin/users/{id}/teacher-profile` (ADMIN): any teacher's, in any school. The permission is ADMIN-only in
     * `permissions.json`, so there is no school to check here — the route exists precisely to cross schools, exactly
     * as `GET /admin/users` does.
     */
    public TeacherDto.TeacherProfile of(String userId) { return dto(requireTeacherRow(userId)); }

    // ---------------------------------------------------------------- write

    @Transactional
    public TeacherDto.TeacherProfile saveMine(Principals.User caller, TeacherDto.UpdateTeacherProfileRequest request) {
        return save(caller.userId(), request);
    }

    @Transactional
    public TeacherDto.TeacherProfile save(String userId, TeacherDto.UpdateTeacherProfileRequest request) {
        var user = requireTeacherRow(userId);
        var profile = profiles.findById(userId).orElseGet(() -> {
            var fresh = new TeacherEntity();
            fresh.setUserId(userId);
            return fresh;
        });

        if (request.displayName() != null) user.setDisplayName(SafeText.plainText(request.displayName(), "displayName", 120));
        if (request.photoUrl() != null) user.setPhotoUrl(SafeText.httpsUrl(request.photoUrl(), "photoUrl"));
        if (request.subjects() != null) profile.setSubjectsJson(json.write(normalised(request.subjects(), SUBJECTS, "subject")));
        if (request.curriculum() != null) profile.setCurriculum(oneOf(request.curriculum(), CURRICULA, "curriculum"));
        if (request.grades() != null) profile.setGradesJson(json.write(grades(request.grades())));
        if (request.bioEn() != null) profile.setBioEn(SafeText.plainText(request.bioEn(), "bioEn", MAX_BIO));
        if (request.bioAr() != null) profile.setBioAr(SafeText.plainText(request.bioAr(), "bioAr", MAX_BIO));

        user.setUpdatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        users.save(user);
        profiles.save(profile);
        return dto(user);
    }

    // ---------------------------------------------------------------- the restricted chooser (§6 screen 13)

    /**
     * `GET /teacher/options`: her curriculum, her grades, her subjects and the classes she owns — the only lesson
     * the New lesson chooser may offer, because {@link quest.server.tenancy.TenantGuard#lessonCreator} refuses
     * anything else with a 403 the moment she submits it.
     *
     * <p>`complete` is false when the profile has no subjects, no curriculum or no grades: the dashboard shows the
     * "ask your school to finish your profile" empty state rather than a chooser that cannot produce a valid lesson.
     */
    public TeacherDto.TeacherOptions options(Principals.User caller) {
        var profile = profiles.findById(caller.userId()).orElse(null);
        List<String> subjects = profile == null ? List.of() : json.strings(profile.getSubjectsJson());
        List<Integer> grades = profile == null ? List.<Integer>of() : json.read(profile.getGradesJson(), new TypeReference<List<Integer>>() {});
        String curriculum = profile == null ? null : profile.getCurriculum();
        var name = users.findById(caller.userId()).map(UserEntity::getDisplayName).orElse(null);
        var mine = classes.findBySchoolIdAndTeacherIdOrderByCurriculumAscGradeAscSubjectAsc(tenant.writeSchoolId(), caller.userId()).stream()
                .map(k -> new quest.server.dashboard.SchoolDataDto.SchoolClass(k.getId(), k.getSchoolId(), k.getCurriculum(), k.getGrade(),
                        k.getSubject(), k.getTeacherId(), name, k.getCreatedAt().toEpochMilli()))
                .toList();
        boolean complete = curriculum != null && !subjects.isEmpty() && !grades.isEmpty();
        return new TeacherDto.TeacherOptions(curriculum, grades, subjects, mine, complete);
    }

    /** Every class of hers, for the Home and My lessons screens; used by the other services in this package too. */
    public List<ClassEntity> myClasses(Principals.User caller) {
        return classes.findBySchoolIdAndTeacherIdOrderByCurriculumAscGradeAscSubjectAsc(tenant.writeSchoolId(), caller.userId());
    }

    // ---------------------------------------------------------------- helpers

    /** The account, refused unless it is a TEACHER: there is no teacher profile on an Admin or a Managerial row. */
    private UserEntity requireTeacherRow(String userId) {
        var user = users.findById(userId).orElseThrow(() -> ApiException.notFound("teacher"));
        if (!"TEACHER".equals(user.getRole())) throw ApiException.notFound("teacher");
        return user;
    }

    TeacherDto.TeacherProfile dto(UserEntity user) {
        var profile = profiles.findById(user.getId()).orElse(null);
        return new TeacherDto.TeacherProfile(user.getId(), user.getEmail(), user.getDisplayName(), user.getPhotoUrl(),
                profile == null ? List.of() : json.strings(profile.getSubjectsJson()),
                profile == null ? null : profile.getCurriculum(),
                profile == null ? List.<Integer>of() : json.read(profile.getGradesJson(), new TypeReference<List<Integer>>() {}),
                profile == null ? null : profile.getBioEn(),
                profile == null ? null : profile.getBioAr());
    }

    private static List<String> normalised(List<String> values, List<String> allowed, String field) {
        var out = new ArrayList<String>();
        for (String value : values) { var one = oneOf(value, allowed, field); if (!out.contains(one)) out.add(one); }
        return out;
    }

    private static List<Integer> grades(List<Integer> values) {
        var out = new ArrayList<Integer>();
        for (Integer grade : values) {
            if (grade == null || grade < 1 || grade > 12) throw ApiException.badRequest("grade " + grade + " is outside 1–12");
            if (!out.contains(grade)) out.add(grade);
        }
        return out;
    }

    private static String oneOf(String value, List<String> allowed, String field) {
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(lower)) throw ApiException.badRequest(field + " must be one of " + String.join(", ", allowed));
        return lower;
    }
}

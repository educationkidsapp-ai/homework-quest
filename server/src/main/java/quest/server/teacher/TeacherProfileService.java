package quest.server.teacher;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
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
import quest.server.tenancy.Entities.ClassEntity;

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
    private static final List<String> SUBJECTS = List.of("math", "english", "french", "science", "religion", "arabic");
    /** A teacher's biography on the island and the school page; two paragraphs, not an essay. */
    private static final int MAX_BIO = 2000;

    private final UserRepository users; private final TeacherRepository profiles; private final TeacherAccess access;
    private final Json json;

    public TeacherProfileService(UserRepository users, TeacherRepository profiles, TeacherAccess access, Json json) {
        this.users = users; this.profiles = profiles; this.access = access; this.json = json;
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
     * `GET /teacher/options`: the classes she is assigned to, and the curriculum, grades and subjects those
     * assignments add up to — the only lesson the New lesson chooser may offer, because
     * {@link quest.server.tenancy.TeacherScope#requireAssignment} refuses anything else with a 403 the moment she
     * submits it.
     *
     * <p><strong>Her assignments, never her profile alone (N2.3b).</strong> §1 of `docs/teacher-flow.md`: "the
     * grades she teaches are not stored; they are derived from her assignments." A seeded teacher has an empty
     * `grades_json` and two classes, and reading the profile answered her an empty chooser she could not use. The
     * profile is now only the fallback for the one field a class cannot supply for itself — her curriculum, when
     * she has no assignment yet.
     *
     * <p>`complete` is false when nothing has been assigned to her: the dashboard shows the "ask your school to
     * finish your profile" empty state rather than a chooser that cannot produce a valid lesson.
     */
    public TeacherDto.TeacherOptions options(Principals.User caller) {
        var name = users.findById(caller.userId()).map(UserEntity::getDisplayName).orElse(null);
        var sections = new LinkedHashMap<String, ClassEntity>();
        for (var section : access.ownedClasses(caller)) sections.put(section.getId(), section);
        // One entry per (class, subject) she holds, which is what the chooser picks: the subject lives on the
        // assignment since V7, so a section's own `subject` column is null for everything the Admin API created.
        var mine = new ArrayList<quest.server.dashboard.SchoolDataDto.SchoolClass>();
        var grades = new TreeSet<Integer>(); var subjects = new LinkedHashSet<String>(); var curricula = new LinkedHashSet<String>();
        for (var assignment : access.assignmentsOf(caller)) {
            var section = sections.get(assignment.getClassId());
            if (section == null) continue;
            grades.add(section.getGrade()); subjects.add(assignment.getSubject()); curricula.add(section.getCurriculum());
            mine.add(new quest.server.dashboard.SchoolDataDto.SchoolClass(section.getId(), section.getSchoolId(),
                    section.getCurriculum(), section.getGrade(), assignment.getSubject(), caller.userId(), name,
                    section.getCreatedAt().toEpochMilli()));
        }
        var profile = profiles.findById(caller.userId()).orElse(null);
        // `curriculum` is one field and a teacher may in principle be assigned across two: hers is the one her
        // assignments agree on, and her profile's only while she has none.
        String curriculum = curricula.size() == 1 ? curricula.iterator().next() : profile == null ? null : profile.getCurriculum();
        boolean complete = curriculum != null && !subjects.isEmpty() && !grades.isEmpty();
        return new TeacherDto.TeacherOptions(curriculum, List.copyOf(grades), List.copyOf(subjects), List.copyOf(mine), complete);
    }

    /**
     * Every class of hers, for the Home and My lessons screens. Her assignments since V7 — `classes.teacher_id` is
     * not written any more, so the query this used to run answered nothing for a teacher created after it.
     */
    public List<ClassEntity> myClasses(Principals.User caller) { return access.ownedClasses(caller); }

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

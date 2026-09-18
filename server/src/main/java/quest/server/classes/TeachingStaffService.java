package quest.server.classes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.AuthService;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.RefreshTokenService;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.TeachingAssignmentRepository;
import quest.server.tenancy.TenantContext;

/**
 * The Admin's Teachers screen (`docs/prompts/dashboard-first-one-school.md` §6, `docs/teacher-flow.md` §1–§2):
 * creating a teacher with a one-time password, editing her, and setting what she teaches.
 *
 * <p><strong>One teacher per subject per class.</strong> The database holds it (UNIQUE on
 * `teaching_assignments(class_id, subject)`), and {@link #setAssignments} checks it first so the refusal can name the
 * colleague who holds the slot — "1A · math is taught by Sara Al Harbi" — instead of surfacing a constraint nobody
 * can read.
 *
 * <p><strong>The temporary password is answered once.</strong> Only its bcrypt hash is stored, the account carries
 * `must_change_password`, and nothing here puts the value into the audit row, a log line or a mail.
 */
@Service
public class TeachingStaffService {
    private static final List<String> SUBJECTS = List.of("math", "english");
    private static final List<String> CURRICULA = List.of("american", "british");

    private final UserRepository users; private final TeacherRepository profiles; private final TeachingAssignmentRepository assignments;
    private final SectionService sections; private final TemporaryPasswords passwords; private final PasswordEncoder encoder;
    private final RefreshTokenService refreshTokens; private final TenantContext tenant; private final AuditService audit; private final Json json;

    public TeachingStaffService(UserRepository users, TeacherRepository profiles, TeachingAssignmentRepository assignments,
                                SectionService sections, TemporaryPasswords passwords, PasswordEncoder encoder,
                                RefreshTokenService refreshTokens, TenantContext tenant, AuditService audit, Json json) {
        this.users = users; this.profiles = profiles; this.assignments = assignments; this.sections = sections;
        this.passwords = passwords; this.encoder = encoder; this.refreshTokens = refreshTokens; this.tenant = tenant;
        this.audit = audit; this.json = json;
    }

    /** Every teacher of the school with what she teaches: four statements, never one per teacher. */
    public List<ClassDto.TeacherAccount> list() {
        var rows = users.findBySchoolIdAndRole(tenant.writeSchoolId(), "TEACHER").stream()
                .sorted(java.util.Comparator.comparing(SectionService::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(UserEntity::getId).toList();
        var mine = assignments.findByTeacherIdInOrderByClassIdAscSubjectAsc(ids);
        var sectionsById = sections.sectionsOf(mine);
        var byTeacher = new LinkedHashMap<String, List<ClassDto.TeachingAssignment>>();
        for (var a : sections.withTeacherNames(mine, sectionsById))
            byTeacher.computeIfAbsent(a.teacherId(), k -> new ArrayList<>()).add(a);
        var profileRows = new LinkedHashMap<String, TeacherEntity>();
        profiles.findAllById(ids).forEach(p -> profileRows.put(p.getUserId(), p));
        return rows.stream().map(u -> account(u, profileRows.get(u.getId()), byTeacher.getOrDefault(u.getId(), List.of()))).toList();
    }

    @Transactional
    public ClassDto.TeacherCreated create(Principals.User caller, ClassDto.CreateTeacherRequest request) {
        String schoolId = tenant.writeSchoolId();
        String fullName = text(request.fullName(), "fullName", 80);
        String email = AuthService.normalise(request.email());
        if (!email.contains("@")) throw ApiException.badRequest("That is not an email address.");
        if (users.findIdByEmailAcrossSchools(email).isPresent()) throw ApiException.conflict("That address already has an account.");
        var subjects = subjects(request.subjects());
        String curriculum = request.curriculum() == null ? null : SectionService.oneOf(request.curriculum(), CURRICULA, "curriculum");
        String temporary = passwords.generate();

        var user = new UserEntity();
        user.setId(UUID.randomUUID().toString()); user.setSchoolId(schoolId); user.setEmail(email); user.setRole("TEACHER");
        user.setStatus("active"); user.setMustChangePassword(true); user.setDisplayName(fullName);
        user.setPhotoUrl(photo(request.photoUrl())); user.setPasswordHash(encoder.encode(temporary));
        user.setCreatedAt(Instant.now()); user.setUpdatedAt(Instant.now());
        users.save(user);
        saveProfile(user.getId(), subjects, curriculum);

        // The password is deliberately not in the audit details: the row is readable by every Admin, for ever.
        audit.record(caller.userId(), "teacher.create", "user", user.getId(), schoolId, Map.of("email", email));
        return new ClassDto.TeacherCreated(account(user, profiles.findById(user.getId()).orElse(null), List.of()), temporary);
    }

    @Transactional
    public ClassDto.TeacherAccount update(Principals.User caller, String userId, ClassDto.UpdateTeacherRequest request) {
        var user = teacher(userId);
        if (request.fullName() != null) user.setDisplayName(text(request.fullName(), "fullName", 80));
        if (request.photoUrl() != null) user.setPhotoUrl(request.photoUrl().isBlank() ? null : photo(request.photoUrl()));
        if (request.active() != null) {
            if (caller.userId().equals(user.getId())) throw ApiException.badRequest("You cannot disable your own account.");
            user.setStatus(request.active() ? "active" : "disabled");
            if (!request.active()) refreshTokens.revokeAll(user.getId());        // her sessions die with the account
        }
        user.setUpdatedAt(Instant.now());
        users.save(user);
        if (request.subjects() != null || request.curriculum() != null) {
            var profile = profiles.findById(userId).orElseGet(TeacherEntity::new);
            var subjects = request.subjects() == null ? json.strings(profile.getSubjectsJson()) : subjects(request.subjects());
            String curriculum = request.curriculum() == null ? profile.getCurriculum() : SectionService.oneOf(request.curriculum(), CURRICULA, "curriculum");
            saveProfile(userId, subjects, curriculum);
        }
        audit.record(caller.userId(), "teacher.update", "user", user.getId(), user.getSchoolId(), Map.of("status", user.getStatus()));
        return one(user);
    }

    /** A new one-time password for an account that already exists; the old one stops working immediately. */
    @Transactional
    public ClassDto.TemporaryPassword resetPassword(Principals.User caller, String userId) {
        var user = teacher(userId);
        String temporary = passwords.generate();
        user.setPasswordHash(encoder.encode(temporary)); user.setMustChangePassword(true); user.setUpdatedAt(Instant.now());
        users.save(user);
        refreshTokens.revokeAll(user.getId());
        audit.record(caller.userId(), "teacher.resetPassword", "user", user.getId(), user.getSchoolId(), Map.of());
        return new ClassDto.TemporaryPassword(temporary);
    }

    /**
     * `PUT /admin/teachers/{id}/assignments`: the complete set she should hold afterwards. A pair another teacher
     * holds is 409 naming her, checked before anything is written so a refused call changes nothing.
     */
    @Transactional
    public List<ClassDto.TeachingAssignment> setAssignments(Principals.User caller, String userId, ClassDto.AssignmentsRequest request) {
        var user = teacher(userId);
        String schoolId = tenant.writeSchoolId();
        var wanted = new LinkedHashMap<String, ClassDto.AssignmentInput>();
        var sectionsById = new LinkedHashMap<String, ClassEntity>();
        for (var input : request.assignments() == null ? List.<ClassDto.AssignmentInput>of() : request.assignments()) {
            var section = sections.section(input.classId());
            String subject = SectionService.oneOf(input.subject(), SUBJECTS, "subject");
            sectionsById.put(section.getId(), section);
            wanted.put(section.getId() + "|" + subject, new ClassDto.AssignmentInput(section.getId(), subject));
        }
        for (var input : wanted.values()) {
            var taken = assignments.findByClassIdAndSubject(input.classId(), input.subject())
                    .filter(a -> !a.getTeacherId().equals(user.getId())).orElse(null);
            if (taken == null) continue;
            String holder = users.findById(taken.getTeacherId()).map(SectionService::displayName).orElse("another teacher");
            throw ApiException.conflict(sectionsById.get(input.classId()).getName() + " · " + input.subject() + " is taught by " + holder);
        }

        var existing = assignments.findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(schoolId, user.getId());
        var keep = new LinkedHashSet<String>();
        for (var row : existing) {
            String key = row.getClassId() + "|" + row.getSubject();
            if (wanted.containsKey(key)) keep.add(key); else assignments.delete(row);
        }
        for (var input : wanted.values()) {
            String key = input.classId() + "|" + input.subject();
            if (keep.contains(key)) continue;
            var row = new TeachingAssignmentEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setTeacherId(user.getId());
            row.setClassId(input.classId()); row.setSubject(input.subject()); row.setCreatedAt(Instant.now());
            assignments.save(row);
        }
        audit.record(caller.userId(), "teacher.assignments", "user", user.getId(), schoolId, Map.of("count", wanted.size()));
        var saved = assignments.findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(schoolId, user.getId());
        return sections.withTeacherNames(saved, sections.sectionsOf(saved));
    }

    // ---------------------------------------------------------------- helpers

    /** A TEACHER of the caller's scope. `users` is filtered, so another school's is simply not found. */
    private UserEntity teacher(String userId) {
        return users.findById(userId)
                .filter(u -> "TEACHER".equals(u.getRole()) && java.util.Objects.equals(tenant.writeSchoolId(), u.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("teacher"));
    }

    private ClassDto.TeacherAccount one(UserEntity user) {
        var mine = assignments.findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(tenant.writeSchoolId(), user.getId());
        return account(user, profiles.findById(user.getId()).orElse(null), sections.withTeacherNames(mine, sections.sectionsOf(mine)));
    }

    private void saveProfile(String userId, List<String> subjects, String curriculum) {
        var profile = profiles.findById(userId).orElseGet(TeacherEntity::new);
        profile.setUserId(userId); profile.setSubjectsJson(json.write(subjects)); profile.setCurriculum(curriculum);
        profile.setUpdatedAt(Instant.now());
        profiles.save(profile);
    }

    private ClassDto.TeacherAccount account(UserEntity user, TeacherEntity profile, List<ClassDto.TeachingAssignment> mine) {
        return new ClassDto.TeacherAccount(user.getId(), user.getEmail(), SectionService.displayName(user), user.getPhotoUrl(),
                user.getStatus(), profile == null ? List.of() : json.strings(profile.getSubjectsJson()),
                profile == null ? null : profile.getCurriculum(), mine);
    }

    private List<String> subjects(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(v -> SectionService.oneOf(v, SUBJECTS, "subject")).distinct().toList();
    }

    /** The photo is rendered into an `img src` by the dashboard and by the app's teacher island: https only. */
    private static String photo(String url) {
        if (url == null || url.isBlank()) return null;
        String cleaned = url.trim();
        if (!cleaned.toLowerCase(Locale.ROOT).startsWith("https://")) throw ApiException.badRequest("A photo URL must start with https://");
        return cleaned;
    }

    private static String text(String value, String field, int max) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > max) throw ApiException.badRequest(field + " is 1–" + max + " characters.");
        return cleaned;
    }
}

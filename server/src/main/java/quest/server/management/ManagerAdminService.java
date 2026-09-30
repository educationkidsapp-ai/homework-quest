package quest.server.management;

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
import quest.api.dto.Curriculum;
import quest.server.auth.AuditService;
import quest.server.auth.AuthService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.classes.SectionService;
import quest.server.classes.TemporaryPasswords;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.ManagerScope;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TenantContext;

/**
 * The Admin's Managers screen (RM1, DR5): how a department manager comes to exist, and how the department she manages is
 * changed. {@code CoordinatorAdminService}'s mirror, and shaped after it deliberately — the two write the same table and
 * differ only in which half of a `staff_scopes` row they fill. A manager's row is a department (`curriculum` set,
 * `subject` null), which is exactly what `SchoolSeed` writes from `managers.csv`; until RM1 that seed was the only way a
 * MANAGERIAL account could come into being, because `POST /admin/users` creates a TEACHER or a MANAGERIAL of one school
 * and writes no scope row at all.
 *
 * <p><strong>The temporary password is in the response body and nowhere else</strong>
 * ({@link TemporaryPasswords}): not in the audit row, not in a log line, not in a mail, and the route that answers one
 * is a POST so no proxy or browser history holds it in a URL.
 *
 * <p><strong>The department is validated before anything is written.</strong> A curriculum outside {@link Curriculum}
 * and the same department twice are both 400s raised while building the desired set, so a refused call changes nothing.
 * {@link #setDepartments} then replaces her whole set, as `PUT /admin/coordinators/{id}/scopes` does, which is what
 * makes the screen's Save one request.
 *
 * <p><strong>Scope of the caller.</strong> Nothing here takes a school from the request: it is
 * {@link TenantContext#writeSchoolId()}, so an Admin who picked a school with `X-School-Id` acts in that one. A manager
 * of another school is simply not found, because `users` is a filtered query.
 */
@Service
public class ManagerAdminService {
    /** The two tracks the contract has (`quest.api.dto.Curriculum`), as the wire spells them. */
    private static final List<String> CURRICULA = java.util.Arrays.stream(Curriculum.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList();

    private final UserRepository users; private final StaffScopeRepository scopes; private final TemporaryPasswords passwords;
    private final PasswordEncoder encoder; private final TenantContext tenant; private final AuditService audit;

    public ManagerAdminService(UserRepository users, StaffScopeRepository scopes, TemporaryPasswords passwords,
                               PasswordEncoder encoder, TenantContext tenant, AuditService audit) {
        this.users = users; this.scopes = scopes; this.passwords = passwords; this.encoder = encoder;
        this.tenant = tenant; this.audit = audit;
    }

    /** Every manager of the school with her departments: two statements, never one per person. */
    public List<ManagementDto.ManagerAccount> list() {
        String schoolId = tenant.writeSchoolId();
        var rows = users.findBySchoolIdAndRole(schoolId, ManagerScope.ROLE).stream()
                .sorted(java.util.Comparator.comparing(SectionService::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
        if (rows.isEmpty()) return List.of();
        var byUser = new LinkedHashMap<String, List<String>>();
        for (var row : scopes.findBySchoolIdAndUserIdInOrderBySubjectAscCurriculumAsc(schoolId, rows.stream().map(UserEntity::getId).toList()))
            if ((row.getSubject() == null || row.getSubject().isBlank()) && row.getCurriculum() != null)
                byUser.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(ManagerScope.normalise(row.getCurriculum()));
        return rows.stream().map(u -> account(u, byUser.getOrDefault(u.getId(), List.of()))).toList();
    }

    /** 201 with the one and only sight of the password; it cannot be read again. */
    @Transactional
    public ManagementDto.ManagerCreated create(Principals.User caller, ManagementDto.CreateManagerRequest request) {
        String schoolId = tenant.writeSchoolId();
        String fullName = text(request.fullName());
        String email = AuthService.normalise(request.email());
        if (!email.contains("@")) throw ApiException.badRequest("That is not an email address.");
        if (users.findIdByEmailAcrossSchools(email).isPresent()) throw ApiException.conflict("That address already has an account.");
        var wanted = wanted(List.of(request.curriculum()));
        String temporary = passwords.generate();

        var user = new UserEntity();
        user.setId(UUID.randomUUID().toString()); user.setSchoolId(schoolId); user.setEmail(email);
        user.setRole(ManagerScope.ROLE); user.setStatus("active"); user.setMustChangePassword(true);
        user.setDisplayName(fullName); user.setPasswordHash(encoder.encode(temporary));
        user.setPhone(quest.server.platform.Phones.normalise(request.phone(), "phone"));
        user.setCreatedAt(Instant.now()); user.setUpdatedAt(Instant.now());
        users.save(user);
        write(schoolId, user.getId(), wanted);

        // The password is deliberately not in the audit details: the row is readable by every Admin, for ever.
        audit.record(caller.userId(), "manager.create", "user", user.getId(), schoolId,
                Map.of("email", email, "departments", wanted.size()));
        return new ManagementDto.ManagerCreated(account(user, wanted), temporary);
    }

    /** The complete set of departments she should hold afterwards — the contract her coordinator siblings have. */
    @Transactional
    public ManagementDto.ManagerAccount setDepartments(Principals.User caller, String userId, ManagementDto.DepartmentsRequest request) {
        String schoolId = tenant.writeSchoolId();
        var user = manager(userId);
        var wanted = wanted(request.curricula());
        // Only her department rows are replaced: a MANAGERIAL user holds no subject row today, and dropping one
        // blindly would make this route a way to wipe a scope it never asked about.
        scopes.deleteAll(scopes.findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(schoolId, user.getId()).stream()
                .filter(r -> r.getSubject() == null || r.getSubject().isBlank()).toList());
        // The DELETEs before the INSERTs, not after: Hibernate orders inserts first inside a transaction, so a Save
        // that keeps a department she already holds would meet the unique index against the row it is replacing.
        scopes.flush();
        write(schoolId, user.getId(), wanted);
        audit.record(caller.userId(), "manager.departments", "user", user.getId(), schoolId, Map.of("departments", wanted.size()));
        return account(user, wanted);
    }

    // ---------------------------------------------------------------- helpers

    /** The desired departments, validated and rejected on a repeat rather than silently de-duplicated. */
    private List<String> wanted(List<String> requested) {
        var out = new LinkedHashSet<String>();
        for (var asked : requested == null ? List.<String>of() : requested)
            if (!out.add(SectionService.oneOf(asked, CURRICULA, "curriculum")))
                throw ApiException.badRequest(ManagerScope.normalise(asked) + " is named twice.");
        if (out.isEmpty()) throw ApiException.badRequest("A manager needs at least one department to manage.");
        return List.copyOf(out);
    }

    private void write(String schoolId, String userId, List<String> curricula) {
        for (var curriculum : curricula) {
            var row = new StaffScopeEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setUserId(userId);
            row.setSubject(null); row.setCurriculum(curriculum); row.setCreatedAt(Instant.now());
            scopes.save(row);
        }
    }

    /** A MANAGERIAL user of the caller's scope. `users` is filtered, so another school's is simply not found. */
    private UserEntity manager(String userId) {
        return users.findById(userId)
                .filter(u -> ManagerScope.ROLE.equals(u.getRole()) && java.util.Objects.equals(tenant.writeSchoolId(), u.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("manager"));
    }

    private static ManagementDto.ManagerAccount account(UserEntity user, List<String> departments) {
        return new ManagementDto.ManagerAccount(user.getId(), user.getEmail(), SectionService.displayName(user),
                user.getPhone(), user.getStatus(), departments);
    }

    private static String text(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > 80) throw ApiException.badRequest("fullName is 1–80 characters.");
        return cleaned;
    }
}

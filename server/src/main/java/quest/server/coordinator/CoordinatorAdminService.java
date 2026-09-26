package quest.server.coordinator;

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
import quest.api.dto.Subject;
import quest.server.auth.AuditService;
import quest.server.auth.AuthService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.classes.SectionService;
import quest.server.classes.TemporaryPasswords;
import quest.server.config.ApiException;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TenantContext;

/**
 * The Admin's Coordinators screen (R2): an account created in the room with a password read out once, and the
 * `staff_scopes` rows that decide everything she can then reach.
 *
 * <p>Shaped after {@link quest.server.classes.TeachingStaffService} deliberately, because the two are the same job:
 * the row is written here rather than through {@code UserService} for the reason the seed writes a manager itself —
 * `POST /admin/users` creates a TEACHER or a MANAGERIAL of one school and nothing else, and widening it would make
 * every caller of it able to mint a role. The temporary password is in the response body and nowhere else
 * ({@link TemporaryPasswords}): not in the audit row, not in a log line, not in a mail, and the route that answers
 * one is a POST so no proxy or browser history holds it in a URL.
 *
 * <p><strong>The scope is validated before anything is written.</strong> A subject outside {@link Subject}, a
 * curriculum outside {@link Curriculum} and the same pair twice are all 400s raised while building the desired set,
 * so a refused call changes nothing — the same contract `setAssignments` has. {@link #setScopes} then replaces her
 * whole set, as that route does, which is what makes the screen's Save one request.
 *
 * <p><strong>Scope of the caller.</strong> Nothing here takes a school from the request: it is
 * {@link TenantContext#writeSchoolId()}, so an Admin who picked a school with `X-School-Id` acts in that one. A
 * coordinator of another school is simply not found, because `users` is a filtered query.
 */
@Service
public class CoordinatorAdminService {
    /** The six subjects the contract has (`quest.api.dto.Subject`), as the wire spells them. */
    private static final List<String> SUBJECTS = java.util.Arrays.stream(Subject.values()).map(s -> s.name().toLowerCase(Locale.ROOT)).toList();
    private static final List<String> CURRICULA = java.util.Arrays.stream(Curriculum.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList();

    private final UserRepository users; private final StaffScopeRepository scopes; private final TemporaryPasswords passwords;
    private final PasswordEncoder encoder; private final TenantContext tenant; private final AuditService audit;

    public CoordinatorAdminService(UserRepository users, StaffScopeRepository scopes, TemporaryPasswords passwords,
                                   PasswordEncoder encoder, TenantContext tenant, AuditService audit) {
        this.users = users; this.scopes = scopes; this.passwords = passwords; this.encoder = encoder;
        this.tenant = tenant; this.audit = audit;
    }

    /** Every coordinator of the school with her whole scope: two statements, never one per person. */
    public List<CoordinatorDto.CoordinatorAccount> list() {
        String schoolId = tenant.writeSchoolId();
        var rows = users.findBySchoolIdAndRole(schoolId, CoordinatorScope.ROLE).stream()
                .sorted(java.util.Comparator.comparing(SectionService::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
        if (rows.isEmpty()) return List.of();
        var byUser = new LinkedHashMap<String, List<CoordinatorDto.Scope>>();
        for (var row : scopes.findBySchoolIdAndUserIdInOrderBySubjectAscCurriculumAsc(schoolId, rows.stream().map(UserEntity::getId).toList()))
            if (row.getSubject() != null) byUser.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(scope(row));
        return rows.stream().map(u -> account(u, byUser.getOrDefault(u.getId(), List.of()))).toList();
    }

    /** 201 with the one and only sight of the password; it cannot be read again. */
    @Transactional
    public CoordinatorDto.CoordinatorCreated create(Principals.User caller, CoordinatorDto.CreateCoordinatorRequest request) {
        String schoolId = tenant.writeSchoolId();
        String fullName = text(request.fullName());
        String email = AuthService.normalise(request.email());
        if (!email.contains("@")) throw ApiException.badRequest("That is not an email address.");
        if (users.findIdByEmailAcrossSchools(email).isPresent()) throw ApiException.conflict("That address already has an account.");
        var wanted = wanted(request.scopes());
        String temporary = passwords.generate();

        var user = new UserEntity();
        user.setId(UUID.randomUUID().toString()); user.setSchoolId(schoolId); user.setEmail(email);
        user.setRole(CoordinatorScope.ROLE); user.setStatus("active"); user.setMustChangePassword(true);
        user.setDisplayName(fullName); user.setPasswordHash(encoder.encode(temporary));
        user.setCreatedAt(Instant.now()); user.setUpdatedAt(Instant.now());
        users.save(user);
        write(schoolId, user.getId(), wanted);

        // The password is deliberately not in the audit details: the row is readable by every Admin, for ever.
        audit.record(caller.userId(), "coordinator.create", "user", user.getId(), schoolId,
                Map.of("email", email, "scopes", wanted.size()));
        return new CoordinatorDto.CoordinatorCreated(account(user, wanted.stream().map(CoordinatorAdminService::scope).toList()), temporary);
    }

    /** The complete set she should hold afterwards — the contract `PUT /admin/teachers/{id}/assignments` has. */
    @Transactional
    public CoordinatorDto.CoordinatorAccount setScopes(Principals.User caller, String userId, CoordinatorDto.ScopesRequest request) {
        String schoolId = tenant.writeSchoolId();
        var user = coordinator(userId);
        var wanted = wanted(request.scopes());
        scopes.deleteAll(scopes.findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(schoolId, user.getId()));
        // The DELETEs before the INSERTs, not after: Hibernate orders inserts first inside a transaction, so a Save
        // that keeps one of the pairs she already holds would meet the unique index against the row it is replacing.
        scopes.flush();
        write(schoolId, user.getId(), wanted);
        audit.record(caller.userId(), "coordinator.scopes", "user", user.getId(), schoolId, Map.of("scopes", wanted.size()));
        return account(user, wanted.stream().map(CoordinatorAdminService::scope).toList());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The desired set, validated and de-duplicated. A subject named twice for the same track is a 400 rather than a
     * silently dropped row, because the unique index treats two "both tracks" rows of one subject as distinct
     * (NULLs are distinct in a unique index on both PostgreSQL and H2) and the refusal has to come from here.
     */
    private List<StaffScopeEntity> wanted(List<CoordinatorDto.Scope> requested) {
        var seen = new LinkedHashSet<String>();
        var out = new ArrayList<StaffScopeEntity>();
        for (var asked : requested == null ? List.<CoordinatorDto.Scope>of() : requested) {
            String subject = SectionService.oneOf(asked.subject(), SUBJECTS, "subject");
            String curriculum = asked.curriculum() == null || asked.curriculum().isBlank() ? null
                    : SectionService.oneOf(asked.curriculum(), CURRICULA, "curriculum");
            if (!seen.add(subject + "/" + curriculum))
                throw ApiException.badRequest(subject + (curriculum == null ? " (both tracks)" : " · " + curriculum) + " is named twice.");
            var row = new StaffScopeEntity();
            row.setSubject(subject); row.setCurriculum(curriculum);
            out.add(row);
        }
        if (out.isEmpty()) throw ApiException.badRequest("A coordinator needs at least one subject to coordinate.");
        return out;
    }

    private void write(String schoolId, String userId, List<StaffScopeEntity> rows) {
        for (var row : rows) {
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setUserId(userId);
            row.setCreatedAt(Instant.now());
            scopes.save(row);
        }
    }

    /** A COORDINATOR of the caller's scope. `users` is filtered, so another school's is simply not found. */
    private UserEntity coordinator(String userId) {
        return users.findById(userId)
                .filter(u -> CoordinatorScope.ROLE.equals(u.getRole()) && java.util.Objects.equals(tenant.writeSchoolId(), u.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("coordinator"));
    }

    private static CoordinatorDto.CoordinatorAccount account(UserEntity user, List<CoordinatorDto.Scope> scopes) {
        return new CoordinatorDto.CoordinatorAccount(user.getId(), user.getEmail(), SectionService.displayName(user),
                user.getStatus(), scopes);
    }

    private static CoordinatorDto.Scope scope(StaffScopeEntity row) {
        return new CoordinatorDto.Scope(row.getSubject(), row.getCurriculum());
    }

    private static String text(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > 80) throw ApiException.badRequest("fullName is 1–80 characters.");
        return cleaned;
    }
}

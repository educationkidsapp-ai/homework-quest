package quest.server.workers;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.RefreshTokenService;
import quest.server.auth.UserRepository;
import quest.server.classes.StaffAccounts;
import quest.server.classes.TemporaryPasswords;
import quest.server.config.ApiException;
import quest.server.tenancy.TenantContext;
import quest.server.workers.Entities.WorkerEntity;

/**
 * Feature 13: the login a worker never had. `POST /admin/workers/{id}/app-access` mints it and
 * `POST /admin/workers/{id}/reset-password` replaces the password; both are ADMIN only (`worker.access`) and both
 * answer {@link WorkerDto.WorkerAccess} <strong>once</strong>, because nothing reads it back afterwards.
 *
 * <p><strong>Why a username rather than an address.</strong> {@link quest.server.classes.TeachingStaffService} signs a
 * teacher in with her work address; the caretaker and the driver have none, and asking an Admin to invent one would
 * put a fictional mailbox in the column every password reset mails to. So the login is generated —
 * `w.<first name in ASCII>.<four digits>` — and written to `users.email`, which is the login column and not a
 * promise of deliverability: `DashboardDto.SignInRequest` is `@NotBlank` and never `@Email`, so
 * `POST /auth/sign-in` takes it exactly as it stands. The four digits are what make it unique, and a collision is
 * simply drawn again.
 *
 * <p>Everything else is the rule the dashboard accounts already follow: {@link TemporaryPasswords} for the password,
 * `mustChangePassword` so it stops being a secret at the first sign-in, and {@link StaffAccounts#reset} for the
 * reset — one implementation, so a worker's account cannot drift from a coordinator's. Her role is WORKER, which
 * `SecurityConfig` admits to the app's own `/staff` facade, her notifications and her phone, and to nothing else.
 *
 * <p><strong>Retiring her takes the account with it</strong> ({@link #disable}): `DELETE /admin/workers/{id}` sets
 * `status = 'disabled'` on the `users` row and revokes its refresh tokens, so the phone in her pocket stops working
 * with the session it already holds rather than at the end of the access token's fifteen minutes. The row stays, as
 * the worker's own does — a person who has left is part of the school's record — and enabling her again reuses it.
 */
@Service
public class WorkerAccessService {
    /** `w.` plus a first name plus four digits: short enough to read out, long enough not to collide in a school. */
    private static final int NAME_MAX = 20, DIGITS = 4, ATTEMPTS = 20;

    private final WorkerRepository workers; private final UserRepository users; private final TenantContext tenant;
    private final PasswordEncoder encoder; private final TemporaryPasswords passwords;
    private final RefreshTokenService refreshTokens; private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public WorkerAccessService(WorkerRepository workers, UserRepository users, TenantContext tenant, PasswordEncoder encoder,
                               TemporaryPasswords passwords, RefreshTokenService refreshTokens, AuditService audit) {
        this.workers = workers; this.users = users; this.tenant = tenant; this.encoder = encoder;
        this.passwords = passwords; this.refreshTokens = refreshTokens; this.audit = audit;
    }

    /** The account behind each of these workers, by user id — one statement for the whole page. */
    Map<String, String> usernames(Collection<String> userIds) {
        var real = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (real.isEmpty()) return Map.of();
        return users.findAllById(real).stream().collect(java.util.stream.Collectors.toMap(UserEntity::getId, UserEntity::getEmail));
    }

    @Transactional
    public WorkerDto.WorkerAccess enable(Principals.User caller, String workerId) {
        var row = worker(workerId);
        if (row.getUserId() != null) throw ApiException.conflict("That worker already has app access.");
        if (!row.isActive()) throw ApiException.badRequest("Bring her back to the staff list before giving her an account.");
        String username = freeUsername(row.getFullName());
        String temporary = passwords.generate();

        var user = new UserEntity();
        user.setId(UUID.randomUUID().toString()); user.setSchoolId(row.getSchoolId()); user.setEmail(username);
        user.setRole("WORKER"); user.setStatus("active"); user.setMustChangePassword(true);
        user.setDisplayName(row.getFullName()); user.setPhone(row.getPhone());
        user.setPasswordHash(encoder.encode(temporary));
        user.setCreatedAt(Instant.now()); user.setUpdatedAt(Instant.now());
        users.save(user);
        row.setUserId(user.getId());
        workers.save(row);

        // The password is deliberately not in the audit details: the row is readable by every Admin, for ever.
        audit.record(caller.userId(), "worker.appAccess", "worker", row.getId(), row.getSchoolId(), Map.of("username", username));
        return new WorkerDto.WorkerAccess(username, temporary);
    }

    /** A new one-time password on the account she already has; the old one stops working immediately. */
    @Transactional
    public WorkerDto.WorkerAccess resetPassword(Principals.User caller, String workerId) {
        var row = worker(workerId);
        var user = account(row);
        String temporary = StaffAccounts.reset(user, encoder, passwords, refreshTokens);
        users.save(user);
        audit.record(caller.userId(), "worker.appAccess.reset", "worker", row.getId(), row.getSchoolId(), Map.of());
        return new WorkerDto.WorkerAccess(user.getEmail(), temporary);
    }

    /** Called when she is retired: the account is disabled and its sessions die with it. Silent when she has none. */
    void disable(WorkerEntity row) {
        if (row.getUserId() == null) return;
        users.findById(row.getUserId()).ifPresent(user -> {
            user.setStatus("disabled"); user.setUpdatedAt(Instant.now());
            users.save(user);
            refreshTokens.revokeAll(user.getId());
        });
    }

    // ---------------------------------------------------------------- helpers

    /** {@link WorkerService}'s own lookup: `findById` is `em.find`, which the `school` filter never reaches. */
    private WorkerEntity worker(String id) {
        return workers.findById(id).filter(w -> Objects.equals(tenant.writeSchoolId(), w.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("worker"));
    }

    private UserEntity account(WorkerEntity row) {
        if (row.getUserId() == null) throw ApiException.notFound("worker account");
        return users.findById(row.getUserId()).orElseThrow(() -> ApiException.notFound("worker account"));
    }

    /**
     * `w.<first name>.<four digits>`, drawn again while the login is taken. The name is her first word reduced to
     * ASCII letters and digits — a school writes "محمد علي" as often as "Mohamed Ali", and a login has to be typeable
     * on the keyboard she has — and `staff` stands in when nothing survives that reduction.
     */
    private String freeUsername(String fullName) {
        String stem = "w." + asciiFirstName(fullName) + ".";
        for (int i = 0; i < ATTEMPTS; i++) {
            String candidate = stem + digits();
            if (users.findIdByEmailAcrossSchools(candidate).isEmpty()) return candidate;
        }
        throw ApiException.conflict("Could not pick a username for her; try again.");
    }

    static String asciiFirstName(String fullName) {
        String first = fullName == null ? "" : fullName.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        var kept = new StringBuilder();
        for (char c : first.toCharArray())
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) { kept.append(c); if (kept.length() == NAME_MAX) break; }
        return kept.isEmpty() ? "staff" : kept.toString();
    }

    private String digits() {
        var out = new StringBuilder(DIGITS);
        for (int i = 0; i < DIGITS; i++) out.append((char) ('0' + random.nextInt(10)));
        return out.toString();
    }
}

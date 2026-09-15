package quest.server.users;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AdminJwtService;
import quest.server.auth.AuditService;
import quest.server.auth.AuthService;
import quest.server.auth.DashboardDto;
import quest.server.auth.Entities;
import quest.server.auth.Principals;
import quest.server.auth.RefreshTokenService;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;

/** §6 screen 6: the dashboard users list, disable/enable, rename, reset password and "View as…" (§5). */
@Service
public class UserService {
    static final List<String> SCHOOL_ROLES = List.of("TEACHER", "MANAGERIAL");
    private static final List<String> STATUSES = List.of("active", "disabled", "invited");

    private final UserRepository users; private final AuthService auth; private final AdminJwtService jwt;
    private final RefreshTokenService refreshTokens; private final AuditService audit;

    public UserService(UserRepository users, AuthService auth, AdminJwtService jwt, RefreshTokenService refreshTokens, AuditService audit) {
        this.users = users; this.auth = auth; this.jwt = jwt; this.refreshTokens = refreshTokens; this.audit = audit;
    }

    /**
     * A Managerial caller only ever sees their own school, whatever they ask for. The scope and the filters are part of
     * the query: the list must not load the users table and throw most of it away.
     */
    public List<DashboardDto.DashboardUser> list(Principals.User caller, String role, String schoolId, String status) {
        String scope = caller.isAdmin() ? schoolId : caller.schoolId();
        Specification<Entities.UserEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (scope != null) where.add(cb.equal(root.get("schoolId"), scope));
            if (role != null) where.add(cb.equal(cb.upper(root.get("role")), role.trim().toUpperCase(Locale.ROOT)));
            if (status != null) where.add(cb.equal(cb.lower(root.get("status")), status.trim().toLowerCase(Locale.ROOT)));
            return cb.and(where.toArray(Predicate[]::new));
        };
        return users.findAll(spec, Sort.by(Sort.Order.asc("email").ignoreCase())).stream().map(u -> DashboardDto.of(u, null)).toList();
    }

    @Transactional
    public DashboardDto.DashboardUser update(Principals.User caller, String userId, UserDto.UpdateUserRequest request) {
        var user = reachable(caller, userId);
        if (request.displayName() != null) user.setDisplayName(request.displayName().isBlank() ? null : request.displayName().trim());
        if (request.status() != null) {
            String status = request.status().trim().toLowerCase(Locale.ROOT);
            if (!STATUSES.contains(status)) throw ApiException.badRequest("status is active, disabled or invited");
            if (Objects.equals(caller.userId(), user.getId()) && !"active".equals(status)) throw ApiException.badRequest("You cannot disable your own account.");
            user.setStatus(status);
            if ("disabled".equals(status)) refreshTokens.revokeAll(user.getId());       // sessions die with the account
        }
        if (request.role() != null) {
            String role = request.role().trim().toUpperCase(Locale.ROOT);
            if (!SCHOOL_ROLES.contains(role)) throw ApiException.badRequest("A dashboard user is a TEACHER or a MANAGERIAL of one school.");
            if (user.getSchoolId() == null) throw ApiException.badRequest("The platform admin has no school role.");
            user.setRole(role);
        }
        user.setUpdatedAt(Instant.now());
        users.save(user);
        audit.record(caller.userId(), "user.update", "user", user.getId(), user.getSchoolId(),
                Map.of("status", user.getStatus(), "role", user.getRole()));
        return DashboardDto.of(user, null);
    }

    /** Only an active account: a reset link never revives a disabled one, and an invited one finishes through its invite. */
    @Transactional
    public void sendPasswordReset(Principals.User caller, String userId) {
        var user = reachable(caller, userId);
        if (!"active".equals(user.getStatus())) throw ApiException.badRequest("That account is not active.");
        auth.sendResetLink(user);
        audit.record(caller.userId(), "user.resetPassword", "user", user.getId(), user.getSchoolId(), Map.of());
    }

    /** §5 "View as…": a 30-minute read-only token for a Teacher or Managerial user. Admins are never impersonated. */
    @Transactional
    public DashboardDto.SignInResponse impersonate(Principals.User caller, String userId) {
        var user = reachable(caller, userId);
        if (!SCHOOL_ROLES.contains(user.getRole())) throw ApiException.badRequest("Only a teacher or a managerial user can be viewed as.");
        if (!"active".equals(user.getStatus())) throw ApiException.badRequest("That account is not active.");
        var issued = jwt.issueImpersonation(user.getId(), user.getEmail(), user.getRole(), user.getSchoolId(), caller.userId());
        audit.record(caller.userId(), "user.impersonate", "user", user.getId(), user.getSchoolId(), Map.of("email", user.getEmail()));
        return new DashboardDto.SignInResponse(issued.token(), user.getEmail(), issued.expiresAt().toEpochMilli(), user.getRole(),
                user.getSchoolId(), user.getDisplayName(), false, null);
    }

    /** The row, if the caller is allowed to touch it at all: an Admin may, anyone else only inside their own school. */
    Entities.UserEntity reachable(Principals.User caller, String userId) {
        var user = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
        if (!caller.isAdmin() && (caller.schoolId() == null || !caller.schoolId().equals(user.getSchoolId()))) throw ApiException.notFound("user");
        return user;
    }
}

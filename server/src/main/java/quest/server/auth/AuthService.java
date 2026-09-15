package quest.server.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.config.ApiException;
import quest.server.mail.DashboardMails;

/** Sign-in, session rotation and the three password flows (§5). Accounts are never created here — only invites do that. */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users; private final PasswordEncoder encoder; private final AdminJwtService jwt;
    private final RefreshTokenService refreshTokens; private final SignInRateLimiter limiter; private final DashboardMails mails;
    private final AuditService audit;

    public AuthService(UserRepository users, PasswordEncoder encoder, AdminJwtService jwt, RefreshTokenService refreshTokens,
                       SignInRateLimiter limiter, DashboardMails mails, AuditService audit) {
        this.users = users; this.encoder = encoder; this.jwt = jwt; this.refreshTokens = refreshTokens;
        this.limiter = limiter; this.mails = mails; this.audit = audit;
    }

    /** The dashboard sign-in: 15-minute access token + 30-day refresh token. Disabled and not-yet-accepted accounts are refused. */
    @Transactional
    public DashboardDto.SignInResponse signIn(String email, String password, String ip) { return session(authenticate(email, password, ip)); }

    /** A fresh access + refresh pair for a user who has just proved who they are (sign-in, or accepting an invite). */
    public DashboardDto.SignInResponse session(Entities.UserEntity user) {
        var access = jwt.issueAccess(user.getId(), user.getEmail(), user.getRole(), user.getSchoolId());
        return new DashboardDto.SignInResponse(access.token(), user.getEmail(), access.expiresAt().toEpochMilli(), user.getRole(),
                user.getSchoolId(), user.getDisplayName(), user.isMustChangePassword(), refreshTokens.issue(user.getId()));
    }

    /** The long session `webAdmin/` signs in with (`POST /admin/auth/sign-in`); same checks, no refresh token. */
    @Transactional
    public Entities.UserEntity signInLegacy(String email, String password, String ip) { return authenticate(email, password, ip); }

    /** `disabled` and `invited` accounts are refused exactly like a wrong password, and count towards the rate limit. */
    private Entities.UserEntity authenticate(String email, String password, String ip) {
        String normalised = normalise(email);
        limiter.check(normalised, ip);
        var user = users.findByEmailIgnoreCase(normalised).orElse(null);
        if (user == null || !"active".equals(user.getStatus()) || !encoder.matches(password, user.getPasswordHash())) {
            limiter.recordFailure(normalised, ip);
            throw ApiException.unauthorized("Wrong email or password.");
        }
        limiter.recordSuccess(normalised, ip);
        touchLogin(user);
        return user;
    }

    /** `noRollbackFor`: when a replayed token revokes the family, that revocation must survive the 401 it throws. */
    @Transactional(noRollbackFor = ApiException.class)
    public DashboardDto.TokenPair refresh(String refreshToken) {
        var rotated = refreshTokens.rotate(refreshToken);
        var user = users.findById(rotated.userId()).filter(u -> "active".equals(u.getStatus()))
                .orElseThrow(() -> ApiException.unauthorized("That session has expired. Sign in again."));
        var access = jwt.issueAccess(user.getId(), user.getEmail(), user.getRole(), user.getSchoolId());
        return new DashboardDto.TokenPair(access.token(), rotated.refreshToken(), access.expiresAt().toEpochMilli());
    }

    @Transactional
    public void signOut(String refreshToken) { refreshTokens.revoke(refreshToken); }

    /** Always silent: whether or not the address has an account, the caller gets 204. */
    @Transactional
    public void forgotPassword(String email) {
        users.findByEmailIgnoreCase(normalise(email)).filter(u -> !"disabled".equals(u.getStatus())).ifPresentOrElse(
                this::sendResetLink,
                () -> log.info("password reset asked for an address with no active account"));
    }

    /** Used by `POST /admin/users/{id}/reset-password` as well: the link is the only thing that leaves the server. */
    public void sendResetLink(Entities.UserEntity user) {
        mails.sendPasswordReset(user.getEmail(), jwt.issueReset(user.getId(), user.getPasswordHash()));
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        requireStrong(newPassword);
        String userId = jwt.verifyReset(token, id -> users.findById(id).map(Entities.UserEntity::getPasswordHash).orElse(null))
                .orElseThrow(() -> ApiException.unauthorized("That link has expired or has already been used."));
        var user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("That link has expired or has already been used."));
        setPassword(user, newPassword);
        refreshTokens.revokeAll(user.getId());                                  // every other session dies with the old password
        audit.record(user.getId(), "auth.resetPassword", "user", user.getId(), user.getSchoolId(), Map.of());
    }

    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        requireStrong(newPassword);
        var user = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
        if (!encoder.matches(currentPassword, user.getPasswordHash())) throw ApiException.badRequest("That is not your current password.");
        setPassword(user, newPassword);
        refreshTokens.revokeAll(user.getId());
        audit.record(user.getId(), "auth.changePassword", "user", user.getId(), user.getSchoolId(), Map.of());
    }

    public Entities.UserEntity require(String userId) { return users.findById(userId).orElseThrow(() -> ApiException.notFound("user")); }

    public static void requireStrong(String password) {
        if (password == null || password.trim().length() < DashboardDto.MIN_PASSWORD)
            throw ApiException.badRequest("Choose a password of at least " + DashboardDto.MIN_PASSWORD + " characters.");
    }

    private void setPassword(Entities.UserEntity user, String password) {
        user.setPasswordHash(encoder.encode(password));
        user.setMustChangePassword(false);
        user.setStatus("active");
        user.setUpdatedAt(Instant.now());
        users.save(user);
    }

    private void touchLogin(Entities.UserEntity user) { user.setLastLoginAt(Instant.now()); user.setUpdatedAt(Instant.now()); users.save(user); }

    public static String normalise(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
}

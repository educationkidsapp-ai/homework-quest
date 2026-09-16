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
import quest.server.mail.OutgoingMail;

/** Sign-in, session rotation and the three password flows (§5). Accounts are never created here — only invites do that. */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users; private final PasswordEncoder encoder; private final AdminJwtService jwt;
    /**
     * A real hash, of a value nobody knows, that an address with no account is checked against: a miss then costs the
     * same ~100 ms of BCrypt as a hit, so sign-in cannot be timed to find out which addresses exist. Made by the
     * configured encoder at startup so it is always in the format that encoder actually verifies.
     */
    private final String dummyHash;
    private final RefreshTokenService refreshTokens; private final SignInRateLimiter limiter; private final OutgoingMail mails;
    private final AuditService audit;

    public AuthService(UserRepository users, PasswordEncoder encoder, AdminJwtService jwt, RefreshTokenService refreshTokens,
                       SignInRateLimiter limiter, OutgoingMail mails, AuditService audit) {
        this.users = users; this.encoder = encoder; this.jwt = jwt; this.refreshTokens = refreshTokens;
        this.limiter = limiter; this.mails = mails; this.audit = audit;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
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

    /**
     * `disabled` and `invited` accounts are refused exactly like a wrong password, and count towards the rate limit.
     * An address with no account is hashed against {@link #dummyHash} so that it costs the same as one that exists.
     */
    private Entities.UserEntity authenticate(String email, String password, String ip) {
        String normalised = normalise(email);
        limiter.check(normalised, ip);
        var user = users.findByEmailIgnoreCase(normalised).orElse(null);
        boolean passwordMatches = encoder.matches(password == null ? "" : password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !"active".equals(user.getStatus()) || !passwordMatches) {
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

    /**
     * Always silent: whether or not the address has an account, the caller gets 204. The mail itself is queued and
     * leaves after the transaction commits, off the request thread, so the answer is not slower for an address that
     * exists ({@link OutgoingMail}).
     */
    @Transactional
    public void forgotPassword(String email) {
        users.findByEmailIgnoreCase(normalise(email)).filter(u -> "active".equals(u.getStatus())).ifPresentOrElse(
                this::sendResetLink,
                () -> log.info("password reset asked for an address with no active account"));
    }

    /** Used by `POST /admin/users/{id}/reset-password` as well: the link is the only thing that leaves the server. */
    public void sendResetLink(Entities.UserEntity user) {
        mails.passwordReset(user.getEmail(), jwt.issueReset(user.getId(), user.getPasswordHash()));
    }

    /**
     * A link only ever sets a password; it never changes what the account is. A `disabled` account stays disabled (and
     * is refused in the same words as an invalid link, so the answer says nothing about it), and an `invited` account
     * finishes through the invite instead — a reset must not be a second way in.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        requireStrong(newPassword);
        String userId = jwt.verifyReset(token, id -> users.findById(id).map(Entities.UserEntity::getPasswordHash).orElse(null))
                .orElseThrow(AuthService::staleLink);
        var user = users.findById(userId).orElseThrow(AuthService::staleLink);
        if (!"active".equals(user.getStatus())) throw staleLink();
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

    /**
     * `PATCH /me` (§6): a user's own display name, photo and language, and nothing else — role, status, school and
     * email are somebody else's to change. `photoUrl` goes through the same `SafeText` rule a school's logo does:
     * it is rendered into an `img src` by the dashboard and by the app's teacher island, so only `https://` is
     * accepted. An impersonated session never reaches here — `ReadOnlyGuard` refuses every write on a "View as…"
     * token before the handler runs.
     */
    @Transactional
    public Entities.UserEntity updateSelf(String userId, DashboardDto.UpdateMeRequest request) {
        var user = require(userId);
        if (request.displayName() != null) user.setDisplayName(quest.server.platform.SafeText.plainText(request.displayName(), "displayName", 120));
        if (request.photoUrl() != null) user.setPhotoUrl(quest.server.platform.SafeText.httpsUrl(request.photoUrl(), "photoUrl"));
        if (request.language() != null) user.setLanguage(language(request.language()));
        user.setUpdatedAt(java.time.Instant.now());
        return users.save(user);
    }

    /** The two languages §6 ships: the dashboard has an EN and an AR catalogue and nothing else to fall back to. */
    private static String language(String value) {
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.List.of("en", "ar").contains(lower)) throw ApiException.badRequest("language must be en or ar");
        return lower;
    }

    /** Counted the way the DTOs' `@Size(min = …)` counts it, and on the value that is actually stored. */
    public static void requireStrong(String password) {
        if (password == null || password.length() < DashboardDto.MIN_PASSWORD)
            throw ApiException.badRequest("Choose a password of at least " + DashboardDto.MIN_PASSWORD + " characters.");
    }

    /** Password only: `status` is the account's own lifecycle (invite, disable) and is never touched from here. */
    private void setPassword(Entities.UserEntity user, String password) {
        user.setPasswordHash(encoder.encode(password));
        user.setMustChangePassword(false);
        user.setUpdatedAt(Instant.now());
        users.save(user);
    }

    private static ApiException staleLink() { return ApiException.unauthorized("That link has expired or has already been used."); }

    private void touchLogin(Entities.UserEntity user) { user.setLastLoginAt(Instant.now()); user.setUpdatedAt(Instant.now()); users.save(user); }

    public static String normalise(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
}

package quest.server.classes;

import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.RefreshTokenService;
import quest.server.config.ApiException;

/**
 * The two edits every dashboard staff account has, in one place (MA1): rename / re-number / disable, and a new
 * one-time password. {@link TeachingStaffService} has had both since §6 and MA1 gives them to a coordinator and to a
 * department manager, whose screens the owner asked to work like the Teachers page — so the rules live here rather than
 * three times over.
 *
 * <p>Two of those rules are the ones worth not re-deriving: <strong>disabling an account revokes its refresh
 * tokens</strong>, so a session already open dies with it rather than living until its access token expires, and
 * <strong>nobody may disable her own account</strong>, which would lock the only Admin out of the deployment.
 *
 * <p>The teacher's own service still applies them inline, because her edit also carries a photo, her subjects and her
 * track; what is shared is what all three have.
 */
public final class StaffAccounts {
    private StaffAccounts() {}

    /** Only the fields that are present are written. Returns nothing: the caller answers its own account shape. */
    public static void apply(Principals.User caller, UserEntity user, String fullName, String phone, Boolean active,
                      RefreshTokenService refreshTokens) {
        if (fullName != null) user.setDisplayName(name(fullName));
        if (phone != null) user.setPhone(quest.server.platform.Phones.normalise(phone, "phone"));
        if (active != null) {
            if (caller.userId().equals(user.getId())) throw ApiException.badRequest("You cannot disable your own account.");
            user.setStatus(active ? "active" : "disabled");
            if (!active) refreshTokens.revokeAll(user.getId());                  // her sessions die with the account
        }
        user.setUpdatedAt(Instant.now());
    }

    /** A new one-time password on an account that already exists; the old one stops working immediately. */
    public static String reset(UserEntity user, PasswordEncoder encoder, TemporaryPasswords passwords, RefreshTokenService refreshTokens) {
        String temporary = passwords.generate();
        user.setPasswordHash(encoder.encode(temporary)); user.setMustChangePassword(true); user.setUpdatedAt(Instant.now());
        refreshTokens.revokeAll(user.getId());
        return temporary;
    }

    private static String name(String value) {
        String cleaned = value.trim();
        if (cleaned.isEmpty() || cleaned.length() > 80) throw ApiException.badRequest("fullName is 1–80 characters.");
        return cleaned;
    }
}

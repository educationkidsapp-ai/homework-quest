package quest.server.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The JSON the dashboard exchanges with `/auth/**`, `/me` and `/admin/users` — the Java mirror of
 * `quest.api.dashboard` in shared-api. Records, not the Kotlin types, so springdoc can describe them.
 */
public final class DashboardDto {
    private DashboardDto() {}

    /** Passwords are BCrypt-hashed and never shorter than this (§5). */
    public static final int MIN_PASSWORD = 10;

    public record SignInRequest(@NotBlank String email, @NotBlank String password) {}

    /** `refreshToken` is only issued by `/auth/sign-in`; the `/admin/auth/sign-in` alias keeps its own long session. */
    public record SignInResponse(String token, String email, long expiresAt, String role, String schoolId,
                                 String displayName, boolean mustChangePassword, String refreshToken) {}

    public record TokenPair(String token, String refreshToken, long expiresAt) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record ForgotPasswordRequest(@NotBlank @Email String email) {}
    public record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = MIN_PASSWORD) String newPassword) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = MIN_PASSWORD) String newPassword) {}

    /**
     * `impersonatedBy` is set on `GET /me` only, while an Admin is viewing as this user, and `platformName` there
     * too: §A's resolution order — the school's `theme.appName`, else the platform's name, else the name seeded by
     * `V5__flags_themes.sql`. It is what the dashboard puts in the title, the heading and the footer.
     */
    public record DashboardUser(String id, String email, String role, String schoolId, String status, String displayName,
                                String photoUrl, String language, boolean mustChangePassword, Long lastLoginAt, long createdAt,
                                String impersonatedBy, String platformName, String schoolName) {}

    public record MePermissions(String role, List<String> permissions, boolean readOnly) {}

    public static DashboardUser of(Entities.UserEntity u, String impersonatedBy) { return of(u, impersonatedBy, null, null); }

    /** `schoolName` saves the Admin's cross-school Users list (§6 screen 6) a second request per row. */
    public static DashboardUser of(Entities.UserEntity u, String impersonatedBy, String platformName, String schoolName) {
        return new DashboardUser(u.getId(), u.getEmail(), u.getRole(), u.getSchoolId(), u.getStatus(), u.getDisplayName(),
                u.getPhotoUrl(), u.getLanguage(), u.isMustChangePassword(),
                u.getLastLoginAt() == null ? null : u.getLastLoginAt().toEpochMilli(),
                u.getCreatedAt() == null ? 0 : u.getCreatedAt().toEpochMilli(), impersonatedBy, platformName, schoolName);
    }
}

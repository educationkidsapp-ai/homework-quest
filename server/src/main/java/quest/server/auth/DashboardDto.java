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
    /**
     * MH1: how long a <em>typed</em> telephone number may be. `users.phone` is 20 characters because that is E.164's
     * own maximum, and what is stored is the normalised number — but "+44 (0) 20 7946 0958" is 20 characters of
     * separators before it is normalised, so the request field is looser than the column and
     * {@link quest.server.platform.Phones} is what decides whether it is a number at all.
     */
    public static final int PHONE = 30;

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
                                String photoUrl, String phone, String language, boolean mustChangePassword, Long lastLoginAt, long createdAt,
                                String impersonatedBy, String platformName, String schoolName,
                                List<quest.server.classes.ClassDto.TeachingAssignment> assignments,
                                List<String> departments) {}

    public record MePermissions(String role, List<String> permissions, boolean readOnly) {}

    /**
     * `PATCH /me`: the three things any dashboard user may change about herself. Only the fields that are present
     * are written. Everything else about an account — role, status, school, email — is somebody else's to change
     * (`PATCH /admin/users/{id}`), and a TEACHER's teaching profile is `PUT /teacher/profile`.
     */
    public record UpdateMeRequest(@Size(max = 120) String displayName, String photoUrl, String language,
                                  @Size(max = PHONE) String phone) {}

    /**
     * MH1: a parent's own account as the app sees it (`GET`/`PATCH /parent/me`). She has no `users` row — she is a
     * `parents` row created from her Firebase identity — so this is the whole of it: the address she signed up with
     * and the mobile number the department manager reaches her on. `phone` is the only field she may change.
     */
    public record ParentProfile(String parentId, String email, String phone) {}

    /** `PATCH /parent/me`: an empty `phone` clears the number, which is how she takes it back off the directory. */
    public record UpdateParentRequest(@Size(max = PHONE) String phone) {}

    public static DashboardUser of(Entities.UserEntity u, String impersonatedBy) { return of(u, impersonatedBy, null, null); }

    /**
     * `GET /me` for a TEACHER: her account with what she teaches, so her navigation needs no second request — and, for a
     * MANAGERIAL user, the departments she runs (RM1, DR5), which is the same argument for the same screen-building job.
     * Both are null for every role they do not belong to.
     */
    public static DashboardUser of(Entities.UserEntity u, String impersonatedBy, String platformName, String schoolName,
                                   List<quest.server.classes.ClassDto.TeachingAssignment> assignments,
                                   List<String> departments) {
        var base = of(u, impersonatedBy, platformName, schoolName);
        return new DashboardUser(base.id(), base.email(), base.role(), base.schoolId(), base.status(), base.displayName(),
                base.photoUrl(), base.phone(), base.language(), base.mustChangePassword(), base.lastLoginAt(), base.createdAt(),
                base.impersonatedBy(), base.platformName(), base.schoolName(), assignments, departments);
    }

    /** `schoolName` saves the Admin's cross-school Users list (§6 screen 6) a second request per row. */
    public static DashboardUser of(Entities.UserEntity u, String impersonatedBy, String platformName, String schoolName) {
        return new DashboardUser(u.getId(), u.getEmail(), u.getRole(), u.getSchoolId(), u.getStatus(), u.getDisplayName(),
                u.getPhotoUrl(), u.getPhone(), u.getLanguage(), u.isMustChangePassword(),
                u.getLastLoginAt() == null ? null : u.getLastLoginAt().toEpochMilli(),
                u.getCreatedAt() == null ? 0 : u.getCreatedAt().toEpochMilli(), impersonatedBy, platformName, schoolName, null, null);
    }
}

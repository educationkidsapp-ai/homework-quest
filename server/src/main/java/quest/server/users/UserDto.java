package quest.server.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import quest.server.auth.DashboardDto;

/** The Java mirror of the user/invite half of `quest.api.dashboard` (§5, §6 screen 6). */
public final class UserDto {
    private UserDto() {}

    /** `PATCH /admin/users/{id}`: disable/enable, rename, or move to another role inside the same school. */
    public record UpdateUserRequest(String status, @Size(max = 120) String displayName, String role) {}

    /** The teacher half of an invite, saved the moment the invite goes out so the account is complete when accepted. */
    public record TeacherProfileInput(@Size(max = 120) String displayName, String photoUrl, List<String> subjects,
                                      String curriculum, List<Integer> grades, String bioEn, String bioAr) {}

    public record CreateInviteRequest(@NotBlank @Email String email, @NotBlank String role, TeacherProfileInput teacherProfile) {}

    public record Invite(String id, String email, String role, String schoolId, String invitedBy, long expiresAt, Long acceptedAt, long createdAt) {}

    /** What the public accept-invite page shows before the person picks a password. */
    public record InviteInfo(String email, String role, String schoolName, long expiresAt) {}

    public record AcceptInviteRequest(@NotBlank @Size(min = DashboardDto.MIN_PASSWORD) String password, @Size(max = 120) String displayName) {}
}

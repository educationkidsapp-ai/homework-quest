package quest.server.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import quest.server.config.ApiException;

@RestController
@RequestMapping("/admin/auth")
@Tag(name = "Auth", description = "Dashboard sign-in")
public class AdminAuthController {
    public record SignIn(@NotBlank String email, @NotBlank String password) {}
    /** `quest.api.dashboard.SignInResponse`; `token`/`email`/`expiresAt` keep `webAdmin/`'s `AdminSession` working. */
    public record Session(String token, String email, long expiresAt, String role, String schoolId, String displayName, boolean mustChangePassword) {}

    private final UserRepository users; private final PasswordEncoder encoder; private final AdminJwtService jwt;
    public AdminAuthController(UserRepository users, PasswordEncoder encoder, AdminJwtService jwt) { this.users = users; this.encoder = encoder; this.jwt = jwt; }

    /** Email + password against `users` (accounts come from the seed script or an invite, never self-registration). */
    @PostMapping(value = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Session signIn(@RequestBody @Valid SignIn body) {
        var user = users.findByEmailIgnoreCase(body.email().trim())
                .filter(u -> !"disabled".equals(u.getStatus()))
                .filter(u -> encoder.matches(body.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Wrong email or password."));
        user.setLastLoginAt(Instant.now()); user.setUpdatedAt(Instant.now()); users.save(user);
        var issued = jwt.issue(user.getId(), user.getEmail(), user.getRole(), user.getSchoolId());
        return new Session(issued.token(), user.getEmail(), issued.expiresAt().toEpochMilli(), user.getRole(), user.getSchoolId(), user.getDisplayName(), user.isMustChangePassword());
    }
}

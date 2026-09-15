package quest.server.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/auth")
@Tag(name = "Auth", description = "Dashboard sign-in")
public class AdminAuthController {
    public record SignIn(@NotBlank String email, @NotBlank String password) {}
    /**
     * `webAdmin/`'s `AdminSession`. It stays byte-for-byte what it was: that client decodes with
     * `ignoreUnknownKeys = false`, so no field may be added here — the dashboard calls `/auth/sign-in` instead,
     * which answers with the same fields plus `refreshToken`.
     */
    public record Session(String token, String email, long expiresAt, String role, String schoolId, String displayName, boolean mustChangePassword) {}

    private final AuthService auth; private final AdminJwtService jwt;
    public AdminAuthController(AuthService auth, AdminJwtService jwt) { this.auth = auth; this.jwt = jwt; }

    /**
     * Email + password against `users` (accounts come from the seed script or an invite, never self-registration).
     * The token lives `quest.auth.jwt-hours`, because `webAdmin/` cannot refresh; D9 retires this with that module.
     */
    @PostMapping(value = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public Session signIn(@RequestBody @Valid SignIn body, HttpServletRequest request) {
        var user = auth.signInLegacy(body.email(), body.password(), AuthController.clientIp(request));
        var issued = jwt.issue(user.getId(), user.getEmail(), user.getRole(), user.getSchoolId());
        return new Session(issued.token(), user.getEmail(), issued.expiresAt().toEpochMilli(), user.getRole(), user.getSchoolId(), user.getDisplayName(), user.isMustChangePassword());
    }
}

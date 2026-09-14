package quest.server.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import quest.server.config.ApiException;

@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {
    public record SignIn(@NotBlank String email, @NotBlank String password) {}
    public record Session(String token, String email, long expiresAt) {}

    private final AdminUserRepository admins; private final PasswordEncoder encoder; private final AdminJwtService jwt;
    public AdminAuthController(AdminUserRepository admins, PasswordEncoder encoder, AdminJwtService jwt) { this.admins = admins; this.encoder = encoder; this.jwt = jwt; }

    /** Email + password against `admin_users` (accounts come from the seed script, never self-registration). */
    @PostMapping("/sign-in")
    public Session signIn(@RequestBody @Valid SignIn body) {
        var admin = admins.findByEmailIgnoreCase(body.email().trim()).filter(a -> encoder.matches(body.password(), a.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Wrong email or password."));
        var issued = jwt.issue(admin.getId(), admin.getEmail());
        return new Session(issued.token(), admin.getEmail(), issued.expiresAt().toEpochMilli());
    }
}

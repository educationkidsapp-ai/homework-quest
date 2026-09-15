package quest.server.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.config.ApiException;

/**
 * Dashboard auth (§5): sign-in with a short access token and a rotating refresh token, the three password flows,
 * and who the caller is. `POST /admin/auth/sign-in` lives on in {@link AdminAuthController} for `webAdmin/`.
 */
@RestController
@Tag(name = "Auth", description = "Dashboard sign-in, sessions and passwords")
public class AuthController {
    private final AuthService auth; private final Permissions permissions;
    public AuthController(AuthService auth, Permissions permissions) { this.auth = auth; this.permissions = permissions; }

    @PostMapping(value = "/auth/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public DashboardDto.SignInResponse dashboardSignIn(@RequestBody @Valid DashboardDto.SignInRequest body, HttpServletRequest request) {
        return auth.signIn(body.email(), body.password(), clientIp(request));
    }

    @PostMapping(value = "/auth/refresh", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public DashboardDto.TokenPair refresh(@RequestBody @Valid DashboardDto.RefreshRequest body) { return auth.refresh(body.refreshToken()); }

    @PostMapping(value = "/auth/sign-out", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("permitAll")
    public void signOut(@RequestBody @Valid DashboardDto.RefreshRequest body) { auth.signOut(body.refreshToken()); }

    /** Always 204, so the page cannot be used to find out which addresses have an account. */
    @PostMapping(value = "/auth/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("permitAll")
    public void forgotPassword(@RequestBody @Valid DashboardDto.ForgotPasswordRequest body) { auth.forgotPassword(body.email()); }

    @PostMapping(value = "/auth/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("permitAll")
    public void resetPassword(@RequestBody @Valid DashboardDto.ResetPasswordRequest body) { auth.resetPassword(body.token(), body.newPassword()); }

    /** Clears `mustChangePassword`, so this is what a first login goes through. */
    @PostMapping(value = "/auth/change-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('auth.changePassword')")
    public void changePassword(@AuthenticationPrincipal Principals.User caller, @RequestBody @Valid DashboardDto.ChangePasswordRequest body) {
        auth.changePassword(require(caller).userId(), body.currentPassword(), body.newPassword());
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('me.read')")
    public DashboardDto.DashboardUser me(@AuthenticationPrincipal Principals.User caller) {
        var principal = require(caller);
        return DashboardDto.of(auth.require(principal.userId()), principal.impersonatedBy());
    }

    /** The keys of `permissions.json` the caller's role holds; the dashboard hides what the server would refuse. */
    @GetMapping(value = "/me/permissions", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('me.permissions')")
    public DashboardDto.MePermissions myPermissions(@AuthenticationPrincipal Principals.User caller) {
        var principal = require(caller);
        return new DashboardDto.MePermissions(principal.role(), permissions.forRole(principal.role()), principal.isImpersonated());
    }

    private static Principals.User require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller;
    }

    /** Cloud Run puts the caller in `X-Forwarded-For`; the first entry is the client. */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }
}

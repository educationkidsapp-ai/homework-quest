package quest.server.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.config.ApiException;
import quest.server.platform.ThemeService;

/**
 * Dashboard auth (§5): sign-in with a short access token and a rotating refresh token, the three password flows,
 * and who the caller is. `POST /admin/auth/sign-in` lives on in {@link AdminAuthController} for `webAdmin/`.
 */
@RestController
@Tag(name = "Auth", description = "Dashboard sign-in, sessions and passwords")
public class AuthController {
    private final AuthService auth; private final Permissions permissions; private final ThemeService themes;
    private final quest.server.schools.SchoolService schools; private final quest.server.classes.SectionService sections;
    public AuthController(AuthService auth, Permissions permissions, ThemeService themes,
                          quest.server.schools.SchoolService schools, quest.server.classes.SectionService sections) {
        this.auth = auth; this.permissions = permissions; this.themes = themes; this.schools = schools; this.sections = sections;
    }

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

    /** `platformName` follows §A's order: the school's `theme.appName`, then the platform's name, then the seed. */
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('me.read')")
    public DashboardDto.DashboardUser me(@AuthenticationPrincipal Principals.User caller) {
        var principal = require(caller);
        var user = auth.require(principal.userId());
        // The platform ADMIN has no school, and an immutable `Map` refuses even to be *asked* about a null key.
        String schoolName = user.getSchoolId() == null ? null
                : schools.namesOf(java.util.List.of(user.getSchoolId())).get(user.getSchoolId());
        // V7: a teacher's navigation is her assignments (`docs/teacher-flow.md` §2), so they arrive with her account
        // rather than as a second request. Two statements, and none at all for ADMIN and MANAGERIAL.
        var assignments = "TEACHER".equals(user.getRole()) ? sections.assignmentsOfTeacher(user.getId()) : null;
        return DashboardDto.of(user, principal.impersonatedBy(), themes.displayName(user.getSchoolId()), schoolName, assignments);
    }

    /**
     * §6: a dashboard user changes her own name, photo and language here — and nothing else about her account.
     * Answers the same `DashboardUser` `GET /me` does, so the shell can re-render from the response.
     */
    @PatchMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('me.update')")
    public DashboardDto.DashboardUser updateMe(@AuthenticationPrincipal Principals.User caller,
                                               @RequestBody @Valid DashboardDto.UpdateMeRequest body) {
        var principal = require(caller);
        var user = auth.updateSelf(principal.userId(), body);
        String schoolName = user.getSchoolId() == null ? null
                : schools.namesOf(java.util.List.of(user.getSchoolId())).get(user.getSchoolId());
        return DashboardDto.of(user, principal.impersonatedBy(), themes.displayName(user.getSchoolId()), schoolName);
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

    /**
     * The peer the request really came from, never a header the caller wrote. `X-Forwarded-For` is appended to by every
     * hop, so its first entry is whatever the client typed — reading it let one caller rotate through 10.0.0.1, 10.0.0.2…
     * and get a fresh rate-limit bucket per attempt. `server.forward-headers-strategy: native` puts Tomcat's
     * `RemoteIpValve` in front of us instead: it walks the header from the right, skips the hops it trusts
     * (`server.tomcat.remoteip.internal-proxies`) and leaves the rightmost untrusted one — the address Cloud Run's front
     * end appended — in `getRemoteAddr()`. Entries a caller adds on the left are ignored.
     */
    public static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }
}

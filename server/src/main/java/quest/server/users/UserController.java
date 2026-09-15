package quest.server.users;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.DashboardDto;
import quest.server.auth.Principals;
import quest.server.config.ApiException;

/** §6 screen 6 (Users) and the invite half of §5. Everything here is scoped to the caller's school unless they are Admin. */
@RestController
@Tag(name = "Dashboard users", description = "Dashboard accounts, invites and View as…")
public class UserController {
    private final UserService users; private final InviteService invites;
    public UserController(UserService users, InviteService invites) { this.users = users; this.invites = invites; }

    @GetMapping(value = "/admin/users", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('user.read')")
    public List<DashboardDto.DashboardUser> listUsers(@AuthenticationPrincipal Principals.User caller,
                                                      @RequestParam(required = false) String role,
                                                      @RequestParam(required = false) String schoolId,
                                                      @RequestParam(required = false) String status) {
        return users.list(require(caller), role, schoolId, status);
    }

    @PatchMapping(value = "/admin/users/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('user.write')")
    public DashboardDto.DashboardUser updateUser(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                 @RequestBody @Valid UserDto.UpdateUserRequest body) {
        return users.update(require(caller), id, body);
    }

    /** Emails the person a reset link; nothing about the account changes until they use it. */
    @PostMapping(value = "/admin/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('user.write')")
    public void resetUserPassword(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        users.sendPasswordReset(require(caller), id);
    }

    /** §5 "View as…": a read-only token for a Teacher or Managerial user; every request with it is audit-logged. */
    @PostMapping(value = "/admin/users/{id}/impersonate", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('user.impersonate')")
    public DashboardDto.SignInResponse impersonate(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return users.impersonate(require(caller), id);
    }

    /** ADMIN only (§5): an account created outright with a password to hand over, which its owner must change at the first sign-in. */
    @PostMapping(value = "/admin/schools/{id}/users", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('user.create')")
    public DashboardDto.DashboardUser createUser(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                 @RequestBody @Valid UserDto.CreateUserRequest body) {
        return users.create(require(caller), id, body);
    }

    @PostMapping(value = "/admin/schools/{id}/invites", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('user.invite')")
    public UserDto.Invite createInvite(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                       @RequestBody @Valid UserDto.CreateInviteRequest body) {
        return invites.create(require(caller), id, body);
    }

    @GetMapping(value = "/invites/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public UserDto.InviteInfo inviteInfo(@PathVariable String token) { return invites.info(token); }

    @PostMapping(value = "/invites/{token}/accept", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public DashboardDto.SignInResponse acceptInvite(@PathVariable String token, @RequestBody @Valid UserDto.AcceptInviteRequest body) {
        return invites.accept(token, body);
    }

    private static Principals.User require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller;
    }
}

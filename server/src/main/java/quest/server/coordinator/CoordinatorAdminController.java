package quest.server.coordinator;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.tenancy.CoordinatorScope;

/**
 * `/admin/coordinators/**` (R2): how a coordinator comes to exist, and how what she coordinates is changed.
 *
 * <p><strong>ADMIN only</strong> — `coordinator.manage` is granted to that role alone, so a coordinator cannot widen
 * her own scope and a MANAGERIAL user cannot mint one (the department managers' own screens are RM1). The refusal is
 * `permissions.json`'s, checked route by route by `PreAuthorizeCoverageTest`.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, and is named in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE` beside {@code TeacherAdminController} for its exact reason: this is how a
 * coordinator comes to exist at all and how one locked out of her account is given a new password, so a flag that
 * could switch it off is one nobody could turn back on without a database session.
 *
 * <p><strong>The temporary password is in the response body and nowhere else</strong>
 * ({@link quest.server.classes.TemporaryPasswords}), and the route that answers one is a POST, so it never reaches a
 * URL, an access log or a browser history.
 */
@RestController
@Tag(name = "Coordinators", description = "Coordinator accounts and the subjects and tracks they supervise")
public class CoordinatorAdminController {
    private final CoordinatorAdminService coordinators;

    public CoordinatorAdminController(CoordinatorAdminService coordinators) { this.coordinators = coordinators; }

    @GetMapping(value = "/admin/coordinators", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.manage')")
    public List<CoordinatorDto.CoordinatorAccount> coordinators() { return coordinators.list(); }

    /** 201 with the one and only sight of the password; it cannot be read again. */
    @PostMapping(value = "/admin/coordinators", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('coordinator.manage')")
    public CoordinatorDto.CoordinatorCreated createCoordinator(@AuthenticationPrincipal Principals.User caller,
                                                               @RequestBody @Valid CoordinatorDto.CreateCoordinatorRequest body) {
        return coordinators.create(CoordinatorScope.require(caller), body);
    }

    /** Replaces her whole set; a subject named twice for the same track is a 400 and nothing is written. */
    @PutMapping(value = "/admin/coordinators/{id}/scopes", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.manage')")
    public CoordinatorDto.CoordinatorAccount setCoordinatorScopes(@AuthenticationPrincipal Principals.User caller,
                                                                  @PathVariable String id,
                                                                  @RequestBody @Valid CoordinatorDto.ScopesRequest body) {
        return coordinators.setScopes(CoordinatorScope.require(caller), id, body);
    }
}

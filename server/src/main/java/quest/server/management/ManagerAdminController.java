package quest.server.management;

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
import quest.server.tenancy.ManagerScope;

/**
 * `/admin/managers/**` (RM1): how a department manager comes to exist, and how her department is changed.
 *
 * <p><strong>ADMIN only</strong> — `manager.manage` is granted to that role alone, so a manager cannot widen her own
 * department and cannot mint a second one. The refusal is `permissions.json`'s, checked route by route by
 * `PreAuthorizeCoverageTest`; the matcher over `/admin/**` lets a MANAGERIAL caller knock, and the key is what turns her
 * away.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, and is named in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE` beside {@code CoordinatorAdminController} for its exact reason: this is how a
 * manager comes to exist at all, so a flag that could switch it off is one nobody could turn back on without a database
 * session.
 */
@RestController
@Tag(name = "Managers", description = "Department manager accounts and the departments they manage")
public class ManagerAdminController {
    private final ManagerAdminService managers;

    public ManagerAdminController(ManagerAdminService managers) { this.managers = managers; }

    @GetMapping(value = "/admin/managers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('manager.manage')")
    public List<ManagementDto.ManagerAccount> managers() { return managers.list(); }

    /** 201 with the one and only sight of the password; it cannot be read again. */
    @PostMapping(value = "/admin/managers", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('manager.manage')")
    public ManagementDto.ManagerCreated createManager(@AuthenticationPrincipal Principals.User caller,
                                                     @RequestBody @Valid ManagementDto.CreateManagerRequest body) {
        return managers.create(ManagerScope.require(caller), body);
    }

    /** Replaces her whole set of departments; a track named twice is a 400 and nothing is written. */
    @PutMapping(value = "/admin/managers/{id}/scopes", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('manager.manage')")
    public ManagementDto.ManagerAccount setManagerDepartments(@AuthenticationPrincipal Principals.User caller,
                                                              @PathVariable String id,
                                                              @RequestBody @Valid ManagementDto.DepartmentsRequest body) {
        return managers.setDepartments(ManagerScope.require(caller), id, body);
    }
}

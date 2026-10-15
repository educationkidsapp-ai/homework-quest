package quest.server.floors;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.FloorScope;

/**
 * `/admin/floors/**` and `/admin/floor-supervisors/**` (feature 20): how a floor and the people who hold it come to
 * exist.
 *
 * <p><strong>ADMIN only</strong> — `floor.manage` is granted to that role alone, so a floor supervisor cannot widen
 * her own scope and a MANAGERIAL user cannot mint one. The refusal is `permissions.json`'s, checked route by route by
 * `PreAuthorizeCoverageTest`.
 *
 * <p><strong>Behind `floorSupervisor`</strong>, unlike {@code CoordinatorAdminController}, which carries no flag. The
 * argument that exempts that class — "a flag over the screen that creates the role is one nobody could turn back on"
 * — does not apply to a role that does not exist yet: a school with the flag off has no floor supervisor to lock out,
 * and the flag is the one switch that decides whether the school uses floors at all. `FeatureFlagCoverageTest`
 * therefore needs no new exemption.
 *
 * <p><strong>The temporary password is in the response body and nowhere else</strong>
 * ({@link quest.server.classes.TemporaryPasswords}), and the route that answers one is a POST, so it never reaches a
 * URL, an access log or a browser history.
 */
@RestController
@FeatureFlag(FlagKeys.FLOOR_SUPERVISOR)
@Tag(name = "Floors", description = "Floors, the classes on them and the supervisors who hold them")
public class FloorAdminController {
    private final FloorAdminService floors;

    public FloorAdminController(FloorAdminService floors) { this.floors = floors; }

    @GetMapping(value = "/admin/floors", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.manage')")
    public List<FloorDto.Floor> floors() { return floors.list(); }

    /** 201 with the floor as it now stands — its classes and its supervisors, so the page redraws from one response. */
    @PostMapping(value = "/admin/floors", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('floor.manage')")
    public FloorDto.Floor createFloor(@AuthenticationPrincipal Principals.User caller,
                                      @RequestBody @Valid FloorDto.CreateFloorRequest body) {
        return floors.create(FloorScope.require(caller), body);
    }

    /** A present `classIds` or `supervisorIds` replaces the whole set; a class already on another floor is a 409. */
    @PatchMapping(value = "/admin/floors/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.manage')")
    public FloorDto.Floor updateFloor(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                      @RequestBody @Valid FloorDto.UpdateFloorRequest body) {
        return floors.update(FloorScope.require(caller), id, body);
    }

    @DeleteMapping(value = "/admin/floors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('floor.manage')")
    public void deleteFloor(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        floors.delete(FloorScope.require(caller), id);
    }

    @GetMapping(value = "/admin/floor-supervisors", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.manage')")
    public List<FloorDto.FloorSupervisorAccount> floorSupervisors() { return floors.supervisors(); }

    /** 201 with the one and only sight of the password; it cannot be read again. */
    @PostMapping(value = "/admin/floor-supervisors", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('floor.manage')")
    public FloorDto.FloorSupervisorCreated createFloorSupervisor(@AuthenticationPrincipal Principals.User caller,
                                                                 @RequestBody @Valid FloorDto.CreateFloorSupervisorRequest body) {
        return floors.createSupervisor(FloorScope.require(caller), body);
    }

    @PatchMapping(value = "/admin/floor-supervisors/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.manage')")
    public FloorDto.FloorSupervisorAccount updateFloorSupervisor(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                                 @RequestBody @Valid FloorDto.UpdateFloorSupervisorRequest body) {
        return floors.updateSupervisor(FloorScope.require(caller), id, body);
    }

    @PostMapping(value = "/admin/floor-supervisors/{id}/reset-password", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.manage')")
    public quest.server.classes.ClassDto.TemporaryPassword resetFloorSupervisorPassword(
            @AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return floors.resetPassword(FloorScope.require(caller), id);
    }
}

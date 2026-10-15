package quest.server.floors;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingDto;
import quest.server.tenancy.FloorScope;

/**
 * `/floor/**` — the floor supervisor's area (feature 20), the shape `/coordinator/**` has one role over.
 *
 * <p><strong>Every route here reads.</strong> Her writes are communication and live next door on
 * {@link FloorChatController}; there is no POST, PUT, PATCH or DELETE on this class at all, and
 * `FloorScopeArchitectureTest` asserts it rather than leaving it to review. She holds no `*.write` key in
 * `permissions.json`, and `SecurityConfig` does not let her knock on `/teacher/**`, `/admin/**`, `/coordinator/**` or
 * `/management/**` in the first place.
 *
 * <p><strong>Scope.</strong> The namespace names no school and no floor: hers come from her token and her
 * `floor_supervisors` rows, through {@link FloorScope}. `id` is the only parameter that names a row and it is
 * resolved through {@link FloorScope#requireChild} — 404 for another school's, 403 for another floor's.
 *
 * <p><strong>One flag on the class.</strong> `floorSupervisor` is what decides whether a school has floors at all, so
 * every route is a 404 while it is off — including Home, whose staff half also depends on `staffAttendance` and is
 * simply absent (`board: null`) when that one is off rather than taking the rest of the screen down with it.
 */
@RestController
@FeatureFlag(FlagKeys.FLOOR_SUPERVISOR)
@Tag(name = "Floor supervisor", description = "A floor supervisor's view of the classes, staff and children of her floors")
public class FloorController {
    private final FloorService floors;

    public FloorController(FloorService floors) { this.floors = floors; }

    /** Her floors and their three numbers — what the Home screen leads with, in one request. */
    @GetMapping(value = "/floor/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.read')")
    public FloorDto.FloorMe floorMe(@AuthenticationPrincipal Principals.User caller) {
        return floors.me(FloorScope.require(caller));
    }

    /** Today in the school's zone: the staff board for the people she leads, and the children's register per class. */
    @GetMapping(value = "/floor/home", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.read')")
    public FloorDto.FloorHome floorHome(@AuthenticationPrincipal Principals.User caller) {
        return floors.home(FloorScope.require(caller));
    }

    /** The teachers on her floors: every one holding an assignment there, with the sections and subjects. */
    @GetMapping(value = "/floor/teachers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.read')")
    public List<FloorDto.FloorTeacher> floorTeachers(@AuthenticationPrincipal Principals.User caller) {
        return floors.teachers(FloorScope.require(caller));
    }

    /** A card per class on her floors — grade, track, the floor and the class size. */
    @GetMapping(value = "/floor/classes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.read')")
    public List<FloorDto.FloorClass> floorClasses(@AuthenticationPrincipal Principals.User caller) {
        return floors.classes(FloorScope.require(caller));
    }

    /** The children on her floors, filtered by `q` against the name and the addresses the school holds. */
    @GetMapping(value = "/floor/children", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.read')")
    public List<FloorDto.FloorChild> floorChildren(@AuthenticationPrincipal Principals.User caller,
                                                   @RequestParam(required = false) String q) {
        return floors.children(FloorScope.require(caller), q);
    }

    /** §7's child page for a child on one of her floors, read-only and whole — every subject of her section. */
    @GetMapping(value = "/floor/children/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('floor.read')")
    public GradingDto.ChildReport floorChild(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return floors.child(FloorScope.require(caller), id);
    }
}

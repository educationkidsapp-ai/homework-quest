package quest.server.classes;

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
import quest.server.tenancy.TeacherScope;

/**
 * The teacher's own half of the roster (`docs/teacher-flow.md` §3, Class page → Children): the same three operations
 * the Admin has, on her classes only, and only while `teacher.rosterEdit` is on — a school that wants the office to
 * own its rosters switches the flag off and these routes are 404 for everyone.
 *
 * <p>Every handler goes through {@link TeacherScope}, so a class she holds no assignment on is a 403 and another
 * school's is a 404, whatever the path says. `PATCH` names the class as well as the child so the check happens on
 * the way in rather than after the row is loaded.
 */
@RestController
@FeatureFlag(FlagKeys.TEACHER_ROSTER_EDIT)
@Tag(name = "Teacher roster", description = "A teacher's own class rosters")
public class TeacherRosterController {
    private final RosterService rosters;

    public TeacherRosterController(RosterService rosters) { this.rosters = rosters; }

    @GetMapping(value = "/teacher/classes/{classId}/children", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.teacher')")
    public List<ClassDto.RosterChild> myClassChildren(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId) {
        return rosters.list(TeacherScope.require(caller), classId);
    }

    /**
     * The children the attach sheet offers: her school's, on no roster at all, and of this section's curriculum and
     * grade. Until this existed the only list of unplaced children was `GET /admin/children?unassigned=true`, which
     * is `roster.read` and so ADMIN/MANAGERIAL — a teacher could attach a child she already had the id of and had
     * no way to find one.
     */
    @GetMapping(value = "/teacher/classes/{classId}/children/unassigned", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.teacher')")
    public List<ClassDto.RosterChild> unassignedForMyClass(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId) {
        return rosters.unassignedFor(TeacherScope.require(caller), classId);
    }

    @PostMapping(value = "/teacher/classes/{classId}/children", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('roster.teacher')")
    public ClassDto.RosterChild addToMyClass(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                             @RequestBody @Valid ClassDto.CreateRosterChildRequest body) {
        return rosters.add(TeacherScope.require(caller), classId, body);
    }

    /**
     * The teacher's own half of §3's attach: a child a parent registered in the app, put onto one of <em>her</em>
     * sections so she sees the child's work and the child sees her lessons once rather than once per section.
     * {@link TeacherScope} makes a class she holds no assignment on a 403 and another school's a 404.
     */
    @PostMapping(value = "/teacher/classes/{classId}/roster/attach", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.teacher')")
    public ClassDto.RosterChild attachToMyClass(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                                @RequestBody @Valid ClassDto.AttachChildRequest body) {
        return rosters.attach(TeacherScope.require(caller), classId, body.childId());
    }

    @DeleteMapping(value = "/teacher/classes/{classId}/roster/{childId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.teacher')")
    public ClassDto.RosterChild detachFromMyClass(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                                  @PathVariable String childId) {
        return rosters.detach(TeacherScope.require(caller), classId, childId);
    }

    @PatchMapping(value = "/teacher/classes/{classId}/children/{childId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.teacher')")
    public ClassDto.RosterChild updateInMyClass(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                                @PathVariable String childId,
                                                @RequestBody @Valid ClassDto.UpdateRosterChildRequest body) {
        // A teacher moves a child between her own classes, never out of them: the class in the path has to be one of
        // hers and the child has to be on its roster, and a `classId` in the body is checked the same way again.
        return rosters.updateIn(TeacherScope.require(caller), classId, childId, body);
    }
}

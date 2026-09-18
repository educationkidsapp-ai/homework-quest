package quest.server.classes;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.tenancy.TeacherScope;

/**
 * The Admin's Teachers screen (`docs/prompts/dashboard-first-one-school.md` §6): an account created in the room with
 * a password read out once, and the assignments that decide everything that teacher can then reach.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, and is named in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE`: this is how a teacher comes to exist at all, and how a teacher locked
 * out of her account gets back in. A flag that could switch it off is one nobody could turn back on without a
 * database session — the same argument that exempts `FlagController` itself.
 *
 * <p><strong>The temporary password is in the response body and nowhere else</strong> ({@link TemporaryPasswords}):
 * not in the audit row, not in a log line, not in a mail. Both routes that answer one are POSTs, so no proxy or
 * browser history ever holds it in a URL.
 */
@RestController
@Tag(name = "Teachers", description = "Teacher accounts and teaching assignments")
public class TeacherAdminController {
    private final TeachingStaffService staff;

    public TeacherAdminController(TeachingStaffService staff) { this.staff = staff; }

    @GetMapping(value = "/admin/teachers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.read')")
    public List<ClassDto.TeacherAccount> teachers() { return staff.list(); }

    /** 201 with the one and only sight of the password; it cannot be read again. */
    @PostMapping(value = "/admin/teachers", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('teacher.manage')")
    public ClassDto.TeacherCreated createTeacher(@AuthenticationPrincipal Principals.User caller,
                                                 @RequestBody @Valid ClassDto.CreateTeacherRequest body) {
        return staff.create(TeacherScope.require(caller), body);
    }

    @PatchMapping(value = "/admin/teachers/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.manage')")
    public ClassDto.TeacherAccount updateTeacher(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                 @RequestBody @Valid ClassDto.UpdateTeacherRequest body) {
        return staff.update(TeacherScope.require(caller), id, body);
    }

    @PostMapping(value = "/admin/teachers/{id}/reset-password", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.manage')")
    public ClassDto.TemporaryPassword resetTeacherPassword(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return staff.resetPassword(TeacherScope.require(caller), id);
    }

    /** Replaces her whole set. A (class, subject) a colleague holds is 409 naming her (`docs/teacher-flow.md` §2). */
    @PutMapping(value = "/admin/teachers/{id}/assignments", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.manage')")
    public List<ClassDto.TeachingAssignment> setAssignments(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                            @RequestBody @Valid ClassDto.AssignmentsRequest body) {
        return staff.setAssignments(TeacherScope.require(caller), id, body);
    }
}

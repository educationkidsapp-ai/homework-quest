package quest.server.management;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.tenancy.ManagerScope;

/**
 * RM5 (DR7): the department's <strong>people</strong> — the staff register she takes, and the directory of who is in
 * her department. `/management/staff-attendance/**` and `/management/people/**`.
 *
 * <p><strong>The one write under `/management`.</strong> `PUT /management/staff-attendance` is the first thing a
 * manager writes in this namespace at all, so it is named in `ManagerScopeArchitectureTest.ALLOWED_WRITES` with the
 * argument for it rather than merely allowed to appear. It writes about her <em>staff</em> and never about a child, a
 * lesson or a class, which is why the read-only rule over the rest of the area still holds.
 *
 * <p><strong>The day rule</strong> lives in {@link StaffAttendanceService}: a day may be marked when it is a teaching
 * day of the school and is not after today in the school's own zone. Both refusals are 400, and the roster's own
 * `editable` flag is what a client uses to offer the day in the first place.
 *
 * <p><strong>Two keys, both ADMIN + MANAGERIAL.</strong> `management.staff.attendance` covers the register, reads and
 * write together — an owner who gives a manager the roster gives her the marking, since a register nobody may mark is
 * an empty screen — and `management.people` covers the three directory reads. No other role holds either
 * (`permissions.json`), and `/management/**` is ADMIN + MANAGERIAL in `SecurityConfig` before that.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, for {@code ManagementController}'s reason and
 * {@code AttendanceController}'s: taking register is not an optional feature and {@link quest.server.flags.FlagKeys}
 * has no key for it, and a directory of the people a role manages is that role's dashboard rather than a feature of
 * it. Named in `FeatureFlagCoverageTest.INFRASTRUCTURE` beside its two neighbours.
 */
@RestController
@Tag(name = "Management people", description = "A department manager's staff register and her people directory")
public class ManagementPeopleController {
    private final StaffAttendanceService register; private final PeopleDirectoryService directory;

    public ManagementPeopleController(StaffAttendanceService register, PeopleDirectoryService directory) {
        this.register = register; this.directory = directory;
    }

    // ---------------------------------------------------------------- the staff register

    /** The department's teachers and coordinators for one day, with that day's status. `day` absent is today. */
    @GetMapping(value = "/management/staff-attendance", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.staff.attendance')")
    public ManagementDto.StaffAttendanceDay staffAttendance(@AuthenticationPrincipal Principals.User caller,
                                                            @RequestParam(required = false) String day) {
        return register.roster(ManagerScope.require(caller), day);
    }

    /**
     * Marks some of her people for one day and answers the whole roster back. A `userId` outside her department is a
     * 403; a day that is not a teaching day, or one still to come, is a 400.
     */
    @PutMapping(value = "/management/staff-attendance", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.staff.attendance')")
    public ManagementDto.StaffAttendanceDay markStaffAttendance(@AuthenticationPrincipal Principals.User caller,
                                                                @RequestParam(required = false) String day,
                                                                @RequestBody List<ManagementDto.MarkStaffAttendance> items) {
        return register.mark(ManagerScope.require(caller), day, items);
    }

    /** A month per person: present, absent, late, leave, the teaching days nobody marked, and a rate. */
    @GetMapping(value = "/management/staff-attendance/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.staff.attendance')")
    public ManagementDto.StaffAttendanceSummary staffAttendanceSummary(@AuthenticationPrincipal Principals.User caller,
                                                                       @RequestParam(required = false) String month) {
        return register.summary(ManagerScope.require(caller), month);
    }

    /** One person's marked days, newest first. Both bounds absent is the window ending today; it is capped. */
    @GetMapping(value = "/management/staff-attendance/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.staff.attendance')")
    public ManagementDto.StaffAttendanceHistory staffAttendanceHistory(@AuthenticationPrincipal Principals.User caller,
                                                                       @PathVariable String userId,
                                                                       @RequestParam(required = false) String from,
                                                                       @RequestParam(required = false) String to) {
        return register.history(ManagerScope.require(caller), userId, from, to);
    }

    // ---------------------------------------------------------------- the directory

    /** The children of her department, or of one section of it, with the contact the school holds for each. */
    @GetMapping(value = "/management/people/children", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.people')")
    public ManagementDto.ChildDirectory directoryChildren(@AuthenticationPrincipal Principals.User caller,
                                                          @RequestParam(required = false) String classId,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false, defaultValue = "0") int page,
                                                          @RequestParam(required = false, defaultValue = "0") int size) {
        return directory.children(ManagerScope.require(caller), classId, q, page, size);
    }

    /** Every teacher of the department, with her subjects and her sections. */
    @GetMapping(value = "/management/people/teachers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.people')")
    public ManagementDto.TeacherDirectory directoryTeachers(@AuthenticationPrincipal Principals.User caller,
                                                            @RequestParam(required = false) String q,
                                                            @RequestParam(required = false, defaultValue = "0") int page,
                                                            @RequestParam(required = false, defaultValue = "0") int size) {
        return directory.teachers(ManagerScope.require(caller), q, page, size);
    }

    /** Every coordinator of the department, with her subjects and her tracks. */
    @GetMapping(value = "/management/people/coordinators", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.people')")
    public ManagementDto.CoordinatorDirectory directoryCoordinators(@AuthenticationPrincipal Principals.User caller,
                                                                     @RequestParam(required = false) String q,
                                                                     @RequestParam(required = false, defaultValue = "0") int page,
                                                                     @RequestParam(required = false, defaultValue = "0") int size) {
        return directory.coordinators(ManagerScope.require(caller), q, page, size);
    }
}

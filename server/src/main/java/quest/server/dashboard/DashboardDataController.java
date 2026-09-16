package quest.server.dashboard;

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
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.flags.FeatureFlag;
import quest.server.schools.SchoolService;
import quest.server.tenancy.TenantContext;

/**
 * What the School page (§6 screen 5), Managerial's School usage and Teachers (§6 screens 19–20) and Admin's Platform
 * usage & cost (§6 screen 10) read.
 *
 * <p><strong>Two families of route, one rule.</strong> `/admin/schools/{id}/**` names the school and is checked with
 * {@link SchoolService#requireVisible} — another school is a 404, join code and all, exactly as
 * `GET /admin/schools/{id}` already is. `/school/**` names no school: it serves the caller's own, read from her
 * token, so a Managerial user cannot ask for another one because there is nowhere to ask. An Admin on those routes
 * gets the school she picked with `X-School-Id`, and is told to pick one if she has not (D6).
 *
 * <p>Carries no {@link FeatureFlag} and is listed as core in `FeatureFlagCoverageTest`: classes are §2's unit of
 * publishing and usage is how a school is run — neither is a feature a tenant can be without, and a flag over them
 * would hide the screen that shows which flags a school has.
 */
@RestController
@Tag(name = "Dashboard data", description = "Classes, school usage, billing, teachers and platform cost")
public class DashboardDataController {
    private final SchoolClassService classes; private final UsageService usage; private final TeacherDirectoryService teachers;
    private final SchoolService schools; private final TenantContext tenant;

    public DashboardDataController(SchoolClassService classes, UsageService usage, TeacherDirectoryService teachers,
                                   SchoolService schools, TenantContext tenant) {
        this.classes = classes; this.usage = usage; this.teachers = teachers; this.schools = schools; this.tenant = tenant;
    }

    // ---------------------------------------------------------------- Classes tab (§6 screen 5)

    @GetMapping(value = "/admin/schools/{id}/classes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('class.read')")
    public List<SchoolDataDto.SchoolClass> schoolClasses(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return classes.list(require(caller), id);
    }

    @PostMapping(value = "/admin/schools/{id}/classes", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('class.write')")
    public SchoolDataDto.SchoolClass createClass(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                 @RequestBody @Valid SchoolDataDto.CreateClassRequest body) {
        return classes.create(require(caller), id, body);
    }

    /** Assigns the class's teacher, or hands it back to nobody with `clearTeacher`. */
    @PatchMapping(value = "/admin/schools/{id}/classes/{classId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('class.write')")
    public SchoolDataDto.SchoolClass updateClass(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                 @PathVariable String classId, @RequestBody @Valid SchoolDataDto.UpdateClassRequest body) {
        return classes.update(require(caller), id, classId, body);
    }

    // ---------------------------------------------------------------- Usage and Billing tabs (§6 screen 5)

    @GetMapping(value = "/admin/schools/{id}/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('usage.school')")
    public SchoolDataDto.SchoolUsage schoolUsage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                 @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return usage.schoolUsage(schools.requireVisible(require(caller), id), from, to);
    }

    @GetMapping(value = "/admin/schools/{id}/billing", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('billing.read')")
    public SchoolDataDto.SchoolBilling schoolBilling(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                     @RequestParam(required = false) Integer months) {
        return usage.billing(schools.requireVisible(require(caller), id), months);
    }

    // ---------------------------------------------------------------- Managerial's own school (§6 screens 19–20)

    @GetMapping(value = "/school/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('usage.school')")
    public SchoolDataDto.SchoolUsage mySchoolUsage(@AuthenticationPrincipal Principals.User caller,
                                                   @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return usage.schoolUsage(schools.require(ownSchool(caller)), from, to);
    }

    @GetMapping(value = "/school/teachers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.read')")
    public List<SchoolDataDto.TeacherSummary> mySchoolTeachers(@AuthenticationPrincipal Principals.User caller) {
        return teachers.teachers(ownSchool(caller));
    }

    // ---------------------------------------------------------------- Platform usage & cost (§6 screen 10)

    @GetMapping(value = "/admin/usage/platform", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('usage.platform')")
    public SchoolDataDto.PlatformUsage platformUsage(@RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        return usage.platformUsage(from, to);
    }

    /**
     * The school a `/school/**` route serves: the caller's own, or the one an Admin picked with `X-School-Id`. An
     * Admin who has picked none is asked to, rather than shown an arbitrary school's numbers.
     */
    private String ownSchool(Principals.User caller) {
        require(caller);
        var schoolId = tenant.schoolId();
        if (schoolId == null) throw ApiException.badRequest("Pick a school first (X-School-Id).");
        return schoolId;
    }

    private static Principals.User require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller;
    }
}

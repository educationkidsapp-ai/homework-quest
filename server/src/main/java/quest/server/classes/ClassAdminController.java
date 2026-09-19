package quest.server.classes;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.server.auth.Principals;
import quest.server.mail.PlatformName;
import quest.server.schools.SchoolService;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * The Admin's Classes and Children screens for one school (`docs/prompts/dashboard-first-one-school.md` §6):
 * sections and their join codes, the printable card, and the roster of each class.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, and is named in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE` beside `HomeController` and `DashboardDataController`, for the same kind
 * of reason those are: a class is §2's unit of publishing and a roster is who the lessons are for. A flag over them
 * would leave a school with no classes to teach, no way to create one and — since the Classes screen is where join
 * codes live — no way for a parent to arrive. What is genuinely optional here is the <em>teacher's</em> half of the
 * roster, and that carries a real flag: {@link TeacherRosterController} is `teacher.rosterEdit`.
 *
 * <p><strong>Scope.</strong> No route names a school. The school is the caller's, or the one an Admin picked with
 * `X-School-Id` ({@link TenantContext}), and every class, assignment and child is reached through a filtered query —
 * so another school's id in the path is a 404 rather than a refusal that confirms it exists.
 */
@RestController
@Tag(name = "Classes", description = "Sections, join codes and class rosters")
public class ClassAdminController {
    private final SectionService sections; private final RosterService rosters; private final JoinCard cards;
    private final SchoolService schools; private final PlatformName platformName;

    public ClassAdminController(SectionService sections, RosterService rosters, JoinCard cards, SchoolService schools,
                                PlatformName platformName) {
        this.sections = sections; this.rosters = rosters; this.cards = cards; this.schools = schools;
        this.platformName = platformName;
    }

    // ---------------------------------------------------------------- sections

    @GetMapping(value = "/admin/classes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('section.read')")
    public List<ClassDto.SchoolClass> classes(@RequestParam(required = false) String curriculum,
                                              @RequestParam(required = false) Integer grade) {
        return sections.list(curriculum, grade);
    }

    @PostMapping(value = "/admin/classes", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('class.write')")
    public ClassDto.SchoolClass createSection(@AuthenticationPrincipal Principals.User caller,
                                              @RequestBody @Valid ClassDto.CreateSectionRequest body) {
        return sections.create(TeacherScope.require(caller), body);
    }

    @PatchMapping(value = "/admin/classes/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('class.write')")
    public ClassDto.SchoolClass updateSection(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                              @RequestBody @Valid ClassDto.UpdateSectionRequest body) {
        return sections.update(TeacherScope.require(caller), id, body);
    }

    /** A new random code. Every card already printed for this class stops working, which is the point of the button. */
    @PostMapping(value = "/admin/classes/{id}/join-code", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('class.write')")
    public ClassDto.SchoolClass regenerateJoinCode(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return sections.regenerateJoinCode(TeacherScope.require(caller), id);
    }

    /** The A5 card, rendered on the spot. `no-store`: a join code is a secret with a printer at the end of it. */
    @GetMapping(value = "/admin/classes/{id}/join-card.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("@permit.has('section.read')")
    public ResponseEntity<byte[]> joinCard(@PathVariable String id) {
        var section = sections.section(id);
        var pdf = cards.render(platformName.get(), schools.require(section.getSchoolId()).getName(), section);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"join-" + section.getName() + ".pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF).body(pdf);
    }

    @GetMapping(value = "/admin/classes/{id}/assignments", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('section.read')")
    public List<ClassDto.TeachingAssignment> classAssignments(@PathVariable String id) { return sections.assignmentsOf(id); }

    // ---------------------------------------------------------------- rosters

    @GetMapping(value = "/admin/classes/{id}/children", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.read')")
    public List<ClassDto.RosterChild> classChildren(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return rosters.list(TeacherScope.require(caller), id);
    }

    @PostMapping(value = "/admin/classes/{id}/children", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('roster.write')")
    public ClassDto.RosterChild addChild(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                         @RequestBody @Valid ClassDto.CreateRosterChildRequest body) {
        return rosters.add(TeacherScope.require(caller), id, body);
    }

    /**
     * The school's children, and with `?unassigned=true` the ones on no roster at all — a parent registered them in
     * the app with the school's join code and nobody has put them in a section yet.
     */
    @GetMapping(value = "/admin/children", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.read')")
    public List<ClassDto.RosterChild> schoolChildren(@RequestParam(defaultValue = "false") boolean unassigned) {
        return rosters.ofSchool(unassigned);
    }

    @PatchMapping(value = "/admin/children/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.write')")
    public ClassDto.RosterChild updateChild(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                            @RequestBody @Valid ClassDto.UpdateRosterChildRequest body) {
        return rosters.update(TeacherScope.require(caller), id, body);
    }

    /**
     * A child who already has an account — one a parent registered in the app with the school's join code — put onto
     * this section's roster, which is what makes her see this class's lessons and puts her work on its teacher's
     * dashboard. Idempotent; another school's child and a curriculum or grade that is not this section's are 409.
     */
    @PostMapping(value = "/admin/classes/{id}/roster/attach", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.write')")
    public ClassDto.RosterChild attachChild(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                            @RequestBody @Valid ClassDto.AttachChildRequest body) {
        return rosters.attach(TeacherScope.require(caller), id, body.childId());
    }

    /** The other way: she keeps her account and her progress, and stops being one of this class's children. */
    @DeleteMapping(value = "/admin/classes/{id}/roster/{childId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.write')")
    public ClassDto.RosterChild detachChild(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                            @PathVariable String childId) {
        return rosters.detach(TeacherScope.require(caller), id, childId);
    }

    /** CSV or XLSX, columns `name,parentEmail`. `dryRun=true` (the default) writes nothing and returns the preview. */
    @PostMapping(value = "/admin/classes/{id}/children/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('roster.write')")
    public ClassDto.ImportPreview importRoster(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                               @RequestPart("file") MultipartFile file,
                                               @RequestParam(defaultValue = "true") boolean dryRun) {
        return rosters.importRoster(TeacherScope.require(caller), id, file, dryRun);
    }
}

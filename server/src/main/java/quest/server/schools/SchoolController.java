package quest.server.schools;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import quest.server.auth.AuthController;
import quest.server.auth.Principals;
import quest.server.auth.SignInRateLimiter;
import quest.server.config.ApiException;

/** §6 screens 4–5: the Schools cards, the New school wizard's submit and the School page's Overview tab. */
@RestController
@Tag(name = "Schools", description = "Tenants: cards, creation and settings")
public class SchoolController {
    private final SchoolService schools; private final SchoolWizardService wizard; private final SignInRateLimiter limiter;
    public SchoolController(SchoolService schools, SchoolWizardService wizard, SignInRateLimiter limiter) {
        this.schools = schools; this.wizard = wizard; this.limiter = limiter;
    }

    /** An Admin gets every card; a Teacher or Managerial user gets the one school their token belongs to. */
    @GetMapping(value = "/admin/schools", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('school.read')")
    public List<SchoolDto.SchoolSummary> listSchools(@AuthenticationPrincipal Principals.User caller) { return schools.summaries(require(caller)); }

    @PostMapping(value = "/admin/schools", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('school.write')")
    public SchoolDto.School createSchool(@AuthenticationPrincipal Principals.User caller, @RequestBody @Valid SchoolDto.CreateSchoolRequest body) {
        return schools.create(caller == null ? null : caller.userId(), body);
    }

    /** Another school is a 404 for a Teacher or Managerial caller, join code and all. */
    @GetMapping(value = "/admin/schools/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('school.read')")
    public SchoolDto.School school(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) { return schools.get(require(caller), id); }

    @PatchMapping(value = "/admin/schools/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('school.write')")
    public SchoolDto.School updateSchool(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody @Valid SchoolDto.UpdateSchoolRequest body) {
        return schools.update(caller == null ? null : caller.userId(), id, body);
    }

    /**
     * §6 screen 4: the whole New school wizard in one transaction — the school, its theme, its flag overrides and its
     * first Managerial user. Any step being refused (a theme below 4.5:1, an unknown flag key, a taken address)
     * leaves nothing behind.
     */
    @PostMapping(value = "/admin/schools/wizard", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('school.write')")
    public SchoolDto.SchoolWizardResponse createSchoolWithWizard(@AuthenticationPrincipal Principals.User caller,
                                                                 @RequestBody @Valid SchoolDto.SchoolWizardRequest body) {
        return wizard.create(require(caller), body);
    }

    /** Public: the app's Join school step turns a code into a name, a logo and the school's curriculum/grade options. */
    @GetMapping(value = "/schools/by-code/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public SchoolDto.JoinSchoolInfo schoolByCode(@PathVariable String code) { return schools.byCode(code); }

    /**
     * Public (§6 screen 1): the logo and name to fade in once the person has typed their address, and nothing more.
     * 204 when the domain belongs to no school or to several — see {@link SchoolService#logoByEmail}, which is where
     * that rule and the reason for it live. Rate-limited in its own bucket so it cannot be swept and so throttling it
     * never uses up a real sign-in's attempts.
     *
     * <p><strong>POST, with the address in the body</strong> (P4.0, from the #52 review), even though it reads
     * rather than writes. A query string is the one part of a request that is logged everywhere by default — the
     * access log, the load balancer, any forward proxy, the browser's own history — and the value here is a named
     * person's email address, typed on a page nobody has signed in to yet. The body is logged by none of those. The
     * `GET` form this replaces is gone rather than deprecated: nothing shipped calls it (`webAdmin` never did, and
     * the dashboard's sign-in page moves to the POST), so there is no window to keep open.
     */
    @PostMapping(value = "/schools/logo", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public ResponseEntity<SchoolDto.SchoolLogo> schoolLogo(@RequestBody SchoolDto.SchoolLogoRequest body, HttpServletRequest request) {
        String email = body == null ? null : body.email();
        limiter.probe("schools.logo", email, AuthController.clientIp(request));
        var logo = schools.logoByEmail(email);
        return logo == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(logo);
    }

    private static Principals.User require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller;
    }
}

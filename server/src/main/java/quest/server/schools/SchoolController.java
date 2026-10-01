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
    private final quest.server.classes.SectionService sections; private final SchoolLogoService logos;
    public SchoolController(SchoolService schools, SchoolWizardService wizard, SignInRateLimiter limiter,
                            quest.server.classes.SectionService sections, SchoolLogoService logos) {
        this.schools = schools; this.wizard = wizard; this.limiter = limiter; this.sections = sections; this.logos = logos;
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
     * S1 (owner's list of 2026-10-01): the school's logo as an upload — one JPEG, PNG or WebP of at most 1 MB under
     * `file`, sniffed from its bytes. It replaces the logo there was and becomes the theme's `logoUrl` everywhere.
     */
    @org.springframework.web.bind.annotation.PutMapping(value = "/admin/schools/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('school.write')")
    public SchoolDto.SchoolLogo uploadSchoolLogo(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                 @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return logos.upload(require(caller), id, file);
    }

    /** The upload and any typed `logoUrl` are both cleared: the school has no logo afterwards. */
    @org.springframework.web.bind.annotation.DeleteMapping("/admin/schools/{id}/logo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('school.write')")
    public void deleteSchoolLogo(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) { logos.delete(require(caller), id); }

    /**
     * Public and cacheable: the bytes of the uploaded logo, addressed by the school's id and nothing else — it is
     * what the sign-in page shows before there is a token. Images only (the stored type is one of three sniffed
     * ones), `nosniff`, and 404 for a school without an upload. The theme's `logoUrl` carries `?v=`, so a day in a
     * shared cache never serves a replaced logo to a page that read the new theme.
     */
    @GetMapping("/schools/{id}/logo")
    @PreAuthorize("permitAll")
    public ResponseEntity<byte[]> schoolLogoImage(@PathVariable String id) {
        var blob = logos.read(id);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.maxAge(1, java.util.concurrent.TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(blob.mimeType())).body(blob.bytes());
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
     * Public (V7): the narrower join step — the code printed on a <strong>class</strong>'s card turns into that
     * class, so the parent picks no curriculum and no grade at all (`docs/teacher-flow.md` §2). It lives here rather
     * than with the Admin class routes because it is the sibling of `by-code` above: the two public things a parent
     * types before she has an account.
     *
     * <p><strong>POST, with the code in the body</strong>, for the reason `/schools/logo` below is: a join code is a
     * credential, and a query string is the one part of a request that is logged everywhere by default — the access
     * log, the load balancer, any forward proxy, the browser's own history. The body is logged by none of those.
     * `/schools/by-code/{code}` keeps its path parameter only because the app already ships calling it.
     *
     * <p>The answer carries the class, the course and the school's name and nothing else — no roster, no teacher, no
     * school id. Unknown, disabled and retired codes are one uniform 404, and the route is rate-limited in a bucket
     * of its own (`classes.lookup`) so a code space cannot be swept and so throttling it never uses up a real
     * sign-in's attempts.
     */
    @PostMapping(value = "/classes/lookup", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public quest.server.classes.ClassDto.ClassLookup classByJoinCode(@RequestBody @Valid quest.server.classes.ClassDto.ClassLookupRequest body,
                                                                     HttpServletRequest request) {
        limiter.probe("classes.lookup", null, AuthController.clientIp(request));
        return sections.lookup(body == null ? null : body.code());
    }

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

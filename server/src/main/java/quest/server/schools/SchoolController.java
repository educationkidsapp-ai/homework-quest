package quest.server.schools;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.config.ApiException;

/** §6 screens 4–5: the Schools cards, the New school wizard's submit and the School page's Overview tab. */
@RestController
@Tag(name = "Schools", description = "Tenants: cards, creation and settings")
public class SchoolController {
    private final SchoolService schools;
    public SchoolController(SchoolService schools) { this.schools = schools; }

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

    /** Public: the app's Join school step turns a code into a name, a logo and the school's curriculum/grade options. */
    @GetMapping(value = "/schools/by-code/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public SchoolDto.JoinSchoolInfo schoolByCode(@PathVariable String code) { return schools.byCode(code); }

    private static Principals.User require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller;
    }
}

package quest.server.platform;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.HttpCaching;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;

/**
 * §3: the school theme the dashboard and the app both read, and the Admin's editor behind it.
 *
 * <p>Like {@link quest.server.flags.FlagController} this controller carries no {@link FeatureFlag}: theming is not a
 * feature a school can be without, and `FeatureFlagCoverageTest` lists it as infrastructure.
 */
@RestController
@Tag(name = "Themes", description = "Per-school white label (§3)")
public class ThemeController {
    private final ThemeService themes; private final Json json;
    public ThemeController(ThemeService themes, Json json) { this.themes = themes; this.json = json; }

    /** Public and cacheable: the app applies this after the parent joins, without a rebuild. */
    @GetMapping(value = "/schools/{id}/theme", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public ResponseEntity<ThemeDto.SchoolTheme> schoolTheme(@PathVariable String id,
                                                            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        var theme = themes.forSchool(id);
        return HttpCaching.cached(theme, json.write(theme), ifNoneMatch);
    }

    /** The Theme tab of the School page. A Teacher or Managerial caller sees her own school's theme only. */
    @GetMapping(value = "/admin/schools/{id}/theme", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('theme.read')")
    public ThemeDto.SchoolTheme adminSchoolTheme(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return themes.forSchool(visible(caller, id));
    }

    /** Validated server-side (§3): a pair below 4.5:1 is a 400 naming the pair and its ratio. */
    @PutMapping(value = "/admin/schools/{id}/theme", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('theme.write')")
    public ThemeDto.SchoolTheme saveSchoolTheme(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                @RequestBody @Valid ThemeDto.SchoolTheme body) {
        return themes.save(caller, id, body);
    }

    /** Another school is a 404, not a 403: a scoped caller learns nothing about the other tenants (as §2 elsewhere). */
    private static String visible(Principals.User caller, String schoolId) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        if (!caller.isAdmin() && !schoolId.equals(caller.schoolId())) throw ApiException.notFound("school");
        return schoolId;
    }
}

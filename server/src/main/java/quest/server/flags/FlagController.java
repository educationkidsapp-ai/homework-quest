package quest.server.flags;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.config.HttpCaching;
import quest.server.config.Json;

/**
 * §4: the public flag set the app syncs, and the Admin matrix behind it.
 *
 * <p>This controller carries no {@link FeatureFlag} itself — a flag that could switch off the endpoint which
 * switches flags has no way back on. `FeatureFlagCoverageTest` lists it, {@link quest.server.platform.ThemeController}
 * and {@link quest.server.platform.PlatformSettingsController} as infrastructure for that reason.
 */
@RestController
@Tag(name = "Feature flags", description = "The per-school feature matrix (§4)")
public class FlagController {
    private final FlagService flags; private final Json json;
    public FlagController(FlagService flags, Json json) { this.flags = flags; this.json = json; }

    /**
     * Public: `{ "lessons.pdf": true, … }` for all 14 keys. The app calls it on launch and every six hours, so the
     * answer is cacheable and carries an ETag.
     */
    @GetMapping(value = "/schools/{id}/flags", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public ResponseEntity<Map<String, Boolean>> schoolFlags(@PathVariable String id,
                                                            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        var body = flags.forSchool(id);
        return HttpCaching.cached(body, json.write(body), ifNoneMatch);
    }

    /** The matrix: every definition, and a row per school the caller may see (a MANAGERIAL user sees only hers). */
    @GetMapping(value = "/admin/flags", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('flag.read')")
    public FlagDto.FlagMatrix matrix(@AuthenticationPrincipal Principals.User caller) { return flags.matrix(caller); }

    /** Who flipped what and when, newest first. */
    @GetMapping(value = "/admin/flags/audit", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('flag.read')")
    public List<FlagDto.FlagAuditEntry> audit(@RequestParam(required = false, defaultValue = "0") int limit) { return flags.audit(limit); }

    /** One cell of the matrix. Returns the school's whole flag set, so the dashboard can re-render the row. */
    @PutMapping(value = "/admin/schools/{id}/flags/{key}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('flag.write')")
    public Map<String, Boolean> setSchoolFlag(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                              @PathVariable String key, @RequestBody @Valid FlagDto.UpdateFlagRequest body) {
        return flags.set(caller, id, key, body.enabled());
    }

    /** §4's "enable for all / disable for all" on a column; one audit row with `school_id` null records it. */
    @PutMapping(value = "/admin/flags/{key}/all", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('flag.write')")
    public FlagDto.FlagMatrix setFlagEverywhere(@AuthenticationPrincipal Principals.User caller, @PathVariable String key,
                                                @RequestBody @Valid FlagDto.UpdateFlagRequest body) {
        return flags.setForAll(caller, key, body.enabled());
    }
}

package quest.server.brief;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.TenantContext;

/**
 * Feature 25: the one call behind the morning brief above the Admin's and the department manager's Home cards — see
 * {@link MorningBriefService} for what each number is read from and who narrows it.
 *
 * <p>`brief.read` is ADMIN and MANAGERIAL, feature 21's two roles and for its reason: the brief is about deciding
 * things for a whole school or a whole academy. A teacher, a coordinator or a floor supervisor has her own Home, and
 * a parent or a child never reaches a staff route at all.
 *
 * <p>Behind `morningBrief`, so a school that has not turned it on answers 404 here and the dashboard keeps feature
 * 21's Home exactly as it is.
 */
@RestController
@FeatureFlag(FlagKeys.MORNING_BRIEF)
@Tag(name = "Morning brief", description = "The Admin's and the department manager's morning brief (feature 25)")
public class MorningBriefController {
    private final MorningBriefService briefs; private final TenantContext tenant;

    public MorningBriefController(MorningBriefService briefs, TenantContext tenant) {
        this.briefs = briefs; this.tenant = tenant;
    }

    /**
     * Today's brief, generated on the spot when {@link MorningBriefJob} has not reached this leader yet — the same
     * claim, so a Home reloaded five times is one brief and one model call.
     *
     * @return `200` with the brief, or `204 No Content` on a day the school does not teach
     */
    @GetMapping(value = "/me/morning-brief", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('brief.read')")
    public ResponseEntity<MorningBriefDto.View> morningBrief(@AuthenticationPrincipal Principals.User caller) {
        var brief = briefs.brief(tenant.writeSchoolId(), caller);
        return brief == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(brief);
    }
}

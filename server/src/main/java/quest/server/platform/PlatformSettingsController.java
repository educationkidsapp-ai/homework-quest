package quest.server.platform;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.config.Json;

/**
 * §A: the product's own name, short name and logo, editable by Admin. Deliberately not cacheable — a rename has to
 * reach the browser title, the sign-in heading and the footer on the next page load, not within five minutes.
 *
 * <p>Infrastructure, like {@link ThemeController}: no {@link quest.server.flags.FeatureFlag}.
 */
@RestController
@Tag(name = "Platform settings", description = "The platform's own name, logo and default theme (§A)")
public class PlatformSettingsController {
    private final PlatformSettingsService settings; private final ThemeService themes; private final Json json;
    private final SchoolCalendar calendar; private final quest.server.tenancy.TenantContext tenant;

    public PlatformSettingsController(PlatformSettingsService settings, ThemeService themes, Json json,
                                      SchoolCalendar calendar, quest.server.tenancy.TenantContext tenant) {
        this.settings = settings; this.themes = themes; this.json = json; this.calendar = calendar; this.tenant = tenant;
    }

    /**
     * Public: what the sign-in page and the app need before anyone has a token.
     *
     * <p><strong>With the school's override applied once there is one (N2.3b).</strong> `schoolWeek` and `timezone`
     * are the two fields a school may override (V7), and the teacher screens lay out a week with them — a dashboard
     * reading the platform's Sunday–Thursday for a Monday–Friday school would draw the wrong grid. So a signed-in
     * caller is answered her own school's week and zone, resolved by {@link SchoolCalendar} exactly as the week
     * grid and the calendar resolve them; an anonymous caller still gets the platform's, because there is no school
     * to ask about yet.
     */
    @GetMapping(value = "/platform-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll")
    public PlatformDto.PlatformSettings platformSettings(@AuthenticationPrincipal Principals.User caller) {
        var base = settings.publicSettings();
        if (caller == null) return base;
        var week = calendar.of(tenant.writeSchoolId());
        var days = week.days().stream().map(d -> d.name().substring(0, 3)).toList();
        return new PlatformDto.PlatformSettings(base.name(), base.shortName(), base.logoUrl(), base.supportEmail(),
                base.defaultTheme(), days, week.zone().getId());
    }

    /** Every field, including the platform-wide default theme every school without one of its own is shown. */
    @GetMapping(value = "/admin/platform-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('platform.manage')")
    public PlatformDto.PlatformSettings adminPlatformSettings() { return settings.settings(themes.platformDefault()); }

    /** Only the fields that are present are written; a `defaultTheme` goes through the §3 contrast validation. */
    @PutMapping(value = "/admin/platform-settings", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('platform.write')")
    public PlatformDto.PlatformSettings savePlatformSettings(@AuthenticationPrincipal Principals.User caller,
                                                             @RequestBody @Valid PlatformDto.UpdatePlatformSettingsRequest body) {
        String themeJson = body.defaultTheme() == null ? null : json.write(themes.validated(body.defaultTheme()));
        settings.update(caller, body, themeJson);
        return settings.settings(themes.platformDefault());
    }
}

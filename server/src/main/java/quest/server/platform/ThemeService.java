package quest.server.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;

/**
 * §3 white label: a school's theme, the validation Admin's save goes through, and the default every school starts on.
 *
 * <p>Child-mode rules are enforced here, not in the UI: every colour must be `#RRGGBB`, and every text/background
 * pair must clear {@link Contrast#MINIMUM}. A theme that fails is a 400 naming the first failing pair and its ratio.
 *
 * <p>Resolution of "the theme of school X": the school's own `theme_json`, else the Admin's platform-wide default
 * (`platform_settings.default_theme_json`), else the theme built from `design/tokens.json` ({@link DesignTokens}).
 */
@Service
public class ThemeService {
    private final SchoolRepository schools; private final PlatformSettingsService platform; private final DesignTokens tokens;
    private final Json json; private final AuditService audit;

    public ThemeService(SchoolRepository schools, PlatformSettingsService platform, DesignTokens tokens, Json json, AuditService audit) {
        this.schools = schools; this.platform = platform; this.tokens = tokens; this.json = json; this.audit = audit;
    }

    /** The theme of a school, or a 404 when there is no such school. */
    public ThemeDto.SchoolTheme forSchool(String schoolId) { return themeOf(require(schoolId)); }

    /** The theme of a school that has already been loaded (the join-by-code lookup hands its row straight in). */
    public ThemeDto.SchoolTheme themeOf(SchoolEntity school) {
        if (school.getThemeJson() != null && !school.getThemeJson().isBlank())
            return json.read(school.getThemeJson(), ThemeDto.SchoolTheme.class);
        return platformDefault();
    }

    /** The platform-wide default: the Admin's, or the design tokens' when they have not set one. */
    public ThemeDto.SchoolTheme platformDefault() {
        String stored = platform.defaultThemeJson();
        return stored == null || stored.isBlank() ? tokens.defaultTheme() : json.read(stored, ThemeDto.SchoolTheme.class);
    }

    /** `PUT /admin/schools/{id}/theme`: validate, then store on the school. */
    @Transactional
    public ThemeDto.SchoolTheme save(Principals.User actor, String schoolId, ThemeDto.SchoolTheme requested) {
        var school = require(schoolId);
        var theme = validated(requested);
        school.setThemeJson(json.write(theme));
        schools.save(school);
        audit.record(actor == null ? null : actor.userId(), "school.theme", "school", school.getId(), school.getId(),
                Map.of("appName", theme.appName() == null ? "" : theme.appName()));
        return theme;
    }

    /**
     * §A: the name shown inside a school's scope is its `theme.appName`; without one it is the platform's name, which
     * is itself seeded in `V5__flags_themes.sql`. A caller with no school (the platform ADMIN) gets the platform name.
     */
    public String displayName(String schoolId) {
        if (schoolId != null) {
            var school = schools.findById(schoolId).orElse(null);
            if (school != null) {
                var appName = themeOf(school).appName();
                if (appName != null && !appName.isBlank()) return appName.trim();
            }
        }
        return platform.name();
    }

    /**
     * Normalises every colour to upper-case hex and refuses the theme when a colour is malformed or a pair is below
     * its threshold. The pairs, in the order they are reported:
     * <ol>
     *   <li>`primaryInk` on `primary` — text, {@link Contrast#MINIMUM}</li>
     *   <li>`primaryInk` on `ground` — text</li>
     *   <li>`accent` on `ground` — text</li>
     *   <li>`mascotColor` on `ground` — a graphic, so {@link Contrast#MINIMUM_NON_TEXT}</li>
     *   <li>each world's `ink` on its `soft` (the ground that world is drawn on) — text, math before english</li>
     * </ol>
     */
    public ThemeDto.SchoolTheme validated(ThemeDto.SchoolTheme requested) {
        if (requested == null) throw ApiException.badRequest("theme is required");
        String primary = colour(requested.primary(), "primary");
        String primaryInk = colour(requested.primaryInk(), "primaryInk");
        String accent = colour(requested.accent(), "accent");
        String ground = colour(requested.ground(), "ground");
        String softBorder = colour(requested.softBorder(), "softBorder");
        String mascotColor = colour(requested.mascotColor(), "mascotColor");

        var worlds = new LinkedHashMap<String, ThemeDto.WorldPalette>();
        var requestedWorlds = requested.worldPalettes() == null ? Map.<String, ThemeDto.WorldPalette>of() : requested.worldPalettes();
        for (String key : requestedWorlds.keySet())
            if (!ThemeDto.WORLDS.contains(key)) throw ApiException.badRequest("worldPalettes has no world " + key + "; it is " + String.join(" and ", ThemeDto.WORLDS));
        for (String world : ThemeDto.WORLDS) {
            var palette = requestedWorlds.get(world);
            if (palette == null) { worlds.put(world, tokens.defaultTheme().worldPalettes().get(world)); continue; }
            worlds.put(world, new ThemeDto.WorldPalette(colour(palette.primary(), world + ".primary"), colour(palette.deep(), world + ".deep"),
                    colour(palette.soft(), world + ".soft"), colour(palette.ink(), world + ".ink")));
        }

        var pairs = new ArrayList<Pair>(List.of(
                new Pair("primaryInk", primaryInk, "primary", primary, Contrast.MINIMUM),
                new Pair("primaryInk", primaryInk, "ground", ground, Contrast.MINIMUM),
                new Pair("accent", accent, "ground", ground, Contrast.MINIMUM),
                new Pair("mascotColor", mascotColor, "ground", ground, Contrast.MINIMUM_NON_TEXT)));
        for (String world : ThemeDto.WORLDS) {
            var palette = worlds.get(world);
            pairs.add(new Pair(world + ".ink", palette.ink(), world + ".soft", palette.soft(), Contrast.MINIMUM));
        }
        for (Pair pair : pairs) {
            double ratio = Contrast.ratio(pair.foreground(), pair.background());
            if (ratio < pair.minimum())
                throw ApiException.badRequest(pair.on() + " on " + pair.over() + " is " + Contrast.format(ratio)
                        + ":1, needs " + Contrast.format(pair.minimum()) + ":1");
        }

        return new ThemeDto.SchoolTheme(blankToNull(requested.logoUrl()), blankToNull(requested.appName()), primary, primaryInk,
                accent, ground, softBorder, mascotColor, java.util.Collections.unmodifiableMap(worlds),
                requested.fontChoice() == null ? ThemeDto.FontChoice.NUNITO : requested.fontChoice());
    }

    /** One contrast rule: [on] drawn over [over], and the ratio it has to clear. */
    private record Pair(String on, String foreground, String over, String background, double minimum) {}

    private SchoolEntity require(String schoolId) {
        return schools.findById(schoolId == null ? "" : schoolId).orElseThrow(() -> ApiException.notFound("school"));
    }

    private static String colour(String value, String field) {
        if (!Contrast.isHex(value)) throw ApiException.badRequest(field + " must be a colour like #RRGGBB, not " + (value == null ? "nothing" : value));
        return value.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

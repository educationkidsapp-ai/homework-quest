package quest.server.platform;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;

/**
 * §A: nothing in the code base names the product. The name, short name, logo and support address come from the one
 * `platform_settings` row, which `V5__flags_themes.sql` seeds, and Admin edits under Platform settings.
 *
 * <p>Read on every email subject and every `GET /me`, so the row is cached for {@value #CACHE_SECONDS} seconds and
 * dropped the moment this instance writes it. A rename is therefore visible to the Admin who made it at once, and to
 * another Cloud Run instance within the TTL.
 */
@Service
public class PlatformSettingsService {
    static final long CACHE_SECONDS = 60;

    private record Cached(Entities.PlatformSettingsEntity row, long expiresAtNanos) {}

    private final PlatformSettingsRepository repository; private final AuditService audit;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public PlatformSettingsService(PlatformSettingsRepository repository, AuditService audit) {
        this.repository = repository; this.audit = audit;
    }

    /** The platform's name (§A) — what an email subject and an unscoped `GET /me` show. */
    public String name() { return row().getName(); }
    public String shortName() { return row().getShortName(); }

    /**
     * `GET /platform-settings` (public): the three fields a browser needs — plus, since N2.1, the school week and
     * timezone, because the teacher's week grid cannot be laid out without knowing which days it has and where the
     * day turns over. Neither is a secret: they are the same calendar the join card already prints.
     */
    public PlatformDto.PlatformSettings publicSettings() {
        var row = row();
        return new PlatformDto.PlatformSettings(row.getName(), row.getShortName(), row.getLogoUrl(), null, null,
                schoolWeek(), row.getTimezone());
    }

    /** `GET /admin/platform-settings`: every field, including the platform-wide default theme. */
    public PlatformDto.PlatformSettings settings(ThemeDto.SchoolTheme defaultTheme) {
        var row = row();
        return new PlatformDto.PlatformSettings(row.getName(), row.getShortName(), row.getLogoUrl(), row.getSupportEmail(),
                defaultTheme, schoolWeek(), row.getTimezone());
    }

    /** The platform's teaching days, in the order the week runs; `SchoolCalendar` applies a school's override. */
    public List<DayOfWeek> schoolWeekDays() { return SchoolCalendar.parseWeek(row().getSchoolWeekJson()); }

    /** The platform's timezone, as a validated IANA id. */
    public String timezoneId() { return row().getTimezone(); }

    private List<String> schoolWeek() { return schoolWeekDays().stream().map(DayOfWeek::name).map(n -> n.substring(0, 3)).toList(); }

    /** The Admin's platform-wide default theme as stored, or null when the design tokens' theme still applies. */
    public String defaultThemeJson() { return row().getDefaultThemeJson(); }

    @Transactional
    public void update(Principals.User actor, PlatformDto.UpdatePlatformSettingsRequest request, String defaultThemeJson) {
        // The same fields as a school theme's, with the same public reach (§A), so the same checks: `logoUrl` is
        // served by the public route and rendered as an `img src`, and the name lands in every mail subject.
        var name = SafeText.plainText(request.name(), "name", SafeText.MAX_NAME);
        var shortName = SafeText.plainText(request.shortName(), "shortName", SafeText.MAX_NAME);
        var logoUrl = SafeText.httpsUrl(request.logoUrl(), "logoUrl");
        var supportEmail = SafeText.plainText(request.supportEmail(), "supportEmail", SafeText.MAX_EMAIL);

        var row = repository.findById(Entities.PlatformSettingsEntity.ID).orElseThrow(PlatformSettingsService::missing);
        if (name != null) row.setName(name);
        if (shortName != null) row.setShortName(shortName);
        if (request.logoUrl() != null) row.setLogoUrl(logoUrl);
        if (request.supportEmail() != null) row.setSupportEmail(supportEmail);
        if (defaultThemeJson != null) row.setDefaultThemeJson(defaultThemeJson);
        // The week and the zone decide what every teacher's grid looks like, so they are validated rather than
        // trusted: an unknown day name or a zone `ZoneId` does not know is a 400, never a row that breaks the grid.
        if (request.schoolWeek() != null) row.setSchoolWeekJson(SchoolCalendar.weekJson(request.schoolWeek()));
        if (request.timezone() != null) row.setTimezone(SchoolCalendar.zoneId(request.timezone()).getId());
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        cache.set(null);
        audit.record(actor == null ? null : actor.userId(), "platform.update", "platform", row.getId(), null,
                Map.of("name", row.getName(), "shortName", row.getShortName()));
    }

    /** Drops the cached row; the next read goes to the database. */
    public void invalidate() { cache.set(null); }

    private Entities.PlatformSettingsEntity row() {
        var cached = cache.get();
        if (cached != null && System.nanoTime() < cached.expiresAtNanos()) return cached.row();
        var row = repository.findById(Entities.PlatformSettingsEntity.ID).orElseThrow(PlatformSettingsService::missing);
        cache.set(new Cached(row, System.nanoTime() + Duration.ofSeconds(CACHE_SECONDS).toNanos()));
        return row;
    }

    private static IllegalStateException missing() {
        return new IllegalStateException("platform_settings has no '" + Entities.PlatformSettingsEntity.ID + "' row — V5__flags_themes.sql seeds it");
    }
}

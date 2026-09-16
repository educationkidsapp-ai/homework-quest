package quest.server.mail;

import org.springframework.stereotype.Component;
import quest.server.platform.PlatformSettingsService;

/**
 * What the platform calls itself in an email subject. The mail code never spells the name out: it asks this bean,
 * which reads the one `platform_settings` row (§A) through {@link PlatformSettingsService}, cached for a minute
 * there. Admin renames the platform under Platform settings and every subject follows; nothing in `src/main` holds
 * the name as a literal, which is what `ProductNameTest` enforces.
 */
@Component
public class PlatformName {
    private final PlatformSettingsService settings;
    public PlatformName(PlatformSettingsService settings) { this.settings = settings; }

    public String get() { return settings.name(); }
    @Override public String toString() { return get(); }
}
